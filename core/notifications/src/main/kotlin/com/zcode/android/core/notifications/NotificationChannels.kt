package com.zcode.android.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * §24.2 channels — created at FIRST RUN (Android 26+ requirement; minSdk 26). The
 * ids/importances are the §24.1 table verbatim: approvals ride HIGH (sound+vibrate),
 * task outcomes DEFAULT, workflow progress SILENT (reserved; nothing posts to it
 * until a workflow surface exists), and the §24.5 Live-connection FGS indicator is
 * its own LOW channel (no sound, no heads-up — a persistent status, not an alert).
 */
object NotificationChannels {
    const val PERMISSIONS = "permissions"
    const val TASK_STATUS = "task-status"
    const val PROGRESS = "progress"
    const val LIVE_CONNECTION = "live-connection"

    fun ensure(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                PERMISSIONS,
                "Approvals & questions",
                NotificationManagerCompat.IMPORTANCE_HIGH,
            ).apply { description = "ZCode needs your approval or an answer to continue" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                TASK_STATUS,
                "Task status",
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            ).apply { description = "Task finished, failed, or the connection was lost" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS,
                "Progress (silent)",
                NotificationManagerCompat.IMPORTANCE_MIN,
            ).apply { description = "Silent background progress updates" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                LIVE_CONNECTION,
                "Live connection",
                NotificationManagerCompat.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent indicator shown while the live connection service is enabled"
                setShowBadge(false)
            },
        )
    }
}
