package com.zcode.android.features.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.AttachmentViewerDialog
import com.zcode.android.features.conversation.ui.YouBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * pass-0 Phase 7 inbound-attachment pins:
 *
 *  - **F-P11-A**: the conversation attachment chip has an honest UNAVAILABLE face —
 *    a failed read used to leave "Loading…" forever (produceState swallowed the
 *    failure; no unavailable state, no retry). A failing loader must render the
 *    unavailable face and a tap on it must RE-TRY the load.
 *  - **N-BJ-1**: the thumbnail decode is CAPPED (sample-down, aspect preserved) —
 *    the fixed 132×96dp frame arrived with G28-era sizing, but the decode itself
 *    was full-resolution (a 4000×3000 photo allocated its full bitmap for a
 *    132dp window).
 *  - The zoom viewer shares the loader channel — a failing load renders its honest
 *    unavailable face too (never an eternal Loading).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase7AttachmentChipFacesTest {

    @get:Rule
    val rule = createComposeRule()

    private val tinyPng = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
    )

    private fun imageAtt(ref: String = "att-img-9") = ConversationRow.UserInput.AttachmentMeta(
        ref = ref, fileName = "diagram.png", mime = "image/png", bytes = tinyPng.size.toLong(), previewRef = null,
    )

    /** produceState settles on Robolectric's scheduler asynchronously — wait, never
     *  assume waitForIdle caught the recomposition (the G28 viewer test precedent). */
    private fun awaitUnavailable(ref: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("row_attachment_unavailable_$ref", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `F-P11-A - a failing loader renders the unavailable face, never eternal Loading`() {
        val loads = mutableListOf<String>()
        rule.setContent {
            ZCodeTheme {
                Column {
                    YouBlock(
                        text = "see attached",
                        attachments = listOf(imageAtt("att-fail-a")),
                        loadAttachment = { att -> loads.add(att.ref); null }, // the read fails
                        onOpenAttachment = {},
                    )
                }
            }
        }
        awaitUnavailable("att-fail-a")
        rule.onNodeWithTag("row_attachment_unavailable_att-fail-a", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Unavailable", substring = true).assertExists()
        // The lying window: the chip still claiming "Loading…" after the read failed.
        rule.onAllNodesWithText("Loading", substring = true).let {
            assertEquals("a failed read must not leave the loading face up", 0, it.fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `F-P11-A - tapping the unavailable face retries the load`() {
        val loads = mutableListOf<String>()
        rule.setContent {
            ZCodeTheme {
                Column {
                    YouBlock(
                        text = "see attached",
                        attachments = listOf(imageAtt("att-retry-b")),
                        loadAttachment = { att -> loads.add(att.ref); null },
                        onOpenAttachment = {},
                    )
                }
            }
        }
        awaitUnavailable("att-retry-b")
        assertEquals("the initial load ran once", 1, loads.size)
        // The G28 tap pattern: click the CHIP's base tag (the Surface carries the
        // retry clickable in the unavailable state — the same node the zoom tap
        // uses in the ready state).
        rule.onNodeWithTag("row_attachment_att-retry-b").performClick()
        // The re-keyed produceState restarts the producer asynchronously — settle
        // the clock (waitUntil's pump misses it on Robolectric; the probe proved
        // idle+advance+idle sees the retry).
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals("the retry must re-invoke the loader", 2, loads.size)
    }

    @Test
    fun `F-P11-A - a successful load still renders the image (no regression)`() {
        rule.setContent {
            ZCodeTheme {
                Column {
                    YouBlock(
                        text = "see attached",
                        attachments = listOf(imageAtt()),
                        loadAttachment = { tinyPng },
                        onOpenAttachment = {},
                    )
                }
            }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Unavailable", substring = true).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText("Loading", substring = true).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("row_attachment_att-img-9").assertExists()
    }

    @Test
    fun `F-P11-A - the zoom viewer renders its honest unavailable face on a failed load`() {
        rule.setContent { ZCodeTheme { AttachmentViewerDialog(att = imageAtt(), loader = { null }, onDismiss = {}) } }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("attachment_viewer_unavailable", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("Loading", substring = true).let {
            assertEquals("the viewer must not claim Loading after the load failed", 0, it.fetchSemanticsNodes().size)
        }
    }

    // ------------------------------------------------------------------ N-BJ-1

    /**
     * The decode cap: the chip's thumbnail decode is the sampled-down helper
     * (aspect preserved, max dimension bounded) — never a full-resolution decode
     * behind a 132dp window.
     */
    @Test
    fun `N-BJ-1 - the thumbnail decode is the capped sampler, not the raw decoder`() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        val source = java.io.File(
            root,
            "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/RowRenderers.kt",
        ).readText()
        val view = source.substringAfter("private fun AttachmentView(")
            .substringBefore("/**\n * G18:")
        assertTrue(
            "the chip must decode through the capped sampler (decodeAttachmentThumbnail), not raw BitmapFactory.decodeByteArray",
            view.contains("decodeAttachmentThumbnail("),
        )
        assertTrue(
            "the capped sampler must exist with a max-dimension bound",
            Regex("""decodeAttachmentThumbnail[\s\S]{0,400}maxDim""").containsMatchIn(source),
        )
    }

}
