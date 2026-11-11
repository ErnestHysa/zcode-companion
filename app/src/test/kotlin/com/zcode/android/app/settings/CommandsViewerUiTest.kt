package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SlashCatalogExtras
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G74 (vc52 Phase 12) compose leg: the read-only custom-COMMAND viewer — user and
 * plugin commands render (name · source badge · description · argumentHint), the
 * disabled filter matches the slash panel's contract, the honest empty face and
 * fault face render, and the dated authoring boundary note renders. NO management
 * affordance exists (create/edit/delete/import are desktop-side).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class CommandsViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun port(commands: List<SlashCatalogExtras.CommandEntry>?, error: String? = null) = object : CommandsPort {
        override suspend fun commands(): CommandsPort.Result = CommandsPort.Result(commands, error)
    }

    private val seed = listOf(
        SlashCatalogExtras.CommandEntry("deploy", "Deploy the project", "[env]", "user"),
        SlashCatalogExtras.CommandEntry("acme:review", "Acme code review pass", "[path]", "plugin"),
    )

    @Test
    fun `user and plugin commands render with source badges`() {
        rule.setContent { ZCodeTheme { CommandsViewerScreen(port = port(seed)) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("commands_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("command_row_user_deploy").assertIsDisplayed()
        rule.onNodeWithTag("command_row_plugin_acme:review").assertIsDisplayed()
        rule.onNodeWithText("Deploy the project").assertExists()
        rule.onNodeWithText("plugin", substring = true).assertExists()
        rule.onNodeWithText("/deploy [env]", substring = true).assertExists()
    }

    @Test
    fun `no management affordance anywhere (authoring is a dated desktop boundary)`() {
        rule.setContent { ZCodeTheme { CommandsViewerScreen(port = port(seed)) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("commands_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("command_create", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("command_edit", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("command_delete", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("commands_boundary_note").assertIsDisplayed()
    }

    @Test
    fun `the empty face is the desktop-pointer copy`() {
        rule.setContent { ZCodeTheme { CommandsViewerScreen(port = port(emptyList())) } }
        rule.onNodeWithTag("commands_empty").assertIsDisplayed()
    }

    @Test
    fun `the fault face is honest`() {
        rule.setContent { ZCodeTheme { CommandsViewerScreen(port = port(null, "fault.mock.unscripted:commands.list")) } }
        rule.onNodeWithTag("commands_error").assertIsDisplayed()
    }
}
