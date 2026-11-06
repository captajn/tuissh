package com.tuissh.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoLog

/**
 * Restarts scheduled monitoring after a reboot, mirroring the iOS
 * `BGTaskSchedulerPermittedIdentifiers` (`com.neo.NeoMan`) registration.
 *
 * The actual scheduling lives in WorkManager; this receiver only re-arms it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        NeoLog.i(LogTag.METRICS, "boot completed, re-arming monitor schedule")
        MonitorScheduler.ensurePeriodic(context)
    }
}
