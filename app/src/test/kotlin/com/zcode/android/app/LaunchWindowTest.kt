package com.zcode.android.app

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeColorsDark
import com.zcode.android.core.ui.theme.ZCodeColorsLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B3-splash (gaps-and-polish Phase 2B, P1; pixel-proven N4.1): the cold launch showed a
 * featureless WHITE window for seconds (content by ~3.1–3.5s, M6) — the launcher theme
 * was a bare `Theme.Material.Light.NoActionBar` with no `windowBackground` and no
 * core-splashscreen.
 *
 * Pinned: the launcher activity's theme is a `Theme.SplashScreen` whose splash
 * background is the designed `bg` token; its post-splash theme paints the same branded
 * `windowBackground` (so the window is never blank white between splash and first
 * frame); and the night qualifier resolves the dark pair.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class LaunchWindowTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun launcherTheme(): Resources.Theme {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertTrue("MainActivity must declare its own theme", info.themeResource != 0)
        return context.resources.newTheme().apply { applyStyle(info.themeResource, true) }
    }

    private fun attr(name: String): Int = context.resources.getIdentifier(name, "attr", context.packageName)

    private fun Resources.Theme.color(attrId: Int): Int {
        val tv = TypedValue()
        assertTrue("attribute must resolve in the theme", resolveAttribute(attrId, tv, true))
        assertTrue("attribute must be a color, got type ${tv.type}", tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT)
        return tv.data
    }

    @Test
    fun `launcher theme is a core-splashscreen theme with the designed bg as splash background`() {
        val splashBg = attr("windowSplashScreenBackground")
        assertTrue("core-splashscreen attr windowSplashScreenBackground must exist (dependency + Theme.SplashScreen)", splashBg != 0)
        assertEquals(ZCodeColorsLight.bg.toArgb(), launcherTheme().color(splashBg))
    }

    @Test
    fun `post-splash theme paints the branded window background - never blank white`() {
        val postAttr = attr("postSplashScreenTheme")
        assertTrue("postSplashScreenTheme attr must exist", postAttr != 0)
        val tv = TypedValue()
        assertTrue(launcherTheme().resolveAttribute(postAttr, tv, true))
        assertTrue("postSplashScreenTheme must reference a style", tv.resourceId != 0)
        val post = context.resources.newTheme().apply { applyStyle(tv.resourceId, true) }
        val windowBg = post.color(android.R.attr.windowBackground)
        assertEquals(ZCodeColorsLight.bg.toArgb(), windowBg)
        assertNotEquals("blank white is the N4.1 defect", 0xFFFFFFFF.toInt(), windowBg)
    }
}

/** Night pair: the splash and window backgrounds follow the designed dark `bg`. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "night")
class LaunchWindowNightTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `night splash and post-splash backgrounds are the dark bg`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        val theme = context.resources.newTheme().apply { applyStyle(info.themeResource, true) }
        val splashBg = context.resources.getIdentifier("windowSplashScreenBackground", "attr", context.packageName)
        assertTrue(splashBg != 0)
        val tv = TypedValue()
        assertTrue(theme.resolveAttribute(splashBg, tv, true))
        assertEquals(ZCodeColorsDark.bg.toArgb(), tv.data)
        val postAttr = context.resources.getIdentifier("postSplashScreenTheme", "attr", context.packageName)
        val ptv = TypedValue()
        assertTrue(theme.resolveAttribute(postAttr, ptv, true))
        val post = context.resources.newTheme().apply { applyStyle(ptv.resourceId, true) }
        val wtv = TypedValue()
        assertTrue(post.resolveAttribute(android.R.attr.windowBackground, wtv, true))
        assertEquals(ZCodeColorsDark.bg.toArgb(), wtv.data)
    }
}
