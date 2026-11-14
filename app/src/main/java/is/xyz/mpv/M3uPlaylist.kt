package `is`.xyz.mpv

import java.io.BufferedReader
import java.io.IOException
import java.io.Writer

internal object M3uPlaylist {
    const val MIME_TYPE = "audio/x-mpegurl"

    data class Entry(val location: String, val title: String? = null, val durationMs: Long? = null)

    data class Contents(val entries: List<Entry>, val isHls: Boolean)

    fun read(input: BufferedReader): Contents {
        val entries = mutableListOf<Entry>()
        var title: String? = null
        var durationMs: Long? = null
        var isHls = false

        for (rawLine in input.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF").trimStart()
            when {
                line.isBlank() -> Unit
                line.startsWith("#EXT-X-", ignoreCase = true) -> isHls = true
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substringAfter(':')
                    title = info.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }
                    durationMs = info.substringBefore(',').trim().takeWhile { !it.isWhitespace() }
                        .toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it > 0 }
                        ?.let { (it * 1000).toLong() }
                }
                line.startsWith('#') -> Unit
                else -> {
                    entries.add(Entry(line, title, durationMs))
                    title = null
                    durationMs = null
                }
            }
        }

        return Contents(entries, isHls)
    }

    fun write(output: Writer, entries: List<Entry>) {
        if (entries.any { !isValidLocation(it.location) }) {
            throw IOException("A playlist entry has an invalid location")
        }

        output.write("#EXTM3U\n")
        for (entry in entries) {
            val seconds = entry.durationMs?.takeIf { it > 0 }?.let { it / 1000.0 } ?: -1
            val title = entry.title.orEmpty().replace('\r', ' ').replace('\n', ' ')
                .replace('\u0000', ' ')
            output.write("#EXTINF:$seconds,$title\n")
            output.write(entry.location)
            output.write("\n")
        }
    }

    private fun isValidLocation(location: String): Boolean =
        location.isNotBlank() && !location.trimStart().startsWith('#') &&
            location.none { it == '\r' || it == '\n' || it == '\u0000' }
}
