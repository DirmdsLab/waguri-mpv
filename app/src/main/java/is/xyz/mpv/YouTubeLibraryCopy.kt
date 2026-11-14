package `is`.xyz.mpv

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Locale

/** Copies the server's final media and sidecars into one writable Tracks folder. */
internal object YouTubeLibraryCopy {
    private val manifestPathPattern = Regex("/results/([A-Za-z0-9_-]{32})/library\\.json")
    private val extensionPattern = Regex("[A-Za-z0-9]{1,12}")
    private val videoTypes = mapOf("mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm")

    private data class ResultFile(val path: String, val size: Long, val suffix: String, val mimeType: String)

    fun isManifestPath(path: String): Boolean = manifestPathPattern.matches(path)

    fun copy(resolver: ContentResolver, tree: Uri, manifestPath: String): Int {
        val (sourceStem, files) = readManifest(manifestPath)
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val existingNames = resolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            buildSet<String> {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    add(name.lowercase(Locale.ROOT))
                }
            }
        } ?: throw IOException("The Tracks folder could not be read.")

        var stem = sourceStem
        var number = 2
        while (files.any { "$stem${it.suffix}".lowercase(Locale.ROOT) in existingNames }) {
            stem = "$sourceStem ($number)"
            number++
        }

        val created = mutableListOf<Uri>()
        try {
            for ((index, file) in files.withIndex()) {
                fetch(file.path) { connection, input ->
                    if (connection.contentLengthAsLong() != file.size) {
                        throw IOException("The server returned a different file size.")
                    }
                    val target = DocumentsContract.createDocument(resolver, parent, file.mimeType, "$stem${file.suffix}")
                        ?: throw IOException("The Tracks folder could not create the download.")
                    created.add(target)
                    val output = resolver.openOutputStream(target, "wt")
                        ?: throw IOException("The downloaded file could not be opened for writing.")
                    output.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw IOException("The copy was interrupted.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > file.size) throw IOException("The server sent more data than expected.")
                            stream.write(buffer, 0, count)
                        }
                        if (written != file.size) throw IOException("The server file was incomplete.")
                    }
                    if (index == 0) {
                        val actualName = resolver.query(
                            target, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
                        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        if (actualName != null && actualName.endsWith(file.suffix, ignoreCase = true)) {
                            stem = actualName.dropLast(file.suffix.length)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            created.asReversed().forEach { uri -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            throw error
        }
        return created.size
    }

    private fun readManifest(manifestPath: String): Pair<String, List<ResultFile>> {
        val token = manifestPathPattern.matchEntire(manifestPath)?.groupValues?.get(1)
            ?: throw IOException("The server result link is invalid.")
        val manifest = fetch(manifestPath) { _, input ->
            val reader = input.bufferedReader(Charsets.UTF_8)
            val buffer = CharArray(2048)
            val json = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (json.length + count > 16 * 1024) throw IOException("The server result is too large.")
                json.append(buffer, 0, count)
            }
            JSONObject(json.toString())
        }
        val entries = manifest.getJSONArray("files")
        if (entries.length() !in 1..3) throw IOException("The server result has an invalid file list.")
        val mediaName = entries.getJSONObject(0).getString("name")
        val stem = mediaName.substringBeforeLast('.', "")
        val paths = mutableSetOf<String>()
        val suffixes = mutableSetOf<String>()
        val files = List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val name = entry.getString("name")
            val path = entry.getString("url")
            val size = entry.getLong("size")
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (name.startsWith('.') || name.any { it == '/' || it == '\\' || it.isISOControl() } ||
                !extensionPattern.matches(extension) || size <= 0 ||
                !path.startsWith("/results/$token/files/") ||
                path.substringAfterLast('/').toIntOrNull()?.let { "/results/$token/files/$it" } != path ||
                !paths.add(path)
            ) throw IOException("The server result contains an invalid file.")

            val source = name.substringBeforeLast('.')
            val (suffix, mime) = when {
                index == 0 && stem.isNotBlank() && extension in videoTypes -> ".$extension" to videoTypes.getValue(extension)
                index > 0 && source == "${stem}_sub" -> ".$extension" to fileMimeType(extension)
                index > 0 && source == "${stem}_thumbnail" -> "_thumbnail.$extension" to fileMimeType(extension)
                else -> throw IOException("The server result does not match the merged video.")
            }
            if (!suffixes.add(suffix)) throw IOException("The server result contains duplicate files.")
            ResultFile(path, size, suffix, mime)
        }
        return stem to files
    }

    private fun fileMimeType(extension: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

    private fun HttpURLConnection.contentLengthAsLong(): Long? = getHeaderField("Content-Length")?.toLongOrNull()

    private fun <T> fetch(path: String, read: (HttpURLConnection, InputStream) -> T): T {
        val connection = URL(YouTubeDownloadPage.SERVER_ORIGIN + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("The local server returned HTTP ${connection.responseCode}.")
            }
            return connection.inputStream.use { read(connection, it) }
        } finally {
            connection.disconnect()
        }
    }
}
