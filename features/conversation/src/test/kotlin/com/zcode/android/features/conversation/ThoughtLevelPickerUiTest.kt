package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * A3.4+J3 (gaps-and-polish Phase 4C): the effort picker is WIRE-DRIVEN — the offered
 * levels are the CURRENT MODEL's `config.thoughtLevels` (runtime-projected, snapshot.ts
 * sessionConfigStateSchema), never a pinned list; no levels → no chip. And the mode
 * labels are PER-RUNTIME (desktop mode.label.*): a glm/zai session shows the desktop's
 * glm copy; a claude-runtime session (J3's rig-scripted pin) shows the same WIRE
 * vocabulary (the shipped host normalizes every runtime vocabulary onto the four
 * switchable modes — toZCodeMode) with neutral, non-glm copy.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ThoughtLevelPickerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val picks = mutableListOf<Pair<String, String>>()

    private fun config(provider: String, thought: String, levels: List<String>) = SessionConfigState(
        provider = provider, model = "m-1", thought = thought, thoughtLevels = levels,
        followupMode = "queue", mode = "build", planEnabled = false,
        modelSelectionProviderId = provider, modelSelectionModelId = "m-1",
    )

    private fun setContent(cfg: SessionConfigState) {
        picks.clear()
        rule.setContent {
            ZCodeTheme {
                SessionModeChips(
                    config = cfg,
                    onSwitchMode = {},
                    onSwitchModel = { _, _, _ -> },
                    modelOptionsProvider = { emptyList() },
                    onSetThoughtLevel = { level -> picks += (cfg.provider to level) },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the effort chip shows the current level and opens the wire-driven dialog`() {
        setContent(config("zai", thought = "medium", levels = listOf("minimal", "low", "medium", "high")))
        rule.onNodeWithTag("conv_effort_chip").assertIsDisplayed()
        rule.onNodeWithTag("conv_effort_chip").performClick(); rule.waitForIdle()
        for (level in listOf("minimal", "low", "medium", "high")) {
            assertEquals(level, 1, rule.onAllNodesWithTag("effort_option_$level").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `picking a level sends it through the callback`() {
        setContent(config("zai", thought = "low", levels = listOf("low", "medium", "high")))
        rule.onNodeWithTag("conv_effort_chip").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("effort_option_high").performClick(); rule.waitForIdle()
        assertEquals(listOf("zai" to "high"), picks)
    }

    @Test
    fun `no wire levels - no effort chip (nothing to offer)`() {
        setContent(config("zai", thought = "medium", levels = emptyList()))
        assertEquals(0, rule.onAllNodesWithTag("conv_effort_chip").fetchSemanticsNodes().size)
    }

    @Test
    fun `a glm session shows the desktop's glm mode copy`() {
        setContent(config("zai", thought = "medium", levels = listOf("low")))
        rule.onNodeWithTag("conv_mode_chip").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Ask before changes").assertIsDisplayed()
    }

    @Test
    fun `a claude-runtime session keeps the wire vocabulary with neutral copy (J3 pin)`() {
        setContent(config("claude-code", thought = "medium", levels = listOf("low")))
        rule.onNodeWithTag("conv_mode_chip").performClick(); rule.waitForIdle()
        // The WIRE's four modes (toZCodeMode normalizes every runtime onto them)…
        for (mode in listOf("build", "edit", "plan", "yolo")) {
            assertEquals(mode, 1, rule.onAllNodesWithTag("mode_option_$mode").fetchSemanticsNodes().size)
        }
        // …with NEUTRAL copy, not glm's "Ask before changes".
        assertEquals(0, rule.onAllNodesWithText("Ask before changes").fetchSemanticsNodes().size)
        rule.onNodeWithText("Build").assertIsDisplayed()
    }
}
