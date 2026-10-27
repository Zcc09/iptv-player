package com.zcc09.iptvplayer.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.Logx
import com.zcc09.iptvplayer.core.Recorder
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.core.VersionTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

private fun formatDate(atMillis: Long): String =
    if (atMillis <= 0) "unknown date" else timeFormat.format(Date(atMillis))

/** Play a recording file with the regular player (Media3 plays .ts files). */
private fun playRecording(context: Context, rec: Recorder.Recording) {
    val file = File(Recorder.recordingsDir(context), rec.fileName)
    if (!file.exists()) {
        Repo.postStatus("Recording no longer on disk: ${rec.displayName}")
        return
    }
    val uri = file.toUri().toString()
    val channel = Channel(
        id = "rec_${rec.fileName}",
        playlistId = "",
        name = rec.displayName,
        url = uri
    )
    Nav.push(Screen.Player(channel, uri))
}

/** Shared state + actions for the recordings list (phone tab or TV screen). */
@Composable
private fun recordingsState(
    context: Context,
    reload: Int
): Pair<List<Recorder.Recording>, (Recorder.Recording) -> Unit> {
    val entries by produceState<List<Recorder.Recording>>(
        initialValue = emptyList(),
        key1 = reload
    ) {
        value = withContext(Dispatchers.IO) { Recorder.list(context) }
    }
    // E2E hook: emitted every time the recordings screen renders its list.
    LaunchedEffect(entries) { Logx.i("E2E REC count=${entries.size}") }
    return entries to { rec -> Recorder.delete(context, rec.fileName) }
}

// ------------------------------------------------------------------------ phone

/** Content of the "Recordings" tab in the channels screen (below the tab row). */
@Composable
internal fun RecordingsTabScreen() {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Recorder.Recording?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val (entries, deleteOne) = recordingsState(context, reload)

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Text(
                "${entries.size} recording${if (entries.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDeleteAll = true }, enabled = entries.isNotEmpty()) {
                Text("Delete all")
            }
        }
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Start recording from the player with the Record button.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(entries, key = { it.fileName }) { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { playRecording(context, rec) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rec.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                            Text(
                                "${formatDate(rec.startedAt)}  •  ${VersionTools.formatBytes(rec.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { confirmDelete = rec }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete recording",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording") },
            text = { Text("${rec.displayName} (${VersionTools.formatBytes(rec.sizeBytes)}) — delete this file?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOne(rec)
                    confirmDelete = null
                    reload++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all recordings") },
            text = { Text("This removes ${entries.size} files from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    Recorder.deleteAll(context)
                    confirmDeleteAll = false
                    reload++
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
            }
        )
    }
}

// ------------------------------------------------------------------------- TV

/** 10-foot recordings screen, opened from the Recordings tile on the TV home. */
@Composable
fun RecordingsScreen() {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Recorder.Recording?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val (entries, deleteOne) = recordingsState(context, reload)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            IconButton(onClick = { Nav.pop() }) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF94A3B8)
                )
            }
            Text(
                "Recordings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${entries.size} file${if (entries.size == 1) "" else "s"}",
                fontSize = 13.sp,
                color = Color(0xFF94A3B8)
            )
            Spacer(Modifier.weight(1f))
            TvTopActionPill("Delete all", enabled = entries.isNotEmpty()) { confirmDeleteAll = true }
        }

        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No recordings yet", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Start recording from the player OSD.",
                    color = Color.DarkGray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.fileName }) { rec ->
                    TvRecordingRow(
                        recording = rec,
                        onPlay = { playRecording(context, rec) },
                        onDelete = { confirmDelete = rec }
                    )
                }
            }
            Text(
                "[Enter] Play    [B] Delete    [Back] Back",
                fontSize = 12.sp,
                color = Color(0xFF64748B),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )
        }
    }

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording") },
            text = { Text("${rec.displayName} (${VersionTools.formatBytes(rec.sizeBytes)}) — delete this file?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOne(rec)
                    confirmDelete = null
                    reload++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all recordings") },
            text = { Text("This removes ${entries.size} files from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    Recorder.deleteAll(context)
                    confirmDeleteAll = false
                    reload++
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TvRecordingRow(
    recording: Recorder.Recording,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    TvFocusableCard(
        onClick = onPlay,
        onSpecialKey = { keyCode ->
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                onDelete()
                true
            } else false
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) { isFocused ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = recording.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    "${formatDate(recording.startedAt)}  •  ${VersionTools.formatBytes(recording.sizeBytes)}",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
            }
            Text(
                "🗑",
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

/** Small focusable pill for the TV recordings header (delete all). */
@Composable
private fun TvTopActionPill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            !enabled -> Color(0xFF131924)
            isFocused -> Color(0xFF38BDF8)
            else -> Color(0xFF1E2838)
        },
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
