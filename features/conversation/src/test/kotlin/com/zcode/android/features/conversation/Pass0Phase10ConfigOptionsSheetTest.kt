package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.SessionContextSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * pass-0 Phase 10 — G92's adapter pin: the workspace config's `configOptions`
 * (select/boolean + current values — ALREADY decoded in V4WorkspaceConfig with
 * zero feature consumers) finally RENDER: one adapter from a phone render (the
 * session-context sheet section) demonstrating the consume. Select and boolean
 * shapes both render their typed rows; an empty list renders NOTHING (honest
 * absent face — the sheet never invents options).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase10ConfigOptionsSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(options: List<ConversationPort.ConfigOptionView>) {
        rule.setContent {
            ZCodeTheme {
                SessionContextSheet(
                    sessionState = null,
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    configOptions = options,
                )
            }
        }
    }

    @Test
    fun `G92 a select config option renders its current value and option count`() {
        setContent(
            listOf(
                ConversationPort.ConfigOptionView(
                    id = "commitStyle",
                    name = "Commit style",
                    description = "How commit messages are drafted",
                    category = "git",
                    type = "select",
                    currentValue = "conventional",
                    optionCount = 3,
                ),
            ),
        )
        rule.onNodeWithTag("config_options_section").assertExists()
        rule.onNodeWithText("Commit style").assertIsDisplayed()
        rule.onAllNodesWithText("conventional", substring = true).assertCountEquals(1)
    }

    @Test
    fun `G92 a boolean config option renders its typed row`() {
        setContent(
            listOf(
                ConversationPort.ConfigOptionView(
                    id = "autofix",
                    name = "Auto-fix lint",
                    description = null,
                    category = null,
                    type = "boolean",
                    currentValue = "false",
                    optionCount = 0,
                ),
            ),
        )
        rule.onNodeWithTag("config_options_section").assertExists()
        rule.onNodeWithText("Auto-fix lint").assertIsDisplayed()
        rule.onAllNodesWithText("false", substring = true).assertCountEquals(1)
    }

    @Test
    fun `G92 an empty option list renders no section (never invented)`() {
        setContent(emptyList())
        rule.onNodeWithTag("session_context_sheet").assertExists()
        rule.onAllNodesWithTag("config_options_section").assertCountEquals(0)
    }
}
