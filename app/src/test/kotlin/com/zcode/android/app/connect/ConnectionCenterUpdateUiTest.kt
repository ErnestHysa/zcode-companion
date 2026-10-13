package com.zcode.android.app.connect

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.update.DownloadState
import com.zcode.android.app.update.ReleaseInfo
import com.zcode.android.app.update.UpdateCheckState
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * AAP-3 (§14.2 T4) — the connection-center compat-warning integration. The report's
 * rule, pinned as rendered state: desktop > maxTested + newer release → the warning
 * BECOMES the update action (banner + "Update now"); no update → the honest warning
 * stays; desktop ≤ maxTested → NOTHING appears (no banner, no warning, no button).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConnectionCenterUpdateUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun release(vc: Long = 37, desktopNewer: Boolean = true) = ReleaseInfo(
        versionName = "0.11.5",
        versionCode = vc,
        apkUrl = "https://x/app-release.apk",
        apkAssetName = "app-release.apk",
        sha256Hex = null,
        sha256Url = null,
        tag = "v0.11.5-vc$vc",
        notes = null,
        maxTestedDesktop = null,
    )

    private fun content(
        desktopVersion: String?,
        updateState: UpdateCheckState?,
        downloadState: DownloadState? = null,
        onUpdateNow: () -> Unit = {},
    ) {
        rule.setContent {
            ZCodeTheme {
                ConnectionCenterScreen(
                    phase = ConnectionPhase.CONNECTED,
                    endpointName = "Ernest's Mac",
                    desktopVersion = desktopVersion,
                    lastError = null,
                    lastRefreshAtMs = null,
                    onRetry = {},
                    onDismissError = {},
                    onBack = {},
                    updateState = updateState,
                    downloadState = downloadState,
                    onUpdateNow = onUpdateNow,
                )
            }
        }
    }

    private fun exists(tag: String): Boolean =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `desktop newer + release available - warning becomes the banner with Update now`() {
        content("3.14.5", UpdateCheckState.Available(release(37), desktopNewer = true))
        rule.onNodeWithTag("update_banner").assertExists()
        rule.onNodeWithTag("update_now").assertExists()
        // The honest warning is REPLACED by the action when an update exists.
        assertEquals(false, exists("connection_compat_warning"))
    }

    @Test
    fun `desktop newer + no update yet - the honest compat warning stays`() {
        content("3.14.5", null)
        rule.onNodeWithTag("connection_compat_warning").assertExists()
        assertEquals(false, exists("update_banner"))
        assertEquals(false, exists("update_now"))
    }

    @Test
    fun `desktop newer + release check failed - warning stays + honest failure line`() {
        content("3.14.5", UpdateCheckState.Failed("releases metadata unavailable"))
        rule.onNodeWithTag("connection_compat_warning").assertExists()
        rule.onNodeWithTag("update_error").assertExists()
        assertEquals(false, exists("update_now"))
    }

    @Test
    fun `desktop newer + up-to-date release - warning stays + no-update line`() {
        content("3.14.5", UpdateCheckState.UpToDate)
        rule.onNodeWithTag("connection_compat_warning").assertExists()
        rule.onNodeWithTag("update_uptodate").assertExists()
        assertEquals(false, exists("update_now"))
    }

    @Test
    fun `desktop within tested matrix - banner shows WITHOUT compat warning (field fix)`() {
        // FIELD FIX (2026-10-12): the update offer is no longer gated on the desktop
        // version — a newer release shows the plain banner, no compat warning.
        content("3.14.4", UpdateCheckState.Available(release(37), desktopNewer = true))
        assertEquals(false, exists("connection_compat_warning"))
        rule.onNodeWithTag("update_banner").assertExists()
        rule.onNodeWithTag("update_now").assertExists()
    }

    @Test
    fun `manual check button always available`() {
        content("3.14.4", UpdateCheckState.Idle)
        rule.onNodeWithTag("update_check_manual").assertExists()
    }

    @Test
    fun `checking state shows the checking line under the warning`() {
        content("3.14.5", UpdateCheckState.Checking)
        rule.onNodeWithTag("connection_compat_warning").assertExists()
        rule.onNodeWithTag("update_checking").assertExists()
    }

    @Test
    fun `download progress and ready faces render under the banner`() {
        content(
            "3.14.5",
            UpdateCheckState.Available(release(37), desktopNewer = true),
            DownloadState.Running(bytesRead = 5_000, totalBytes = 10_000),
        )
        rule.onNodeWithTag("update_banner").assertExists()
        val progress = rule.onNodeWithTag("update_progress").assertExists()
        // 5000/10000 → 50%
        val text = progress.fetchSemanticsNode().config.getOrElse(
            androidx.compose.ui.semantics.SemanticsProperties.Text,
        ) { emptyList() }.joinToString(" ") { it.text }
        assertEquals(true, "50%" in text)
        // No re-entry to the download while it runs.
        assertEquals(false, exists("update_now"))
    }

    @Test
    fun `ready face - downloaded line, no re-download button`() {
        val apk = java.io.File(System.getProperty("java.io.tmpdir"), "ui-ready.apk")
        content(
            "3.14.5",
            UpdateCheckState.Available(release(37), desktopNewer = true),
            DownloadState.Ready(apk),
        )
        rule.onNodeWithTag("update_ready").assertExists()
        assertEquals(false, exists("update_now"))
    }

    @Test
    fun `Update now click hands off to the controller callback`() {
        var clicked = 0
        content(
            "3.14.5",
            UpdateCheckState.Available(release(37), desktopNewer = true),
            onUpdateNow = { clicked += 1 },
        )
        rule.onNodeWithTag("update_now").performClick()
        rule.waitForIdle()
        assertEquals(1, clicked)
    }
}
