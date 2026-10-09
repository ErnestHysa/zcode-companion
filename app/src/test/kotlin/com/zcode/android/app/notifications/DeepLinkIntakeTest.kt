package com.zcode.android.app.notifications

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.domain.recovery.DeepLinkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §24.7 deep-link matrix at the APP layer (§43.2-8.2 "deep-link matrix… U21 green;
 * stale-safe"): the manifest filter, the intake parser, and the pendingLink
 * lifecycle (unpaired holds; malformed never offers). The resolve matrix itself is
 * the domain suite's (DeepLinkRouteTest); the on-device tap-through leg is the
 * instrumented Phase 8 journey.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DeepLinkIntakeTest {

    private fun viewIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(
            ApplicationProvider.getApplicationContext<android.content.Context>().packageName,
        )

    // ---------------------------------------------------------------- manifest

    @Test
    fun `manifest exposes the zcode task VIEW filter (browsable)`() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val intents = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("zcode://task/some-task")),
            android.content.pm.PackageManager.GET_RESOLVED_FILTER,
        )
        val filter = intents.map { it.filter }.firstOrNull { f ->
            f != null && f.hasAction(Intent.ACTION_VIEW) && f.hasCategory(Intent.CATEGORY_BROWSABLE)
        }
        assertTrue("the merged manifest must route zcode://task into MainActivity (§24.7)", filter != null)
        // The filter must MATCH our exact URI shape (scheme/host), browsable category included.
        val match = filter!!.match(
            Intent.ACTION_VIEW,
            null,
            "zcode",
            Uri.parse("zcode://task/some-task"),
            setOf(Intent.CATEGORY_DEFAULT),
            "deeplink-test",
        )
        assertTrue("zcode://task URIs must match the declared filter (match=$match)", match >= 0)
    }

    // ---------------------------------------------------------------- intake parse

    @Test
    fun `happy path parses task id and interaction fragment`() {
        val c = DeepLinks.intake(viewIntent("zcode://task/task-9#interaction=i-42"))!!
        assertEquals("task-9", c.taskId)
        assertEquals("i-42", c.interactionId)
    }

    @Test
    fun `no fragment parses to a null interaction`() {
        val c = DeepLinks.intake(viewIntent("zcode://task/task-9"))!!
        assertEquals("task-9", c.taskId)
        assertNull(c.interactionId)
    }

    @Test
    fun `wrong host or scheme is not ours - never offered`() {
        assertNull(DeepLinks.intake(viewIntent("zcode://other/task-9")))
        assertNull(DeepLinks.intake(viewIntent("https://task/task-9")))
        assertNull(DeepLinks.intake(viewIntent("zcode://task/task-9/extra")))
    }

    @Test
    fun `malformed shapes never crash and never offer - 24-7`() {
        assertNull(DeepLinks.intake(viewIntent("zcode://task/")))
        assertNull(DeepLinks.intake(viewIntent("zcode://task/%20#interaction=x")))
        assertNull(DeepLinks.intake(Intent(Intent.ACTION_MAIN)))
        assertNull(DeepLinks.intake(null as Intent?))
        // Blank interaction fragment → dropped, task id kept.
        val c = DeepLinks.intake(viewIntent("zcode://task/task-9#interaction="))!!
        assertNull(c.interactionId)
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `pending link holds until consumed - unpaired revalidation shape`() {
        val pending = PendingDeepLink()
        pending.offer(DeepLinks.Candidate("t-1", "i-1"))
        assertEquals("t-1", pending.value.value?.taskId)
        // The slot HOLDS the offered link until an explicit consume (the nav
        // graph's post-pairing re-resolution is that consumer).
        assertEquals("t-1", pending.value.value?.taskId)
        pending.consume()
        assertNull(pending.value.value)
    }

    @Test
    fun `intake candidate flows into the pending slot untouched`() {
        val pending = PendingDeepLink()
        DeepLinks.intake(viewIntent("zcode://task/task-9#interaction=i-42"))?.let { pending.offer(it) }
        assertEquals("i-42", pending.value.value?.interactionId)
    }

    @Test
    fun `domain matrix spot-check - stale interaction resolves to OPEN_TASK_INTERACTION_STALE (S14 safe)`() {
        val link = DeepLinkRoute.parse("task-9", "i-1")!!
        val outcome = DeepLinkRoute.resolve(
            link,
            DeepLinkRoute.Environment.Paired(
                endpointId = "e1",
                connected = true,
                taskExists = true,
                interactionKnown = false,
                existsOnOtherEndpoint = false,
            ),
        )
        assertEquals(DeepLinkRoute.Outcome.OPEN_TASK_INTERACTION_STALE, outcome)
    }
}
