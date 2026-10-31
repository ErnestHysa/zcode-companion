package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.EmptyBody
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G10 (vc51) — the draft screen's suggested prompts (featureSuggestedPrompts.ts
 * + featureSuggestedPromptRotation.ts). Pins: the ROTATION contract (a cursor
 * window over the pool, wrapping, no duplicates within a pane, PANE_COUNT
 * chips) and the UI (chips render on the empty screen; a tap prefills the
 * composer with the full prompt body).
 */
class SuggestedPromptRotationTest {

    @Test
    fun `the pane window is PANE_COUNT chips with no duplicates`() {
        val pane = SuggestedPrompts.selectForPane(SuggestedPrompts.POOL, cursor = 0)
        assertEquals(SuggestedPrompts.PANE_COUNT, pane.size)
        assertEquals(pane.size, pane.map { it.id }.distinct().size)
    }

    @Test
    fun `advancing the cursor rotates the window and wraps`() {
        val pool = SuggestedPrompts.POOL
        val first = SuggestedPrompts.selectForPane(pool, cursor = 0)
        val second = SuggestedPrompts.selectForPane(pool, cursor = 1)
        assertTrue(first != second, "the next pane shows a DIFFERENT window (rotation)")
        // cursor + pool.size wraps to the original window.
        assertEquals(first, SuggestedPrompts.selectForPane(pool, cursor = pool.size))
        // A full rotation covers every item.
        val seen = (0 until pool.size).flatMap { SuggestedPrompts.selectForPane(pool, it).map { p -> p.id } }.toSet()
        assertEquals(pool.map { it.id }.toSet(), seen)
    }

    @Test
    fun `the mode split pools filter cleanly (the G36 tie-in)`() {
        assertTrue(SuggestedPrompts.poolFor("office").all { it.mode == "office" })
        assertTrue(SuggestedPrompts.poolFor("coding").all { it.mode == "coding" })
        assertEquals(SuggestedPrompts.POOL, SuggestedPrompts.poolFor(null))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SuggestedPromptsUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `chips render on the empty screen and a tap prefills the composer`() {
        var prefilled: String? = null
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                // The screen's EmptyBody is ColumnScope-scoped — mirror the host shape.
                androidx.compose.foundation.layout.Column {
                    EmptyBody(
                        onPrefill = { p -> prefilled = p },
                        promptCursor = 0,
                    )
                }
            }
        }
        rule.onNodeWithTag("suggested_prompt_0").performClick()
        val expected = SuggestedPrompts.selectForPane(SuggestedPrompts.POOL, 0).first().prompt
        assertEquals(expected, prefilled)
    }
}
