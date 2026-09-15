package com.zcc09.iptvplayer.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects whether the app is running on an Android TV device, in widescreen landscape,
 * or being controlled with a TV remote or gamepad.
 */
object TvDetector {

    private val _gamepadDetected = MutableStateFlow(false)
    val gamepadDetected: StateFlow<Boolean> = _gamepadDetected.asStateFlow()

    /**
     * Intercepts key presses from remote controls and gamepads to dynamically enable
     * TV mode when input is received.
     */
    fun notifyInputKey(keyCode: Int): Boolean {
        val isTvOrGamepadKey = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_PROG_YELLOW,
            KeyEvent.KEYCODE_PROG_BLUE,
            KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_INFO -> true
            else -> false
        }

        if (isTvOrGamepadKey && !_gamepadDetected.value) {
            _gamepadDetected.value = true
            Logx.i("TV/Gamepad input detected (keyCode=$keyCode), dynamic remote mode enabled")
        }
        return isTvOrGamepadKey
    }

    /**
     * Returns true if the Android TV / 10-foot remote interface should be shown.
     */
    fun isTvMode(
        context: Context,
        configuration: Configuration,
        modePref: AppUiMode,
        gamepadActive: Boolean = _gamepadDetected.value
    ): Boolean {
        return when (modePref) {
            AppUiMode.TV -> true
            AppUiMode.MOBILE -> false
            AppUiMode.AUTO -> {
                // 1. Explicit Android TV system mode
                val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
                    return true
                }
                // 2. Android Leanback feature
                val pm = context.packageManager
                if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    return true
                }
                // 3. No touchscreen (pure TV box / stick)
                if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                    return true
                }
                // 4. Remote / Gamepad controller actively used
                if (gamepadActive) {
                    return true
                }
                // 5. Started in widescreen landscape mode (aspect ratio >= 1.5 and landscape width >= 600dp)
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val isWidescreen = configuration.screenWidthDp >= 600
                if (isLandscape && isWidescreen) {
                    return true
                }
                false
            }
        }
    }
}
