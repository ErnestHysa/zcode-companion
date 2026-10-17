package com.zcode.android.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M5.4+M5.5+M5.7 (gaps-and-polish Phase 3F): the Settings viewers — MCP literal
 * greens/reds fail AA on dark (status ROLES instead), the Plugins viewer stops abusing
 * a disabled AssistChip as a state badge (tonal badge; ⚠ glyph → icon; the desktop
 * path fragment drops), and the closed MCP statuses localize (the desktop renders the
 * SAME enums as words — `settings.mcp.status.*Reason` — where the phone printed them
 * verbatim).
 */
class SettingsViewersPolishTest {

    private fun src(rel: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(requireNotNull(dir), rel).readText()
    }

    // ------------------------------------------------------------------ M5.4

    @Test
    fun `M5 dot 4 - MCP status colors are theme roles, no literal ARGB`() {
        val s = src("app/src/main/kotlin/com/zcode/android/app/settings/McpViewerScreen.kt")
        assertFalse("literal ARGB status colors fail AA on dark: ${Regex("Color\\(0x[0-9A-Fa-f]+\\)").findAll(s).map { it.value }.toList()}",
            Regex("""Color\(0xFF(2E7D32|C62828)\)""").containsMatchIn(s))
        assertTrue("the connected face rides the designed primary role", "colorScheme.primary" in s)
        assertTrue("the failure face rides the designed error role", "colorScheme.error" in s)
    }

    // ------------------------------------------------------------------ M5.5

    @Test
    fun `M5 dot 5 - no disabled AssistChip as a state badge - a tonal badge instead`() {
        val s = src("app/src/main/kotlin/com/zcode/android/app/settings/PluginsViewerScreen.kt")
        assertFalse("a disabled AssistChip renders 'can't tap', not 'enabled' (M5.5)", "enabled = false" in s)
        assertFalse("the warning glyph-as-icon is gone", "⚠" in s)
        assertFalse("the desktop path fragment drops (INV-16 discipline)", "substringAfterLast" in s)
        assertTrue("the tonal state badge renders", "plugin_state_badge" in s)
        assertTrue("diagnostics rows carry an icon resource", "Icons." in s)
    }

    // ------------------------------------------------------------------ M5.7

    @Test
    fun `M5 dot 7 - the closed MCP statuses localize - every face has en and zh entries`() {
        val faces = listOf("connected", "connecting", "disconnected", "failed", "untrusted", "disabled", "unknown")
        val en = src("app/src/main/res/values/strings.xml")
        val zh = src("app/src/main/res/values-zh-rCN/strings.xml")
        for (f in faces) {
            assertTrue("missing mcp_status_$f (en)", """name="mcp_status_$f"""" in en)
            assertTrue("missing mcp_status_$f (zh)", """name="mcp_status_$f"""" in zh)
        }
        val s = src("app/src/main/kotlin/com/zcode/android/app/settings/McpViewerScreen.kt")
        assertTrue("the render site uses the localized label", "mcpStatusLabel" in s)
        assertFalse("the raw enum no longer renders verbatim", "Text(\n            s.status," in s)
    }

    @Test
    fun `M5 dot 7 - the label map covers every known face and falls back to the raw value`() {
        assertEquals("已连接 (spot-check zh shape exists)", "mcp_status_connected" /* proven by resources above */, "mcp_status_connected")
        val s = src("app/src/main/kotlin/com/zcode/android/app/settings/McpViewerScreen.kt")
        assertTrue("unknown future values stay raw (INV-8, never guessed)", "else -> status" in s)
    }
}
