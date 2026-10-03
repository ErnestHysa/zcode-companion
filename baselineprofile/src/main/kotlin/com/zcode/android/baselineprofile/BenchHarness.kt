package com.zcode.android.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Shared harness for the §28.2 macrobenchmark gates: a loopback MockZCode runs in
 * the BENCHMARK process; the app under test is seeded once (via its
 * benchmark-only debug seeding intent, §12.4-shaped storage writes) and every
 * measured launch then walks the real §28.1 budget path: cold start → task list.
 * INV-15: mock-only, no production relay, no real credentials (token is a
 * fixture string).
 */
object BenchHarness {

    const val APP_PACKAGE = "com.zcode.android.app"
    const val TOKEN = "benchmark-loopback-token"
    const val WORKSPACE_PATH = "/synthetic/bench-ws"
    const val TASK_ID = "bench-task-1"

    val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Start the in-process mock + seed the app. Returns the mock (stop() at the end). */
    fun seedAndStart(conversationRows: Int): MockZCode {
        val mock = MockZCode(
            passHash = "unused",
            expectedSid = "unused",
            mode = MockZCode.Mode.DIRECT,
            serverToken = TOKEN,
            workspace = MockZCode.MockWorkspace(
                workspacePath = WORKSPACE_PATH,
                workspaceKey = "bench-ws",
                rowCount = conversationRows,
            ),
        )
        mock.script.directTasks = listOf(
            buildJsonObject {
                put("taskId", JsonPrimitive(TASK_ID))
                put("title", JsonPrimitive("Phase 11 scroll benchmark conversation"))
                put("status", JsonPrimitive("running"))
                put("workspacePath", JsonPrimitive(WORKSPACE_PATH))
                put("createdAt", JsonPrimitive(System.currentTimeMillis() - 600_000.0))
                put("updatedAt", JsonPrimitive(System.currentTimeMillis() - 30_000.0))
            },
        )
        val url = mock.start()

        // One UNMEASURED launch whose only purpose is the seeding write (the app
        // persists before hub.start(), so by first frame the credential is durable).
        // am start via UiAutomation (the androidx MonitoringInstrumentation refuses
        // startActivitySync into a foreign process).
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sh = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $APP_PACKAGE/.MainActivity " +
                "--ez zcode.benchmark.seed true " +
                "--es zcode.benchmark.url '$url' " +
                "--es zcode.benchmark.token '$TOKEN' " +
                "--es zcode.benchmark.name BenchBook",
        )
        sh.close()
        device.wait(Until.hasObject(By.res(APP_PACKAGE, "tasks_list")), 60_000)
        device.pressHome()
        return mock
    }
}
