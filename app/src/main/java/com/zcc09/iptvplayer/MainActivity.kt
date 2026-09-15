package com.zcc09.iptvplayer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zcc09.iptvplayer.core.DebugHooks
import com.zcc09.iptvplayer.core.Logx
import com.zcc09.iptvplayer.core.TvDetector
import com.zcc09.iptvplayer.ui.AppRoot
import com.zcc09.iptvplayer.ui.IptvTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IptvTheme {
                AppRoot()
            }
        }
        handleExtras(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            TvDetector.notifyInputKey(event.keyCode)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Debug builds accept `-e e2e <action>` commands from `adb shell am start`
     * (used by the CI end-to-end job). No-op in release builds.
     */
    private fun handleExtras(intent: Intent?) {
        val action = intent?.getStringExtra("e2e") ?: return
        val bundle = intent.extras ?: Bundle()
        val extras = HashMap<String, String>()
        for (key in bundle.keySet()) {
            val value = bundle.getString(key)
            if (value != null) extras[key] = value
        }
        Logx.i("E2E command received: $action")
        DebugHooks.handle(this, action, extras)
    }
}
