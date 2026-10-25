package com.zcode.android.app.theme

import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

/**
 * U7 (verification pass 2, 2026-10-21, live-found on the rig): picking "Dark" in Settings
 * while the system is light re-themed the app instantly — but the status-bar clock and
 * icons stayed DARK on the now-dark background (nearly invisible) and the navigation bar
 * kept its LIGHT scrim under the dark app. `enableEdgeToEdge()` ran once in onCreate with
 * its default detector, which reads the SYSTEM uiMode; the in-app override never reached
 * the window. (A ModalBottomSheet's own window flipped correctly — the main window alone
 * was stale.)
 *
 * Two pins: the helper drives the window's light-bar flags and nav scrim from the given
 * dark flag (sdk 29 keeps those flags readable as systemUiVisibility), and MainActivity
 * re-applies it from the composition keyed on the RESOLVED dark value.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class SystemBarsFollowThemeTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `the helper drives light-bar flags and the nav scrim from the app's dark flag`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val decor = activity.window.decorView

        activity.applySystemBarsFor(dark = false)
        val lightFlags = decor.systemUiVisibility
        assertTrue("light theme → light status bar (dark icons)", lightFlags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0)
        assertTrue("light theme → light navigation bar (dark icons)", lightFlags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0)

        activity.applySystemBarsFor(dark = true)
        val darkFlags = decor.systemUiVisibility
        assertEquals("dark theme → light icons on the status bar", 0, darkFlags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        assertEquals("dark theme → light icons on the navigation bar", 0, darkFlags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        // API 29+: the nav bar stays TRANSPARENT and the system paints its contrast scrim
        // from the light/dark appearance flag above — the flag IS the scrim's source.
        assertEquals("edge-to-edge keeps the nav bar transparent", android.graphics.Color.TRANSPARENT, activity.window.navigationBarColor)
    }

    @Test
    fun `MainActivity re-applies the system bars from the composition keyed on the resolved dark flag`() {
        val main = File(root, "app/src/main/kotlin/com/zcode/android/app/MainActivity.kt").readText()
        assertTrue(
            "the system bars must follow the resolved theme: applySystemBarsFor(dark) inside an effect keyed on it",
            Regex("""LaunchedEffect\(dark\)\s*\{\s*applySystemBarsFor\(dark\)\s*\}""").containsMatchIn(main),
        )
        assertTrue("the resolved flag comes from the theme mode (override wins, SYSTEM reads the system)", "themeMode.resolveDark()" in main)
    }
}
