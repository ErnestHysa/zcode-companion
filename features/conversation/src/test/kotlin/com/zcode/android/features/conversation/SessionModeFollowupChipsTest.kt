package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.features.conversation.ui.SessionModeChips
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G59 (vc52 Phase 12) compose leg: the session chip row carries the compact
 * mode/FOLLOWUP switcher — the followup chip renders the LIVE mode (queue|guide,
 * wire-driven from config.followupMode; the local default seeds while unset) and
 * switching fires the setFollowupMode verb; the desktop's FIFTH mode (guarded,
 * fresh asar 4a6080ed… enum) appears in the mode dialog with the shipped copy.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SessionModeFollowupChipsTest {

    @get:Rule
    val rule = createComposeRule()

    private val fired = mutableListOf<String>()

    private fun config(followup: String?, mode: String = "build") = SessionConfigState(
        provider = "zai",
        model = "glm-5.3",
        thought = "high",
        thoughtLevels = listOf("high"),
        followupMode = followup,
        mode = mode,
        planEnabled = null,
        modelSelectionProviderId = null,
        modelSelectionModelId = null,
    )

    private fun setContent(followup: String?, mode: String = "build") {
        fired.clear()
        rule.setContent {
            ZCodeTheme {
                SessionModeChips(
                    config = config(followup, mode),
                    onSwitchMode = { fired.add("mode:$it") },
                    onSwitchModel = { _, _, _ -> },
                    modelOptionsProvider = { emptyList() },
                    followupMode = followup,
                    onSetFollowupMode = { fired.add("followup:$it") },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the followup chip renders the live mode and switching fires the verb`() {
        setContent("queue")
        rule.onNodeWithTag("conv_followup_chip").assertIsDisplayed()
        rule.onNodeWithText("followup · queue").assertExists()
        rule.onNodeWithTag("conv_followup_chip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("followup_option_guide").assertIsDisplayed()
        rule.onNodeWithText("Answers arrive as guided prompts while you type.").assertExists()
        rule.onNodeWithTag("followup_option_guide").performClick()
        rule.waitForIdle()
        assertEquals(listOf("followup:guide"), fired)
    }

    @Test
    fun `switching to the SAME mode fires nothing (the radio contract)`() {
        setContent("guide")
        rule.onNodeWithTag("conv_followup_chip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("followup_option_guide").performClick()
        rule.waitForIdle()
        assertEquals(0, fired.size)
    }

    @Test
    fun `an unset followup hides the chip (null face)`() {
        setContent(null)
        rule.onAllNodesWithTag("conv_followup_chip").assertCountEquals(0)
    }

    @Test
    fun `the mode dialog lists the desktop's FIVE modes including guarded`() {
        setContent("build")
        rule.onNodeWithTag("conv_mode_chip").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Autonomous mode").assertExists()
        rule.onNodeWithText("Ask when there’s risk").assertExists()
        rule.onAllNodesWithTag("mode_option_guarded").assertCountEquals(1)
        // The 5th item sits below the dialog fold — scroll the list to it first.
        rule.onNodeWithTag("mode_options_list").performScrollToNode(hasTestTag("mode_option_guarded"))
        rule.waitForIdle()
        rule.onNodeWithTag("mode_option_guarded").performClick()
        rule.waitForIdle()
        assertEquals(listOf("mode:guarded"), fired)
    }

    @Test
    fun `guarded copy uses the desktop vocabulary, not invented copy`() {
        setContent("build", mode = "guarded")
        rule.onNodeWithTag("conv_mode_chip").performClick()
        rule.waitForIdle()
        // The shipped labels: mode.label.glm.guarded / mode.description.glm.guarded.
        rule.onNodeWithText("Autonomous mode").assertExists()
        rule.onNodeWithText("Ask when there’s risk").assertExists()
    }
}
