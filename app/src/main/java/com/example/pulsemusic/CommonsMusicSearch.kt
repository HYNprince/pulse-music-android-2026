package com.example.pulsemusic

import android.net.Uri
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-demand search for ACTUAL audio files on Wikimedia Commons.
 * This is not Spotify search: only Ogg files explicitly marked CC0 or
 * public-domain in Wikimedia's machine-readable metadata are exposed.
 * No private keys, scraping of streaming services, or copyrighted catalog.
 *
 * Commons is a volunteer-run host with API limits; use sparingly.
 */
class CommonsMusicSearch {
    suspend fun search(text: String): List<Song> = withContext(Dispatchers.IO) {
        val query = text.trim().take(70)
        if (query.length < 3) return@withContext emptyList()
        val endpoint = Uri.parse("https://commons.wikimedia.org/w/api.php")
            .buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrnamespace", "6")
            .appendQueryParameter("gsrsearch", "$query filetype:audio")
            .appendQueryParameter("gsrlimit", "20")
            .appendQueryParameter("prop", "imageinfo")
            .appendQueryParameter("iiprop", "url|mime|size|extmetadata")
            .appendQueryParameter("iiextmetadatafilter",
                "LicenseShortName|LicenseUrl|Artist|ObjectName|Copyrighted")
            .appendQueryParameter("iiextmetadatalanguage", "en")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("formatversion", "2")
            .build().toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 14_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent",
                "PulseMusic/0.5 (https://github.com/HYNprince/pulse-music-android-2026)")
        }
        try {
            val status = conn.responseCode
            if (status == 429 || status == 503) error("Music source is busy. Please try again shortly.")
            if (status !in 200..299) error("Music search is temporarily unavailable (HTTP $status)")
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val pages = JSONObject(raw).optJSONObject("query")?.optJSONArray("pages")
                ?: return@withContext emptyList()
            val found = mutableListOf<Song>()
            for (i in 0 until pages.length()) {
                val page = pages.optJSONObject(i) ?: continue
                val fileTitle = page.optString("title")
                val filename = fileTitle.removePrefix("File:")
                if (!filename.endsWith(".ogg", true) && !filename.endsWith(".oga", true)) continue
                val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val mime = info.optString("mime").lowercase()
                if (mime != "application/ogg" && mime != "audio/ogg" &&
                    mime != "audio/x-vorbis+ogg") continue
                val size = info.optLong("size", -1L)
                if (size <= 0L || size > 80_000_000L) continue
                val meta = info.optJSONObject("extmetadata") ?: continue
                fun value(key: String): String =
                    meta.optJSONObject(key)?.optString("value").orEmpty()
                val license = clean(value("LicenseShortName"), 80)
                val licenseUrl = value("LicenseUrl").lowercase()
                val isCC0 = license.contains("CC0", ignoreCase = true) ||
                    licenseUrl.contains("creativecommons.org/publicdomain/zero/")
                val isPD = license.contains("public domain", ignoreCase = true) ||
                    licenseUrl.contains("creativecommons.org/publicdomain/mark/")
                if (!isCC0 && !isPD) continue
                val artist = clean(value("Artist"), 92).ifBlank { "Wikimedia Commons contributor" }
                val objectName = clean(value("ObjectName"), 140)
                val displayTitle = objectName.ifBlank {
                    filename.substringBeforeLast(".").replace('_', ' ')
                }.take(130)
                val pageId = page.optLong("pageid", -1)
                if (pageId <= 0L) continue
                val filePath = Uri.encode(filename)
                found.add(
                    Song(
                        id = "commons-$pageId",
                        title = displayTitle,
                        artist = artist,
                        streamUrl = "https://commons.wikimedia.org/wiki/Special:Redirect/file/$filePath",
                        sourcePage = "https://commons.wikimedia.org/wiki/File:$filePath",
                        license = if (isCC0) "CC0 1.0 · Wikimedia Commons" else
                            "Public domain · Wikimedia Commons",
                        genre = "Commons",
                        gradient = (pageId % 5).toInt()
                    )
                )
                if (found.size == 12) break
            }
            found
        } finally {
            conn.disconnect()
        }
    }

    private fun clean(raw: String, limit: Int): String {
        if (raw.isBlank()) return ""
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ").trim().take(limit)
    }
}
