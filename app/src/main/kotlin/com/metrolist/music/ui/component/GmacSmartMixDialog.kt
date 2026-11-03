/**
 * Gmac Smart Mix creation dialog - local, no network, no automatic sync.
 * GPL-3.0.
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.smartmix.SmartMixCandidate
import com.metrolist.music.smartmix.SmartMixEngine
import com.metrolist.music.smartmix.SmartMixPick
import com.metrolist.music.smartmix.SmartMixPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@Composable
fun GmacSmartMixDialog(
    onDismiss: () -> Unit,
    onSaved: (playlistId: String) -> Unit,
) {
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(SmartMixPreset.BALANCED) }
    var songCount by remember { mutableIntStateOf(25) }
    var offlineOnly by remember { mutableStateOf(false) }
    var variation by remember { mutableIntStateOf(0) }
    var candidates by remember { mutableStateOf<List<SmartMixCandidate>>(emptyList()) }
    var preview by remember { mutableStateOf<List<SmartMixPick>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var generating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(database) {
        loading = true
        try {
            candidates = withContext(Dispatchers.IO) {
                database.smartMixCandidates(
                    recentStart = LocalDateTime.now().minusDays(30),
                    maxCandidates = 2000,
                )
            }
        } catch (e: Exception) {
            errorMessage = "Could not read your music library."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(candidates, preset, songCount, offlineOnly, variation, loading) {
        if (!loading && errorMessage == null) {
            generating = true
            preview = withContext(Dispatchers.Default) {
                SmartMixEngine.generate(
                    candidates = candidates,
                    preset = preset,
                    count = songCount,
                    offlineOnly = offlineOnly,
                    seed = variation,
                )
            }
            generating = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Create Smart Mix") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Choose a mix", style = MaterialTheme.typography.titleSmall)
                SmartMixPreset.entries.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        RadioButton(
                            selected = preset == option,
                            onClick = { preset = option },
                            enabled = !saving,
                        )
                        Text(
                            option.title,
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text("Songs: $songCount", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = songCount.toFloat(),
                    onValueChange = { songCount = (it.toInt() / 5 * 5).coerceIn(10, 50) },
                    valueRange = 10f..50f,
                    steps = 7,
                    enabled = !saving,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = offlineOnly || preset == SmartMixPreset.OFFLINE,
                        onCheckedChange = { offlineOnly = it },
                        enabled = preset != SmartMixPreset.OFFLINE && !saving,
                    )
                    Text(
                        "Offline-available songs only",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when {
                    loading || generating -> CircularProgressIndicator()
                    errorMessage != null -> Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                    preview.isEmpty() -> Text("No matching songs yet. Play or like some music first, or try another filter.")
                    else -> {
                        Text("Preview: ${preview.size} songs", style = MaterialTheme.typography.titleSmall)
                        preview.take(7).forEachIndexed { index, song ->
                            Text(
                                "${index + 1}. ${song.title} — ${song.reason}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (preview.size > 7) Text("...and ${preview.size - 7} more", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { variation++ }, enabled = !saving) {
                            Text("Regenerate mix")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && !loading && !generating && preview.isNotEmpty(),
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val now = LocalDateTime.now()
                            val newPlaylist = PlaylistEntity(
                                name = "Gmac ${preset.title} ${now.monthValue}/${now.dayOfMonth}",
                                bookmarkedAt = now,
                                isEditable = true,
                                isAutoSync = false,
                            )
                            val songIds = preview.map { it.id }
                            withContext(Dispatchers.IO) {
                                database.withTransaction {
                                    insert(newPlaylist)
                                    val playlist = playlistBlocking(newPlaylist.id)
                                        ?: error("Could not create local playlist")
                                    addSongsToPlaylist(playlist, songIds.map { it to null })
                                }
                            }
                            onSaved(newPlaylist.id)
                        } catch (e: Exception) {
                            errorMessage = "Could not save this mix. Please try again."
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(if (saving) "Saving..." else "Save Mix") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}
