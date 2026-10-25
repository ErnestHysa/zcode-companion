package com.zcode.android.app.notifications

import android.content.Context
import android.content.Intent
import com.zcode.android.core.notifications.NotificationActions

/**
 * C3 (gaps-and-polish Phase 4E): the shade-level Approve-once / Deny receiver. The
 * decision routes through the SAME fail-closed inbox path as the approvals screen
 * (`answerFromInbox` → the ONE interaction gate — never a bespoke wire call); the
 * answered interaction's notification is cancelled and a post-decision haptic fires
 * (C3's "post-tap haptic"). Invalid/unknown interactions are dropped (INV-14).
 */
class NotificationActionReceiver(
    private val onAnswer: (interactionId: String, optionId: String, taskId: String?) -> Unit =
        { interactionId, optionId, taskId -> answerDelegate?.invoke(interactionId, optionId, taskId) },
) : android.content.BroadcastReceiver() {

    companion object {
        /**
         * The system instantiates receivers with NO constructor args — the app
         * container registers the answer delegate at init (the SAME fail-closed
         * `answerFromInbox` gate the approvals screen uses; never a bespoke wire
         * call from a receiver).
         */
        @Volatile var answerDelegate: ((interactionId: String, optionId: String, taskId: String?) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationActions.ACTION) return
        val interactionId = intent.getStringExtra(NotificationActions.EXTRA_INTERACTION_ID) ?: return
        val optionId = intent.getStringExtra(NotificationActions.EXTRA_OPTION_ID) ?: return
        val taskId = intent.getStringExtra(NotificationActions.EXTRA_TASK_ID)
        val notificationKey = intent.getStringExtra(NotificationActions.EXTRA_NOTIFICATION_KEY)

        // Post-decision haptic (C3): a confirmation tick the user feels without looking.
        val vibrator = context.getSystemService(android.os.Vibrator::class.java)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(40)
        }

        if (notificationKey != null) {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationKey.hashCode())
        }
        onAnswer(interactionId, optionId, taskId)
    }
}
