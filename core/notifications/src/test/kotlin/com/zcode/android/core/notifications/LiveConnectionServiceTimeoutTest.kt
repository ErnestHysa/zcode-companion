package com.zcode.android.core.notifications

import android.app.Service
import android.os.Looper
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * H3.1 (gaps-and-polish Phase 1A, P0): the FGS 30-min auto-timeout left the
 * Settings switch LYING — `isRunning` was a plain companion `var` snapshotted into
 * `remember {}`, so after the timeout fired the switch stayed ON until the screen
 * was recreated. The fix replaces the var with an observable [StateFlow]: the
 * service lifecycle (and therefore the timeout stop path) is the single writer,
 * and the Settings switch collects it — no recreation needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LiveConnectionServiceTimeoutTest {

    @Test
    fun `isRunning is an observable StateFlow (the stale-switch fix seam)`() {
        // Pre-fix this reads the plain Boolean var through Any — the runtime probe
        // fails (Boolean is not StateFlow); post-fix `isRunning` IS the flow.
        val observable: Any = LiveConnectionService.isRunning
        assertTrue(
            "LiveConnectionService.isRunning must be an observable StateFlow (H3.1)",
            observable is StateFlow<*>,
        )
    }

    @Test
    fun `service lifecycle flips the observable - create on, destroy off`() {
        runBlocking {
        val controller = Robolectric.buildService(LiveConnectionService::class.java)
        try {
            // The cast is the seam probe: pre-fix (plain Boolean var) this throws
            // ClassCastException — the observable contract did not exist.
            val isRunning = LiveConnectionService.isRunning as StateFlow<Boolean>
            controller.create()
            withTimeout(2_000) {
                isRunning.first { it == true }
            }
            controller.destroy()
            withTimeout(2_000) {
                isRunning.first { it == false }
            }
        } finally {
            LiveConnectionService.autoTimeoutMs = LiveConnectionService.AUTO_TIMEOUT_MS_DEFAULT
        }
        }
    }

    @Test
    fun `timeout fires the stop path - foreground removed and stop requested`() {
        val previousTimeout = LiveConnectionService.autoTimeoutMs
        try {
            LiveConnectionService.autoTimeoutMs = 50
            val controller = Robolectric.buildService(LiveConnectionService::class.java)
            val service = controller.create().startCommand(0, 1).get() as Service
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))

            val shadow = shadowOf(service)
            assertTrue("the timeout path must stopForeground", shadow.isForegroundStopped)
            assertTrue("the timeout path must stopSelf", shadow.isStoppedBySelf)
        } finally {
            LiveConnectionService.autoTimeoutMs = previousTimeout
        }
    }

    @Test
    fun `zero timeout means no auto-stop`() {
        val previousTimeout = LiveConnectionService.autoTimeoutMs
        try {
            LiveConnectionService.autoTimeoutMs = 0
            val controller = Robolectric.buildService(LiveConnectionService::class.java)
            val service = controller.create().startCommand(0, 1).get() as Service
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            assertFalse(
                "0 = stay connected until toggled off (§24.5 vc35)",
                shadowOf(service).isStoppedBySelf,
            )
        } finally {
            LiveConnectionService.autoTimeoutMs = previousTimeout
        }
    }
}
