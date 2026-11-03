/** Gmac Smart Mix Room integration tests (GNU GPL v3). */
package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmartMixDatabaseIntegrationTest {
    private lateinit var database: InternalDatabase
    private val now = LocalDateTime.of(2026, 11, 3, 12, 0)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.dao.insert(
            SongEntity(id = "liked", title = "Liked Song", liked = true, totalPlayTime = 300_000L)
        )
        database.dao.insert(
            SongEntity(id = "offline", title = "Offline Song", isDownloaded = true)
        )
        database.dao.insert(
            SongEntity(id = "unrelated", title = "Unrelated Song")
        )
    }

    @After fun tearDown() { database.close() }

    @Test fun `candidate query aggregates play events without repeating songs`() = runBlocking {
        database.dao.insert(Event(id = 1L, songId = "liked", timestamp = now.minusDays(90), playTime = 90_000))
        database.dao.insert(Event(id = 2L, songId = "liked", timestamp = now.minusDays(2), playTime = 30_000))
        database.dao.insert(Event(id = 3L, songId = "liked", timestamp = now.minusDays(1), playTime = 60_000))
        val candidates = database.dao.smartMixCandidates(now.minusDays(30), 2000)
        assertEquals(setOf("liked", "offline"), candidates.map { it.id }.toSet())
        val liked = candidates.first { it.id == "liked" }
        assertEquals(90_000L, liked.recentPlayMs)
        assertEquals(90_000L, liked.olderPlayMs)
        assertEquals(now.minusDays(1), liked.lastPlayedAt)
        assertTrue(liked.liked)
        assertTrue(candidates.first { it.id == "offline" }.isDownloaded)
    }

    @Test fun `local mix persists in existing playlist tables and stays local`() {
        val snapshot = PlaylistEntity(id = "LP_SMART_MIX_TEST", name = "Gmac Balanced Mix",
            bookmarkedAt = now, isEditable = true, isAutoSync = false)
        database.dao.insert(snapshot)
        val stored = database.dao.playlistBlocking(snapshot.id)
        assertNotNull(stored)
        database.dao.addSongsToPlaylist(stored!!, listOf("liked" to null, "offline" to null))
        assertEquals(listOf("liked", "offline"), database.dao.playlistSongIds(snapshot.id))
        val persisted = database.dao.playlistBlocking(snapshot.id)
        assertNotNull(persisted)
        assertFalse(persisted!!.playlist.isAutoSync)
        assertEquals(null, persisted.playlist.browseId)
    }
}
