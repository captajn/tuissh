package com.tuissh.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager schedule for the server monitor loop.
 *
 * Android enforces a 15-minute floor on periodic work, so the per-server
 * `MonitorSettingEntity.intervalSeconds` is honoured inside the worker rather than
 * by the scheduler: the worker runs every 15 minutes and only samples servers whose
 * own interval has elapsed.
 */
object MonitorScheduler {

    private const val WORK_NAME = "neoserver.monitor.periodic"

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
