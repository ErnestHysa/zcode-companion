package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.tasks.port.AutomationsPort
import com.zcode.android.features.tasks.ui.AutomationsSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F5 (String-fix family, Phase 2D): an ERRORED automations channel read as a
 * CAPABILITY statement ("Automations are unavailable on this connection" while
 * Connected). The copy must distinguish "the desktop didn't answer" from "not
 * supported" — through the shared K9 fault classifier.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AutomationsErrorCopyTest {

    @get:Rule
    val rule = createComposeRule()

    private fun port(error: String) = object : AutomationsPort {
        override suspend fun lists(): AutomationsPort.Lists = AutomationsPort.Lists(null, null, error)
        override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite): Boolean = false
        override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite): Boolean = false
        override suspend fun delete(automationId: String): Boolean = false
        override suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean = false
        override suspend fun runNow(automationId: String): Boolean = false
    }

    @Test
    fun `an unsupported-class fault reads as not available on this desktop`() {
        rule.setContent { ZCodeTheme { AutomationsSection(port = port("fault.mock.unscripted:listAutomations")) } }
        rule.waitForIdle()
        rule.onAllNodesWithText("isn't available on the connected desktop", substring = true).assertCountEquals(1)
    }

    @Test
    fun `a transport error reads as a connection problem - not a capability statement`() {
        rule.setContent { ZCodeTheme { AutomationsSection(port = port("channel closed: no outbound sink attached")) } }
        rule.waitForIdle()
        rule.onAllNodesWithText("Automations are unavailable on this connection").assertCountEquals(0)
        rule.onAllNodesWithText("Connection lost", substring = true).assertCountEquals(1)
    }
}
