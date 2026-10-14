package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ModelOption
import com.zcode.android.domain.model.ModelProviderOption
import com.zcode.android.features.conversation.ui.ComposerModelPickerDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * E2-title + E9-empty (gaps-and-polish Phase 1D, P0; live-found E2/F3/E9): the
 * composer model picker rendered `R.string.action_close` ("Close") as its dialog
 * TITLE (copy-pasted into the title slot), and had NO empty state — an empty
 * provider list rendered a completely empty body under the title.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ComposerModelPickerDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private fun provider(id: String, model: String) = ModelProviderOption(
        providerId = id,
        providerName = id.uppercase(),
        models = listOf(ModelOption(id = model, label = model, available = true)),
    )

    private fun assertTagExists(tag: String) {
        org.junit.Assert.assertTrue(
            "expected node with tag $tag",
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun setContent(models: List<ModelProviderOption>?, onPick: (String, String) -> Unit = { _, _ -> }) {
        rule.setContent {
            ZCodeTheme {
                ComposerModelPickerDialog(
                    current = null,
                    modelOptionsProvider = { models ?: emptyList() },
                    onPick = onPick,
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the dialog title is the picker title - not the Close label`() {
        setContent(listOf(provider("zai", "glm-5.1")))
        // E2: the title slot carried R.string.action_close (live-verified twice).
        assertTagExists("composer_model_picker_title")
    }

    @Test
    fun `empty provider list renders an honest empty state - not a blank body`() {
        setContent(emptyList())
        // E9: null → spinner, else a bare for-loop — an empty list rendered NOTHING.
        assertTagExists("composer_model_picker_empty")
    }

    @Test
    fun `picking a model invokes onPick with provider and model`() {
        var picked: Pair<String, String>? = null
        setContent(listOf(provider("zai", "glm-5.1")), onPick = { p, m -> picked = p to m })
        rule.onNodeWithTag("composer_model_row_glm-5.1").performClick()
        rule.waitForIdle()
        assertEquals("zai" to "glm-5.1", picked)
    }
}
