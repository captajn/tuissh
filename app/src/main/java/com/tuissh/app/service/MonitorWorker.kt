package com.tuissh.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoLog

/**
 * Periodic server monitor.
 *
 * Full implementation lands with the monitor parsers (plan phase 3). This worker
 * exists now so the scheduler, broadcast receiver and WorkManager wiring compile
 * and can be verified end-to-end.
 */
class MonitorWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        NeoLog.d(LogTag.METRICS, "monitor tick")
        return Result.success()
    }
}
