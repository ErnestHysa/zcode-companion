package com.zcode.android.core.notifications

import com.zcode.android.core.notifications.R

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
                context.getString(R.string.channel_permissions_name),
                NotificationManagerCompat.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_permissions_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                TASK_STATUS,
                context.getString(R.string.channel_task_status_name),
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_task_status_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS,
                context.getString(R.string.channel_progress_name),
                NotificationManagerCompat.IMPORTANCE_MIN,
            ).apply { description = context.getString(R.string.channel_progress_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                LIVE_CONNECTION,
                context.getString(R.string.channel_live_connection_name),
                NotificationManagerCompat.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_live_connection_desc)
                setShowBadge(false)
            },
        )
    }
}
