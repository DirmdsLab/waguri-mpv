package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.Locale

internal class M3uPlaylistFiles(context: Context) {
    private val resolver = context.contentResolver

    data class Opened(val name: String, val tracks: List<MediaFile>, val unavailable: Int)

    fun open(uri: Uri, relativeFolder: Uri? = null): Opened {
        val source = describe(uri)
        if (!source.isPlaylist) throw IOException("The selected document is not an M3U playlist")

        val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the playlist")
        val contents = input.bufferedReader(Charsets.UTF_8).use(M3uPlaylist::read)

        // An HLS manifest describes one stream, with segments or bitrate variants.
        if (contents.isHls) return Opened(source.displayName, listOf(source), 0)

        val trees = resolver.persistedUriPermissions
            .filter { it.isReadPermission && it.uri.authority == EXTERNAL_STORAGE }
            .mapNotNull { grant ->
                documentId(grant.uri, tree = true)?.let { id ->
                    externalFile(id)?.let { folder -> folder to grant.uri }
                }
            }
            .sortedByDescending { (folder, _) -> folder.path.length }

        val parent = localFile(uri)?.parentFile
        val resolved = mutableMapOf<String, MediaFile?>()
        var unavailable = 0
        val tracks = contents.entries.map { entry ->
            if (!resolved.containsKey(entry.location)) {
                resolved[entry.location] =
                    resolve(entry.location, parent, trees, relativeFolder)?.let { describe(it) }
            }
            val track = resolved[entry.location]
            if (track == null) unavailable++
            (track ?: describe(Uri.parse(entry.location), isAvailable = false))
                .copy(playlistTitle = entry.title, durationMs = entry.durationMs)
        }

        return Opened(source.displayName, tracks, unavailable)
    }

    fun export(destination: Uri, tracks: List<MediaFile>) {
        val entries = tracks.map { track ->
            val file = localFile(track.uri)
            M3uPlaylist.Entry(
                file?.path ?: track.uri.toString(),
                track.playlistTitle ?: track.displayName,
                track.durationMs,
            )
        }
        val output = resolver.openOutputStream(destination, "wt")
            ?: throw IOException("Could not open the export destination")
        output.bufferedWriter(Charsets.UTF_8).use { M3uPlaylist.write(it, entries) }
    }

    private fun resolve(
        location: String,
        parent: File?,
        trees: List<Pair<File, Uri>>,
        relativeFolder: Uri?,
    ): Uri? {
        if ('\u0000' in location) return null
        val uri = Uri.parse(location)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme == "content") return uri.takeIf(::canRead)
        if (scheme != null && scheme != "file") {
            return uri.takeIf { scheme in Utils.PROTOCOLS && "://" in location }
        }

        val path = if (scheme == "file") uri.path ?: return null else location.replace('\\', '/')
        val file = File(path)
        val local = if (file.isAbsolute) file else parent?.let { File(it, path) }
        if (local != null) {
            val target = normalizeStoragePath(local)
            for ((root, tree) in trees) {
                val document = documentInTree(root, tree, target) ?: continue
                if (canRead(document)) return document
            }
            Uri.fromFile(target).takeIf(::canRead)?.let { return it }
        }
        return if (!file.isAbsolute && relativeFolder != null) {
            resolveRelativeDocument(relativeFolder, file)
        } else null
    }

    private fun resolveRelativeDocument(tree: Uri, path: File): Uri? {
        val parts = path.normalize().path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null

        var parentId = DocumentsContract.getTreeDocumentId(tree)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        for (name in parts) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            parentId = runCatching {
                resolver.query(children, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == name) return@use cursor.getString(0)
                    }
                    null
                }
            }.getOrNull() ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, parentId).takeIf(::canRead)
    }

    private fun documentInTree(root: File, tree: Uri, target: File): Uri? {
        val prefix = root.path.trimEnd('/') + "/"
        if (!target.path.startsWith(prefix)) return null
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val separator = if (rootId.endsWith(':')) "" else "/"
        val id = rootId + separator + target.path.removePrefix(prefix)
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private fun canRead(uri: Uri): Boolean = try {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).isFile && File(it).canRead() } == true
        } else {
            resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun describe(uri: Uri, isAvailable: Boolean = true): MediaFile {
        val documentName = if (isAvailable && uri.scheme == "content") {
            queryString(uri, OpenableColumns.DISPLAY_NAME)
        } else null
        val name = documentName ?: Utils.fileBasename(uri.toString().replace('\\', '/'))
            .substringAfterLast('/').ifBlank { uri.toString() }
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mimeType = if (isAvailable && uri.scheme == "content") {
            runCatching { resolver.getType(uri) }.getOrNull()
        } else null
        return MediaFile(
            uri,
            name,
            mimeType ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty(),
            0L,
            isAvailable = isAvailable,
        )
    }

    private fun localFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.let(::normalizeStoragePath)
        val path = when (uri.authority) {
            EXTERNAL_STORAGE -> return documentId(uri)?.let(::externalFile)
            "media" -> queryString(uri, MediaStore.MediaColumns.DATA)
            "com.android.providers.downloads.documents" -> documentId(uri)
                ?.takeIf { it.startsWith("raw:") }
                ?.removePrefix("raw:")
            else -> null
        }
        return path?.let(::File)?.takeIf { it.isAbsolute }?.let(::normalizeStoragePath)
    }

    private fun queryString(uri: Uri, column: String): String? =
        runCatching {
            resolver.query(uri, arrayOf(column), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(column)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()

    private fun documentId(uri: Uri, tree: Boolean = false): String? =
        runCatching {
            if (tree) DocumentsContract.getTreeDocumentId(uri)
            else DocumentsContract.getDocumentId(uri)
        }.getOrNull()

    private fun externalFile(id: String): File? {
        val volume = id.substringBefore(':', "")
        if (volume.isBlank() || volume == "." || volume == ".." || '/' in volume) return null
        val root = if (volume == "primary") Environment.getExternalStorageDirectory()
            else File("/storage", volume)
        return File(root, id.substringAfter(':').trimStart('/')).normalize()
    }

    private fun normalizeStoragePath(file: File): File {
        val path = file.path
        val aliases = listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")
        val alias = aliases.firstOrNull { path == it || path.startsWith("$it/") }
        return if (alias == null) file.normalize()
        else File(Environment.getExternalStorageDirectory(), path.removePrefix(alias).trimStart('/'))
            .normalize()
    }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }
}
