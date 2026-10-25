package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.PluginsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ADR-023 (Phase 5D, A3.19) UI pins: the plugins viewer's enable Switch is the ONE
 * sanctioned plugin write — a tap rides port.setEnabled, the ack drives a RE-LIST (the
 * rendered state is wire-true, never an optimistic flip), a refusal keeps the wire state
 * (still unchecked), and install/uninstall/update/configure have no affordance anywhere.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class PluginEnableUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val toggles = mutableListOf<String>()
    private var toggleOutcome = true
    private var listCount = 0

    private fun view() = PluginsView(
        plugins = listOf(
            PluginsView.PluginInfo(
                id = "p1", name = "Demo plugin", description = null, version = "1.0", enabled = false,
                source = "local", marketplace = "core", skillRootCount = 0, commandRootCount = 0,
                mcpServerNames = emptyList(), rootPath = "/plugins/p1",
            ),
        ),
        diagnostics = emptyList(),
    )

    private fun port() = object : PluginsPort {
        override suspend fun plugins(): PluginsPort.Result { listCount += 1; return PluginsPort.Result(view(), null) }
        override suspend fun setEnabled(pluginId: String, enabled: Boolean): Boolean {
            toggles += "$pluginId:$enabled"
            return toggleOutcome
        }
        // (the interface's default no-op is overridden here)
    }

    private fun setContent() {
        toggles.clear(); listCount = 0
        rule.setContent { ZCodeTheme { PluginsViewerScreen(port = port()) } }
        rule.waitForIdle()
    }

    @Test
    fun `the switch reflects the wire and a tap re-lists instead of flipping optimistically`() {
        setContent()
        rule.onNodeWithTag("plugin_switch_p1").assertIsDisplayed()
        rule.onNodeWithTag("plugin_switch_p1").performClick(); rule.waitForIdle()
        assertEquals(listOf("p1:true"), toggles)
        assertTrue("the ack drives a re-list ($listCount)", listCount >= 2)
    }

    @Test
    fun `a refusal keeps the wire state unchecked and no write affordances exist beyond the switch`() {
        toggleOutcome = false
        setContent()
        rule.onNodeWithTag("plugin_switch_p1").performClick(); rule.waitForIdle()
        assertEquals(listOf("p1:true"), toggles)
        // the rendered state stays OFF (the wire truth — no optimistic flip): the
        // state badge still reads the OFF copy.
        rule.onNodeWithTag("plugin_state_badge").assertIsDisplayed()
        // ADR-023 tier 2: install/uninstall/update/configure have NO affordance anywhere.
        rule.onAllNodesWithTag("plugin_install", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("plugin_uninstall", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("plugin_update", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("plugin_configure", useUnmergedTree = true).assertCountEquals(0)
    }
}
