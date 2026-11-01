package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SlashCatalogExtras
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G30 (vc51 Phase 4B) — the read-only BOTS viewer (the INV-9 seed). Pins: the scripted
 * inventory renders rows with provider + mode/state badges (both mode faces); NO
 * management affordance exists (create/edit/credentials are a DATED B3 boundary); the
 * honest-fault face is what a real shipped host (no bots RPC) renders.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class BotsViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fakePort(
        bots: List<SlashCatalogExtras.BotEntry>? = null,
        error: String? = null,
    ) = object : BotsPort {
        override suspend fun bots(): BotsPort.Result = BotsPort.Result(bots, error)
    }

    private val seed = listOf(
        SlashCatalogExtras.BotEntry("bot-telegram-1", "telegram", "task", "/synthetic/ws-one", "synth-task-1", enabled = true),
        SlashCatalogExtras.BotEntry("bot-discord-1", "discord", "draft", "/synthetic/ws-two", null, enabled = false),
    )

    @Test
    fun rendersInventory_withProviderAndModeBadges_bothFaces() {
        rule.setContent { ZCodeTheme { BotsViewerScreen(port = fakePort(seed)) } }
        rule.onNodeWithTag("bots_list").assertIsDisplayed()
        rule.onNodeWithTag("bot_row_bot-telegram-1").assertIsDisplayed()
        rule.onNodeWithTag("bot_row_bot-discord-1").assertIsDisplayed()
        rule.onNodeWithTag("bot_provider_badge_bot-telegram-1").assertExists()
        rule.onNodeWithTag("bot_state_badge_bot-discord-1").assertExists()
    }

    @Test
    fun noManagementAffordance_anywhere() {
        rule.setContent { ZCodeTheme { BotsViewerScreen(port = fakePort(seed)) } }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("bots_list").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithTag("bot_create", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("bot_edit", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("bot_delete", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun honestFaultFace_isWhatARealHostRenders() {
        rule.setContent { ZCodeTheme { BotsViewerScreen(port = fakePort(error = "fault.mock.unscripted:bots.list")) } }
        rule.onNodeWithTag("bots_error").assertIsDisplayed()
    }

    @Test
    fun emptyFace_isHonest() {
        rule.setContent { ZCodeTheme { BotsViewerScreen(port = fakePort(emptyList())) } }
        rule.onNodeWithTag("bots_empty").assertIsDisplayed()
    }
}
