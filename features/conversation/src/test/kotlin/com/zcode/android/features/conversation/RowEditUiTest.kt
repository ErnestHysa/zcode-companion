package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.EditUserQueryDialog
import com.zcode.android.features.conversation.ui.YouBlock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-10 (the project roadmap; docs/protocol/v4-wire.md §11.4) — the edit
 * affordance: long-press on a user row opens the action sheet (Copy — always,
 * Edit — only when the row carries an entityId, the desktop's own gate
 * ConversationRowView.tsx:1295 `onEdit && row.entityId`); Edit opens the edit
 * sheet pre-filled with the row text; confirming RE-ASKS the turn behind an
 * explicit confirm dialog (desktop UX parity). The projection itself only
 * changes when the authoritative churn arrives (§11.4 — no optimistic rewrite).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RowEditUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun longPressEditableRow_showsCopyAndEditActions() {
        rule.setContent {
            ZCodeTheme { YouBlock(text = "original question", createdAtMs = null, editable = true, onEdit = {}) }
        }
        rule.onNodeWithTag("row_user").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("row_copy_action").assertExists()
        rule.onNodeWithTag("row_edit_action").assertExists()
    }

    @Test
    fun longPressNonEditableRow_editActionNotOffered() {
        rule.setContent {
            ZCodeTheme { YouBlock(text = "old question", createdAtMs = null, editable = false, onEdit = null) }
        }
        rule.onNodeWithTag("row_user").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("row_copy_action").assertExists()
        rule.onAllNodesWithTag("row_edit_action").assertCountEquals(0) // no entityId → no edit entry (desktop gate)
    }

    @Test
    fun editSheet_prefilledWithRowText_andConfirmReasksTheTurn() {
        var confirmed: String? = null
        rule.setContent {
            ZCodeTheme {
                EditUserQueryDialog(
                    initialText = "original question",
                    onConfirm = { confirmed = it },
                    onDismiss = { },
                )
            }
        }
        val field = rule.onNodeWithTag("edit_text_field")
        field.assertExists()
        field.performTextReplacement("edited question text")
        // Review → the explicit confirm dialog (the turn is re-asked).
        rule.onNodeWithTag("edit_review").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("edit_confirm_dialog").assertExists()
        rule.onNodeWithTag("edit_confirm_button").performClick()
        rule.waitForIdle()
        assertEquals("edited question text", confirmed)
    }

    @Test
    fun editSheet_dismissDoesNotConfirm() {
        var confirmed: String? = null
        rule.setContent {
            ZCodeTheme {
                EditUserQueryDialog(initialText = "t", onConfirm = { confirmed = it }, onDismiss = { })
            }
        }
        rule.onNodeWithTag("edit_text_field").performTextInput("more")
        rule.onNodeWithTag("edit_dismiss").performClick()
        rule.waitForIdle()
        assertEquals(null, confirmed)
    }
}
