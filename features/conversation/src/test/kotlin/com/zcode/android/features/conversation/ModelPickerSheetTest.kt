package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ModelOption
import com.zcode.android.domain.model.ModelProviderOption
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.features.conversation.ui.ComposerModelPickerDialog
import com.zcode.android.features.conversation.ui.SessionModeChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * B1+D4 (gaps-and-polish Phase 3A, P2) — ONE shared ModelPickerSheet for both entry
 * points, with G2's live-captured defect as the pin: on the conversation picker
 * "tap `glm-5.1-air` → checked stays on `glm-5.1`" because `selected` derived from the
 * SESSION echo (`Phase9Components.kt:341`) and the only acknowledgment was a
 * "Thought level for …" section appended BELOW the list (:348-365) with the OLD
 * session's level marked `• high`. Plus F3 (composer rows 42dp < 48dp), D4 (badges,
 * search, "Current" tag) and the create-task stacking (pinned in features/tasks).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ModelPickerSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun config(levels: List<String> = listOf("high", "medium", "low")) = SessionConfigState(
        provider = "zai",
        model = "glm-5.1",
        thought = "high",
        thoughtLevels = levels,
        followupMode = null,
        mode = "build",
        planEnabled = null,
        modelSelectionProviderId = null,
        modelSelectionModelId = null,
    )

    /** G2's live list: Z.ai with glm-5.1 (current), glm-5.1-air, glm-5.1-flash + a second provider. */
    private val providers = listOf(
        ModelProviderOption(
            providerId = "zai",
            providerName = "Z.ai",
            models = listOf(
                ModelOption(id = "glm-5.1", label = "glm-5.1"),
                ModelOption(id = "glm-5.1-air", label = "glm-5.1-air"),
                ModelOption(id = "glm-5.1-flash", label = "glm-5.1-flash", badge = "Preview"),
            ),
            badge = "Default",
        ),
        ModelProviderOption(
            providerId = "openai-compatible",
            providerName = "OpenAI-compatible",
            models = listOf(ModelOption(id = "gpt-x", label = "gpt-x")),
        ),
    )

    private fun openConversationPicker(
        levels: List<String> = listOf("high", "medium", "low"),
        onSwitchModel: (String, String, String) -> Unit = { _, _, _ -> },
    ) {
        rule.setContent {
            ZCodeTheme {
                SessionModeChips(
                    config = config(levels),
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

    private fun selected(modelId: String): Boolean =
        rule.onNodeWithTag("model_option_$modelId", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Selected]

    // ------------------------------------------------------------------ G2 sequence

    @Test
    fun `G2 - tapping a non-current model highlights THAT row immediately from local state`() {
        openConversationPicker()
        assertTrue("current model is marked before any tap", selected("glm-5.1"))
        rule.onNodeWithTag("model_option_glm-5.1-air", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        // The live dump (G2 step 3): checked STAYED on glm-5.1 and the tapped row read false.
        assertTrue("tapped row must be the pending highlight", selected("glm-5.1-air"))
        assertFalse("the old current must drop its highlight", selected("glm-5.1"))
    }

    @Test
    fun `G2 - the thought-level step is ATTACHED to the tapped row, not a bottom section`() {
        openConversationPicker()
        rule.onNodeWithTag("model_option_glm-5.1-air", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val step = rule.onNodeWithTag("model_thought_step_glm-5.1-air", useUnmergedTree = true)
        step.assertIsDisplayed()
        val row = rule.onNodeWithTag("model_option_glm-5.1-air", useUnmergedTree = true).getBoundsInRoot()
        val next = rule.onNodeWithTag("model_option_glm-5.1-flash", useUnmergedTree = true).getBoundsInRoot()
        val stepBounds = step.getBoundsInRoot()
        assertTrue("step must sit below the tapped row (top=${stepBounds.top} row.top=${row.top})", stepBounds.top >= row.top)
        assertTrue("step must sit above the NEXT row (bottom=${stepBounds.bottom} next.top=${next.top})", stepBounds.bottom <= next.top)
    }

    @Test
    fun `G2 - no level is pre-marked on a NON-current model (the ambiguous bullet is gone)`() {
        openConversationPicker()
        rule.onNodeWithTag("model_option_glm-5.1-air", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("• high").assertCountEquals(0)
        // The chips are plain level names.
        rule.onNodeWithTag("thought_option_high", useUnmergedTree = true).assertIsDisplayed()
        val selectedLevels = listOf("high", "medium", "low").filter { lvl ->
            rule.onNodeWithTag("thought_option_$lvl", useUnmergedTree = true)
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
        }
        assertTrue("no level may read selected for a model that is not current: $selectedLevels", selectedLevels.isEmpty())
    }

    @Test
    fun `G2 - picking a level on the attached step sends the three wire values`() {
        var picked: Triple<String, String, String>? = null
        openConversationPicker { p, m, t -> picked = Triple(p, m, t) }
        rule.onNodeWithTag("model_option_glm-5.1-air", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("thought_option_medium", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(Triple("zai", "glm-5.1-air", "medium"), picked)
    }

    @Test
    fun `one tap picks when the model has a single effort level`() {
        var picked: Triple<String, String, String>? = null
        openConversationPicker(levels = listOf("high")) { p, m, t -> picked = Triple(p, m, t) }
        rule.onNodeWithTag("model_option_glm-5.1-air", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(Triple("zai", "glm-5.1-air", "high"), picked)
        assertTrue(rule.onAllNodesWithTag("model_thought_step_glm-5.1-air", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `re-picking the CURRENT model marks its current level honestly`() {
        openConversationPicker()
        rule.onNodeWithTag("model_option_glm-5.1", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val high = rule.onNodeWithTag("thought_option_high", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)
        assertEquals(true, high)
    }

    // ------------------------------------------------------------------ D4 parity

    @Test
    fun `D4 - the current model carries a Current tag and a check marker, not a radio`() {
        openConversationPicker()
        rule.onNodeWithTag("model_current_tag", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("model_check_glm-5.1", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("model_check_glm-5.1-air", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `D4 - wire badges render on models and provider groups`() {
        openConversationPicker()
        rule.onNodeWithText("Preview").assertIsDisplayed()
        rule.onNodeWithText("Default").assertIsDisplayed()
    }

    @Test
    fun `D4 - search filters the grouped list`() {
        openConversationPicker()
        rule.onNodeWithTag("model_picker_search").performTextInput("air")
        rule.waitForIdle()
        rule.onNodeWithTag("model_option_glm-5.1-air", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("model_option_glm-5.1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("model_option_gpt-x", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // A provider whose models are all filtered out drops its header.
        assertTrue(rule.onAllNodesWithTag("model_provider_openai-compatible", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `D4 - search with no match renders an honest empty state`() {
        openConversationPicker()
        rule.onNodeWithTag("model_picker_search").performTextInput("zzz-nothing")
        rule.waitForIdle()
        rule.onNodeWithTag("model_picker_no_results").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ F3 geometry + sharing

    @Test
    fun `F3 - composer picker rows are at least 48dp`() {
        rule.setContent {
            ZCodeTheme {
                ComposerModelPickerDialog(
                    current = null,
                    modelOptionsProvider = { providers },
                    onPick = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("composer_model_row_glm-5.1", useUnmergedTree = true).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("model_option_glm-5.1", useUnmergedTree = true).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the composer picker highlights its pending pick from local state too`() {
        var picked: Pair<String, String>? = null
        rule.setContent {
            ZCodeTheme {
                ComposerModelPickerDialog(
                    current = "zai" to "glm-5.1",
                    modelOptionsProvider = { providers },
                    onPick = { p, m -> picked = p to m },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(selected("glm-5.1"))
        rule.onNodeWithTag("composer_model_row_glm-5.1-air", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("zai" to "glm-5.1-air", picked)
    }

    @Test
    fun `ONE shared sheet - both entry points route through ModelPickerContent and no AlertDialog remains`() {
        val src = File(repoRoot(), "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/Phase9Components.kt").readText()
        val sheet = File(repoRoot(), "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/ModelPickerSheet.kt")
        assertTrue("the shared sheet file must exist", sheet.isFile)
        val sheetSrc = sheet.readText()
        assertTrue("exactly one picker body", Regex("""fun ModelPickerContent\(""").findAll(sheetSrc).count() == 1)
        assertTrue("the conversation picker delegates to the shared sheet", "ModelPickerSheet(" in src)
        assertTrue("the composer picker delegates to the shared sheet", Regex("""fun ComposerModelPickerDialog[\s\S]*?ModelPickerSheet\(""").containsMatchIn(src))
        // The old two-step dialog body is gone: no AlertDialog in the model-picker path.
        val pickerPath = src.substringAfter("fun ModelPickerDialog(").substringBefore("fun TurnDiffDialog(")
        assertFalse("ModelPickerDialog must not host an AlertDialog", "AlertDialog(" in pickerPath)
        assertNull("no bottom thought section remains", Regex("""thoughtMenuFor\?\.let""").find(src))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir)
    }
}
