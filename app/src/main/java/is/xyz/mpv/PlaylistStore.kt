package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Stores media URIs and playlist metadata. Access remains owned by Android. */
internal class PlaylistStore(context: Context, storageName: String = "saved_playlists") {
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)

    fun names(): List<String> = preferences.all.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun read(name: String): List<MediaFile> =
        runCatching {
                val array = JSONArray(preferences.getString(name, "[]"))
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    MediaFile(
                        Uri.parse(item.getString("uri")),
                        item.getString("name"),
                        item.optString("mime"),
                        item.optLong("modified"),
                        item.optLong("duration").takeIf { it > 0 },
                        folderPath = item.optString("folder"),
                        volumeName = item.optString("volume").takeIf { it.isNotEmpty() },
                        playlistTitle = item.optString("title").takeIf { it.isNotEmpty() },
                        parentDocumentUri = item.optString("parentDocument")
                            .takeIf { it.isNotEmpty() }?.let(Uri::parse),
                    )
                }
            }
            .getOrDefault(emptyList())
            .withPlaylistOccurrences()

    fun save(name: String, items: List<MediaFile>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri.toString())
                    .put("name", item.displayName)
                    .put("mime", item.mimeType)
                    .put("modified", item.lastModified)
                    .put("duration", item.durationMs ?: 0)
                    .put("folder", item.folderPath)
                    .put("volume", item.volumeName)
                    .put("title", item.playlistTitle)
                    .put("parentDocument", item.parentDocumentUri?.toString())
            )
        }
        preferences.edit().putString(name, array.toString()).apply()
    }

    fun delete(name: String) {
        preferences.edit().remove(name).apply()
    }
}
