package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.RewindPreview
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-27-B (pass 31 dropped-default-arg sweep + 3rd/4th sightings): the screen's timeline
 * composables declare defaulted callback parameters that the CALL SITES drop, so whole
 * planes stay structurally null: the rewind preview sheet never paints a face (the
 * provider never reaches TurnSeparator — TimelineRow drops it), the G28 zoom viewer is
 * dead at the timeline (TimelineBody + TimelineRow both drop onOpenAttachment), external
 * handoff drops too, and the L7 diagnostics seam is null (onSectionCrash dropped at
 * TimelineBody). Pins:
 *
 * 1. THE SWEEP (whole-file, not a spot check): every defaulted FUNCTION-typed parameter
 *    of `TimelineBody` and `TimelineRow` must appear as a named argument at its call
 *    site — a NEW defaulted callback param added to either signature fails the pin
 *    until it is forwarded everywhere (the roadmap's named minimum).
 * 2. Behavior: a live thumbnail tap reaches onOpenAttachment (the zoom viewer gets data).
 * 3. Behavior: the rewind sheet paints the preview faces (safe/unsafe/ignored) with the
 *    canApply gate on the Confirm (loading/unavailable are the sheet's own states —
 *    the wiring is what this pin owns).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class TimelineCallbackWiringTest {

    @get:Rule
    val rule = createComposeRule()

    // ---------------------------------------------------------- 1. the source sweep

    private fun conversationScreenSource(): String {
        // Gradle's unit-test working dir is the module dir.
        val candidates = listOf(
            File("src/main/kotlin/com/zcode/android/features/conversation/ui/ConversationScreen.kt"),
            File("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/ConversationScreen.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("ConversationScreen.kt not found from the test working dir")
    }

    /** Start index of the named composable's declaration (its receiver-qualified `fun`). */
    private fun declarationStart(source: String, composable: String): Int {
        val needle = "ColumnScope.$composable("
        val callIdx = source.indexOf(needle)
        val defIdx = if (callIdx >= 0) source.lastIndexOf("fun ", callIdx) else -1
        val plain = source.indexOf("private fun $composable(")
        return if (defIdx >= 0 && callIdx >= 0) defIdx else plain
    }

    /** Defaulted function-typed params of the named private composable's signature. */
    private fun defaultedCallbackParams(source: String, composable: String): List<String> {
        val start = declarationStart(source, composable)
        assertTrue("declaration for $composable not found", start >= 0)
        // This file's style: the signature closes with a line-start ')' before the body.
        val end = source.indexOf("\n)", start)
        assertTrue("signature end for $composable not found", end > start)
        val signature = source.substring(start, end)
        return Regex("\\n\\s*(\\w+):\\s*[^\\n]*->[^\\n]*=\\s*(null|\\{})")
            .findAll(signature)
            .map { it.groupValues[1] }
            .filter { it != "modifier" }
            .toList()
    }

    /** Named arguments present at the first call site of `composable(` (balanced-paren scan). */
    private fun callSiteArgs(source: String, composable: String): Set<String> {
        val callIdx = source.indexOf("$composable(")
        assertTrue("call site for $composable not found", callIdx >= 0)
        var depth = 0
        var i = callIdx + composable.length
        var end = source.length
        while (i < source.length) {
            val c = source[i]
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) { end = i; break }
            }
            i++
        }
        val callText = source.substring(callIdx, end)
        return Regex("\\n\\s*(\\w+)\\s*=").findAll(callText).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `F27-B sweep - every defaulted callback param of the timeline composables is forwarded`() {
        val source = conversationScreenSource()
        val failures = mutableListOf<String>()
        // TimelineBody: ONE call site (the `else ->` arm of the screen's when).
        val bodyArgs = callSiteArgs(source, "TimelineBody")
        for (param in defaultedCallbackParams(source, "TimelineBody")) {
            if (param !in bodyArgs) failures += "TimelineBody call site drops `$param`"
        }
        // TimelineRow: ONE call site (the Single entry arm inside TimelineBody).
        val rowArgs = callSiteArgs(source, "TimelineRow")
        for (param in defaultedCallbackParams(source, "TimelineRow")) {
            if (param !in rowArgs) failures += "TimelineRow call site drops `$param`"
        }
        assertTrue(
            "F-27-B class re-manifested — defaulted callbacks dropped at call sites:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // ------------------------------------------------- 2. behavior: attachment tap

    private fun base(seq: Long) = ConversationRow.Base(createdAt = seq.toDouble(), createdAtSeq = seq)

    private fun attachmentState() = ConversationUiState(
        rows = listOf(
            ConversationRow.UserInput(
                rowId = 1, turnId = "t1", base = base(1), text = "look at this",
                epilogueStart = null, origin = "user", originMeta = null, guided = false, sourceCommandId = null,
                attachments = listOf(
                    ConversationRow.UserInput.AttachmentMeta(
                        ref = "img-1", fileName = "mock.png", mime = "image/png", bytes = 12, previewRef = null,
                    ),
                ),
                raw = JsonObject(emptyMap()),
            ),
        ),
        loading = false,
        taskState = TaskStateMachine.State.IDLE,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
    )

    @Test
    fun `F27-B - a thumbnail tap reaches onOpenAttachment`() {
        var opened: ConversationRow.UserInput.AttachmentMeta? = null
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = attachmentState(),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    loadAttachment = { null },
                    onOpenAttachment = { opened = it },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("row_attachment_img-1").performClick()
        rule.waitForIdle()
        assertEquals("thumbnail tap never reached onOpenAttachment (dropped at a call site)", "img-1", opened?.ref)
    }

    // --------------------------------------------- 3. behavior: rewind sheet faces

    private fun rewindRow(seq: Long) = ConversationRow.TurnHeader(
        rowId = seq, turnId = "t1",
        base = ConversationRow.Base(
            entityId = "e1", createdAt = seq.toDouble(), createdAtSeq = seq,
            actions = ConversationRow.Base.Actions(canRewindFiles = true),
        ),
        origin = "user", executionKind = null,
        state = ConversationRow.TurnState.COMPLETED_SUCCESS, startedAt = 1.0, endedAt = 2.0,
        activeMs = 4200, sourceCommandId = null, historyRoundCount = null,
        fileChanges = ConversationRow.TurnHeader.FileChanges(additions = 3, deletions = 1, files = 2, state = "changed"),
        raw = JsonObject(emptyMap()),
    )

    private fun preview(canApply: Boolean) = RewindPreview(
        canApply = canApply,
        safeFiles = listOf(RewindPreview.FileAction(action = "restore", path = "src/a.kt", operationCount = 2, toolNames = listOf("Edit"))),
        unsafeFiles = listOf(RewindPreview.UnsafeFile(path = "gen/out.txt", reason = "external_modified", message = "modified outside", operationCount = 1, toolNames = listOf("Bash"))),
        ignoredFiles = listOf(RewindPreview.IgnoredFile(path = "log.txt", reason = "bash_ignored", operationCount = 1)),
    )

    private fun rewindScreenState() = ConversationUiState(
        rows = listOf(rewindRow(1)),
        loading = false,
        taskState = TaskStateMachine.State.IDLE,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
    )

    @Test
    fun `F27-B - the rewind sheet paints the preview faces and gates Confirm on canApply=false`() {
        var providerRowId: Long? = null
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = rewindScreenState(),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    rewindPreviewProvider = { rowId, _ ->
                        providerRowId = rowId
                        preview(canApply = false)
                    },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("row_turn_rewind").performClick()
        rule.waitForIdle()
        // The provider RODE the wire to the row (pre-fix: TimelineRow dropped it → never called).
        assertEquals("rewindPreviewProvider never reached the row", 1L, providerRowId)
        // The faces paint: the preview body + the unsafe reason + the ignored count + the canApply refusal.
        rule.onNodeWithTag("rewind_preview").assertExists()
        rule.onNodeWithTag("rewind_unsafe_external_modified").assertExists()
        // (G72 vc52 Phase 12 upgraded the ignored face from a bare count line to the
        // titled per-file section — the batch that changed the face owns the pin.)
        rule.onNodeWithTag("rewind_ignored_title").assertExists()
        rule.onNodeWithTag("rewind_cannot_apply").assertExists()
        // The desktop's gate: canApply=false disables the destructive Confirm.
        rule.onNodeWithTag("rewind_confirm").assertExists()
        val confirm = rule.onNodeWithTag("rewind_confirm").fetchSemanticsNode().config
        assertTrue(
            "Confirm NOT disabled while canApply=false (the desktop's gate is dead)",
            confirm.contains(SemanticsProperties.Disabled),
        )
    }

    @Test
    fun `F27-B - canApply=true keeps the rewind Confirm enabled`() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = rewindScreenState(),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    rewindPreviewProvider = { _, _ -> preview(canApply = true) },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("row_turn_rewind").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("rewind_preview").assertExists()
        val confirm = rule.onNodeWithTag("rewind_confirm").fetchSemanticsNode().config
        assertFalse(
            "Confirm disabled while canApply=true (the gate refuses a safe rewind)",
            confirm.contains(SemanticsProperties.Disabled),
        )
    }
}
