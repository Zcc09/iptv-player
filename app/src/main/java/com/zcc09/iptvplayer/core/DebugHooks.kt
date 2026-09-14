package com.zcc09.iptvplayer.core

import android.app.Activity
import com.zcc09.iptvplayer.BuildConfig

/** Contract implemented by the debug-only end-to-end driver. */
interface E2eHandler {
    fun handle(activity: Activity, action: String, extras: Map<String, String>)
}

/**
 * Bridge that lets the debug source set receive `am start ... -e e2e <action>`
 * commands without the main source set depending on debug-only classes.
 */
object DebugHooks {

    @Volatile
    private var handler: E2eHandler? = null

    /** Reflective lookup; only ever attempted in debug builds. */
    fun install() {
        if (!BuildConfig.DEBUG) return
        try {
            val cls = Class.forName("com.zcc09.iptvplayer.debug.E2e")
            val instance = cls.getDeclaredField("INSTANCE").get(null)
            handler = instance as? E2eHandler
            if (handler != null) Logx.i("E2E hook installed") else Logx.w("E2E hook wrong type")
        } catch (t: Throwable) {
            Logx.w("E2E hook absent (${t.javaClass.simpleName})")
        }
    }

    fun handle(activity: Activity, action: String, extras: Map<String, String>): Boolean {
        val h = handler ?: return false
        return try {
            h.handle(activity, action, extras)
            true
        } catch (t: Throwable) {
            Logx.e("E2E command '$action' failed", t)
            false
        }
    }
}
