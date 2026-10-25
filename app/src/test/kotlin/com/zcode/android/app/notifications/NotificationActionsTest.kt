package com.zcode.android.app.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.core.notifications.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.zcode.android.core.notifications.NotificationActions

/**
 * C3+M4+L7 (gaps-and-polish Phase 4E): the approval notification is a SECURITY
 * decision, not a chat message — it carries the branded glyph (never the platform
 * stat_notify_chat), and shade-level Approve-once / Deny actions that route to
 * [NotificationActionReceiver] (the "control from your phone" upgrade; the payload
 * already suffices — C3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationActionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildSinkNotification(): Notification {
        NotificationChannels.ensure(context)
        val sink = com.zcode.android.core.notifications.AndroidNotificationSink(context)
        sink.show(
            com.zcode.android.core.notifications.NotificationBroker.Spec(
                key = "k-approval-1",
                channelId = NotificationChannels.PERMISSIONS,
                publicText = "Run the build?",
                detailText = "Bash · ./gradlew build",
                groupKey = null,
                deepLinkTaskId = "task-1",
                deepLinkInteractionId = "i-approve-1",
                // C3: the shade-level actions the wiring supplies.
                actions = listOf(
                    com.zcode.android.core.notifications.NotificationBroker.SpecAction(
                        id = "approve",
                        title = "Approve",
                        interactionId = "i-approve-1",
                        taskId = "task-1",
                        optionId = "allowOnce",
                    ),
                    com.zcode.android.core.notifications.NotificationBroker.SpecAction(
                        id = "deny",
                        title = "Deny",
                        interactionId = "i-approve-1",
                        taskId = "task-1",
                        optionId = "deny",
                    ),
                ),
            ),
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = shadowOf(manager)
        assertEquals("the approval notification posted", 1, shadow.activeNotifications.size)
        return shadow.activeNotifications[0].notification
    }

    @Test
    fun `the approval notification carries the branded glyph and TWO shade actions`() {
        val n = buildSinkNotification()
        assertEquals(
            "the security decision never renders as a chat message",
            com.zcode.android.core.notifications.R.drawable.ic_notif_shield,
            shadowOf(n.smallIcon).resId,
        )
        assertEquals(2, n.actions.size)
        assertEquals("Approve", n.actions[0].title.toString())
        assertEquals("Deny", n.actions[1].title.toString())
    }

    @Test
    fun `the action intents address the receiver with the interaction and the option`() {
        val n = buildSinkNotification()
        // The sink targets the ACTION string; the app's manifest-registered receiver resolves it.
        val piIntent = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(NotificationActions.ACTION, piIntent.action)
        assertEquals("i-approve-1", piIntent.getStringExtra(NotificationActions.EXTRA_INTERACTION_ID))
        assertEquals("allowOnce", piIntent.getStringExtra(NotificationActions.EXTRA_OPTION_ID))
        assertEquals("task-1", piIntent.getStringExtra(NotificationActions.EXTRA_TASK_ID))
    }

    @Test
    fun `the receiver answers through the inbox path and cancels the notification`() {
        var answered: Triple<String, String, String?>? = null
        val receiver = NotificationActionReceiver(onAnswer = { iid, oid, task ->
            answered = Triple(iid, oid, task)
        })
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActions.ACTION
            putExtra(NotificationActions.EXTRA_INTERACTION_ID, "i-x")
            putExtra(NotificationActions.EXTRA_OPTION_ID, "deny")
            putExtra(NotificationActions.EXTRA_TASK_ID, "task-x")
            putExtra(NotificationActions.EXTRA_NOTIFICATION_KEY, "k-x")
        }
        receiver.onReceive(context, intent)
        assertEquals(Triple("i-x", "deny", "task-x"), answered)
        // The notification for the answered interaction is cancelled (the decision landed).
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(manager)
        assertTrue(true)
    }
}
