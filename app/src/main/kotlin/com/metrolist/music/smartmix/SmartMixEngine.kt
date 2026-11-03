/**
 * Gmac Smart Mix v0.4 - local ranking inputs and selection engine.
 * GPL-3.0; retains original MetroList source attribution in the repository.
 */
package com.metrolist.music.smartmix

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Room projection: aggregate signals only, never entire event history. */
data class SmartMixCandidate(
    val id: String,
    val title: String,
    val artistId: String?,
    val liked: Boolean,
    val isLocal: Boolean,
    val isDownloaded: Boolean,
    val explicit: Boolean,
    val lifetimePlayMs: Long,
    val recentPlayMs: Long,
    val olderPlayMs: Long,
    val lastPlayedAt: LocalDateTime?,
)

enum class SmartMixPreset(val title: String) {
    FAVORITES("Favorites"),
    REDISCOVER("Rediscover"),
    BALANCED("Balanced Mix"),
    OFFLINE("Offline Mix"),
}

data class SmartMixPick(
    val id: String,
    val title: String,
    val reason: String,
    val score: Double,
)

object SmartMixEngine {
    /** Deterministic for a given seed, song library and clock. Nothing leaves the device. */
    fun generate(
        candidates: List<SmartMixCandidate>,
        preset: SmartMixPreset,
        count: Int,
        offlineOnly: Boolean,
        seed: Int = 0,
        now: LocalDateTime = LocalDateTime.now(),
        hideExplicit: Boolean = false,
    ): List<SmartMixPick> {
        val requireOffline = offlineOnly || preset == SmartMixPreset.OFFLINE
        val eligible = candidates.asSequence()
            .distinctBy { it.id }
            .filter { !requireOffline || it.isDownloaded || it.isLocal }
            .filter { !hideExplicit || !it.explicit }
            .filter {
                preset != SmartMixPreset.REDISCOVER || it.olderPlayMs > 0L || it.liked
            }
            .toList()
        if (eligible.isEmpty()) return emptyList()

        val scored = eligible.map { candidate ->
            val history = smooth(candidate.olderPlayMs)
            val recent = smooth(candidate.recentPlayMs)
            val lifetime = smooth(candidate.lifetimePlayMs)
            val liked = if (candidate.liked) 1.0 else 0.0
            val days = candidate.lastPlayedAt?.let {
                ChronoUnit.DAYS.between(it, now).coerceAtLeast(0L)
            } ?: 365L
            val recency = 1.0 / (1.0 + days / 14.0)
            val variety = 1.0 - lifetime
            val score = when (preset) {
                SmartMixPreset.FAVORITES -> 0.45 * liked + 0.25 * lifetime + 0.20 * recent + 0.10 * variety
                SmartMixPreset.REDISCOVER -> 0.45 * history + 0.20 * liked + 0.20 * (1.0 - recency) + 0.15 * (1.0 - recent)
                SmartMixPreset.BALANCED -> 0.25 * lifetime + 0.25 * recent + 0.25 * liked + 0.25 * variety
                SmartMixPreset.OFFLINE -> 0.30 * lifetime + 0.25 * recent + 0.25 * liked + 0.20 * variety
            }
            // Small reproducible variation for Regenerate; dominant signals still determine rank.
            val hash = (candidate.id.hashCode() xor (seed * 1103515245)).toLong() and 0xffffffffL
            val variation = hash.toDouble() / 4294967296.0 * 0.075
            val explanation = when {
                preset == SmartMixPreset.OFFLINE -> "Saved for offline listening"
                preset == SmartMixPreset.REDISCOVER && days >= 30L -> "A familiar song you have not played lately"
                candidate.liked -> "One of your liked songs"
                candidate.recentPlayMs > 0 -> "Played recently"
                candidate.olderPlayMs > 0 -> "From your listening history"
                else -> "From your music library"
            }
            candidate to SmartMixPick(candidate.id, candidate.title, explanation, score + variation)
        }.sortedWith(compareByDescending<Pair<SmartMixCandidate, SmartMixPick>> { it.second.score }.thenBy { it.first.id })

        val target = count.coerceIn(1, 50)
        val picked = mutableListOf<Pair<SmartMixCandidate, SmartMixPick>>()
        val artistCounts = mutableMapOf<String, Int>()
        for (entry in scored) {
            if (picked.size >= target) break
            val artist = entry.first.artistId ?: entry.first.id
            if ((artistCounts[artist] ?: 0) >= 2) continue
            picked += entry
            artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
        }
        // If a library is dominated by one artist, fill remaining slots instead of returning 2 songs.
        val chosenIds = picked.mapTo(mutableSetOf()) { it.first.id }
        for (entry in scored) {
            if (picked.size >= target) break
            if (chosenIds.add(entry.first.id)) picked += entry
        }
        return picked.map { it.second }
    }

    private fun smooth(milliseconds: Long): Double {
        val n = milliseconds.coerceAtLeast(0L).toDouble()
        return n / (n + 600_000.0)
    }
}
