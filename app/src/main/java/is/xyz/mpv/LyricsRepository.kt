package `is`.xyz.mpv

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class LyricLine(val milliseconds: Long, val text: String)

internal data class LyricsResult(
    val title: String,
    val artist: String,
    val plain: String,
    val lines: List<LyricLine>,
    val instrumental: Boolean,
)

/** Performs lyrics lookups and persistent offline storage on the dedicated IO executor. */
internal class LyricsRepository(context: Context) {
    private val cache = File(context.cacheDir, "lyrics").apply { mkdirs() }
    private val offlineStore = File(context.filesDir, "lyrics-offline").apply { mkdirs() }
    private var nextRequestAt = 0L

    class RateLimited : IOException("Lyrics request is temporarily rate limited")

    /**
     * Reads lyrics previously selected for this exact media item.
     * The durable app-private files directory is used instead of cacheDir so
     * Android cache cleanup cannot silently remove offline lyrics.
     */
    fun loadOffline(mediaKey: String): LyricsResult? {
        val file = offlineFile(mediaKey)
        if (!file.isFile) return null

        val record = JSONObject(file.readText(Charsets.UTF_8))
        val lineArray = record.optJSONArray("lines") ?: JSONArray()
        val lines =
            (0 until lineArray.length()).mapNotNull { index ->
                val item = lineArray.optJSONObject(index) ?: return@mapNotNull null
                val timestamp = item.optLong("milliseconds", -1L)
                if (timestamp < 0L) return@mapNotNull null
                LyricLine(timestamp, item.optString("text"))
            }

        return LyricsResult(
            title = record.optString("title"),
            artist = record.optString("artist"),
            plain = record.optString("plain"),
            lines = lines,
            instrumental = record.optBoolean("instrumental"),
        )
    }

    /** Saves a manually selected or automatically matched result for offline use. */
    fun saveOffline(mediaKey: String, result: LyricsResult) {
        val record = JSONObject()
            .put("title", result.title)
            .put("artist", result.artist)
            .put("plain", result.plain)
            .put("instrumental", result.instrumental)

        val lines = JSONArray()
        result.lines.forEach { line ->
            lines.put(
                JSONObject()
                    .put("milliseconds", line.milliseconds)
                    .put("text", line.text)
            )
        }
        record.put("lines", lines)

        val destination = offlineFile(mediaKey)
        val temporary = File(offlineStore, "${destination.name}.tmp")
        temporary.writeText(record.toString(), Charsets.UTF_8)
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IOException("Could not replace cached lyrics")
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun offlineFile(mediaKey: String): File {
        val key =
            MessageDigest.getInstance("SHA-256")
                .digest(mediaKey.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return File(offlineStore, "$key.json")
    }

    fun search(query: String): List<LyricsResult> {
        val normalized = query.trim().take(200)
        if (normalized.isEmpty()) return emptyList()
        val key =
            MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        val file = File(cache, "$key.json")
        val cached =
            file
                .takeIf {
                    it.exists() && System.currentTimeMillis() - it.lastModified() < 7 * 86_400_000L
                }
                ?.let { runCatching { it.readText() }.getOrNull() }
        val raw =
            cached
                ?: fetch(normalized).also { response ->
                    runCatching {
                        file.writeText(response)
                        cache
                            .listFiles()
                            ?.sortedByDescending { it.lastModified() }
                            ?.drop(40)
                            ?.forEach { it.delete() }
                    }
                }
        val data = JSONArray(raw)
        return (0 until minOf(data.length(), 20)).map { index ->
            val record = data.getJSONObject(index)
            fun text(key: String) = if (record.isNull(key)) "" else record.optString(key)
            LyricsResult(
                text("trackName"),
                text("artistName"),
                text("plainLyrics"),
                parseLrc(text("syncedLyrics")),
                record.optBoolean("instrumental"),
            )
        }
    }

    private fun fetch(query: String): String {
        if (System.currentTimeMillis() < nextRequestAt) throw RateLimited()
        val connection =
            URL("https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}")
                .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "UwUPlayer/${BuildConfig.VERSION_NAME} (based on https://github.com/mpv-android/mpv-android; uwu.waguri.mpvplayer)",
            )
            val code = connection.responseCode
            nextRequestAt = System.currentTimeMillis() + 500
            if (code == 429) {
                val seconds =
                    connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400)
                        ?: 60
                nextRequestAt = System.currentTimeMillis() + seconds * 1000 + 1000
                throw RateLimited()
            }
            if (code != 200) throw IOException("Lyrics HTTP $code")
            return connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Search cancelled")
                    val count = reader.read(buffer)
                    if (count < 0) break
                    result.append(buffer, 0, count)
                    if (result.length > 2_000_000) throw IOException("Lyrics response is too large")
                }
                result.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun filenameQuery(filename: String): String =
            filename
                .substringBeforeLast('.', filename)
                .replace('_', ' ')
                .replace(Regex("(?i)\\s*[\\[(](official[^\\])]*|lyrics?|audio|video)[\\])]"), "")
                .replace(Regex("^\\d{1,3}[ ._-]+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

        private val lrcStamp = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
        private val lrcOffset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

        fun parseLrc(source: String): List<LyricLine> {
            val offset = readLrcOffset(source)
            return source
                .lineSequence()
                .flatMap { line ->
                    val text = lrcStamp.replace(line, "").trim()
                    lrcStamp.findAll(line).mapNotNull { match ->
                        readLrcTimestamp(match, offset)?.let { LyricLine(it, text) }
                    }
                }
                .sortedBy { it.milliseconds }
                .toList()
        }

        fun retimeLrc(source: String, delayMs: Long): String {
            val offset = readLrcOffset(source)
            val adjusted = lrcStamp.replace(source) { stamp ->
                val time = readLrcTimestamp(stamp, offset)
                if (time == null) stamp.value else formatLrcTimestamp((time + delayMs).coerceAtLeast(0L))
            }
            return lrcOffset.replace(adjusted, "[offset:0]")
        }

        fun formatLrcTimestamp(milliseconds: Long): String {
            val minutes = milliseconds / 60_000
            val seconds = (milliseconds % 60_000) / 1_000
            val fraction = milliseconds % 1_000
            return "[%02d:%02d.%03d]".format(Locale.ROOT, minutes, seconds, fraction)
        }

        private fun readLrcOffset(source: String): Long =
            lrcOffset.find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        private fun readLrcTimestamp(stamp: MatchResult, offset: Long): Long? {
            val minutes = stamp.groupValues[1].toLongOrNull() ?: return null
            val seconds = stamp.groupValues[2].toLongOrNull() ?: return null
            if (seconds >= 60 || minutes > 100_000) return null
            val fraction = stamp.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
            return (minutes * 60_000 + seconds * 1000 + fraction - offset).coerceAtLeast(0L)
        }
    }
}
