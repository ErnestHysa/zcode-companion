package com.zcode.android.core.ui.components

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B3-motion + K2 (Phase 2B): the motion system's contract — the desktop's exact
 * vocabulary (K2 numbers), and ONE kill-switch: under the system reduced-motion
 * setting every helper collapses to zero duration / `None` / the identity modifier.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MotionSystemTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    @Test
    fun `vocabulary is the desktop's shipped numbers - never springy`() {
        assertEquals(CubicBezierEasing(0.16f, 1f, 0.3f, 1f), ZCodeMotion.StreamIn)
        assertEquals(900, ZCodeMotion.StreamInMs)
        assertEquals(CubicBezierEasing(0.42f, 0f, 0.58f, 1f), ZCodeMotion.Collapse)
        assertEquals(300, ZCodeMotion.CollapseMs)
        assertEquals(CubicBezierEasing(0.22f, 1f, 0.36f, 1f), ZCodeMotion.Waterfall)
        assertEquals(260, ZCodeMotion.WaterfallMs)
        assertEquals(300, ZCodeMotion.NavMs)
    }

    @Test
    fun `reduced motion collapses every duration to zero`() {
        setAnimatorScale(0f)
        var spec: MotionSpec? = null
        rule.setContent { spec = rememberMotionSpec() }
        rule.runOnIdle {
            assertFalse(spec!!.enabled)
            assertEquals(0, spec!!.ms(ZCodeMotion.StreamInMs))
            assertEquals(0, spec!!.ms(ZCodeMotion.NavMs))
        }
    }

    @Test
    fun `normal motion keeps the durations`() {
        setAnimatorScale(1f)
        var spec: MotionSpec? = null
        rule.setContent { spec = rememberMotionSpec() }
        rule.runOnIdle {
            assertTrue(spec!!.enabled)
            assertEquals(900, spec!!.ms(ZCodeMotion.StreamInMs))
        }
    }

    @Test
    fun `nav transitions are None under reduced motion and real otherwise`() {
        setAnimatorScale(0f)
        var reduced: NavTransitions? = null
        rule.setContent { reduced = rememberNavTransitions() }
        rule.runOnIdle {
            assertSame(EnterTransition.None, reduced!!.enter)
            assertSame(ExitTransition.None, reduced!!.exit)
            assertSame(EnterTransition.None, reduced!!.popEnter)
            assertSame(ExitTransition.None, reduced!!.popExit)
        }
    }

    @Test
    fun `nav transitions are real at normal scale`() {
        setAnimatorScale(1f)
        var t: NavTransitions? = null
        rule.setContent { t = rememberNavTransitions() }
        rule.runOnIdle {
            assertNotEquals(EnterTransition.None, t!!.enter)
            assertNotEquals(ExitTransition.None, t!!.exit)
        }
    }

    @Test
    fun `animateCollapse and streamIn are the identity modifier under reduced motion`() {
        setAnimatorScale(0f)
        val base = Modifier
        var collapse: Modifier? = null
        var stream: Modifier? = null
        rule.setContent {
            val tracker = rememberArrivalTracker()
            collapse = base.animateCollapse()
            stream = base.streamIn(key = "row-1", index = 0, tracker = tracker)
        }
        rule.runOnIdle {
            assertSame("animateCollapse must be a no-op under reduced motion", base, collapse)
            assertSame("streamIn must be a no-op under reduced motion", base, stream)
        }
    }

    @Test
    fun `streamIn arrives once per key - a seen row is the identity modifier`() {
        setAnimatorScale(1f)
        val base = Modifier
        var fresh: Modifier? = null
        var repeat: Modifier? = null
        var arrived: Boolean? = null
        rule.setContent {
            val tracker = rememberArrivalTracker()
            tracker.arrive("already-here")
            fresh = base.streamIn(key = "new-row", index = 0, tracker = tracker)
            repeat = base.streamIn(key = "already-here", index = 0, tracker = tracker)
            arrived = tracker.arrive("new-row") // second call: the fresh row is already recorded
        }
        rule.runOnIdle {
            assertNotEquals("a fresh row must get the arrival animation", base, fresh)
            assertSame("a row already seen must not re-arrive", base, repeat)
            assertEquals("arrival is recorded exactly once", false, arrived)
        }
    }
}
