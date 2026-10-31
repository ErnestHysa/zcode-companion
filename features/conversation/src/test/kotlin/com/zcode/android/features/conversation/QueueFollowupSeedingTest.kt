package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G4/AC6 (vc51): the default interaction-behavior SETTING (the desktop's
 * `settings.zcodeInteractionBehavior` Queue/Guide) seeds the composer's follow-up steering
 * while the session's wire `followupMode` is unset — the chips never rendered at all
 * before. The wire's value stays the truth once the desktop declares one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class QueueFollowupSeedingTest {

    @get:Rule
    val rule = createComposeRule()

    private fun state(wireFollowupMode: String?) = ConversationUiState(
        rows = listOf(ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {})),
        loading = false,
        taskState = TaskStateMachine.State.RUNNING,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(
            queue = QueueState(
                items = listOf(QueueState.QueueItem("q1", "follow-up", kind = null, dispatchState = "queued")),
                autoDrain = true,
                pauseReason = null,
            ),
            config = SessionConfigState(
                provider = "zai", model = "glm-5.3", thought = "high", thoughtLevels = listOf("high"),
                followupMode = wireFollowupMode, mode = "build", planEnabled = null,
                modelSelectionProviderId = null, modelSelectionModelId = null,
            ),
        ),
    )

    private fun setContent(wireFollowupMode: String?, defaultBehavior: String?) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = state(wireFollowupMode),
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
                    onSetFollowupMode = {},
                    defaultFollowupMode = defaultBehavior,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `unset wire mode is seeded by the local default`() {
        setContent(wireFollowupMode = null, defaultBehavior = "queue")
        rule.onNodeWithTag("queue_followup_queue").assertIsSelected()
    }

    @Test
    fun `the wire's declared mode wins over the local default`() {
        setContent(wireFollowupMode = "guide", defaultBehavior = "queue")
        rule.onNodeWithTag("queue_followup_guide").assertIsSelected()
        rule.onNodeWithTag("queue_followup_queue").assertIsNotSelected()
    }

    @Test
    fun `no default and no wire mode keeps the pre-G4 shape (no chips)`() {
        setContent(wireFollowupMode = null, defaultBehavior = null)
        rule.onAllNodesWithTag("queue_followup_queue").assertCountEquals(0)
    }
}
