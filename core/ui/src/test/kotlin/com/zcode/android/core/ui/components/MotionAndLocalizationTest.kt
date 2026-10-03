package com.zcode.android.core.ui.components

import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Phase 11 §30.5 reduced-motion + §30.6 color-independence + §31 relative-time
 * localization (core:ui). Locale-specific legs live in the qualifier-pinned
 * classes below (RelativeTimeZhCnTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MotionAndLocalizationTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale,
        )
    }

    // -------------------------------------------------- §30.5 reduced motion

    @Test
    fun reducedMotion_on_whenSystemRemovesAnimations() {
        setAnimatorScale(0f)
        assertTrue(isReducedMotion(context))
    }

    @Test
    fun reducedMotion_off_atNormalScale() {
        setAnimatorScale(1f)
        assertFalse(isReducedMotion(context))
    }

    @Test
    fun rememberReducedMotion_reflectsTheSystemSetting_inComposition() {
        setAnimatorScale(0f)
        var observed: Boolean? = null
        rule.setContent {
            observed = rememberReducedMotion()
        }
        rule.runOnIdle { assertEquals(true, observed) }
    }

    // -------------------------------------------------- §31 relative time (default en)

    @Test
    fun relativeTime_en_buckets() {
        val now = 1_000_000_000L
        var now_: String? = null
        var mins: String? = null
        var hours: String? = null
        var yesterday: String? = null
        var days: String? = null
        var beyond: String? = null
        rule.setContent {
            now_ = relativeTimeLabel(now - 5_000, now)
            mins = relativeTimeLabel(now - 2 * 60_000L, now)
            hours = relativeTimeLabel(now - 3 * 3_600_000L, now)
            yesterday = relativeTimeLabel(now - 30 * 3_600_000L, now)
            days = relativeTimeLabel(now - 3 * 86_400_000L, now)
            beyond = relativeTimeLabel(now - 30L * 86_400_000L, now)
        }
        rule.runOnIdle {
            assertEquals("now", now_)
            assertEquals("2 min ago", mins)
            assertEquals("3 h ago", hours)
            assertEquals("Yesterday", yesterday)
            assertEquals("3 d ago", days)
            assertNull(beyond)
        }
    }

    // -------------------------------------------------- §30.6 color-independence

    @Test
    fun taskStatusGlyph_coversEveryStatus() {
        for (status in com.zcode.android.domain.model.TaskStatus.entries) {
            assertNotNull(taskStatusGlyph(status))
        }
    }

    @Test
    fun connectionStatusLine_rendersTextLabel_neverColorOnly() {
        rule.setContent {
            ZCodeTheme {
                ConnectionStatusLine(phase = com.zcode.android.domain.state.ConnectionPhase.CONNECTED)
            }
        }
        rule.onNodeWithText("Connected").assertExists()
    }
}

/** §31: zh-CN resource resolution for the shared relative-time strings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "zh-rCN")
class RelativeTimeZhCnTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun relativeTime_resolvesChinese() {
        val now = 1_000_000_000L
        var mins: String? = null
        var hours: String? = null
        var yesterday: String? = null
        rule.setContent {
            mins = relativeTimeLabel(now - 2 * 60_000L, now)
            hours = relativeTimeLabel(now - 3 * 3_600_000L, now)
            yesterday = relativeTimeLabel(now - 30 * 3_600_000L, now)
        }
        rule.runOnIdle {
            assertEquals("2 分钟前", mins)
            assertEquals("3 小时前", hours)
            assertEquals("昨天", yesterday)
        }
    }
}
