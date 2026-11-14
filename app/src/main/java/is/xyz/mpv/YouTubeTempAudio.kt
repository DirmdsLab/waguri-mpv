package `is`.xyz.mpv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

internal object YouTubeTempAudio {
    data class Track(val url: String, val title: String)
    data class Format(val code: String, val label: String)
    data class Audio(val uri: Uri, val title: String, val durationMs: Long?, val cover: Bitmap?, val format: String)

    private val assetPath = Regex("/audio-cache/[a-f0-9]{64}/(audioraw|thumbnail)\\.[A-Za-z0-9]{1,12}")

    fun formats(url: String): List<Format> {
        val items = request("/api/formats", url).getJSONArray("formats")
        return buildList<Format> {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val kind = item.optString("kind")
                if (kind != "audio" && kind != "combined") continue
                val code = item.getString("id")
                val bitrate = item.optDouble("audio_bitrate").takeIf { it.isFinite() && it > 0 }
                val details = listOfNotNull(code, item.optString("ext"), item.optString("audio_codec"),
                    bitrate?.let { "${it.toInt()} kbps" }).filter(String::isNotBlank)
                add(Format(code, details.joinToString(" · ")))
            }
        }.sortedBy { it.code != "251" }
    }

    fun playlist(url: String): List<Track> {
        val items = request("/api/playlist", url).getJSONArray("tracks")
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            Track(item.getString("url"), item.getString("title"))
        }
    }

    fun prepare(url: String, format: String?): Audio {
        val result = request("/api/play-audio", url, format)
        val path = result.getString("audio")
        if (!assetPath.matches(path) || !path.substringAfterLast('/').startsWith("audioraw.")) {
            throw IOException("Invalid audio path from the local server.")
        }
        val cover = result.optString("thumbnail").takeIf { assetPath.matches(it) }
            ?.takeIf { it.substringAfterLast('/').startsWith("thumbnail.") }
            ?.let { runCatching { readCover(it) }.getOrNull() }
        return Audio(
            Uri.parse(YouTubeDownloadPage.SERVER_ORIGIN + path),
            result.getString("title"), result.optLong("duration_ms").takeIf { it > 0 }, cover,
            result.getString("format"),
        )
    }

    private fun readCover(path: String): Bitmap? {
        val connection = connection(path)
        try {
            connection.readTimeout = 10_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8 * 1024 * 1024) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            while (maxOf(options.outWidth, options.outHeight) / maxOf(options.inSampleSize, 1) > 1600) {
                options.inSampleSize = maxOf(options.inSampleSize, 1) * 2
            }
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }

    private fun request(path: String, url: String, format: String? = null): JSONObject {
        val body = JSONObject().put("url", url)
        format?.let { body.put("audio", it) }
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val connection = connection(path)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val input = (if (code == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream)
                ?: throw IOException("The local server returned HTTP $code.")
            val result = input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            if (code != HttpURLConnection.HTTP_OK || !result.optBoolean("success")) {
                throw IOException(result.optString("error", "The local server returned HTTP $code."))
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(path: String): HttpURLConnection =
        (URL(YouTubeDownloadPage.SERVER_ORIGIN + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 180_000
        }
}
