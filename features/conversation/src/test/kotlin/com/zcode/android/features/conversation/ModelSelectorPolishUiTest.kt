package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ModelOption
import com.zcode.android.domain.model.ModelProviderOption
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.features.conversation.ui.SessionModeChips
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-18 (the project roadmap): model picker restyle to the desktop's
 * model-picker parity (ModelConfigSelect.tsx: provider GROUP headers, a CHECKED
 * marker on the active model, disabled state for unavailable entries) — same
 * options as today (no source change), same two-step pick → thought-level flow,
 * same onSwitchModel(provider, model, thought) wire call.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ModelSelectorPolishUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun config() = SessionConfigState(
        provider = "zai",
        model = "glm-5.3",
        thought = "high",
        thoughtLevels = listOf("high", "low"),
        followupMode = null,
        mode = "build",
        planEnabled = null,
        modelSelectionProviderId = null,
        modelSelectionModelId = null,
    )

    private val providers = listOf(
        ModelProviderOption(
            providerId = "zai",
            providerName = "Z.ai",
            models = listOf(
                ModelOption(id = "glm-5.3", label = "GLM-5.3", available = true),
                ModelOption(id = "glm-5.3-air", label = "GLM-5.3-Air", available = false),
            ),
        ),
    )

    private fun openPicker(onSwitchModel: (String, String, String) -> Unit = { _, _, _ -> }) {
        rule.setContent {
            ZCodeTheme {
                SessionModeChips(
                    config = config(),
                    onSwitchMode = {},
                    onSwitchModel = onSwitchModel,
                    modelOptionsProvider = { providers },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("conv_model_chip").performClick()
        rule.waitForIdle()
    }

    @Test
    fun modelPicker_groupsByProvider_marksActiveModel() {
        openPicker()
        rule.onNodeWithTag("model_provider_zai", useUnmergedTree = true).assertIsDisplayed()
        fun selected(modelId: String): Boolean =
            rule.onNodeWithTag("model_option_$modelId", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsProperties.Selected]
        assertEquals(true, selected("glm-5.3"))
        assertEquals(false, selected("glm-5.3-air"))
    }

    @Test
    fun modelPicker_unavailableModel_isDisabledAndInert() {
        openPicker()
        rule.onNodeWithTag("model_option_glm-5.3-air", useUnmergedTree = true)
            .assertIsNotEnabled()
        // Tapping a disabled model must NOT advance to the thought step.
        rule.onNodeWithTag("model_option_glm-5.3-air", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Thought level for glm-5.3-air").assertCountEquals(0)
    }

    @Test
    fun pickingAvailableModel_thenThought_sendsWithThreeValues() {
        var picked: Triple<String, String, String>? = null
        openPicker { p, m, t -> picked = Triple(p, m, t) }
        rule.onNodeWithTag("model_option_glm-5.3", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        // Thought step (unchanged flow) — pick the OTHER level so the wire value is proven.
        rule.onNodeWithTag("thought_option_low", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(Triple("zai", "glm-5.3", "low"), picked)
    }
}
