package com.zcc09.iptvplayer.core

import android.util.Log
import com.zcc09.iptvplayer.BuildConfig

/**
 * Logging. Debug builds mirror everything to the `IPTV_E2E` tag so the CI
 * end-to-end job can assert on real device behaviour.
 */
object Logx {
    const val E2E_TAG = "IPTV_E2E"
    private const val TAG = "IPTVPlayer"

    fun i(msg: String) {
        Log.i(TAG, msg)
        if (BuildConfig.DEBUG) Log.i(E2E_TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        if (BuildConfig.DEBUG) Log.w(E2E_TAG, msg)
    }

    fun e(msg: String) {
        Log.e(TAG, msg)
        if (BuildConfig.DEBUG) Log.e(E2E_TAG, msg)
    }

    fun e(msg: String, t: Throwable) {
        Log.e(TAG, msg, t)
        if (BuildConfig.DEBUG) Log.e(E2E_TAG, "$msg :: ${t.javaClass.simpleName}: ${t.message}")
    }
}
