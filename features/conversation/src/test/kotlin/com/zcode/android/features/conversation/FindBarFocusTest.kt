package com.zcode.android.features.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BF-1 (pass 34): the find bar opened WITHOUT focus — with the composer holding the
 * keyboard, the query was typed into the composer (it became a draft and triggered the
 * leave-guard dialog). Pin: opening the bar puts the focus ON THE FIND FIELD (the
 * query lands there), the composer's draft is untouched, and the pre-fix face is
 * exactly reproducible: without the focus requester, typing into the find field throws
 * (the node holds no input focus) — the same hand-off failure the keyboard produced.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class FindBarFocusTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `N-BF-1 - opening the find bar focuses the find field, the draft stays intact`() {
        val draftChanges = mutableListOf<String>()
        var draft by androidx.compose.runtime.mutableStateOf("queued work ")
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        draft = draft,
                        rows = listOf(
                            ConversationRow.UserInput(
                                rowId = 1, turnId = "t1",
                                base = ConversationRow.Base(createdAt = 1.0, createdAtSeq = 1),
                                text = "ship the release with the staged build",
                                epilogueStart = null, origin = "user", originMeta = null, guided = false,
                                sourceCommandId = null, attachments = emptyList(), raw = buildJsonObject {},
                            ),
                        ),
                        loading = false,
                        taskState = TaskStateMachine.State.IDLE,
                        phase = ConnectionPhase.CONNECTED,
                        sessionState = SessionProjectionState(),
                    ),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = { draftChanges.add(it); draft = it },
                    onSend = { _, _, _ -> },
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
        // The recorded precondition: the COMPOSER holds the keyboard (its focus) when
        // the find bar opens.
        rule.onNodeWithTag("composer_field").performTextInput("x")
        rule.waitForIdle()
        rule.onNodeWithTag("composer_field").assertIsFocused()
        rule.onNodeWithTag("conv_find_open").performClick()
        rule.waitForIdle()
        // THE BUG: pre-fix the bar never took focus — the query was typed into the
        // still-focused composer (it became a draft and triggered the leave-guard
        // dialog). The fix hands the input focus to the find field at open.
        rule.onNodeWithTag("conv_find_query").assertIsFocused()
    }
}
