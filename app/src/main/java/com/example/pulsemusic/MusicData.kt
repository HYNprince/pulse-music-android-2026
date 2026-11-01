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
    val gradient: Int = 0,
    val genre: String = "Classical",
    val durationSeconds: Int = 0
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

/** Curated Wikimedia Commons recordings: track metadata, author, source and license checked.
 * Small demo catalog, NOT a general music service. Rights and hosting must be rechecked
 * before a commercial launch. Each recording uses Wikimedia's canonical redirect endpoint.
 */
private fun commonsTrack(
    id: String, title: String, artist: String, filename: String,
    license: String, genre: String, seconds: Int, gradient: Int
): Song {
    val encoded = Uri.encode(filename)
    return Song(id = id, title = title, artist = artist,
        streamUrl = "https://commons.wikimedia.org/wiki/Special:Redirect/file/" + encoded,
        sourcePage = "https://commons.wikimedia.org/wiki/File:" + encoded,
        license = license, genre = genre, durationSeconds = seconds, gradient = gradient)
}

val curatedCommonsTracks = listOf(
    commonsTrack("cc0-smells-like-rain", "Smells Like Rain", "Thecolororangeiscool",
        "Smells Like Rain.ogg", "CC0 1.0 · original by Thecolororangeiscool",
        "Indie", 180, 3),
    commonsTrack("cc0-one-cool-minute", "One Cool Minute", "Loyalty Freak Music",
        "Loyalty Freak Music - 02 - One Cool Minute.ogg",
        "CC0 1.0 · Loyalty Freak Music", "Electronic", 193, 4),
    commonsTrack("cc0-everyone-alive", "Everyone Is So Alive", "Loyalty Freak Music",
        "Loyalty Freak Music - 01 - Everyone is so alive.ogg",
        "CC0 1.0 · Loyalty Freak Music", "Electronic", 255, 0),
    commonsTrack("cc0-work", "Work", "Loyalty Freak Music",
        "Loyalty Freak Music - 13 - Work.ogg",
        "CC0 1.0 · Loyalty Freak Music", "Electronic", 145, 1),
    commonsTrack("cc0-everyone", "Everyone", "Loyalty Freak Music",
        "Loyalty Freak Music - 06 - Everyone.ogg",
        "CC0 1.0 · Loyalty Freak Music", "Electronic", 289, 2),
    commonsTrack("cc0-no-cadillac", "No Cadillac", "Loyalty Freak Music",
        "Loyalty Freak Music - No Cadillac.ogg",
        "CC0 1.0 · Loyalty Freak Music", "Electronic", 171, 3),
    commonsTrack("cc0-chopin-nocturne", "Nocturne in E Minor", "Frédéric Chopin · Peter Johnston",
        "Chopin Nocturne in Em, Op. posth. 72.ogg",
        "CC0 1.0 · performance by Peter Johnston via Musopen", "Classical", 237, 2),
    commonsTrack("cc0-horroriffic", "Horroriffic", "Kevin MacLeod",
        "Kevin MacLeod - Horroriffic.ogg",
        "CC0 1.0 · Kevin MacLeod via FreePD", "Ambient", 168, 4),
    commonsTrack("cc0-spaceship", "Alien Spaceship Atmosphere", "Kevin MacLeod",
        "Kevin MacLeod - Alien Spaceship Atmosphere (cc0).ogg",
        "CC0 1.0 · Kevin MacLeod via FreePD", "Ambient", 124, 1),
    commonsTrack("cc0-limit70", "Limit 70", "Kevin MacLeod",
        "Kevin MacLeod - Limit 70.ogg",
        "CC0 1.0 · Kevin MacLeod via FreePD", "Ambient", 302, 0),
    commonsTrack("cc0-synth-pop", "Synth Pop with 4 on the Floor", "Mesostic",
        "Synth pop with 4 on the floor.ogg",
        "CC0 1.0 · original song by Mesostic", "Electronic", 198, 2),
    commonsTrack("cc0-juno-synth", "Juno-60 & Jupiter-4", "Raymangold22",
        "Juno-60-jp4.ogg",
        "CC0 1.0 · original synth recording by Raymangold22", "Electronic", 189, 4),
    commonsTrack("cc0-gamebgm", "Game BGM", "Yuyuyunoyuusuke1",
        "GameBGM.ogg",
        "CC0 1.0 · original composition by Yuyuyunoyuusuke1", "Electronic", 237, 3),
    commonsTrack("cc0-stilly-night", "Oft in the Stilly Night", "Endersslay",
        "Oft in the Stilly Night.ogg",
        "CC0 1.0 · violin performance by Endersslay", "Classical", 83, 1)
)

val starterCatalog: List<Song> = curatedCommonsTracks + publicDomainDemos

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

    /** In-app resumable downloader for the curated Ogg recordings only.
     * If interrupted, the .part file remains private and playback never sees it.
     * A later retry issues a Range request; sources that ignore Range restart safely.
     * A true long-running background download service is a separate future upgrade.
     */
    suspend fun downloadSong(song: Song, report: suspend (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(!song.isDeviceFile && song.sourcePage.startsWith("https://commons.wikimedia.org/")) {
                "Only verified Commons recordings are downloadable in this prototype"
            }
            offlinePath(song)?.let { return@withContext it }
            val destination = File(offlineDir, "${song.id}.ogg")
            val partial = File(offlineDir, "${song.id}.part")
            val maxBytes = 80_000_000L

            if (partial.length() >= maxBytes) partial.delete()
            val already = partial.length()
            var connection: HttpURLConnection? = null
            try {
                val url = URL(song.streamUrl)
                require(url.protocol == "https" && url.host == "commons.wikimedia.org") {
                    "Unsupported music host"
                }
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent",
                        "PulseMusic/0.3 (+https://github.com/HYNprince/pulse-music-android-2026)")
                    if (already > 0L) setRequestProperty("Range", "bytes=${already}-")
                    connect()
                }
                val status = connection.responseCode
                if (status !in 200..299) error("Audio source returned HTTP $status")

                // Do not append an entire response if the host ignores Range.
                val append = already > 0L && status == HttpURLConnection.HTTP_PARTIAL
                val start = if (append) already else 0L
                val bodyLength = connection.contentLengthLong
                val expected = if (bodyLength > 0L) start + bodyLength else -1L
                if (expected > maxBytes) error("Recording exceeds 80 MB download limit")
                var total = start
                var reported = -1

                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) error("Recording exceeds 80 MB download limit")
                            output.write(buffer, 0, read)
                            if (expected > 0L) {
                                val percent = ((total * 100) / expected).toInt().coerceIn(0, 100)
                                if (percent - reported >= 5) {
                                    reported = percent
                                    report(percent / 100f)
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (total < 4 || (expected > 0 && total != expected)) {
                    error("Interrupted download. Tap Download again to resume")
                }
                // Prevent HTML error responses from appearing as downloaded audio.
                val signature = partial.inputStream().use { it.readNBytes(4) }
                if (!signature.contentEquals(byteArrayOf(79, 103, 103, 83))) {
                    partial.delete()
                    error("The music host did not return a valid Ogg audio file")
                }
                if (!partial.renameTo(destination)) error("Could not save recording")
                report(1f)
                destination
            } finally {
                connection?.disconnect()
            }
        }

}
