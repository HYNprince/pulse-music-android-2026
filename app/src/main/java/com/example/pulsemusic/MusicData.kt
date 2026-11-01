package com.example.pulsemusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Hand-curated recordings with source-page licensing. Not a mainstream catalog. */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val streamUrl: String,
    val sourcePage: String = "",
    val license: String = "",
    val isDeviceFile: Boolean = false,
    val gradient: Int = 0
)

val publicDomainDemos = listOf(
    Song(
        id = "fur-elise-cc0",
        title = "Für Elise",
        artist = "Ludwig van Beethoven · Gaodifan",
        streamUrl = "https://commons.wikimedia.org/wiki/Special:Redirect/file/FurElise.ogg",
        sourcePage = "https://commons.wikimedia.org/wiki/File:FurElise.ogg",
        license = "CC0 1.0 — Commons recording",
        gradient = 0
    ),
    Song(
        id = "moonlight-pd",
        title = "Moonlight Sonata",
        artist = "Ludwig van Beethoven · Mutopia",
        streamUrl = "https://commons.wikimedia.org/wiki/Special:Redirect/file/Moonlight_Sonata.ogg",
        sourcePage = "https://commons.wikimedia.org/wiki/File:Moonlight_Sonata.ogg",
        license = "Public domain — Commons recording",
        gradient = 1
    )
)

class MusicRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pulse-v1", Context.MODE_PRIVATE)
    private val offlineDir = File(context.filesDir, "offline").apply { mkdirs() }

    fun favorites(): Set<String> = prefs.getStringSet("favoriteIds", emptySet())?.toSet() ?: emptySet()
    fun setFavorites(ids: Set<String>) { prefs.edit().putStringSet("favoriteIds", ids).apply() }

    fun importedFiles(): List<Song> = runCatching {
        val raw = prefs.getString("deviceFiles", "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map { index ->
            val obj = arr.getJSONObject(index)
            Song(
                id = obj.getString("id"),
                title = obj.getString("title"),
                artist = "On your device",
                streamUrl = obj.getString("uri"),
                isDeviceFile = true,
                gradient = 2 + (index % 3)
            )
        }
    }.getOrDefault(emptyList())

    fun addDeviceFiles(uris: List<Uri>): List<Song> {
        val songs = importedFiles().associateBy { it.id }.toMutableMap()
        for (uri in uris) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "Local audio"
            val cleanName = name.substringBeforeLast('.', name).replace('_', ' ')
            val id = "device:${uri}"
            songs[id] = Song(id, cleanName, "On your device", uri.toString(), isDeviceFile = true, gradient = 2)
        }
        val result = songs.values.toList()
        val json = JSONArray().apply {
            result.forEach { song ->
                put(JSONObject().put("id", song.id).put("title", song.title).put("uri", song.streamUrl))
            }
        }
        prefs.edit().putString("deviceFiles", json.toString()).apply()
        return result
    }

    fun offlinePath(song: Song): File? {
        if (song.isDeviceFile) return null
        val file = File(offlineDir, "${song.id}.ogg")
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    fun playableUri(song: Song): Uri = offlinePath(song)?.let(Uri::fromFile) ?: Uri.parse(song.streamUrl)

    /** Foreground-in-app starter downloader, not yet an Android background download service. */
    suspend fun downloadSong(song: Song, report: suspend (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(!song.isDeviceFile && song.sourcePage.isNotBlank()) { "This track cannot be downloaded" }
        offlinePath(song)?.let { return@withContext it }
        val destination = File(offlineDir, "${song.id}.ogg")
        val temp = File(offlineDir, "${song.id}.part")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(song.streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 25_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PulseMusicPrototype/0.1 (educational Android music player)")
                connect()
            }
            if (connection.responseCode !in 200..299) error("Audio host returned HTTP ${connection.responseCode}")
            val length = connection.contentLengthLong
            var written = 0L
            var reported = -1
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        written += read
                        if (written > 80_000_000L) error("Demo download exceeds 80 MB limit")
                        output.write(buffer, 0, read)
                        if (length > 0) {
                            val percent = ((written * 100) / length).toInt().coerceIn(0, 100)
                            if (percent >= reported + 5) {
                                reported = percent
                                report(percent / 100f)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (written == 0L || (length > 0L && written != length)) error("Download incomplete")
            if (!temp.renameTo(destination)) error("Could not save offline file")
            report(1f)
            destination
        } catch (t: Throwable) {
            temp.delete()
            throw t
        } finally {
            connection?.disconnect()
        }
    }
}
