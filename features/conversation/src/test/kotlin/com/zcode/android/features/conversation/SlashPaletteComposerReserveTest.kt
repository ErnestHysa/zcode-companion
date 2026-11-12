package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.BackgroundWork
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.SharedContextImportState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BD-1 (pass 31; worse face at pass 35 — "2 visible rows of 20"): with the armed
 * pinned stack (interaction card + queue strip + background banner + shared-context
 * chip) and the palette open, the COMPOSER ROW WAS NOT LAID OUT AT ALL — absent from
 * the a11y tree, Send unreachable; typing still worked (focus persisted) but nothing
 * could be sent while the draft matched "/". Mechanism: the palette is an in-flow
 * child of the composer column measured BEFORE the shell — it consumed the remaining
 * height and the shell (field + action row) measured to zero.
 *
 * Pin (the armed shape, h520dp = the IME-open window the rig reproduced the squeeze
 * at): composer + Send stay VISIBLE with the palette open; the palette still scrolls
 * all 20 commands (the G21 contract survives).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h700dp")
class SlashPaletteComposerReserveTest {

    @get:Rule
    val rule = createComposeRule()

    private fun permissionInteraction() = PendingInteraction(
        interactionId = "perm-palette",
        kind = PendingInteraction.Kind.PERMISSION,
        anchorRowId = null,
        createdAtMs = 1L,
        permission = PendingInteraction.PermissionPayload(
            toolCallId = null,
            toolName = "Bash",
            summary = "run a command",
            detail = null,
            freeText = null,
            options = listOf(
                com.zcode.android.domain.model.PermissionOption("allowOnce"),
                com.zcode.android.domain.model.PermissionOption("allowAlways"),
                com.zcode.android.domain.model.PermissionOption("deny"),
            ),
            raw = buildJsonObject {},
        ),
        userInput = null,
        raw = buildJsonObject {},
    )

    private fun armedPaletteState() = ConversationUiState(
        rows = listOf(
            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
        ),
        loading = false,
        taskState = TaskStateMachine.State.WAITING_FOR_PERMISSION,
        phase = ConnectionPhase.CONNECTED,
        draft = "/",
        slashCommands = (1..20).map {
            ConversationPort.SlashCommandView(name = "cmd$it", description = "command $it", inputHint = null, source = null)
        },
        sessionState = SessionProjectionState(
            pendingInteractions = listOf(permissionInteraction()),
            queue = QueueState(
                items = (1..2).map { i ->
                    QueueState.QueueItem(
                        queueItemId = "q-$i",
                        text = "queued follow-up number $i that wraps onto the next line of the strip",
                        kind = null,
                        dispatchState = "queued",
                    )
                },
                autoDrain = false,
                pauseReason = null,
            ),
            backgroundWorks = listOf(
                BackgroundWork(workId = "bw-1", kind = "bash", title = "background job", status = "running", cancellable = true),
            ),
            sharedContextImport = SharedContextImportState(
                contextId = "ctx-1", title = "Handover context", status = "pending", shareUrl = null,
            ),
        ),
    )

    @Test
    fun `N-BD-1 - palette open with the armed stack keeps the composer and Send visible`() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = armedPaletteState(),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = { _, _, _ -> },
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    onCancelBackgroundWork = {},
                    backgroundOutputProvider = { null },
                )
            }
        }
        rule.waitForIdle()
        // The palette is open (the draft is a bare slash token).
        rule.onNodeWithTag("slash_menu").assertExists()
        val nodes = rule.onAllNodesWithTag("composer_send").fetchSemanticsNodes()
        for (n in nodes) println("PROBE send bounds=" + n.boundsInRoot)
        // THE BUG: pre-fix the in-flow palette consumed the shell's remaining height —
        // Send unreachable/unpainted (the rig's "composer row not laid out at all").
        rule.onNodeWithTag("composer_send").assertIsDisplayed()
        rule.onNodeWithTag("composer_field").assertIsDisplayed()
    }

    @Test
    fun `N-BD-1 - the open palette still scrolls all 20 commands`() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = armedPaletteState(),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = { _, _, _ -> },
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    onCancelBackgroundWork = {},
                    backgroundOutputProvider = { null },
                )
            }
        }
        rule.waitForIdle()
        // All 20 command rows are composed (a scrollable Column, not a LazyColumn).
        // (The per-command tags live on Texts MERGED into the clickable rows — the
        // rows themselves are the findable nodes.)
        rule.onAllNodesWithTag("slash_menu_item").assertCountEquals(20)
        // …and the LAST one is REACHABLE by scrolling (the G21 contract).
        rule.onAllNodesWithTag("slash_menu_item")[19].performScrollTo()
        rule.waitForIdle()
        val scroll = rule.onNodeWithTag("slash_menu_scroll").fetchSemanticsNode().config
        val axis = scroll[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the palette must be scrollable with 20 commands", axis != null && axis.maxValue.invoke() > 0f)
    }
}
