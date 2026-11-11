package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zcc09.iptvplayer.core.CastSession
import com.zcc09.iptvplayer.core.DlnaDevice

/** One place the viewer can send the picture, whatever protocol it speaks. */
data class CastTarget(
    val id: String,
    val name: String,
    val detail: String,
    val kind: Kind
) {
    enum class Kind { CHROMECAST, DLNA }
}

/**
 * The cast sheet.
 *
 * Casting has exactly one question - "where do I send this?" - so the sheet is a
 * list of places and nothing else. Every paragraph that used to explain relay
 * modes, protocols and Wi-Fi rules is gone: the state of the session answers
 * those questions better than prose (a spinner while looking, a check while
 * casting, one line when nothing is found).
 */
@Composable
fun CastSheet(
    targets: List<CastTarget>,
    searching: Boolean,
    casting: Boolean,
    castingTo: String,
    starting: Boolean,
    status: String,
    onPick: (CastTarget) -> Unit,
    onStopCasting: () -> Unit,
    onSearchAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.width(340.dp)
        ) {
            Column(Modifier.padding(vertical = 18.dp)) {
                Text(
                    text = "Cast",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 22.dp)
                )
                Spacer(Modifier.height(12.dp))

                when {
                    starting -> SheetNote(primary = true, text = "Starting on $castingTo…", progress = true)
                    casting -> {
                        SheetNote(primary = false, text = "Playing on $castingTo")
                        Spacer(Modifier.height(2.dp))
                    }
                }

                when {
                    searching && targets.isEmpty() -> SheetNote(
                        text = "Looking for TVs…", progress = true
                    )

                    targets.isEmpty() -> {
                        SheetNote(text = "No TVs found")
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = onSearchAgain,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) { Text("Search again") }
                    }

                    else -> targets.forEach { target ->
                        TargetRow(
                            target = target,
                            active = casting && castingTo == target.name,
                            onClick = { onPick(target) }
                        )
                    }
                }

                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 22.dp)
                    )
                }

                if (casting || starting) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    TextButton(
                        onClick = onStopCasting,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) { Text("Stop casting") }
                }
            }
        }
    }
}

@Composable
private fun SheetNote(text: String, primary: Boolean = false, progress: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp)
    ) {
        if (progress) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (primary) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun TargetRow(target: CastTarget, active: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !active) { onClick() }
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = target.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = target.detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (active) {
            // A dot, not a badge: one accent, meaning "this one, now".
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}
