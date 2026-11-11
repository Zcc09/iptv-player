package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual direction: a broadcast control surface.
 *
 * Product: a player for someone who watches one live panel every day, on a phone
 * in the morning and a TV at night. Character: quiet, dense, exact. World:
 * broadcast desks - ink neutrals, slate hairlines, one signal colour.
 *
 * The chrome is monochrome and the only saturated colour is the green that means
 * "live / on air", used for the primary action, the current programme and the
 * playing indicator. Colour marks state; it never decorates a surface.
 */
private val Ink900 = Color(0xFF0B0C0E) // page
private val Ink800 = Color(0xFF111316) // app bar / sheets
private val Ink700 = Color(0xFF171A1E) // raised surface, list rows
private val Ink600 = Color(0xFF1F2329) // selection, fields
private val Hair = Color(0xFF2A2F36) // borders, dividers
private val TextHigh = Color(0xFFE9EBEE)
private val TextMid = Color(0xFF9AA3AD)
private val Signal = Color(0xFF46B37C) // "on air"
private val SignalDim = Color(0xFF12251B) // signal as a container
private val Alert = Color(0xFFD9534F) // recording / failure only

private val IptvColors = darkColorScheme(
    primary = Signal,
    onPrimary = Color(0xFF04140C),
    primaryContainer = SignalDim,
    onPrimaryContainer = Color(0xFFB9E7CC),
    secondary = TextMid,
    onSecondary = Ink900,
    secondaryContainer = Ink600,
    onSecondaryContainer = TextHigh,
    tertiary = Color(0xFFD9A441), // recording marker
    onTertiary = Color(0xFF1A1204),
    background = Ink900,
    onBackground = TextHigh,
    surface = Ink800,
    onSurface = TextHigh,
    surfaceVariant = Ink700,
    onSurfaceVariant = TextMid,
    surfaceContainer = Ink800,
    surfaceContainerHigh = Ink700,
    surfaceContainerHighest = Ink600,
    outline = Hair,
    outlineVariant = Color(0xFF21252B),
    error = Alert,
    onError = Color(0xFF1B0503),
    errorContainer = Color(0xFF2C1310),
    onErrorContainer = Color(0xFFF2C3BE)
)

/**
 * Two weights only (400 / 500) and a short scale: hierarchy comes from size and
 * colour, not from 700-weight headings.
 */
private val IptvType = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.4.sp
    )
)

/** Nested radii: controls 8, cards 12, sheets 16. */
private val IptvShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    // Dark-only by design (a TV app), but the palette is a normal color scheme so
    // a light one can be added without touching call sites.
    MaterialTheme(
        colorScheme = IptvColors,
        typography = IptvType,
        shapes = IptvShapes,
        content = content
    )
}
