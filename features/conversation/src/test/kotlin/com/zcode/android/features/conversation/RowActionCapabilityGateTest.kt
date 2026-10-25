package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * E3.1 (gaps-and-polish Phase 1C, P0): the long-press action sheet gated Edit on
 * `entityId` PRESENCE and ignored the wire's own capability flags
 * (`actions.canEdit` — decoded since Phase 2, zero UI consumers). It offered Edit
 * on rows the desktop forbids and hid it on allowed rows lacking an entityId.
 * Fix: the sheet gates EVERY action on its flag (Copy stays — it is local-only).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RowActionCapabilityGateTest {

    @get:Rule
    val rule = createComposeRule()

    private fun userRow(rowId: Long, entityId: String?, canEdit: Boolean) =
        com.zcode.android.domain.model.ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(rowId))
                put("turnId", JsonPrimitive("t1"))
                put("kind", JsonPrimitive("userInput"))
                put("createdAt", JsonPrimitive(1_700_000_000_000.0))
                put("createdAtSeq", JsonPrimitive(rowId))
                put("text", JsonPrimitive("the user message"))
                put("origin", JsonPrimitive("user"))
                put("guided", JsonPrimitive(false))
                if (entityId != null) put("entityId", JsonPrimitive(entityId))
                put(
                    "actions",
                    buildJsonObject {
                        put("canEdit", JsonPrimitive(canEdit))
                    },
                )
            },
        ) as com.zcode.android.domain.model.ConversationRow.UserInput

    private fun setContent(row: com.zcode.android.domain.model.ConversationRow.UserInput) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = listOf(row),
                        loading = false,
                        taskState = com.zcode.android.domain.state.TaskStateMachine.State.RUNNING,
                        phase = ConnectionPhase.CONNECTED,
                        epoch = "e1",
                    ),
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
                    onEditUserQuery = { _, _, _ -> },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun openActionSheet() {
        rule.onAllNodesWithTag("row_user")[0].performTouchInput { longClick() }
        rule.waitForIdle()
    }

    @Test
    fun `canEdit false with entityId - the sheet offers NO Edit`() {
        setContent(userRow(1, entityId = "ent-1", canEdit = false))
        openActionSheet()

        // Copy is local-only and always offered (GAP-11).
        assertEquals("Copy must stay offered", 1, rule.onAllNodesWithTag("row_copy_action").fetchSemanticsNodes().size)
        assertEquals(
            "the desktop forbids Edit on this row (actions.canEdit=false) — the sheet must not offer it",
            0,
            rule.onAllNodesWithTag("row_edit_action").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `canEdit true without entityId - the sheet offers Edit`() {
        setContent(userRow(2, entityId = null, canEdit = true))
        openActionSheet()

        assertEquals(
            "the wire ALLOWS Edit on this row — a missing entityId must not hide it",
            1,
            rule.onAllNodesWithTag("row_edit_action").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `canEdit true with entityId - the sheet offers Edit`() {
        setContent(userRow(3, entityId = "ent-3", canEdit = true))
        openActionSheet()

        assertEquals(1, rule.onAllNodesWithTag("row_edit_action").fetchSemanticsNodes().size)
    }
}
