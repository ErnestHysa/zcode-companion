package com.zcode.android.core.notifications

import android.content.Context
import com.zcode.android.core.notifications.R

/** §31: resources-backed notification copy (en + zh-rCN). */
class ResourceNotificationCopy(private val context: Context) : NotificationPlan.NotificationCopy {
    override fun approvalPublic() = context.getString(R.string.notif_approval_public)
    override fun questionPublic() = context.getString(R.string.notif_question_public)
    override fun taskFinishedPublic() = context.getString(R.string.notif_task_finished)
    override fun taskFinishedDetail(taskTitle: String) = context.getString(R.string.notif_task_finished_detail, taskTitle)
    override fun taskFailedPublic() = context.getString(R.string.notif_task_failed)
    override fun taskFailedDetail(taskTitle: String) = context.getString(R.string.notif_task_failed_detail, taskTitle)
    override fun connectionLostPublic() = context.getString(R.string.notif_connection_lost)
}
