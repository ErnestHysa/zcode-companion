package com.zcode.android.app

import android.app.Activity
import android.os.Looper
import android.view.WindowManager
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * F-37-F (vc52 Phase 6, P2) — threat: anything that can capture the screen (Recents
 * thumbnails, screenshots, screen recording, "Select" text extraction) reads a live
 * approval's command text, queued follow-ups and drafts. The §27 preference
 * `screenshotProtection` defaulted ON, was persisted, round-tripped and pinned — and was
 * enforced NOWHERE (pass 37: `dumpsys window` showed no SECURE flag; Recents rendered the
 * approval card with Screenshot/Select offered). These pins hold the REAL MainActivity
 * window to the preference, live in both directions, and the Settings → Privacy toggle.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class ScreenshotProtectionTest {

    @get:Rule
    val rule = createComposeRule()

    class HostActivity : FragmentActivity()

    private fun secure(activity: Activity): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    /** DataStore emits from IO threads: pump the main looper until [until] holds (≤ ~4.5 s). */
    private fun pump(until: () -> Boolean): Boolean {
        repeat(150) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (until()) return true
            Thread.sleep(10)
        }
        return until()
    }

    /*
     * The real MainActivity is driven to CREATED only: the preference wiring lives in
     * onCreate (no composition needed), and a full visible() boot trips a Robolectric
     * frame-ordering artifact in ZCodeApp's first-pairing navigation (LaunchedEffect runs
     * before the Scaffold-subcomposed NavHost sets its graph) that production never hits.
     */
    @Test
    fun `F-37-F - MainActivity is FLAG_SECURE from creation (the default-ON preference)`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            assertTrue(
                "MainActivity's window lacks FLAG_SECURE with screenshot protection ON (the §27 default)",
                secure(controller.get()),
            )
            assertTrue("still protected once the stored preference loads", pump { secure(controller.get()) })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `F-37-F - toggling the preference applies live in both directions, no restart`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val prefs = activity.container.stores.preferencesRepository
        try {
            assertTrue("protected by default", pump { secure(activity) })
            runBlocking { prefs.update { it.copy(screenshotProtection = false) } }
            assertTrue("turning protection OFF must clear FLAG_SECURE without a restart", pump { !secure(activity) })
            runBlocking { prefs.update { it.copy(screenshotProtection = true) } }
            assertTrue("turning it back ON must restore FLAG_SECURE without a restart", pump { secure(activity) })
        } finally {
            runBlocking { prefs.update { it.copy(screenshotProtection = true) } }
            controller.destroy()
        }
    }

    @Test
    fun `F-37-F - Settings Privacy exposes the protection toggle and it round-trips the preference`() {
        val activity = Robolectric.setupActivity(HostActivity::class.java)
        val container = AppContainer(activity)
        val prefs = container.stores.preferencesRepository
        runBlocking { prefs.update { it.copy(screenshotProtection = true) } }
        try {
            rule.setContent {
                ZCodeTheme {
                    com.zcode.android.app.settings.SettingsScreen(
                        container = container,
                        endpointName = "MockBook",
                        desktopVersion = "3.14.5",
                        onBack = {},
                    )
                }
            }
            rule.onNodeWithTag("settings_privacy_screenshot").performScrollTo()
            rule.onNodeWithTag("settings_privacy_screenshot_switch").assertIsOn()
            rule.onNodeWithTag("settings_privacy_screenshot_switch").performClick()
            rule.waitUntil(5_000) { runBlocking { !prefs.current().screenshotProtection } }
            rule.onNodeWithTag("settings_privacy_screenshot_switch").assertIsOff()
            rule.onNodeWithTag("settings_privacy_screenshot_switch").performClick()
            rule.waitUntil(5_000) { runBlocking { prefs.current().screenshotProtection } }
            assertEquals(true, runBlocking { prefs.current().screenshotProtection })
        } finally {
            runBlocking { prefs.update { it.copy(screenshotProtection = true) } }
        }
    }
}
