package com.zcode.android.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * H2.1 / H2.2 / H3.2b (gaps-and-polish Phase 2F, P1 — platform-native cheap wins):
 * no `android:localeConfig` (the Android 13+ per-app language page offered nothing
 * despite the maintained zh catalog), no `shortcuts.xml` (long-press offered nothing),
 * and the widget picker previewed `glance_default_loading_layout` (no previewLayout).
 */
class PlatformIntegrationTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    private val manifest by lazy { File(root, "app/src/main/AndroidManifest.xml").readText() }

    @Test
    fun `H2_1 - per-app language config lists en and zh-CN and the manifest points at it`() {
        assertTrue("manifest must declare android:localeConfig", Regex("""android:localeConfig="@xml/locales_config"""").containsMatchIn(manifest))
        val xml = File(root, "app/src/main/res/xml/locales_config.xml")
        assertTrue("res/xml/locales_config.xml must exist", xml.isFile)
        val s = xml.readText()
        assertTrue("en locale", Regex("""android:name="en"""").containsMatchIn(s))
        assertTrue("zh-CN locale", Regex("""android:name="zh-CN"""").containsMatchIn(s))
    }

    @Test
    fun `H2_2 - static shortcuts exist and the launcher activity registers them`() {
        assertTrue("MainActivity must carry the android.app.shortcuts meta-data", Regex("""android:name="android.app.shortcuts"""").containsMatchIn(manifest))
        val xml = File(root, "app/src/main/res/xml/shortcuts.xml")
        assertTrue("res/xml/shortcuts.xml must exist", xml.isFile)
        val s = xml.readText()
        for (id in listOf("new_task", "approvals", "connection")) {
            assertTrue("shortcut $id", Regex("""android:shortcutId="$id"""").containsMatchIn(s))
        }
    }

    @Test
    fun `H3_2b - the widget info declares a real preview layout`() {
        val info = File(root, "app/src/main/res/xml/pending_approvals_widget_info.xml").readText()
        val m = Regex("""android:previewLayout="@layout/([a-z_0-9]+)"""").find(info)
        assertTrue("widget info must declare android:previewLayout", m != null)
        val layout = m!!.groupValues[1]
        assertFalse("the preview must not be Glance's loading placeholder", layout.contains("loading"))
        assertTrue("layout/$layout.xml must exist", File(root, "app/src/main/res/layout/$layout.xml").isFile)
    }
}
