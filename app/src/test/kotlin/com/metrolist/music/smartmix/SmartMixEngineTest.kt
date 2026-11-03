/**
 * Gmac Smart Mix ranking regression tests. GPL-3.0.
 */
package com.metrolist.music.smartmix

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMixEngineTest {
    private val now = LocalDateTime.of(2026, 11, 3, 12, 0)

    private fun candidate(
        id: String,
        artist: String = id,
        liked: Boolean = false,
        local: Boolean = false,
        downloaded: Boolean = false,
        explicit: Boolean = false,
        recent: Long = 0,
        older: Long = 0,
        lastPlayed: LocalDateTime? = null,
    ) = SmartMixCandidate(
        id = id,
        title = id,
        artistId = artist,
        liked = liked,
        isLocal = local,
        isDownloaded = downloaded,
        explicit = explicit,
        lifetimePlayMs = recent + older,
        recentPlayMs = recent,
        olderPlayMs = older,
        lastPlayedAt = lastPlayed,
    )

    @Test fun `empty history is safe`() {
        assertTrue(SmartMixEngine.generate(emptyList(), SmartMixPreset.BALANCED, 25, false, now = now).isEmpty())
    }

    @Test fun `offline mix excludes streaming-only and deduplicates songs`() {
        val songs = listOf(candidate("local", local = true), candidate("offline", downloaded = true),
            candidate("stream"), candidate("local", local = true))
        val result = SmartMixEngine.generate(songs, SmartMixPreset.OFFLINE, 25, false, now = now)
        assertEquals(setOf("local", "offline"), result.map { it.id }.toSet())
        assertEquals(2, result.size)
    }

    @Test fun `rediscover favors previously listened music over new unseen songs`() {
        val songs = listOf(
            candidate("older", liked = true, older = 1_200_000, lastPlayed = now.minusDays(100)),
            candidate("new", lastPlayed = null),
            candidate("recent", recent = 900_000, lastPlayed = now.minusDays(1)),
        )
        val result = SmartMixEngine.generate(songs, SmartMixPreset.REDISCOVER, 25, false, now = now)
        assertEquals(listOf("older"), result.map { it.id })
    }

    @Test fun `same inputs and seed return stable order`() {
        val songs = (1..20).map { candidate("song-$it", liked = it % 2 == 0, recent = it * 10000L) }
        val first = SmartMixEngine.generate(songs, SmartMixPreset.FAVORITES, 10, false, seed = 12, now = now)
        val second = SmartMixEngine.generate(songs, SmartMixPreset.FAVORITES, 10, false, seed = 12, now = now)
        assertEquals(first, second)
        assertEquals(10, first.size)
        assertTrue(first.all { it.reason.isNotBlank() })
    }

    @Test fun `diversifies artists when enough choices exist`() {
        val songs = (1..10).map { candidate("a-$it", artist = "artist-a", liked = true) } +
            (1..6).map { candidate("b-$it", artist = "artist-b", liked = false) } +
            (1..6).map { candidate("c-$it", artist = "artist-c", liked = false) }
        val result = SmartMixEngine.generate(songs, SmartMixPreset.FAVORITES, 6, false, now = now)
        assertEquals(6, result.size)
        assertEquals(2, result.count { it.id.startsWith("a-") })
    }

    @Test fun `explicit filter and requested count are applied`() {
        val songs = (1..25).map { candidate("song-$it", explicit = it % 2 == 0) }
        val result = SmartMixEngine.generate(songs, SmartMixPreset.BALANCED, 8, false, now = now, hideExplicit = true)
        assertEquals(8, result.size)
        assertTrue(result.none { it.id.removePrefix("song-").toInt() % 2 == 0 })
    }
}
