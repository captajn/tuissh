package com.tuissh.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tuissh.app.NeoApplication
import com.tuissh.app.R
import com.tuissh.app.ui.NeoActivity

/**
 * Keeps live SSH/SFTP sockets and the metric poller alive when the app leaves the
 * foreground, replacing the iOS `UIBackgroundModes` combination
 * (`audio`, `fetch`, `processing`).
 *
 * The service never holds credentials itself: sessions live in the process-wide
 * [com.tuissh.app.ssh.SshSessionRegistry].
 */
class TerminalForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 0x4E45 // "NE"
        const val ACTION_START = "com.tuissh.app.action.START"
        const val ACTION_STOP = "com.tuissh.app.action.STOP"
    }

    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!running) {
                    running = true
                    startForeground(NOTIFICATION_ID, buildNotification(0))
                }
            }
        }
        return START_STICKY
    }

    fun updateSessionCount(count: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, NeoActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NeoApplication.CHANNEL_TERMINAL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.terminal_service_title))
            .setContentText(getString(R.string.terminal_service_text, count))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
