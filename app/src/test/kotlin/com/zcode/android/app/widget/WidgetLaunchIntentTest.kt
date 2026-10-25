package com.zcode.android.app.widget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.notifications.DeepLinks
import com.zcode.android.domain.model.NotificationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * H3.2a (gaps-and-polish Phase 1C, P0): the approvals widget launched MainActivity
 * with NO data/extras, so a tap with pending approvals landed on the bare task
 * list. The launch intent must carry the VALIDATED `zcode://task` deep link of the
 * newest open approval (the §24.7 intake parser is already proven — J8) and stay a
 * plain launch when nothing is pending (or the record is link-unsafe).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WidgetLaunchIntentTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun record(taskId: String?, interactionId: String? = null, seen: Boolean = false) =
        NotificationRecord(
            notificationKey = "interaction:/$taskId/$interactionId",
            taskId = taskId,
            kind = NotificationRecord.Kind.APPROVAL_NEEDED,
            createdAtMs = 1_000L,
            seenAtMs = if (seen) 2_000L else null,
            interactionId = interactionId,
        )

    @Test
    fun `pending approval - the intent carries the validated task deep link`() {
        val intent = launchIntent(context, listOf(record("task-9", "i-42")))

        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertNotNull("the launch intent must carry the deep link data", intent.data)
        val candidate = DeepLinks.intake(intent)
        assertNotNull("the carried link must pass the §24.7 intake parser", candidate)
        assertEquals("task-9", candidate!!.taskId)
        assertEquals("i-42", candidate.interactionId)
    }

    @Test
    fun `no pending approvals - plain launch without data`() {
        val intent = launchIntent(context, listOf(record("task-9", seen = true)))
        assertNull("nothing pending → plain launch (no stale link)", intent.data)
    }

    @Test
    fun `link-unsafe record - plain launch (parser-validated, never malformed)`() {
        val intent = launchIntent(context, listOf(record("", "i-1")))
        assertNull("a blank taskId must not produce a malformed link", intent.data)
    }
}
