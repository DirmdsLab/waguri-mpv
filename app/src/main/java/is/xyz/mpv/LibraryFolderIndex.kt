package `is`.xyz.mpv

import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray

internal class LibraryFolderIndex(private val preferences: SharedPreferences) {
    fun read(): List<Uri> {
        val stored = preferences.getString(PREFERENCE_FOLDERS, null)
        val folders = if (stored == null) {
            emptyList()
        } else {
            runCatching {
                val entries = JSONArray(stored)
                List(entries.length()) { index -> entries.getString(index) }
            }.getOrDefault(emptyList())
        }
        return (folders + listOfNotNull(preferences.getString(ACTIVE_FOLDER_KEY, null)))
            .filter { it.isNotBlank() }
            .distinct()
            .map(Uri::parse)
    }

    fun write(editor: SharedPreferences.Editor, folders: List<Uri>): SharedPreferences.Editor =
        editor.putString(PREFERENCE_FOLDERS, JSONArray(folders.distinct().map(Uri::toString)).toString())

    companion object {
        const val ACTIVE_FOLDER_KEY = "media_tree_uri"
        private const val PREFERENCE_FOLDERS = "media_folder_index"
    }
}
