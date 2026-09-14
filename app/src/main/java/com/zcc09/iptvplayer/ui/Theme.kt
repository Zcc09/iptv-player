package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IptvColors = darkColorScheme(
    primary = Color(0xFF3DA9FC),
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF10344F),
    onPrimaryContainer = Color(0xFFD6ECFF),
    secondary = Color(0xFF67D391),
    onSecondary = Color(0xFF04210F),
    background = Color(0xFF0E1115),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1F2630),
    onSurfaceVariant = Color(0xFF9BA7B4),
    outline = Color(0xFF39424E),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0505)
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    // The app is dark-only by design (IPTV/TV viewing), but keep the call so a
    // light scheme can be added later without touching call sites.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = IptvColors, content = content)
}
