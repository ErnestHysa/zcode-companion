package com.zcode.android.core.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §43.2-8.4 "JobScheduler quota behavior / no quota violations": the probe's
 * WorkManager spec is ≥ 15 min + NETWORK-constrained (quota-compliant BY
 * CONSTRUCTION — asserted, not assumed), and the worker body is a no-op-success
 * with a dead process and triggers the hook when one is registered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ReconnectProbeWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `25-3 quota compliance - period is at least 15 minutes`() {
        val request = ReconnectProbeWorker.buildRequest()
        assertTrue(
            "periodic work below the 15-minute floor would burn JobScheduler quota (§29)",
            request.workSpec.intervalDuration >= ReconnectProbeWorker.PERIOD_MINUTES * 60 * 1000,
        )
    }

    @Test
    fun `25-3 quota compliance - network-constrained only`() {
        val request = ReconnectProbeWorker.buildRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `dead process (no hook) is a no-op success`() {
        ReconnectProbeWorker.ProbeHook.probe = null
        var called = false
        ReconnectProbeWorker.ProbeHook.probe = { called = true }
        // With the hook present the worker invokes it and succeeds.
        val worker = TestListenableWorkerBuilder<ReconnectProbeWorker>(context).build()
        val result = kotlinx.coroutines.runBlocking { worker.doWork() }
        assertTrue(called)
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        // With the hook absent (process dead) the worker still succeeds.
        ReconnectProbeWorker.ProbeHook.probe = null
        val idle = TestListenableWorkerBuilder<ReconnectProbeWorker>(context).build()
        val idleResult = kotlinx.coroutines.runBlocking { idle.doWork() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), idleResult)
    }
}
