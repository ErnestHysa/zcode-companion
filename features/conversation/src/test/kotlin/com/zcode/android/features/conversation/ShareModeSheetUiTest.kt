package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.ShareMode
import com.zcode.android.features.conversation.ui.ShareModeSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G19 (vc51 Phase 4D): the share sheet offers ≥2 modes — the plain-text transcript AND
 * the markdown export; picking either dispatches the mode (the read-only-timeline/LINK
 * modes stay a dated boundary — a phone cannot host the desktop's share endpoint).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ShareModeSheetUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `both modes render and picking dispatches the mode`() {
        val picked = mutableListOf<ShareMode>()
        rule.setContent { ZCodeTheme { ShareModeSheet(onMode = { picked += it }, onDismiss = {}) } }
        rule.onNodeWithTag("share_mode_text").assertIsDisplayed()
        rule.onNodeWithTag("share_mode_markdown").assertIsDisplayed()
        rule.onNodeWithText("Plain text").assertIsDisplayed()
        rule.onNodeWithText("Markdown").assertIsDisplayed()
        rule.onNodeWithTag("share_mode_markdown").performClick()
        assertEquals(listOf(ShareMode.MARKDOWN), picked)
    }
}
