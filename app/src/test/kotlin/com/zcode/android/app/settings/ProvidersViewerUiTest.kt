package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ModelOption
import com.zcode.android.domain.model.ModelProviderOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G27 (vc51 Phase 4B) — the read-only MODEL-PROVIDER viewer (INV-9 pattern). Pins: the
 * picker's own wire view renders as grouped providers with display badges + per-model
 * rows; NO credential plane exists anywhere on the surface (API keys never cross the
 * wire; credential entry is a desktop-side boundary); the error and empty faces are
 * honest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ProvidersViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fakePort(
        providers: List<ModelProviderOption>? = null,
        error: String? = null,
    ) = object : ProvidersPort {
        override suspend fun providers(): ProvidersPort.Result = ProvidersPort.Result(providers, error)
    }

    private val seed = listOf(
        ModelProviderOption(
            providerId = "zai",
            providerName = "Z.ai",
            badge = "Coding Plan",
            models = listOf(
                ModelOption(id = "glm-5.1", label = "GLM-5.1", badge = "flagship"),
                ModelOption(id = "glm-4.7", label = "GLM-4.7"),
            ),
        ),
        ModelProviderOption(
            providerId = "anthropic",
            providerName = "Anthropic",
            models = listOf(ModelOption(id = "claude-x", label = "Claude X")),
        ),
    )

    @Test
    fun rendersGroupedProviders_withBadges_andModelRows() {
        rule.setContent { ZCodeTheme { ProvidersViewerScreen(port = fakePort(seed)) } }
        rule.onNodeWithTag("providers_list").assertIsDisplayed()
        rule.onNodeWithTag("provider_header_zai").assertIsDisplayed()
        rule.onNodeWithTag("provider_header_anthropic").assertIsDisplayed()
        // the desktop group badge renders (wire-fed, never synthesized)
        rule.onNodeWithTag("provider_badge_zai").assertExists()
        rule.onAllNodesWithTag("provider_badge_anthropic", useUnmergedTree = true).assertCountEquals(0)
        // model rows with id + label + per-model badge
        rule.onNodeWithTag("provider_model_zai_glm-5.1").assertExists()
        rule.onNodeWithTag("provider_model_badge_zai_glm-5.1").assertExists()
        rule.onNodeWithText("GLM-5.1").assertIsDisplayed()
        rule.onNodeWithText("glm-4.7").assertIsDisplayed()
    }

    @Test
    fun noCredentialSurface_anywhere() {
        rule.setContent { ZCodeTheme { ProvidersViewerScreen(port = fakePort(seed)) } }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("providers_list").fetchSemanticsNodes().isNotEmpty()
        }
        // INV-9 + the B3 boundary: no key/token/credential affordance renders
        rule.onAllNodesWithTag("provider_key", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("provider_token", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun errorFace_isHonest() {
        rule.setContent { ZCodeTheme { ProvidersViewerScreen(port = fakePort(error = "fault.channel.refused:model-selection")) } }
        rule.onNodeWithTag("providers_error").assertIsDisplayed()
    }

    @Test
    fun emptyFace_isHonest() {
        rule.setContent { ZCodeTheme { ProvidersViewerScreen(port = fakePort(emptyList())) } }
        rule.onNodeWithTag("providers_empty").assertIsDisplayed()
    }
}
