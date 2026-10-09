package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * GAP-17 (the project roadmap): mode selector restyled to the desktop's mode
 * affordances (V4ComposerModeControls.tsx: icon + title + description per item,
 * current mode marked, high-permission mode visually flagged) — SAME vocabulary
 * (SWITCHABLE_MODES) and SAME wire behavior (onSwitchMode with the mode id), no
 * protocol change. Desktop titles/descriptions: i18n `mode.label.glm.*` /
 * `mode.description.glm.*` (en-US.ts:5893-5900).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ModeSelectorPolishUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun config(mode: String) = SessionConfigState(
        provider = "zai",
        model = "glm-5.3",
        thought = "high",
        thoughtLevels = listOf("high"),
        followupMode = null,
        mode = mode,
        planEnabled = null,
        modelSelectionProviderId = null,
        modelSelectionModelId = null,
    )

    private fun openDialog(currentMode: String) {
        rule.setContent {
            ZCodeTheme {
                SessionModeChips(
                    config = config(currentMode),
                    onSwitchMode = {},
                    onSwitchModel = { _, _, _ -> },
                    modelOptionsProvider = { emptyList() },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("conv_mode_chip").performClick()
        rule.waitForIdle()
    }

    @Test
    fun modeDialog_showsDesktopTitleAndDescriptionPerMode() {
        openDialog("build")
        // Titles (desktop mode.label.glm.*)
        rule.onNodeWithText("Ask before changes").assertIsDisplayed()
        rule.onNodeWithText("Edit automatically").assertIsDisplayed()
        rule.onNodeWithText("Plan mode").assertIsDisplayed()
        rule.onNodeWithText("Full access").assertIsDisplayed()
        // Descriptions (desktop mode.description.glm.*)
        rule.onNodeWithText("Ask before file changes.").assertIsDisplayed()
        rule.onNodeWithText("Edit files automatically.").assertIsDisplayed()
        rule.onNodeWithText("Plan before editing.").assertIsDisplayed()
        rule.onNodeWithText("Run with fewer confirmations.").assertIsDisplayed()
    }

    @Test
    fun modeDialog_marksCurrentModeSelected_othersUnselected() {
        openDialog("plan")
        fun selected(mode: String): Boolean =
            rule.onNodeWithTag("mode_option_$mode", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsProperties.Selected]
        assertEquals(true, selected("plan"))
        assertEquals(false, selected("build"))
        assertEquals(false, selected("edit"))
        assertEquals(false, selected("yolo"))
    }

    @Test
    fun tappingAnOption_sendsModeSwitchWithTheSameWireValue() {
        var switched: String? = null
        rule.setContent {
            ZCodeTheme {
                SessionModeChips(
                    config = config("build"),
                    onSwitchMode = { switched = it },
                    onSwitchModel = { _, _, _ -> },
                    modelOptionsProvider = { emptyList() },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("conv_mode_chip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mode_option_edit", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("edit", switched)
        // The dialog closes after a choice (desktop dismiss-on-select).
        rule.onAllNodesWithTag("mode_option_edit").assertCountEquals(0)
    }
}
