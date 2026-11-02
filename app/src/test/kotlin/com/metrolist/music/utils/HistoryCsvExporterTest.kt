/**
 * Gmac listening-history CSV export tests.
 * Licensed under GPL-3.0; see the repository LICENSE.
 */
package com.metrolist.music.utils

import java.io.StringWriter
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryCsvExporterTest {
    @Test
    fun `empty history exports a header`() {
        val writer = StringWriter()
        HistoryCsvExporter.writeHeader(writer)
        assertEquals(0, HistoryCsvExporter.writeRows(emptyList(), writer))
        assertEquals("Played at (local time),Title,Artists,Album,Play time (ms),Song ID\r\n", writer.toString())
    }

    @Test
    fun `exports UTF-8 text quotes commas newlines and play duration`() {
        val writer = StringWriter()
        val row =
            HistoryExportRow(
                playedAt = LocalDateTime.of(2026, 11, 2, 15, 4, 5),
                title = "Song, \"தமிழ்\"\nPart 2",
                artists = "Artist A; Artist B",
                album = "The Album",
                playTimeMs = 12_345L,
                songId = "track123",
            )

        HistoryCsvExporter.writeHeader(writer)
        assertEquals(1, HistoryCsvExporter.writeRows(listOf(row), writer))
        assertEquals(
            "Played at (local time),Title,Artists,Album,Play time (ms),Song ID\r\n" +
                "\"2026-11-02T15:04:05\",\"Song, \"\"தமிழ்\"\"\nPart 2\",\"Artist A; Artist B\",\"The Album\",\"12345\",\"track123\"\r\n",
            writer.toString(),
        )
    }

    @Test
    fun `prevents spreadsheet formulas in user controlled text fields`() {
        val writer = StringWriter()
        HistoryCsvExporter.writeRows(
            listOf(
                HistoryExportRow(
                    playedAt = LocalDateTime.of(2026, 11, 2, 1, 0),
                    title = "=HYPERLINK(\"https://example.com\")",
                    artists = "  +malicious",
                    album = "@formula",
                    playTimeMs = 1L,
                    songId = "-track",
                ),
            ),
            writer,
        )
        assertEquals(
            "\"2026-11-02T01:00:00\",\"'=HYPERLINK(\"\"https://example.com\"\")\",\"'  +malicious\",\"'@formula\",\"1\",\"'-track\"\r\n",
            writer.toString(),
        )
    }
}
