package com.zcode.android.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * String-fix family (gaps-and-polish Phase 2D; L10/M7 P2 string fixes consolidated per
 * rule 11): L4.1 raw enum leak (`phase.name` → "CONNECTED"), L4.2/M5.3 the AAPT
 * leading-space trim (` · connected: %s`, ` · v%s`, ` · desktop %s`, `  ·  showing
 * latest…` render glued to the previous word), M5.3 the symbol-only `"  •"` active
 * marker (no semantics), M5.2 the double "ago" ("Last refreshed 1 min ago ago").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class StringFixFamilyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    @Test
    fun `L4_2 and M5_3 - whitespace-leading separators survive AAPT`() {
        assertTrue(context.getString(R.string.settings_connected_suffix, "Connected").startsWith(" · "))
        assertTrue(context.getString(R.string.settings_version_suffix, "3.14.5").startsWith(" · "))
        assertTrue(context.getString(R.string.conn_desktop_version, "3.14.5").startsWith(" · "))
        assertTrue(
            context.getString(com.zcode.android.features.conversation.R.string.conv_showing_latest_of, 20, 100).startsWith("  ·  "),
        )
    }

    @Test
    fun `M5_2 - last refreshed never says ago twice`() {
        val s = context.getString(R.string.conn_last_refreshed, "1 min ago")
        assertFalse("double ago: $s", s.contains("ago ago"))
        assertTrue(s.contains("1 min ago"))
    }

    @Test
    fun `L4_1 - Settings never prints the raw phase enum`() {
        val settings = File(root, "app/src/main/kotlin/com/zcode/android/app/settings/SettingsScreen.kt").readText()
        assertFalse("phase.name leaks the raw enum (localize via connectionPhaseLabel)", "phase.name" in settings)
        assertTrue("the shared phase label must be used", "connectionPhaseLabel(" in settings)
    }

    @Test
    fun `M5_3 - the active endpoint marker is a semantic badge, not a symbol-only suffix`() {
        val settings = File(root, "app/src/main/kotlin/com/zcode/android/app/settings/SettingsScreen.kt").readText()
        assertFalse("symbol-only active marker", "\"  •\"" in settings)
        assertTrue("the active marker carries a contentDescription", "settings_active_endpoint_a11y" in settings)
    }
}
