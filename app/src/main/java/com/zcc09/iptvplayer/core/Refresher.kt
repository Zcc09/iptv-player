package com.zcc09.iptvplayer.core

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Background playlist refresh. */
class RefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        val id = inputData.getString(KEY_PLAYLIST_ID)
        return try {
            if (id.isNullOrBlank()) {
                Repo.refreshAll()
            } else if (Repo.playlist(id) != null) {
                Repo.refresh(id)
            }
            Result.success()
        } catch (t: Throwable) {
            Logx.e("RefreshWorker failed", t)
            Result.retry()
        }
    }

    companion object {
        const val KEY_PLAYLIST_ID = "playlistId"
    }
}

/** Schedules / cancels the periodic auto-refresh jobs. */
object Scheduler {

    private const val MIN_INTERVAL_MINUTES = 15L

    private fun nameFor(playlistId: String) = "iptv-refresh-$playlistId"

    fun schedule(context: Context, playlist: Playlist) {
        val wm = WorkManager.getInstance(context)
        if (!playlist.autoRefresh) {
            wm.cancelUniqueWork(nameFor(playlist.id))
            Logx.i("AUTO_REFRESH_DISABLED playlist=${playlist.name}")
            return
        }
        val minutes = playlist.refreshIntervalMinutes
            .coerceAtLeast(MIN_INTERVAL_MINUTES.toInt())
            .toLong()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(RefreshWorker.KEY_PLAYLIST_ID to playlist.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            nameFor(playlist.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Logx.i("AUTO_REFRESH_SCHEDULED playlist=${playlist.name} everyMin=$minutes")
    }

    fun cancel(context: Context, playlistId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(nameFor(playlistId))
    }

    fun scheduleAll(context: Context) {
        for (p in Repo.playlists.value) schedule(context, p)
    }
}
