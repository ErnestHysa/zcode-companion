package com.zcode.android.app.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * K9-errorcopy (gaps-and-polish Phase 2D, P1): error states leaked RAW fault/transport
 * strings verbatim — Usage `fault.mock.unscripted:getAppUsageStats` (K6), transport
 * "channel closed: no outbound sink attached" (L8), and the three Settings viewers'
 * `result?.error` (McpViewerScreen/PluginsViewerScreen/SkillsViewerScreen).
 *
 * Pinned per screen: NO `fault.*` / transport string renders in any error state; a
 * friendly message does (one mapping mechanism — `friendlyFaultMessage`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RawFaultCopyTest {

    @get:Rule
    val rule = createComposeRule()

    private val rawFault = "fault.mock.unscripted:getAppUsageStats"
    private val rawTransport = "channel closed: no outbound sink attached"

    private fun allText(): List<String> = rule.onRoot().fetchSemanticsNode().let { root ->
        val out = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.children.forEach { walk(it) }
        }
        walk(root)
        out
    }

    private fun assertNoRawLeak(tag: String, raw: String) {
        assertTrue("$tag must render", rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty())
        val texts = allText()
        assertFalse("raw string must not reach the screen: $texts", texts.any { raw in it })
        assertFalse("no fault.* leak: $texts", texts.any { "fault." in it })
        assertFalse("no transport leak: $texts", texts.any { "outbound sink" in it || "channel closed" in it })
    }

    @Test
    fun `usage error state maps a raw desktop fault to friendly copy`() {
        rule.setContent {
            ZCodeTheme {
                UsageScreen(port = object : UsagePort {
                    override suspend fun appUsage(range: String) = UsagePort.Result(null, rawFault)
                })
            }
        }
        assertNoRawLeak("usage_error", rawFault)
    }

    @Test
    fun `usage error state maps a raw transport string to friendly copy`() {
        rule.setContent {
            ZCodeTheme {
                UsageScreen(port = object : UsagePort {
                    override suspend fun appUsage(range: String) = UsagePort.Result(null, rawTransport)
                })
            }
        }
        assertNoRawLeak("usage_error", rawTransport)
    }

    @Test
    fun `mcp viewer error state does not leak the raw error`() {
        rule.setContent {
            ZCodeTheme {
                McpViewerScreen(port = object : McpPort {
                    override suspend fun statuses() = McpPort.Result(null, rawFault)
                })
            }
        }
        assertNoRawLeak("mcp_error", rawFault)
    }

    @Test
    fun `plugins viewer error state does not leak the raw error`() {
        rule.setContent {
            ZCodeTheme {
                PluginsViewerScreen(port = object : PluginsPort {
                    override suspend fun plugins() = PluginsPort.Result(null, rawTransport)
                })
            }
        }
        assertNoRawLeak("plugins_error", rawTransport)
    }

    @Test
    fun `skills viewer error state does not leak the raw error`() {
        rule.setContent {
            ZCodeTheme {
                SkillsViewerScreen(port = object : SkillsPort {
                    override suspend fun catalog() = SkillsPort.Result(null, rawFault)
                })
            }
        }
        assertNoRawLeak("skills_error", rawFault)
    }
}
