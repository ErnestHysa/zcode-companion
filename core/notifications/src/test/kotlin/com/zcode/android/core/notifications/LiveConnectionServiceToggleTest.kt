package com.zcode.android.core.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PINS-BACKLOG anchor (vc51 Phase 5): the LiveConnectionService FGS lifecycle's
 * app-side start/stop toggle seam — the service-internal contract (create/destroy
 * flips the observable isRunning, the 30-min timeout stop path) is pinned by
 * [LiveConnectionServiceTimeoutTest]; this pins the WIRING the Settings surface
 * drives: start(context) posts the compliant foreground-service intent targeting
 * the service (§25.1/§25.2) and stop(context) stops that exact component.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LiveConnectionServiceToggleTest {

    @Test
    fun `the start seam posts the foreground-service intent targeting the service`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LiveConnectionService.start(app)
        val started = shadowOf(app).nextStartedService
        assertEquals(
            "start() must ride startForegroundService with the service component (§25.1)",
            LiveConnectionService::class.java.name,
            started.component?.className,
        )
    }

    @Test
    fun `the stop seam stops that exact component`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LiveConnectionService.stop(app)
        val stopped = shadowOf(app).nextStoppedService
        assertEquals(
            "stop() must stop the service component",
            LiveConnectionService::class.java.name,
            stopped.component?.className,
        )
    }
}
