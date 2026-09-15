package com.zcc09.iptvplayer.core

import android.content.Context

/**
 * GitHub-flavor stand-in for [PlayUpdate].
 *
 * This build is sideloaded from GitHub Releases and updates itself through
 * [Updater], so there is nothing for Google Play to do here. The type must
 * exist in both flavors because shared code (Settings, AppRoot) calls it.
 */
object PlayUpdate {

    /** False: this build does not use Google Play's update mechanism. */
    const val available = false

    /** No-op: startup already checks GitHub Releases instead. */
    fun checkSilently(context: Context, onStatus: (String) -> Unit) {
        Logx.i("PlayUpdate: not applicable (github flavor)")
    }

    /** Manual check from Settings routes to the GitHub updater, not Play. */
    fun checkAndPrompt(context: Context, onStatus: (String) -> Unit) {
        Logx.i("PlayUpdate: not applicable (github flavor)")
    }
}
