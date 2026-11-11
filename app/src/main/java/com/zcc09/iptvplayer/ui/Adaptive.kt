package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Width classes, the standard Android breakpoints.
 *
 * The app has to survive four shapes of the same device: a folded phone held
 * upright (353dp), the same phone sideways, the foldable opened (719dp), and a
 * television. A single "TV mode" boolean cannot express that, and a bare width
 * threshold made a folded phone try to show two panes.
 */
enum class WindowClass { COMPACT, MEDIUM, EXPANDED }

data class Adaptive(
    val widthDp: Int,
    val width: WindowClass,
    val height: WindowClass,
    val landscape: Boolean,
    val tv: Boolean
) {
    /**
     * Two panes - channel list beside a live preview - whenever the window is
     * wide enough for both. A TV always qualifies; a landscape phone usually
     * does; a folded phone in portrait never does.
     */
    val twoPane: Boolean
        get() = width != WindowClass.COMPACT && (
            tv || width == WindowClass.EXPANDED ||
                (width == WindowClass.MEDIUM && (landscape || widthDp >= 700))
            )

    /** The ten-foot treatment: bigger targets, focus-first, no touch imagery. */
    val tenFoot: Boolean get() = tv

    /**
     * Compact portrait: everything stacks and the player is a full screen of its
     * own. Used to decide whether a preview pane may steal width.
     */
    val stacked: Boolean get() = !twoPane
}

private fun classOf(dp: Int): WindowClass = when {
    dp >= 840 -> WindowClass.EXPANDED
    dp >= 600 -> WindowClass.MEDIUM
    else -> WindowClass.COMPACT
}

@Composable
@ReadOnlyComposable
fun rememberAdaptive(tv: Boolean): Adaptive {
    val configuration = LocalConfiguration.current
    // Reading Configuration makes this recompose on rotation, resize and unfold.
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    val landscape = widthDp > heightDp
    return Adaptive(
        widthDp = widthDp,
        width = classOf(widthDp),
        height = classOf(heightDp),
        landscape = landscape,
        tv = tv
    )
}
