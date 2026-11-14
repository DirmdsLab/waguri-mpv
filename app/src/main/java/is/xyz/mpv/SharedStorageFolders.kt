package `is`.xyz.mpv

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

internal object SharedStorageFolders {
    data class Volume(val root: File, val label: String)

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    @RequiresApi(30)
    fun volumes(context: Context): List<Volume> {
        val manager = context.getSystemService(StorageManager::class.java)
        val volumes =
            manager?.storageVolumes.orEmpty().mapNotNull { volume ->
                if (
                    volume.state != Environment.MEDIA_MOUNTED &&
                        volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
                )
                    return@mapNotNull null

                val directory = volume.directory ?: return@mapNotNull null
                val root =
                    canonicalFile(directory)?.takeIf { it.isDirectory && it.canRead() }
                        ?: return@mapNotNull null
                Volume(root, volume.getDescription(context))
            }
        val primary = canonicalFile(Environment.getExternalStorageDirectory())
        val fallback =
            if (primary != null && primary.isDirectory && primary.canRead()) {
                listOf(Volume(primary, context.getString(R.string.internal_storage_label)))
            } else emptyList()
        return (volumes + fallback).distinctBy { it.root }
    }

    fun contains(root: File, entry: File): Boolean =
        entry == root || entry.path.startsWith(root.path + File.separator)

    fun canonicalFile(file: File): File? =
        try {
            file.canonicalFile
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    /** Skip symbolic links so browsing and recursive scans stay in the selected tree. */
    fun readableEntries(directory: File): List<File>? =
        try {
            directory.listFiles()?.filter { entry ->
                canonicalFile(entry) == entry && entry.canRead()
            }
        } catch (_: SecurityException) {
            null
        }
}
