package com.zcode.android.features.conversation

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.AgentProse
import com.zcode.android.features.conversation.ui.CodeBlock
import com.zcode.android.features.conversation.ui.YouBlock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-11 (android-native-gaps.md): copy / select / highlight, ported from the
 * desktop's per-row affordances (ConversationRowView.tsx:1290 CopyRowAction on user
 * rows; :1339 copyLabel on assistant rows; per-code-block copy). Android shape:
 * user row long-press → the EXACT row text lands on the clipboard; assistant rows
 * offer an explicit copy action and are text-SELECTABLE per row (never the whole
 * timeline); every code block carries a copy button. Copy is user-initiated only
 * (§27 — no new leak surface).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RowCopySelectionUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun clipboardText(): String? {
        val cm = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun longPressUserRow_copiesExactRowText() {
        // GAP-10 supersede note (same phase): long-press now opens the row action
        sheet with Copy + Edit (the desktop's user-row affordances); Copy keeps the
        exact-row-text contract.
        rule.setContent {
            ZCodeTheme { YouBlock("copy me exactly", createdAtMs = null) }
        }
        rule.onNodeWithTag("row_user").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("row_copy_action").performClick()
        rule.waitForIdle()
        assertEquals("copy me exactly", clipboardText())
    }

    @Test
    fun assistantRow_copyAction_putsFullTextOnClipboard() {
        val body = "Line one of the answer.\n\nLine two with `code` and **bold**."
        rule.setContent {
            ZCodeTheme { AgentProse(text = body, streaming = false, createdAtMs = null) }
        }
        rule.onNodeWithTag("row_copy_agent").performClick()
        rule.waitForIdle()
        assertEquals(body, clipboardText())
    }

    @Test
    fun assistantProse_isSelectable_withinTheRow() {
        // One SelectionContainer PER prose row — never one wrapping the whole
        // timeline (that would couple selection to unrelated rows).
        rule.setContent {
            ZCodeTheme {
                Column {
                    AgentProse(text = "first selectable prose", streaming = false, createdAtMs = null)
                    AgentProse(text = "second selectable prose", streaming = false, createdAtMs = null)
                    YouBlock("user row is NOT wrapped in a selection container", createdAtMs = null)
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("row_selection_agent").assertCountEquals(2)
    }

    @Test
    fun codeBlock_copyButton_putsCodeOnClipboard() {
        rule.setContent {
            ZCodeTheme { CodeBlock(code = "fun x() = 1\nfun y() = 2", lang = "kotlin") }
        }
        rule.onNodeWithTag("row_copy_code").performClick()
        rule.waitForIdle()
        assertEquals("fun x() = 1\nfun y() = 2", clipboardText())
    }
}
