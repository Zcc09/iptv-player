package com.zcc09.iptvplayer.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast configuration. Uses Google's Default Media Receiver (CC1AD845), which
 * needs no Cast developer registration and works with every Chromecast,
 * Google TV and Android TV device.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(RECEIVER_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Google's Default Media Receiver. */
        const val RECEIVER_ID = "CC1AD845"
    }
}
