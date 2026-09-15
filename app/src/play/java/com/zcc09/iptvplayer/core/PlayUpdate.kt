package com.zcc09.iptvplayer.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Google Play flavor: updates are owned by Play.
 *
 * Google Play forbids apps installing their own updates (and restricts
 * REQUEST_INSTALL_PACKAGES, which this flavor does not declare), so instead of
 * downloading an APK we ask Play itself - the In-App Update API.
 *
 * FLEXIBLE is used rather than IMMEDIATE: the download happens in the
 * background while the user keeps watching, and the app restarts once it lands.
 * Everything here is a no-op on devices without Google Play.
 */
object PlayUpdate {

    /** True: this build updates through Google Play. */
    const val available = true

    const val REQUEST_CODE = 4201

    private var manager: AppUpdateManager? = null
    private var listener: InstallStateUpdatedListener? = null

    private fun managerOf(context: Context): AppUpdateManager =
        manager ?: AppUpdateManagerFactory.create(context.applicationContext).also { manager = it }

    /** Launch-time check: report an available update without interrupting. */
    fun checkSilently(context: Context, onStatus: (String) -> Unit) {
        if (!available) return
        runCatching {
            managerOf(context).getAppUpdateInfo()
                .addOnSuccessListener { info ->
                    val status = info.updateAvailability()
                    if (status == UpdateAvailability.UPDATE_AVAILABLE) {
                        Logx.i(
                            "PLAY_UPDATE_AVAILABLE code=${info.availableVersionCode()} " +
                                "flexible=${info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)} " +
                                "immediate=${info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)}"
                        )
                        onStatus("An update is available on Google Play")
                    } else {
                        Logx.i("PLAY_UPDATE_NONE availability=$status")
                    }
                }
                .addOnFailureListener { t -> Logx.w("PLAY_UPDATE_CHECK_FAILED ${t.message}") }
        }.onFailure { t -> Logx.w("PLAY_UPDATE_UNAVAILABLE ${t.message}") }
    }

    /** Explicit check from Settings: start a flexible update if Play has one. */
    fun checkAndPrompt(context: Context, onStatus: (String) -> Unit) {
        if (!available) return
        val activity = context.findActivity()
        if (activity == null) {
            onStatus("Could not check for updates (no activity)")
            return
        }
        runCatching {
            val mgr = managerOf(context)
            mgr.getAppUpdateInfo()
                .addOnSuccessListener { info ->
                    val status = info.updateAvailability()
                    val allowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                    if (status != UpdateAvailability.UPDATE_AVAILABLE || !allowed) {
                        Logx.i("PLAY_UPDATE_NONE availability=$status allowed=$allowed")
                        onStatus("Internet TV Player is up to date")
                        return@addOnSuccessListener
                    }
                    registerListener(mgr, onStatus)
                    try {
                        mgr.startUpdateFlowForResult(
                            info,
                            AppUpdateType.FLEXIBLE,
                            activity,
                            REQUEST_CODE
                        )
                        Logx.i("PLAY_UPDATE_FLOW_STARTED code=${info.availableVersionCode()}")
                        onStatus("Downloading the update in the background…")
                    } catch (t: Throwable) {
                        Logx.e("PLAY_UPDATE_FLOW_FAILED", t)
                        onStatus("Could not start the update: ${t.message}")
                    }
                }
                .addOnFailureListener { t ->
                    Logx.w("PLAY_UPDATE_CHECK_FAILED ${t.message}")
                    onStatus("Update check failed: ${t.message}")
                }
        }.onFailure { t ->
            Logx.w("PLAY_UPDATE_UNAVAILABLE ${t.message}")
            onStatus("Google Play is not available on this device")
        }
    }

    private fun registerListener(mgr: AppUpdateManager, onStatus: (String) -> Unit) {
        if (listener != null) return
        val l = InstallStateUpdatedListener { state ->
            when (state.installStatus()) {
                InstallStatus.DOWNLOADED -> {
                    Logx.i("PLAY_UPDATE_DOWNLOADED")
                    onStatus("Update downloaded — restarting to finish installing")
                    runCatching { mgr.completeUpdate() }
                        .onFailure { Logx.w("PLAY_UPDATE_COMPLETE_FAILED ${it.message}") }
                }

                InstallStatus.FAILED -> {
                    Logx.w("PLAY_UPDATE_INSTALL_FAILED code=${state.installErrorCode()}")
                    onStatus("Update download failed")
                }

                InstallStatus.DOWNLOADING -> {
                    Logx.i("PLAY_UPDATE_DOWNLOADING ${state.bytesDownloaded()}/${state.totalBytesToDownload()}")
                }

                else -> Logx.i("PLAY_UPDATE_INSTALL_STATE ${state.installStatus()}")
            }
        }
        listener = l
        mgr.registerListener(l)
    }
}

/** Walks ContextWrapper chains to find the hosting Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
