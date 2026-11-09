package com.zcode.android.app.settings

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import com.zcode.android.core.ui.theme.ZCodeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-33-G (P3, honesty; closed by F-34-E, vc52 Phase 8) — with notifications blocked by
 * Android, Settings still said they were on: "Approval requests ON, Task finished / failed
 * ON, Connection lost ON", no warning anywhere, while the broker "posted" into the void.
 * The section must lead with the off-state row and the category switches must read their
 * EFFECTIVE state while blocked. Uses pre-existing API only (it RED-ran behaviorally).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class NotificationsBlockedHonestyTest {

    @get:Rule
    val rule = createComposeRule()

    class HostActivity : FragmentActivity()

    private fun settings(blocked: Boolean) {
        val activity = Robolectric.setupActivity(HostActivity::class.java)
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(nm).setNotificationsEnabled(!blocked)
        val container = AppContainer(activity)
        runBlocking { container.stores.preferencesRepository.update { it.copy(notifyApprovals = true, notifyTaskOutcomes = true, notifyConnection = true) } }
        rule.setContent {
            ZCodeTheme {
                SettingsScreen(container = container, endpointName = "MockBook", desktopVersion = "3.14.5", onBack = {})
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `F-33-G - blocked notifications never read ON - the section leads with the off-state row`() {
        settings(blocked = true)
        // The recorded shape first: "Approval requests ON" while Android blocks everything.
        for (tag in listOf("settings_notif_cat_approvals", "settings_notif_cat_tasks", "settings_notif_cat_connection")) {
            rule.onNodeWithTag("${tag}_switch").performScrollTo()
            rule.onNodeWithTag("${tag}_switch").assertIsOff()
            rule.onNodeWithTag("${tag}_switch").assertIsNotEnabled()
        }
        rule.onNodeWithTag("notifications_off_row").performScrollTo()
    }

    @Test
    fun `F-33-G control - enabled notifications show the stored preference and no warning`() {
        settings(blocked = false)
        assertTrue(rule.onAllNodesWithTag("notifications_off_row").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("settings_notif_cat_approvals_switch").performScrollTo()
        rule.onNodeWithTag("settings_notif_cat_approvals_switch").assertIsOn()
    }
}
