package com.zcode.android.baselineprofile

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §28.1 cold-start gate: cold start → task list (warm cache), measured with
 * StartupTimingMetric against the non-debuggable `benchmark` variant over the
 * loopback MockZCode. Target: ≤ 1.2 s P50 / ≤ 2.0 s P95 ON THE REFERENCE DEVICE
 * (mid-range 2023 class) — this run's numbers are EMULATOR-class evidence (§35.6
 * emulator leg); the reference-device ticket is tracked in docs/perf-phase11.md.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStart_toTaskList() {
        val mock = BenchHarness.seedAndStart(conversationRows = 8)
        try {
            benchmarkRule.measureRepeated(
                packageName = BenchHarness.APP_PACKAGE,
                metrics = listOf(StartupTimingMetric()),
                iterations = 5,
                startupMode = StartupMode.COLD,
                setupBlock = { BenchHarness.device.pressHome() },
            ) {
                startActivityAndWait()
                device.wait(
                    androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.res(BenchHarness.APP_PACKAGE, "tasks_list"),
                    ),
                    60_000,
                )
            }
        } finally {
            mock.stop()
        }
    }
}
