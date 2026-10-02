package com.zcode.android.core.notifications

import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.model.NotificationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §24.1 mapping + §24.2 privacy levels + §9.9 suppression — the pure decision core
 * of the Phase 8.1 broker (§43.2-8.1 "broker unit suite").
 */
class NotificationPlanTest {

    private val background = NotificationPlan.Policy(
        appForeground = false,
        contentLevel = AppPreferences.NotificationContentLevel.MINIMAL,
    )

    @Test
    fun `24-1 mapping - approval rides the HIGH permissions channel with category-only text`() {
        val d = NotificationPlan.decide(NotificationPlan.Event(NotificationRecord.Kind.APPROVAL_NEEDED), background)
        assertTrue(d.show)
        assertEquals(NotificationChannels.PERMISSIONS, d.channelId)
        assertEquals("ZCode needs your approval", d.publicText)
        // §24.1 "no tool/paths": NO detail text at either content level.
        assertNull(d.detailText)
    }

    @Test
    fun `24-1 mapping - user input rides the permissions channel`() {
        val d = NotificationPlan.decide(NotificationPlan.Event(NotificationRecord.Kind.USER_INPUT_NEEDED), background)
        assertTrue(d.show)
        assertEquals(NotificationChannels.PERMISSIONS, d.channelId)
        assertNull(d.detailText)
    }

    @Test
    fun `24-1 mapping - task finished is minimal by default (category only)`() {
        val d = NotificationPlan.decide(
            NotificationPlan.Event(NotificationRecord.Kind.TASK_FINISHED, taskTitle = "Write the release notes"),
            background,
        )
        assertTrue(d.show)
        assertEquals(NotificationChannels.TASK_STATUS, d.channelId)
        assertEquals("Task completed", d.publicText)
        assertNull(d.detailText) // T8: title only when the user opted into CONTENT
    }

    @Test
    fun `24-1 mapping - content level adds the task title (the toggleable cell)`() {
        val d = NotificationPlan.decide(
            NotificationPlan.Event(NotificationRecord.Kind.TASK_FINISHED, taskTitle = "Write the release notes"),
            background.copy(contentLevel = AppPreferences.NotificationContentLevel.CONTENT),
        )
        assertEquals("Task completed: Write the release notes", d.detailText)
    }

    @Test
    fun `24-1 mapping - task failed carries the failed class`() {
        val d = NotificationPlan.decide(
            NotificationPlan.Event(NotificationRecord.Kind.TASK_FAILED, taskTitle = "Fix the bug"),
            background,
        )
        assertEquals("Task failed", d.publicText)
        assertEquals(NotificationChannels.TASK_STATUS, d.channelId)
    }

    @Test
    fun `24-1 mapping - connection lost is category-only on task-status`() {
        val d = NotificationPlan.decide(NotificationPlan.Event(NotificationRecord.Kind.CONNECTION_LOST), background)
        assertEquals("Connection lost", d.publicText)
        assertEquals(NotificationChannels.TASK_STATUS, d.channelId)
    }

    @Test
    fun `9-9 suppression - nothing posts while the app is foregrounded`() {
        val d = NotificationPlan.decide(
            NotificationPlan.Event(NotificationRecord.Kind.APPROVAL_NEEDED),
            background.copy(appForeground = true),
        )
        assertFalse(d.show)
    }

    @Test
    fun `24-3 dedupe keys - one per interaction, collapse per task, one connection slot`() {
        assertEquals(
            "interaction:i-1",
            NotificationPlan.notificationKey(NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-1"),
        )
        assertEquals(
            "task:t1:TASK_FINISHED",
            NotificationPlan.notificationKey(NotificationRecord.Kind.TASK_FINISHED, taskId = "t1", interactionId = null),
        )
        assertEquals(
            "task:t1:TASK_FAILED",
            NotificationPlan.notificationKey(NotificationRecord.Kind.TASK_FAILED, taskId = "t1", interactionId = null),
        )
        assertEquals(
            "connection-lost",
            NotificationPlan.notificationKey(NotificationRecord.Kind.CONNECTION_LOST, null, null),
        )
        // No stable identity → no notification (never key collisions).
        assertNull(NotificationPlan.notificationKey(NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = null))
        assertNull(NotificationPlan.notificationKey(NotificationRecord.Kind.TASK_FINISHED, taskId = null, interactionId = null))
    }
}
