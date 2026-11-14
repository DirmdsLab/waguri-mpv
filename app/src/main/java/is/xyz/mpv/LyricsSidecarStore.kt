package `is`.xyz.mpv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.Reader
import java.util.Locale

/**
 * Reads and writes a sibling .lrc file next to the selected audio/video item.
 *
 * File URIs use normal sibling files, MediaStore items use the same RELATIVE_PATH
 * and volume, and SAF items use the parent document in the granted tree.
 */
internal class LyricsSidecarStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun load(media: MediaFile): LyricsResult? {
        val sidecarName = sidecarName(media.displayName)
        return findExistingSidecar(media, sidecarName)
            ?.let { readSidecar(it, media.displayName) }
    }

    @Synchronized
    fun saveIfAbsent(media: MediaFile, result: LyricsResult): LyricsResult = save(media, result)

    @Synchronized
    fun saveWithDelay(media: MediaFile, result: LyricsResult, delayMs: Long): LyricsResult =
        save(media, result, delayMs)

    private fun save(media: MediaFile, result: LyricsResult, delayMs: Long? = null): LyricsResult {
        val sidecarName = sidecarName(media.displayName)
        val existing = findExistingSidecar(media, sidecarName)
        if (existing != null) {
            val original = readSidecarText(existing)
            val current = parseLrc(original, media.displayName)
            if (delayMs == null) return current
            val content = if (current.lines == result.lines) {
                LyricsRepository.retimeLrc(original, delayMs)
            } else {
                encodeLrc(result, delayMs)
            }
            overwriteSidecar(existing, original, content)
            return parseLrc(content, media.displayName)
        }

        val content = encodeLrc(result, delayMs ?: 0L)
        val documentParent = resolveDocumentParent(media)

        if (documentParent != null) {
            saveDocument(documentParent, sidecarName, content)
        } else if (media.uri.scheme == "file") {
            val parent = localMediaFile(media.uri)?.parentFile ?: throw IOException("Media folder is unavailable")
            val target = File(parent, sidecarName)
            if (!target.createNewFile()) {
                if (delayMs == null) return readSidecar(Uri.fromFile(target), media.displayName)
                throw IOException("The LRC file appeared while saving. Try again.")
            }
            try {
                target.writeText(content, Charsets.UTF_8)
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        } else if (isMediaStoreUri(media.uri)) {
            saveMediaStore(media, sidecarName, content)
        } else {
            throw IOException("This media provider does not expose a writable parent folder")
        }
        return parseLrc(content, media.displayName)
    }

    private fun findExistingSidecar(media: MediaFile, name: String): Uri? {
        val names = sidecarCandidates(name, media.displayName)
        val documentParent = resolveDocumentParent(media)
        val document = documentParent?.let { parent ->
            try {
                findDocumentChild(parent, names)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (document != null) return document

        val indexedUri = mediaStoreUri(media.uri)
        val folder = localMediaFile(indexedUri ?: media.uri)?.parentFile
        val sibling = folder?.let { findFileChild(it, names) }
        if (sibling != null) return Uri.fromFile(sibling)

        return indexedUri?.let { findMediaStoreSidecar(media.copy(uri = it), names) }
    }

    private fun readSidecar(uri: Uri, mediaName: String): LyricsResult {
        return parseLrc(readSidecarText(uri), mediaName)
    }

    private fun readSidecarText(uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the LRC file")
        return input.bufferedReader(Charsets.UTF_8).use(::readLrcText)
    }

    private fun overwriteSidecar(uri: Uri, original: String, content: String) {
        if (content.length > MAX_LRC_CHARS) throw IOException("The updated LRC file is too large")
        val path = uri.path
        if (uri.scheme == "file" && path != null) {
            val file = AtomicFile(File(path))
            val stream = file.startWrite()
            try {
                stream.write(content.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            if (readSidecarText(uri) != content) throw IOException("The LRC update did not reach storage")
            return
        }

        writeDocument(uri, content, original = original)
    }

    private fun readLrcText(reader: Reader): String {
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) return text.toString()
            if (text.length + count > MAX_LRC_CHARS) {
                throw IOException("The LRC sidecar is unexpectedly large")
            }
            text.append(buffer, 0, count)
        }
    }

    private fun isMediaStoreUri(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == "media"

    private fun mediaStoreUri(uri: Uri): Uri? {
        if (isMediaStoreUri(uri)) return uri
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isDocumentUri(uri)) return null
        return runCatching { MediaStore.getMediaUri(appContext, uri) }.getOrNull()
    }

    private fun isDocumentUri(uri: Uri): Boolean =
        uri.scheme == "content" &&
            runCatching { DocumentsContract.isDocumentUri(appContext, uri) }.getOrDefault(
                uri.authority?.contains("documents", ignoreCase = true) == true
            )

    private fun sidecarName(displayName: String): String {
        val base = displayName.substringBeforeLast('.', displayName)
        if (base.isBlank() || base == "." || base == ".." || '/' in displayName || '\u0000' in displayName) {
            throw IOException("Invalid media filename")
        }
        return "$base.lrc"
    }

    private fun saveMediaStore(media: MediaFile, name: String, content: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Saving beside MediaStore items requires Android 10 or newer")
        }
        val folder = mediaStoreFolder(media)
            ?: throw IOException("The media folder path is unavailable")
        val collection = MediaStore.Files.getContentUri(folder.volume)
        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder.path)
            },
        ) ?: throw IOException("Android could not create the LRC file")
        writeNewDocument(uri, content) { resolver.delete(uri, null, null) }
    }

    private data class MediaStoreFolder(val path: String, val volume: String)

    private fun mediaStoreFolder(media: MediaFile): MediaStoreFolder? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val path = queryString(media.uri, MediaStore.MediaColumns.RELATIVE_PATH)
            ?: media.folderPath.takeIf { it.isNotBlank() }
            ?: return null
        val volume = queryString(media.uri, MediaStore.MediaColumns.VOLUME_NAME)
            ?: media.volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        return MediaStoreFolder(normalizeRelativePath(path), volume)
    }

    private fun findMediaStoreSidecar(media: MediaFile, names: List<String>): Uri? {
        val folder = mediaStoreFolder(media) ?: return null
        val collection = MediaStore.Files.getContentUri(folder.volume)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND (" +
            names.joinToString(" OR ") { "${MediaStore.MediaColumns.DISPLAY_NAME}=? COLLATE NOCASE" } + ")"
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val children = resolver.query(
            collection, projection, selection, (listOf(folder.path) + names).toTypedArray(), null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    add(name to ContentUris.withAppendedId(collection, cursor.getLong(0)))
                }
            }
        }.orEmpty()
        return matchSidecar(children, names)
    }

    private fun saveDocument(parent: Uri, name: String, content: String) {
        val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: throw IOException("The selected document provider could not create an LRC file")
        writeNewDocument(uri, content) { DocumentsContract.deleteDocument(resolver, uri) }
    }

    private fun writeNewDocument(uri: Uri, content: String, delete: () -> Unit) {
        try {
            writeDocument(uri, content)
        } catch (error: Exception) {
            runCatching(delete)
            throw error
        }
    }

    private fun writeDocument(uri: Uri, content: String, original: String? = null) {
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Android could not open the LRC file for writing")
        try {
            output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            if (original != null && readSidecarText(uri) != content) {
                throw IOException("The LRC update did not reach storage")
            }
        } catch (error: Exception) {
            // A provider can truncate the file before reporting a write error.
            if (original != null) {
                runCatching { writeDocument(uri, original) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private fun findDocumentChild(parent: Uri, names: List<String>): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val children = resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val documentId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (names.none { name.equals(it, ignoreCase = true) }) continue
                    add(name to DocumentsContract.buildDocumentUriUsingTree(parent, documentId))
                }
            }
        }.orEmpty()
        return matchSidecar(children, names)
    }

    private fun resolveDocumentParent(media: MediaFile): Uri? {
        val uri = media.uri
        if (isDocumentUri(uri) && uri.pathSegments.firstOrNull() == "tree") {
            media.parentDocumentUri?.let { return it }
            val rootId = DocumentsContract.getTreeDocumentId(uri)
            val documentId = DocumentsContract.getDocumentId(uri)
            val parentId = if (uri.authority == EXTERNAL_STORAGE) {
                externalParentId(documentId)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { DocumentsContract.findDocumentPath(resolver, uri)?.path }
                    .getOrNull()?.dropLast(1)?.lastOrNull()
            } else null
            return parentId?.takeIf { it != documentId && it.isNotBlank() }
                ?.takeIf { uri.authority != EXTERNAL_STORAGE || isWithinTree(it, rootId) }
                ?.let { DocumentsContract.buildDocumentUriUsingTree(uri, it) }
        }

        val documentId = if (isDocumentUri(uri) && uri.authority == EXTERNAL_STORAGE) {
            DocumentsContract.getDocumentId(uri)
        } else {
            localMediaFile(mediaStoreUri(uri) ?: uri)?.let(::externalDocumentId)
        } ?: return null
        val parentId = externalParentId(documentId) ?: return null
        val tree = resolver.persistedUriPermissions.asSequence()
            .filter { it.isReadPermission && it.uri.authority == EXTERNAL_STORAGE }
            .mapNotNull { grant ->
                runCatching { DocumentsContract.getTreeDocumentId(grant.uri) }
                    .getOrNull()?.let { it to grant.uri }
            }
            .filter { (rootId, _) -> isWithinTree(parentId, rootId) }
            .maxByOrNull { (rootId, _) -> rootId.length }
            ?.second ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
    }

    private fun localMediaFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)
        if (isMediaStoreUri(uri)) {
            queryString(uri, MediaStore.MediaColumns.DATA)?.let(::File)?.takeIf { it.isAbsolute }
                ?.let { return it }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val folder = queryString(uri, MediaStore.MediaColumns.RELATIVE_PATH) ?: return null
            val name = queryString(uri, MediaStore.MediaColumns.DISPLAY_NAME) ?: return null
            val volume = queryString(uri, MediaStore.MediaColumns.VOLUME_NAME) ?: return null
            val documentVolume = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "primary" else volume
            val documentPath = listOf(folder.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")
            return externalStorageFile("$documentVolume:$documentPath")
        }
        if (!isDocumentUri(uri)) return null

        val id = DocumentsContract.getDocumentId(uri)
        if (uri.authority == DOWNLOADS && id.startsWith("raw:")) {
            return File(id.removePrefix("raw:")).takeIf { it.isAbsolute }
        }
        return if (uri.authority == EXTERNAL_STORAGE) externalStorageFile(id) else null
    }

    private fun externalStorageFile(id: String): File? {
        val volume = id.substringBefore(':', "")
        if (volume.isBlank() || volume == "." || volume == ".." || '/' in volume) return null
        val root = if (volume == "primary") Environment.getExternalStorageDirectory()
            else File("/storage", volume)
        return File(root, id.substringAfter(':').trimStart('/')).normalize()
    }

    private fun externalDocumentId(file: File): String? {
        val path = file.normalize().path
        val primary = Environment.getExternalStorageDirectory().path.trimEnd('/')
        val primaryPaths = listOf(primary, "/sdcard", "/mnt/sdcard", "/storage/self/primary")
        val prefix = primaryPaths.firstOrNull { path.startsWith("$it/") }
        if (prefix != null) return "primary:${path.removePrefix("$prefix/")}"
        if (!path.startsWith("/storage/")) return null
        val relative = path.removePrefix("/storage/")
        val volume = relative.substringBefore('/')
        val documentPath = relative.substringAfter('/', "")
        if (volume.isBlank() || documentPath.isBlank() || volume == "emulated") return null
        return "$volume:$documentPath"
    }

    private fun queryString(uri: Uri, column: String): String? =
        runCatching {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun normalizeRelativePath(path: String): String {
        val normalized = path.trim().trim('/')
        return if (normalized.isBlank()) "" else "$normalized/"
    }

    private fun parseLrc(text: String, mediaName: String): LyricsResult {
        val source = text.removePrefix("\uFEFF")
        val title =
            Regex("(?im)^\\[ti:(.*?)]\\s*$")
                .find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
        val artist =
            Regex("(?im)^\\[ar:(.*?)]\\s*$")
                .find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
        val lines = LyricsRepository.parseLrc(source)
        val instrumental = Regex("(?im)^\\[instrumental]\\s*$").containsMatchIn(source)
        val plain =
            source.lineSequence()
                .filterNot { it.trim().matches(Regex("^\\[[a-zA-Z]+:.*]$")) }
                .filterNot { it.trim().matches(Regex("^\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?].*")) }
                .joinToString("\n")
                .trim()

        val fallbackTitle = mediaName.substringBeforeLast('.', mediaName)
        return LyricsResult(
            title = title.ifBlank { fallbackTitle },
            artist = artist,
            plain = plain,
            lines = lines,
            instrumental = instrumental,
        )
    }

    private fun encodeLrc(result: LyricsResult, delayMs: Long): String {
        fun tag(value: String): String = value.replace("]", ")").replace("\n", " ").trim()
        val header = buildList {
            if (result.title.isNotBlank()) add("[ti:${tag(result.title)}]")
            if (result.artist.isNotBlank()) add("[ar:${tag(result.artist)}]")
            add("[by:UwU Player]")
            if (result.instrumental) add("[instrumental]")
        }
        val timed =
            result.lines.sortedBy { it.milliseconds }.map { line ->
                val time = (line.milliseconds + delayMs).coerceAtLeast(0L)
                LyricsRepository.formatLrcTimestamp(time) + line.text.replace("\r", "").replace("\n", " ")
            }
        val body =
            if (timed.isNotEmpty()) timed.joinToString("\n")
            else result.plain.trim()
        return (header + body).filter { it.isNotBlank() }.joinToString("\n") + "\n"
    }

    companion object {
        private const val MAX_LRC_CHARS = 1_000_000
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        private const val DOWNLOADS = "com.android.providers.downloads.documents"

        internal fun sidecarCandidates(name: String, mediaName: String): List<String> {
            val legacyName = name.removeSuffix(".lrc").trim() + ".lrc"
            val names = listOf(name, "$mediaName.lrc", legacyName)
            // Older versions used text/plain, and some providers appended .txt.
            return (names + names.map { "$it.txt" }).distinct()
        }

        internal fun externalParentId(documentId: String): String? {
            val volume = documentId.substringBefore(':', "")
            val path = documentId.substringAfter(':', "").trim('/')
            if (volume.isBlank() || path.isBlank()) return null
            return "$volume:${path.substringBeforeLast('/', "")}"
        }

        internal fun isWithinTree(documentId: String, rootId: String): Boolean {
            val prefix = if (rootId.endsWith(':')) rootId else "$rootId/"
            return documentId == rootId || documentId.startsWith(prefix)
        }

        internal fun findFileChild(folder: File, names: List<String>): File? {
            val children = folder.listFiles()?.filter(File::isFile).orEmpty().map { it.name to it }
            return matchSidecar(children, names)
                ?: names.firstNotNullOfOrNull { name -> File(folder, name).takeIf { it.isFile } }
        }

        private fun <T> matchSidecar(children: List<Pair<String, T>>, names: List<String>): T? {
            for (name in names) {
                val match = children.firstOrNull { it.first == name }
                    ?: children.firstOrNull { it.first.equals(name, ignoreCase = true) }
                if (match != null) return match.second
            }
            return null
        }
    }
}
