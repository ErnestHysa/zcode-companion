package com.zcode.android.features.tasks

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.tasks.port.AutomationsPort
import com.zcode.android.features.tasks.ui.AutomationsSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Connect-race (found LIVE on the rig, whole-campaign verification 2026-10-21):
 * the section loaded ONCE at composition — on a cold launch that is BEFORE the
 * transport reaches CONNECTED, so `lists()` returned "no active workspace"
 * (bootstrap not yet landed) and the generic fault line rendered forever, with
 * zero re-attempts after `hub.phase → CONNECTED` (logcat: both pre-connect
 * attempts at 18:06:42, CONNECTED at 18:06:44, no further load). The load must
 * re-run when the connection reaches CONNECTED — the F3-LOAD class, on the
 * Automations surface.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AutomationsConnectRaceTest {

    @get:Rule
    val rule = createComposeRule()

    /** Call 1 answers the pre-connect race (no workspace yet); later calls succeed. */
    private class ConnectRacingPort : AutomationsPort {
        var calls = 0
        override suspend fun lists(): AutomationsPort.Lists {
            calls += 1
            return if (calls == 1) {
                AutomationsPort.Lists(null, null, "no active workspace")
            } else {
                AutomationsPort.Lists(emptyList(), null, null)
            }
        }

        override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite): Boolean = false
        override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite): Boolean = false
        override suspend fun delete(automationId: String): Boolean = false
        override suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean = false
        override suspend fun runNow(automationId: String): Boolean = false
    }

    @Test
    fun `a failed pre-connect load is retried when the connection reaches CONNECTED`() {
        val port = ConnectRacingPort()
        val connected = mutableStateOf(false)
        rule.setContent { ZCodeTheme { AutomationsSection(port = port, connected = connected.value) } }
        rule.waitForIdle()
        // The pre-connect race rendered the fault line (calls == 1, no retry yet).
        rule.onNodeWithTag("automations_error").assertExists()

        connected.value = true // hub.phase → CONNECTED
        rule.waitForIdle()

        // The re-load lands: the fault line is replaced by the real (empty) state.
        rule.onAllNodesWithTag("automations_error").assertCountEquals(0)
        rule.onNodeWithTag("automations_empty").assertExists()
    }

    @Test
    fun `the load still fires exactly once when the connection never flips`() {
        val port = ConnectRacingPort()
        rule.setContent { ZCodeTheme { AutomationsSection(port = port, connected = false) } }
        rule.waitForIdle()
        org.junit.Assert.assertEquals(1, port.calls)
    }
}
