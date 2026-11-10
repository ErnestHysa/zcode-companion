package com.zcode.android.app

import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N-BC-4 (vc52 Phase 10): the reportFullyDrawn seam — the app never called
 * Activity.reportFullyDrawn, so Android's startup measurement (logcat "fully drawn
 * line") and the baseline-profile derivation ran on the application-default guess.
 * The seam reports ONCE per cold start (a recreation never re-reports) and never
 * throws. The composition wiring is one LaunchedEffect(Unit) after the first frame;
 * the LIVE face is the exit-gate leg (the logcat line observed once per cold start).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FullyDrawnReporterTest {

    @BeforeTest
    fun reset() {
        MainActivity.FullyDrawnReporter.resetForTest()
    }

    @Test
    fun `the first call reports and never throws`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        MainActivity.FullyDrawnReporter.report(activity)
        assertTrue(MainActivity.FullyDrawnReporter.reportedForTest)
    }

    @Test
    fun `a recreation never re-reports (once per cold start)`() {
        val first = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        MainActivity.FullyDrawnReporter.report(first)
        val recreated = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        MainActivity.FullyDrawnReporter.report(recreated)
        assertTrue(MainActivity.FullyDrawnReporter.reportedForTest)
    }

    @Test
    fun `before any call nothing was reported`() {
        assertFalse(MainActivity.FullyDrawnReporter.reportedForTest)
    }
}
