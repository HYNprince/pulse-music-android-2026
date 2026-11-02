/**
 * Gmac listening-history CSV export.
 * Licensed under GPL-3.0; see the repository LICENSE.
 */
package com.metrolist.music.utils

import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class HistoryExportRow(
    val playedAt: LocalDateTime,
    val title: String,
    val artists: String,
    val album: String,
    val playTimeMs: Long,
    val songId: String,
)

/** Writes UTF-8-ready CSV to a caller-owned writer; no history is uploaded. */
object HistoryCsvExporter {
    private const val HEADER = "Played at (local time),Title,Artists,Album,Play time (ms),Song ID\r\n"

    fun writeHeader(writer: Writer) {
        writer.write(HEADER)
    }

    fun writeRows(rows: Iterable<HistoryExportRow>, writer: Writer): Int {
        var count = 0
        for (row in rows) {
            val columns =
                listOf(
                    row.playedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    row.title,
                    row.artists,
                    row.album,
                    row.playTimeMs.toString(),
                    row.songId,
                )
            writer.write(columns.joinToString(",") { escapeCell(it) })
            writer.write("\r\n")
            count++
        }
        return count
    }

    /** Quote every value, escape quotes, and neutralize spreadsheet formula prefixes. */
    private fun escapeCell(value: String): String {
        val firstContentCharacter = value.trimStart().firstOrNull()
        val safe =
            if (firstContentCharacter == '=' || firstContentCharacter == '+' ||
                firstContentCharacter == '-' || firstContentCharacter == '@'
            ) {
                "'$value"
            } else {
                value
            }
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
