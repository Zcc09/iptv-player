package com.zcc09.iptvplayer.ui

import androidx.compose.runtime.mutableStateListOf
import com.zcc09.iptvplayer.core.Channel

sealed class Screen {
    data object Home : Screen()
    data class EditPlaylist(val playlistId: String?) : Screen()
    data class Channels(val playlistId: String) : Screen()
    data class Player(val channel: Channel, val urlOverride: String? = null) : Screen()
    data object Settings : Screen()
}

/** Tiny hand-rolled back stack - the app has five screens and no deep links. */
object Nav {

    private val stack = mutableStateListOf<Screen>(Screen.Home)

    val current: Screen get() = stack.lastOrNull() ?: Screen.Home

    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun replaceTop(screen: Screen) {
        if (stack.isEmpty()) stack.add(screen) else stack[stack.size - 1] = screen
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun popToHome() {
        while (stack.size > 1) stack.removeAt(stack.size - 1)
    }
}
