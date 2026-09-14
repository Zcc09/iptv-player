package com.zcc09.iptvplayer

import android.app.Application
import com.zcc09.iptvplayer.cast.CastCtl
import com.zcc09.iptvplayer.core.DebugHooks
import com.zcc09.iptvplayer.core.Logx
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.core.Scheduler

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Logx.i("APP_STARTED version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
        DebugHooks.install()
        CastCtl.init(this)
        Scheduler.scheduleAll(this)
        Repo.refreshStaleAsync()
    }
}
