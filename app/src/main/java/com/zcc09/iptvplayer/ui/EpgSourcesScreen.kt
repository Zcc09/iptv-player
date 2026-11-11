package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.core.EpgSource
import com.zcc09.iptvplayer.core.EpgSources
import kotlinx.coroutines.launch

/**
 * Extra XMLTV guides, added once and shared by every playlist.
 *
 * The screen answers three questions in order: which public guides exist, did
 * the one I added actually contain programmes, and is it switched on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpgSourcesScreen() {
    val scope = rememberCoroutineScope()
    val sources by EpgSources.items.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }

    fun addAndTest(label: String, address: String) {
        if (address.isBlank()) return
        val source = EpgSources.add(label, address)
        name = ""
        url = ""
        busyId = source.id
        scope.launch {
            EpgSources.test(source)
            busyId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV guide sources") },
                navigationIcon = {
                    IconButton(onClick = { Nav.pop() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item {
                Text(
                    "Public XMLTV guides. Your provider's own schedule wins where it exists; " +
                        "these fill the channels it leaves empty.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                SectionLabel("Suggested")
            }

            items(EpgSources.presets, key = { it.url }) { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            preset.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { addAndTest(preset.name, preset.url) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text("Add") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }

            item {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Add your own")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("XMLTV URL") },
                    placeholder = { Text("https://…/epg.xml") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { addAndTest(name, url) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add and check")
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                SectionLabel("Added guides")
                Spacer(Modifier.height(4.dp))
            }

            if (sources.isEmpty()) {
                item {
                    Text(
                        "Nothing added yet. A guide is checked as soon as you add it, " +
                            "so you see how many programmes it carries before relying on it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            items(sources, key = { it.id }) { source ->
                EpgSourceRow(
                    source = source,
                    busy = busyId == source.id,
                    onToggle = { EpgSources.setEnabled(source.id, it) },
                    onRetest = {
                        busyId = source.id
                        scope.launch {
                            EpgSources.test(source)
                            busyId = null
                        }
                    },
                    onRemove = { EpgSources.remove(source.id) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun EpgSourceRow(
    source: EpgSource,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRetest: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        // Top-aligned: an error line wraps to two lines, and centring everything
        // against that pushed the status dot and controls off the title.
        verticalAlignment = Alignment.Top
    ) {
        // Signature detail: the dot is only green when a fetch really worked.
        Box(Modifier.padding(top = 6.dp)) {
            StatusDot(ok = source.lastOk, enabled = source.enabled)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                hostOf(source.url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = countsLine(source),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (source.lastOk) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 2
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(12.dp))
            } else {
                IconButton(onClick = onRetest) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Check this guide again",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = source.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove this guide",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean, enabled: Boolean) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.outline
        ok -> Color(0xFF4CAF7D)
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

private fun countsLine(source: EpgSource): String = when {
    source.lastFetchAt == 0L -> "Not checked yet"
    source.lastError.isNotBlank() -> source.lastError
    else -> "${thousands(source.programCount)} programmes · ${thousands(source.channelCount)} channels"
}

private fun thousands(n: Int): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append(',')
        out.append(c)
    }
    return out.toString()
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrNull() ?: url
