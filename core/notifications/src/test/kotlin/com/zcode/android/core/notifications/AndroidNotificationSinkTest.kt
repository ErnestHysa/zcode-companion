package com.zcode.android.core.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * The tray-level surface (Robolectric): §24.2 channels exist at first run, the sink
 * posts through the REAL NotificationManager shadow with the §24.7 deep-link tap
 * target, MINIMAL keeps the content empty, and the per-endpoint group summary
 * appears at ≥2 notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidNotificationSinkTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannels.ensure(context)
    }

    @Test
    fun `24-2 channels are created at first run - idempotent`() {
        NotificationChannels.ensure(context) // second call must not throw/duplicate
        val ids = shadowOf(manager).notificationChannels.map { it.id }
        assertTrue(ids.containsAll(listOf(
            NotificationChannels.PERMISSIONS,
            NotificationChannels.TASK_STATUS,
            NotificationChannels.PROGRESS,
            NotificationChannels.LIVE_CONNECTION,
        )))
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            shadowOf(manager).notificationChannels.first { it.id == NotificationChannels.PERMISSIONS }.importance,
        )
    }

    @Test
    fun `sink posts through NotificationManager with a stable id per key`() {
        val sink = AndroidNotificationSink(context)
        sink.show(NotificationBroker.Spec("interaction:i-1", NotificationChannels.PERMISSIONS, "ZCode needs your approval", null, null, "t1", "i-1"))
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertEquals("ZCode needs your approval", shadowOf(posted.single()).contentTitle)
        // Same key again → same id → system-side update-in-place.
        sink.show(NotificationBroker.Spec("interaction:i-1", NotificationChannels.PERMISSIONS, "ZCode needs your approval", null, null, "t1", "i-1"))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `24-7 tap target carries the deep link with the interaction fragment`() {
        val sink = AndroidNotificationSink(context)
        sink.show(NotificationBroker.Spec("interaction:i-1", NotificationChannels.PERMISSIONS, "ZCode needs your approval", null, null, "task-9", "i-1"))
        val n = shadowOf(manager).allNotifications.single()
        val contentIntent = n.contentIntent
        assertNotNull(contentIntent)
        val intent = shadowOf(contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertEquals(Uri.parse("zcode://task/task-9#interaction=i-1").toString(), intent.data!!.toString())
    }

    @Test
    fun `LEDGER-SMALLS a - connection-scoped posts tap through to the connection screen`() {
        val sink = AndroidNotificationSink(context)
        // the CONNECTION_LOST spec shape: no task, deepLinkConnection=true
        sink.show(NotificationBroker.Spec("connection-lost", NotificationChannels.TASK_STATUS, "Connection lost", null, null, null, null, deepLinkConnection = true))
        val n = shadowOf(manager).allNotifications.single()
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("zcode://connection").toString(), intent.data!!.toString())
    }

    @Test
    fun `LEDGER-SMALLS a - task posts keep the task deep link (no cross-kind regression)`() {
        val sink = AndroidNotificationSink(context)
        sink.show(NotificationBroker.Spec("task:t1", NotificationChannels.TASK_STATUS, "Task done", null, null, "t1", null, deepLinkConnection = false))
        val intent = shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent
        assertEquals(Uri.parse("zcode://task/t1").toString(), intent.data!!.toString())
    }

    @Test
    fun `24-1 minimal keeps the detail text off the tray`() {
        val sink = AndroidNotificationSink(context)
        sink.show(NotificationBroker.Spec("task:t1:TASK_FINISHED", NotificationChannels.TASK_STATUS, "Task completed", null, null, "t1", null))
        val n = shadowOf(manager).allNotifications.single()
        // The public text is the title; no content line beyond it.
        assertEquals("Task completed", shadowOf(n).contentTitle)
        assertEquals(null, n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
    }

    @Test
    fun `24-2 group summary appears at 2+ and disappears below`() {
        val sink = AndroidNotificationSink(context)
        val group = "endpoint-1"
        sink.show(NotificationBroker.Spec("interaction:i-1", NotificationChannels.PERMISSIONS, "ZCode needs your approval", null, group, "t1", "i-1"))
        assertEquals(1, shadowOf(manager).allNotifications.size) // no summary yet
        sink.show(NotificationBroker.Spec("interaction:i-2", NotificationChannels.PERMISSIONS, "ZCode has a question for you", null, group, "t1", "i-2"))
        val withSummary = shadowOf(manager).allNotifications
        assertEquals(3, withSummary.size) // 2 items + summary
        sink.cancel("interaction:i-1")
        sink.cancel("interaction:i-2")
        assertEquals(0, shadowOf(manager).allNotifications.size) // summary cleaned up
    }

    @Test
    fun `cancel removes the notification`() {
        val sink = AndroidNotificationSink(context)
        sink.show(NotificationBroker.Spec("interaction:i-1", NotificationChannels.PERMISSIONS, "x", null, null, "t1", "i-1"))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        sink.cancel("interaction:i-1")
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    /**
     * F-37-B (vc52 Phase 6, P1): a PIN-locked phone approved a real Bash command from the
     * lock screen (pass 37: `deviceLocked=1`, Approve → `sendConversationCommandV4 ok 54ms`)
     * because the actions were attached with the bare addAction(icon, title, intent)
     * overload — no Action object, so authenticationRequired was never set. On API 31+
     * the platform must demand the credential before it dispatches a decision intent.
     * Threat: anyone holding the locked phone answers live approvals (shell commands on
     * the desktop). Passive (task-status) actions stay one-tap.
     */
    @Test
    @Config(sdk = [35])
    fun `F-37-B - permission-channel shade actions demand device authentication, passive ones do not`() {
        val sink = AndroidNotificationSink(context)
        sink.show(
            NotificationBroker.Spec(
                "interaction:i-9", NotificationChannels.PERMISSIONS, "ZCode needs your approval", null, null, "t-9", "i-9",
                actions = listOf(
                    NotificationBroker.SpecAction(NotificationActions.ID_APPROVE, "Approve", "i-9", "t-9", "allowOnce"),
                    NotificationBroker.SpecAction(NotificationActions.ID_DENY, "Deny", "i-9", "t-9", "deny"),
                ),
            ),
        )
        sink.show(
            NotificationBroker.Spec(
                "task:t-9:TASK_FINISHED", NotificationChannels.TASK_STATUS, "Task completed", null, null, "t-9", null,
                actions = listOf(NotificationBroker.SpecAction("open", "Open", "i-0", "t-9", "open")),
            ),
        )
        val posted = shadowOf(manager).allNotifications
        val approval = posted.single { it.channelId == NotificationChannels.PERMISSIONS }
        assertEquals(2, approval.actions.size)
        assertTrue(
            "every permission-channel action must require device authentication",
            approval.actions.all { it.isAuthenticationRequired },
        )
        val passive = posted.single { it.channelId == NotificationChannels.TASK_STATUS }
        assertEquals(1, passive.actions.size)
        assertTrue("passive actions stay one-tap", passive.actions.none { it.isAuthenticationRequired })
    }

    /**
     * F-33-H (vc52 Phase 6, P3, safety): approvals were category-only at BOTH content levels
     * (`android.text=null`, no public version) yet carried Approve/Deny — a shell command
     * could be approved from the shade unseen. The subject now rides the PRIVATE version;
     * the lock screen's PUBLIC version stays category-only, with no actions.
     */
    @Test
    fun `F-33-H - the lock screen gets a category-only public version, the private version carries the subject`() {
        val sink = AndroidNotificationSink(context)
        sink.show(
            NotificationBroker.Spec(
                "interaction:i-7", NotificationChannels.PERMISSIONS, "ZCode needs your approval",
                "Bash: echo synthetic", null, "t-7", "i-7",
                actions = listOf(
                    NotificationBroker.SpecAction(NotificationActions.ID_APPROVE, "Approve", "i-7", "t-7", "allowOnce"),
                    NotificationBroker.SpecAction(NotificationActions.ID_DENY, "Deny", "i-7", "t-7", "deny"),
                ),
            ),
        )
        val n = shadowOf(manager).allNotifications.single()
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Bash: echo synthetic", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
        val public = n.publicVersion
        assertNotNull("no public version: a redacting lock screen has nothing category-only to show", public)
        assertEquals("ZCode needs your approval", public!!.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals(null, public.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        assertEquals(null, public.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))
        assertTrue("the public version must carry no actions", public.actions.isNullOrEmpty())
    }


    @Test
    fun `G77 - a fuse deadline renders as a native countdown chronometer`() {
        val sink = AndroidNotificationSink(context)
        val deadline = 1_794_100_000_000L
        sink.show(
            NotificationBroker.Spec(
                "interaction:i-fuse", NotificationChannels.PERMISSIONS,
                "ZCode has a question for you", "Which channel?", null, "t1", "i-fuse",
                deadlineAtMs = deadline,
            ),
        )
        val n = shadowOf(manager).allNotifications.single()
        assertTrue(
            "the chronometer counts DOWN toward the deadline",
            n.extras.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
        )
        assertEquals("the chronometer's base is the fuse deadline", deadline, n.`when`)
    }

    @Test
    fun `G77 - no fuse - no chronometer (the pre-G77 face)`() {
        val sink = AndroidNotificationSink(context)
        sink.show(
            NotificationBroker.Spec(
                "interaction:i-plain", NotificationChannels.PERMISSIONS,
                "ZCode needs your approval", null, null, "t1", "i-plain",
            ),
        )
        val n = shadowOf(manager).allNotifications.single()
        assertTrue("no fuse → no countdown chronometer", !n.extras.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
    }
}
