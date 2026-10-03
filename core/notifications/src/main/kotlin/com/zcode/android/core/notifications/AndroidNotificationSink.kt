package com.zcode.android.core.notifications

import com.zcode.android.core.notifications.R

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The NotificationManager-backed [NotificationBroker.Sink]. Builds the §24.2
 * notification surface: channel per plan decision, §24.7 deep-link tap target
 * (`zcode://task/{taskId}#interaction={id}`), and per-endpoint GROUP summaries
 * (§24.2 "group summaries per endpoint"). POST_NOTIFICATIONS-ungranted states
 * degrade silently at the SYSTEM level (the broker's records stay truthful).
 */
class AndroidNotificationSink(
    private val context: Context,
) : NotificationBroker.Sink {

    private val groupCounts = HashMap<String, Int>()
    private val groupOfKey = HashMap<String, String>()

    override fun show(spec: NotificationBroker.Spec) {
        val builder = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(spec.publicText)
            .setContentText(spec.detailText)
            .setStyle(
                if (spec.detailText != null) {
                    NotificationCompat.BigTextStyle().bigText(spec.detailText)
                } else {
                    // §24.1 MINIMAL: category-only — the public line is the whole surface.
                    NotificationCompat.BigTextStyle().bigText("")
                },
            )
            .setAutoCancel(true)
            .setCategory(
                if (spec.channelId == NotificationChannels.PERMISSIONS) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_STATUS
                },
            )
            .setPriority(
                if (spec.channelId == NotificationChannels.PERMISSIONS) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
        spec.deepLinkTaskId?.let { taskId ->
            builder.setContentIntent(deepLinkPendingIntent(taskId, spec.deepLinkInteractionId))
        }
        spec.groupKey?.let { group ->
            builder.setGroup(group)
            synchronized(groupCounts) {
                groupOfKey[spec.key] = group
                val count = (groupCounts[group] ?: 0) + 1
                groupCounts[group] = count
                if (count >= GROUP_SUMMARY_THRESHOLD) postGroupSummary(group, count)
            }
        }
        notify(spec.key, builder)
    }

    override fun cancel(key: String) {
        NotificationManagerCompat.from(context).cancel(stableId(key))
        synchronized(groupCounts) {
            val group = groupOfKey.remove(key) ?: return
            val remaining = (groupCounts[group] ?: 1) - 1
            if (remaining <= 0) {
                groupCounts.remove(group)
                NotificationManagerCompat.from(context).cancel(groupSummaryId(group))
            } else {
                groupCounts[group] = remaining
                postGroupSummary(group, remaining)
            }
        }
    }

    private fun postGroupSummary(group: String, count: Int) {
        val summary = NotificationCompat.Builder(context, NotificationChannels.TASK_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.notif_group_summary_title))
            .setContentText(context.resources.getQuantityString(R.plurals.notif_group_summary_text, count, count))
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(false)
        NotificationManagerCompat.from(context).notify(groupSummaryId(group), summary.build())
    }

    /** §24.7: the tap target is a routing HINT — MainActivity validates everything. */
    private fun deepLinkPendingIntent(taskId: String, interactionId: String?): PendingIntent {
        val uri = Uri.parse("zcode://task/$taskId" + (interactionId?.let { "#interaction=$it" } ?: ""))
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(context.packageName)
            // Re-enter the LIVE singleTask instance (onNewIntent); never stack a task.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            stableId("tap:$taskId:${interactionId.orEmpty()}"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(key: String, builder: NotificationCompat.Builder) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return // system-level privacy off — records stay truthful
        manager.notify(stableId(key), builder.build())
    }

    private fun groupSummaryId(group: String) = stableId("summary:$group")

    private fun stableId(key: String): Int = key.hashCode()

    private companion object {
        const val GROUP_SUMMARY_THRESHOLD = 2
    }
}
