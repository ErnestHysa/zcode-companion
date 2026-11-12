package com.zcode.android.core.notifications

import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.model.NotificationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * G63 (vc52 Phase 13, the notification-sound parity small): the desktop ships a
 * per-notification "Notification sound" mute; the phone's channels carry their
 * sound at creation (API 26+ makes post-hoc channel sound edits a no-op), so the
 * honest phone slice is a SILENT twin channel the plan routes to when the
 * user's sound preference is OFF.
 *
 * Pins: the sound-off routing applies ONLY to the task-status family (approvals
 * keep their sound — a muted approval the user never hears is a missed command);
 * the silent channel exists; the default (sound ON) is unchanged.
 */
class NotificationPlanSoundTest {

    private fun decision(
        kind: NotificationRecord.Kind,
        soundOn: Boolean,
    ) = NotificationPlan.decide(
        NotificationPlan.Event(kind, taskTitle = "t"),
        NotificationPlan.Policy(appForeground = false, contentLevel = AppPreferences.NotificationContentLevel.MINIMAL, soundOn = soundOn),
    )

    @Test
    fun `sound-off routes task outcomes to the silent twin channel`() {
        val finished = decision(NotificationRecord.Kind.TASK_FINISHED, soundOn = false)
        val failed = decision(NotificationRecord.Kind.TASK_FAILED, soundOn = false)
        assertEquals(NotificationChannels.TASK_STATUS_SILENT, finished.channelId)
        assertEquals(NotificationChannels.TASK_STATUS_SILENT, failed.channelId)
    }

    @Test
    fun `sound-on keeps the audible task-status channel (default unchanged)`() {
        val finished = decision(NotificationRecord.Kind.TASK_FINISHED, soundOn = true)
        assertEquals(NotificationChannels.TASK_STATUS, finished.channelId)
        assertEquals(NotificationChannels.TASK_STATUS, NotificationPlan.channelIdFor(NotificationRecord.Kind.TASK_FINISHED))
    }

    @Test
    fun `approvals and questions keep their sound even when task sound is off`() {
        // The desktop's mute is the task-notification sound; a silenced approval
        // would be a missed command — the PERMISSIONS channel is never muted here.
        val approval = decision(NotificationRecord.Kind.APPROVAL_NEEDED, soundOn = false)
        val question = decision(NotificationRecord.Kind.USER_INPUT_NEEDED, soundOn = false)
        assertEquals(NotificationChannels.PERMISSIONS, approval.channelId)
        assertEquals(NotificationChannels.PERMISSIONS, question.channelId)
    }

    @Test
    fun `silent and audible channels are distinct ids`() {
        assertNotEquals(NotificationChannels.TASK_STATUS, NotificationChannels.TASK_STATUS_SILENT)
    }

    @Test
    fun `connection-lost follows the same mute`() {
        val lost = decision(NotificationRecord.Kind.CONNECTION_LOST, soundOn = false)
        assertEquals(NotificationChannels.TASK_STATUS_SILENT, lost.channelId)
    }
}
