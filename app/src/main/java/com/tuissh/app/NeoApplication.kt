package com.tuissh.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoLog

/**
 * Application entry point.
 *
 * Mirrors the original `AppDelegate` plus the parts of `FirebaseApp.configure()`
 * that actually mattered: nothing here talks to a vendor SDK.
 */
class NeoApplication : Application() {

    companion object {
        lateinit var instance: NeoApplication
            private set

        lateinit var appContext: Context
            private set

        // Notification channel identifiers (formerly the `Channels` companion).
        const val CHANNEL_TERMINAL = "neoserver.terminal"
        const val CHANNEL_ALERTS = "neoserver.alerts"
        const val CHANNEL_TRANSFERS = "neoserver.transfers"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appContext = applicationContext
        createNotificationChannels()
        NeoLog.i(LogTag.UI, "NeoServer Android started")
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Terminal sessions kept alive in the background.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TERMINAL,
                getString(R.string.channel_terminal),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_terminal_desc)
                setShowBadge(false)
            },
        )

        // Server up/down and threshold alerts (mirrors the iOS push categories).
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.channel_alerts_desc)
            },
        )

        // Transfer progress.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRANSFERS,
                getString(R.string.channel_transfers),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_transfers_desc)
                setShowBadge(false)
            },
        )
    }
}
