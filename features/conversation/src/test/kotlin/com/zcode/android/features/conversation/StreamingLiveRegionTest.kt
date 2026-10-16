package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.AgentProse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N8-liveRegion (gaps-and-polish Phase 2E; tagged P2 in N10, consolidated — one
 * mechanism per streaming row): the conversation module had ZERO `liveRegion`
 * semantics — streamed assistant text never announced to TalkBack. A STREAMING prose
 * row announces politely as it arrives; a completed row does not (no re-announce spam).
 * The TalkBack leg stays code-level (TalkBack is absent from google_apis images, N9).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class StreamingLiveRegionTest {

    @get:Rule
    val rule = createComposeRule()

    private fun liveRegion(root: SemanticsNode): LiveRegionMode? {
        root.config.getOrNull(SemanticsProperties.LiveRegion)?.let { return it }
        return root.children.firstNotNullOfOrNull { liveRegion(it) }
    }

    @Test
    fun `a streaming assistant row is a polite live region`() {
        rule.setContent { ZCodeTheme { AgentProse(text = "partial answer", streaming = true) } }
        val node = rule.onNodeWithTag("row_agent").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, liveRegion(node))
    }

    @Test
    fun `a completed assistant row is not a live region`() {
        rule.setContent { ZCodeTheme { AgentProse(text = "final answer", streaming = false) } }
        val node = rule.onNodeWithTag("row_agent").fetchSemanticsNode()
        assertNull(liveRegion(node))
    }
}
