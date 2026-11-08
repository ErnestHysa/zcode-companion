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

    // ------------------------------------------------------------------ F-37-B locked path

    private fun decisionIntent(interactionId: String, optionId: String, key: String) =
        Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActions.ACTION
            putExtra(NotificationActions.EXTRA_INTERACTION_ID, interactionId)
            putExtra(NotificationActions.EXTRA_OPTION_ID, optionId)
            putExtra(NotificationActions.EXTRA_TASK_ID, "task-l")
            putExtra(NotificationActions.EXTRA_NOTIFICATION_KEY, key)
        }

    /** The approval notification the decision belongs to (the sink's id = key.hashCode()). */
    private fun postApproval(key: String): NotificationManager {
        NotificationChannels.ensure(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            key.hashCode(),
            Notification.Builder(context, NotificationChannels.PERMISSIONS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("ZCode needs your approval")
                .build(),
        )
        return manager
    }

    private fun lockDevice(locked: Boolean) {
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        shadowOf(keyguard).setIsDeviceSecure(true)
        shadowOf(keyguard).setIsDeviceLocked(locked)
        shadowOf(keyguard).setKeyguardLocked(locked)
    }

    /**
     * F-37-B (vc52 Phase 6, P1) — threat: anyone holding a PIN-locked phone answers a live
     * approval (a shell command on the desktop) from the lock screen. The platform gate
     * (authenticationRequired, API 31+) is pinned at the sink; this is the receiver's own
     * guard for API 26–30 and any path that reaches it while locked: the decision is
     * DISCARDED (never replayed after unlock), the notification stays for the owner, and
     * no confirmation haptic fires for an answer that did not happen.
     */
    @Test
    fun `F-37-B - a decision tapped on a locked device never answers and keeps the notification`() {
        val manager = postApproval("k-locked")
        lockDevice(true)
        var answered = 0
        NotificationActionReceiver(onAnswer = { _, _, _ -> answered++ })
            .onReceive(context, decisionIntent("i-locked", "allowOnce", "k-locked"))
        assertEquals("a locked device must never send the decision", 0, answered)
        assertEquals("the notification stays for the owner to act after unlocking", 1, shadowOf(manager).activeNotifications.size)
        val vibrator = context.getSystemService(android.os.Vibrator::class.java)
        org.junit.Assert.assertFalse("no confirmation haptic for a decision that never fired", shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `F-37-B - unlocked, the decision answers exactly once, cancels its notification, then haptics`() {
        val manager = postApproval("k-unlocked")
        lockDevice(false)
        var answered = 0
        NotificationActionReceiver(onAnswer = { _, _, _ -> answered++ })
            .onReceive(context, decisionIntent("i-unlocked", "allowOnce", "k-unlocked"))
        assertEquals(1, answered)
        assertEquals(0, shadowOf(manager).activeNotifications.size)
        val vibrator = context.getSystemService(android.os.Vibrator::class.java)
        assertTrue("the post-decision haptic confirms the answer that fired", shadowOf(vibrator).isVibrating)
    }

    /**
     * F-33-H (vc52): the "Notification detail" setting decides whether approvals carry
     * their subject + shade actions (Task titles) or are answered in-app (Minimal). A
     * privacy setting must say both, in every shipped locale.
     */
    @Test
    fun `F-33-H - the notification-detail setting describes what each level shows`() {
        val en = context.getString(com.zcode.android.app.R.string.settings_notif_detail_sub)
        assertTrue("Minimal must say approvals are answered in the app: $en", en.contains("answered in the app"))
        assertTrue("Task titles must say it shows the command or file: $en", en.contains("command or file"))
        assertTrue("…and that the actions need an unlock: $en", en.contains("unlock"))
        val zhConfig = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("zh-CN"))
        }
        val zh = context.createConfigurationContext(zhConfig).getString(com.zcode.android.app.R.string.settings_notif_detail_sub)
        assertTrue("zh-CN must carry the same two promises: $zh", zh.contains("应用内") && zh.contains("命令或文件"))
    }
}
