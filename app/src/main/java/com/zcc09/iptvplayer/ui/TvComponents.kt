package com.zcc09.iptvplayer.ui

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val TvFocusedBorderColor = Color(0xFF38BDF8) // Bright electric cyan focus ring
val TvCardBackground = Color(0xFF141923)
val TvCardFocusedBackground = Color(0xFF1F2839)

/**
 * 10-foot TV card that scales up and illuminates when focused via D-pad or Gamepad.
 */
@Composable
fun TvFocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSpecialKey: ((Int) -> Boolean)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedBorderColor: Color = TvFocusedBorderColor,
    content: @Composable (isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 140),
        label = "tvCardScale"
    )

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) TvCardFocusedBackground else TvCardBackground
        ),
        border = if (isFocused) {
            BorderStroke(3.dp, focusedBorderColor)
        } else {
            BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isFocused) 12.dp else 2.dp
        ),
        modifier = modifier
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (onSpecialKey?.invoke(code) == true) {
                        return@onKeyEvent true
                    }
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_BUTTON_A -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        content(isFocused)
    }
}

/**
 * Focusable row button for TV sidebar navigation.
 */
@Composable
fun TvNavButton(
    label: String,
    iconText: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(120),
        label = "tvNavScale"
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when {
            isFocused -> TvFocusedBorderColor
            selected -> Color(0xFF232D42)
            else -> Color.Transparent
        },
        border = if (isFocused) {
            BorderStroke(2.dp, Color.White)
        } else if (selected) {
            BorderStroke(1.dp, TvFocusedBorderColor.copy(alpha = 0.4f))
        } else null,
        modifier = modifier
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == KeyEvent.KEYCODE_ENTER ||
                        code == KeyEvent.KEYCODE_BUTTON_A
                    ) {
                        onClick()
                        true
                    } else false
                } else false
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = iconText,
                fontSize = 18.sp,
                modifier = Modifier.padding(end = 10.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Normal,
                color = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (!badgeText.isNullOrBlank()) {
                Surface(
                    color = if (isFocused) Color.Black.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFocused) Color.Black else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Controller / Remote button prompt indicator (e.g. [A] Play, [Y] Fav, [B] Back).
 */
@Composable
fun RemoteButtonHint(
    buttonLabel: String,
    actionName: String,
    modifier: Modifier = Modifier,
    buttonColor: Color = Color(0xFF334155),
    textColor: Color = Color(0xFFE2E8F0)
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = buttonColor,
            modifier = Modifier.padding(end = 5.dp)
        ) {
            Text(
                text = buttonLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Text(
            text = actionName,
            fontSize = 12.sp,
            color = textColor
        )
    }
}

/**
 * Small badge indicating stream quality or type (4K, HD, LIVE, TS, HLS).
 */
@Composable
fun TvBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF0284C7)
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.7f)),
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}
