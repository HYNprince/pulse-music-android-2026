package com.example.pulsemusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(val id: String, val name: String, val trackIds: List<String>)

/** Device-only playlists: no sign-in, server or music redistribution. */
class LocalPlaylists(context: Context) {
    private val prefs = context.getSharedPreferences("pulse_playlists_v1", Context.MODE_PRIVATE)

    fun list(): List<LocalPlaylist> = runCatching {
        val data = JSONArray(prefs.getString("playlists", "[]"))
        buildList {
            for (i in 0 until minOf(data.length(), 150)) {
                val row = data.optJSONObject(i) ?: continue
                val id = row.optString("id")
                val title = row.optString("name")
                val tracks = row.optJSONArray("songs") ?: JSONArray()
                if (id.isBlank() || title.isBlank()) continue
                add(LocalPlaylist(id, title, (0 until minOf(tracks.length(), 3000))
                    .mapNotNull { j -> tracks.optString(j).takeIf(String::isNotBlank) }))
            }
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<LocalPlaylist>): List<LocalPlaylist> {
        val array = JSONArray().apply {
            items.take(150).forEach { playlist ->
                put(JSONObject().put("id", playlist.id).put("name", playlist.name)
                    .put("songs", JSONArray().apply {
                        playlist.trackIds.take(3000).forEach(::put)
                    }))
            }
        }
        prefs.edit().putString("playlists", array.toString()).apply()
        return items
    }

    fun create(rawName: String): List<LocalPlaylist> {
        val name = rawName.trim().take(65)
        require(name.isNotEmpty()) { "Enter a playlist name" }
        return write(list() + LocalPlaylist("local-" + System.nanoTime(), name, emptyList()))
    }

    fun add(playlistId: String, songId: String): List<LocalPlaylist> = write(
        list().map { playlist ->
            if (playlist.id != playlistId || songId in playlist.trackIds) playlist
            else playlist.copy(trackIds = (playlist.trackIds + songId).take(3000))
        }
    )

    fun remove(playlistId: String, songId: String): List<LocalPlaylist> = write(
        list().map { playlist ->
            if (playlist.id != playlistId) playlist
            else playlist.copy(trackIds = playlist.trackIds.filterNot { it == songId })
        }
    )
}
