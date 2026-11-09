package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BH-1 (pass 36): sketch → Done uploads and shows the chip, but with no typed text
 * the Send button stayed DISABLED (`enabled = state.draft.isNotBlank()`) while the
 * Stop/Send morph already used `hasDraftToSubmit` (the GAP-A9 desktop contract counts
 * attachments as submittable). The natural sketch flow dead-ended until the user
 * invented text. Wire truth (shipped desktop asar, 2026-11-09): `sendText.text` is
 * `g.string()` — NO `.min(1)` (only createSelectionSideSession.firstInput.text has
 * `.trim().min(1)`) — so an empty text with attachments is wire-legal.
 *
 * Pins: a READY staged attachment enables Send and dispatches with a blank draft; a
 * blank draft with NO attachments stays disabled (the pre-existing gate); a text-only
 * send is unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class AttachmentOnlySendTest {

    @get:Rule
    val rule = createComposeRule()

    private fun readyAttachment() = ConversationPort.ComposerAttachment(
        uploadId = "u1", fileName = "sketch-1.png", mime = "image/png", bytes = 10,
        state = "ready", progress = 1f, ref = "ref-1", payload = null,
    )

    private fun setContent(attachments: List<ConversationPort.ComposerAttachment>, draft: String = "", onSend: (String?) -> Unit = {}) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = listOf(
                            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
                        ),
                        loading = false,
                        taskState = TaskStateMachine.State.IDLE,
                        phase = ConnectionPhase.CONNECTED,
                        draft = draft,
                        composerAttachments = attachments,
                        sessionState = SessionProjectionState(),
                    ),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = onSend,
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `N-BH-1 - a READY attachment with a blank draft enables Send and dispatches`() {
        var sent: String? = "unset"
        setContent(listOf(readyAttachment()), onSend = { sent = null })
        rule.onNodeWithTag("composer_send").assertIsDisplayed()
        org.junit.Assert.assertFalse(
            "Send must be enabled",
            androidx.compose.ui.semantics.SemanticsProperties.Disabled in rule.onNodeWithTag("composer_send").fetchSemanticsNode().config,
        )
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()
        assertEquals(null, sent)
    }

    @Test
    fun `N-BH-1 - a blank draft with NO attachments keeps Send disabled (unchanged)`() {
        setContent(emptyList())
        rule.onNodeWithTag("composer_send").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `N-BH-1 - a text-only draft still sends (unchanged)`() {
        var sent: String? = null
        setContent(emptyList(), draft = "hello", onSend = { sent = it })
        rule.onNodeWithTag("composer_send").assertIsDisplayed()
        org.junit.Assert.assertFalse(
            "Send must be enabled",
            androidx.compose.ui.semantics.SemanticsProperties.Disabled in rule.onNodeWithTag("composer_send").fetchSemanticsNode().config,
        )
    }
}
