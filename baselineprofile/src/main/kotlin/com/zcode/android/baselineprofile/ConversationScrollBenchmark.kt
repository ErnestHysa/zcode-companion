package com.zcode.android.baselineprofile

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §28.1 streaming-scroll gate (§19.2): FrameTimingMetric over continuous
 * conversation scrolling on a 400-row MockZCode timeline. The rendered surface is
 * the REAL timeline body (markdown rows, tool cards, coalesced stream buffers) —
 * the mock does not pace 3 concurrent LIVE streams, so the "≥ 55 fps under 3
 * active streams" cell of §28.1 remains a reference-device ticket
 * (docs/perf-phase11.md); this gate catches frame-budget regressions in the
 * scroll pipeline itself.
 */
@RunWith(AndroidJUnit4::class)
class ConversationScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun conversationScroll_frameTiming() {
        val mock = BenchHarness.seedAndStart(conversationRows = 400)
        try {
            // Warm launch for scroll measurement (the startup cell has its own gate).
            benchmarkRule.measureRepeated(
                packageName = BenchHarness.APP_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                iterations = 3,
                startupMode = StartupMode.WARM,
                setupBlock = {
                    startActivityAndWait()
                    // Open the seeded conversation (task row → conversation surface).
                    val row = device.wait(
                        Until.findObject(By.res(BenchHarness.APP_PACKAGE, "task_row_${BenchHarness.TASK_ID}")),
                        60_000,
                    )
                    row?.click()
                    device.wait(
                        Until.hasObject(By.res(BenchHarness.APP_PACKAGE, "conversation_list")),
                        60_000,
                    )
                },
            ) {
                // Continuous scroll up/down through the timeline (fling + dwell).
                val centerX = device.displayWidth / 2
                val top = (device.displayHeight * 0.75).toInt()
                val bottom = (device.displayHeight * 0.25).toInt()
                repeat(3) {
                    device.swipe(centerX, top, centerX, bottom, 24)
                    device.waitForIdle(2_000)
                }
                repeat(3) {
                    device.swipe(centerX, bottom, centerX, top, 24)
                    device.waitForIdle(2_000)
                }
            }
        } finally {
            mock.stop()
        }
    }
}
