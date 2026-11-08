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
 *
 * F-37-B (vc52): a decision that reaches this receiver while the device is LOCKED behind
 * a credential is discarded — never sent, never replayed after unlock — and the
 * notification stays so the owner can act once unlocked. On API 31+ the sink's actions
 * are authenticationRequired, so the platform demands the credential before dispatching;
 * this guard is the API 26–30 path and the defense in depth for any locked delivery.
 */
class NotificationActionReceiver(
    private val onAnswer: (interactionId: String, optionId: String, taskId: String?) -> Unit =
        { interactionId, optionId, taskId -> answerDelegate?.invoke(interactionId, optionId, taskId) },
    private val deviceLocked: (Context) -> Boolean = { context ->
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true
    },
) : android.content.BroadcastReceiver() {

    companion object {
        /**
         * The system instantiates receivers with NO constructor args — the app
         * container registers the answer delegate at init (the SAME fail-closed
         * `answerFromInbox` gate the approvals screen uses; never a bespoke wire
         * call from a receiver).
         */
        @Volatile var answerDelegate: ((interactionId: String, optionId: String, taskId: String?) -> Unit)? = null

        /** F-37-B: told when a decision was discarded because the device was locked (diagnostics). */
        @Volatile var lockedRefusalDelegate: ((interactionId: String) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationActions.ACTION) return
        val interactionId = intent.getStringExtra(NotificationActions.EXTRA_INTERACTION_ID) ?: return
        val optionId = intent.getStringExtra(NotificationActions.EXTRA_OPTION_ID) ?: return
        val taskId = intent.getStringExtra(NotificationActions.EXTRA_TASK_ID)
        val notificationKey = intent.getStringExtra(NotificationActions.EXTRA_NOTIFICATION_KEY)

        if (deviceLocked(context)) {
            lockedRefusalDelegate?.invoke(interactionId)
            return
        }

        if (notificationKey != null) {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationKey.hashCode())
        }
        onAnswer(interactionId, optionId, taskId)

        // Post-decision haptic (C3): a confirmation tick the user feels without looking —
        // only for an answer that was actually dispatched.
        val vibrator = context.getSystemService(android.os.Vibrator::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(40)
        }
    }
}
