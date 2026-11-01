package com.example.pulsemusic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Playlist metadata only. These entries do not contain Spotify audio or playback URLs. */
data class ImportedTrack(val title: String, val artist: String)
data class ImportedPlaylist(val name: String, val tracks: List<ImportedTrack>)

class PlaylistImportStore(private val context: Context) {
    private val destination = File(context.filesDir, "playlist-metadata.json")

    fun savedPlaylists(): List<ImportedPlaylist> = runCatching {
        if (!destination.exists()) emptyList() else parse(destination.readText())
    }.getOrDefault(emptyList())

    suspend fun importSpotifyExport(uri: Uri): List<ImportedPlaylist> = withContext(Dispatchers.IO) {
        // Local SAF import; never upload a user's private Spotify export to a server.
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > 5_000_000) error("Export is larger than 5 MB")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Unable to open this file")
        val data = parse(bytes.toString(Charsets.UTF_8))
        require(data.isNotEmpty()) { "No Spotify playlists found. Select a Playlist*.json export." }
        val combined = savedPlaylists().associateBy { it.name.lowercase() }.toMutableMap()
        for (playlist in data) combined[playlist.name.lowercase()] = playlist
        val total = combined.values.take(100).toList()
        val output = JSONObject().put("playlists", JSONArray().apply {
            total.forEach { playlist ->
                put(JSONObject().put("name", playlist.name).put("items", JSONArray().apply {
                    playlist.tracks.take(5000).forEach { item ->
                        put(JSONObject().put("track", JSONObject()
                            .put("trackName", item.title).put("artistName", item.artist)))
                    }
                }))
            }
        })
        val tmp = File(context.filesDir, "playlist-metadata.tmp")
        tmp.writeText(output.toString())
        if (destination.exists() && !destination.delete()) error("Could not update playlist file")
        if (!tmp.renameTo(destination)) error("Could not save imported playlists")
        total
    }

    private fun parse(raw: String): List<ImportedPlaylist> {
        val root = JSONObject(raw)
        val groups = root.optJSONArray("playlists") ?: JSONArray().apply {
            if (root.has("items")) put(root)
        }
        val result = mutableListOf<ImportedPlaylist>()
        for (i in 0 until minOf(groups.length(), 100)) {
            val playlist = groups.optJSONObject(i) ?: continue
            val name = playlist.optString("name").trim().take(150)
            if (name.isEmpty()) continue
            val items = playlist.optJSONArray("items") ?: continue
            val tracks = mutableListOf<ImportedTrack>()
            for (j in 0 until minOf(items.length(), 5000)) {
                val item = items.optJSONObject(j) ?: continue
                val track = item.optJSONObject("track") ?: item.optJSONObject("localTrack") ?: continue
                val title = track.optString("trackName").trim().take(250)
                val artist = track.optString("artistName").trim().take(250)
                if (title.isNotEmpty()) tracks.add(ImportedTrack(title, artist.ifEmpty { "Unknown artist" }))
            }
            result.add(ImportedPlaylist(name, tracks))
        }
        return result
    }
}

/** Conservative matching: do not claim an unrelated recording is the same song. */
fun findPlayableMatch(track: ImportedTrack, songs: List<Song>): Song? {
    fun normalized(value: String) = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
    val title = normalized(track.title)
    val artist = normalized(track.artist)
    return songs.firstOrNull {
        normalized(it.title) == title &&
            (artist.isBlank() || normalized(it.artist).contains(artist) ||
                artist.contains(normalized(it.artist)))
    }
}
