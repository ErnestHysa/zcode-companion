package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.ui.composerAutoCorrectEnabled
import com.zcode.android.features.conversation.ui.composerKeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L5-slash (Phase 6C, pixel-found): Gboard's prose word-suggestions polluted
 * slash-command entry ("he / her / here" above `/he`) — a command context is not
 * prose. The composer's KeyboardOptions derive from the draft: slash mode kills
 * autocorrect + capitalization and forces ASCII (the catalog is ASCII); prose keeps
 * the untouched defaults (bilingual typing must not regress).
 */
class ComposerKeyboardOptionsTest {

    @Test
    fun `slash mode - no suggestions, no capitalization, ascii`() {
        val options = composerKeyboardOptions("/he")
        assertFalse(composerAutoCorrectEnabled(options), "prose word-suggestions must not fire in command context")
        assertEquals(KeyboardCapitalization.None, options.capitalization)
        assertEquals(KeyboardType.Ascii, options.keyboardType)
    }

    @Test
    fun `a draft that merely contains a slash is still prose - only the LEADING slash commands`() {
        val options = composerKeyboardOptions("fix the a/b split")
        assertTrue(composerAutoCorrectEnabled(options))
        assertEquals(KeyboardCapitalization.Sentences, options.capitalization)
        assertEquals(KeyboardType.Text, options.keyboardType)
    }

    @Test
    fun `empty and plain drafts keep the prose defaults`() {
        for (draft in listOf("", "fix the parser")) {
            val options = composerKeyboardOptions(draft)
            assertTrue(composerAutoCorrectEnabled(options))
            assertEquals(KeyboardType.Text, options.keyboardType)
        }
    }
}
