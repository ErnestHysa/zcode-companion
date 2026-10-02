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
}
