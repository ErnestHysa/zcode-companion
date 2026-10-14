package com.zcode.android.core.notifications

import android.app.Service
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class LiveConnectionServiceTimeoutTest {

    @Test
    fun `isRunning is an observable StateFlow (the stale-switch fix seam)`() {
        // Pre-fix this reads the plain Boolean var through Any — the runtime probe
        // fails (Boolean is not a StateFlow); post-fix `isRunning` IS the flow.
        val observable: Any = LiveConnectionService.isRunning
        assertTrue(
            observable is StateFlow<*>,
            "LiveConnectionService.isRunning must be an observable StateFlow (H3.1)",
        )
    }

    @Test
    fun `service lifecycle flips the observable - create on, destroy off`() = runBlocking {
        val controller = Robolectric.buildService(LiveConnectionService::class.java)
        try {
            controller.create()
            withTimeout(2_000) {
                LiveConnectionService.isRunning.first { it == true }
            }
            controller.destroy()
            withTimeout(2_000) {
                LiveConnectionService.isRunning.first { it == false }
            }
        } finally {
            LiveConnectionService.autoTimeoutMs = LiveConnectionService.AUTO_TIMEOUT_MS_DEFAULT
        }
    }

    @Test
    fun `timeout fires the stop path - foreground removed and stop requested`() {
        val previousTimeout = LiveConnectionService.autoTimeoutMs
        try {
            LiveConnectionService.autoTimeoutMs = 50
            val controller = Robolectric.buildService(LiveConnectionService::class.java)
            val service = controller.create().startCommand(0, 1).get()
            shadowOf(Looper.getMainLooper()).idle()

            val shadow = shadowOf(service as Service)
            assertTrue(shadow.isForegroundStopped, "the timeout path must stopForeground")
            assertTrue(shadow.isStoppedBySelf, "the timeout path must stopSelf")
            // Robolectric records stopSelf without invoking onDestroy; the flow
            // flip happens in onDestroy — the lifecycle pin above covers it.
            assertFalse(shadow.isStoppedBySelf == false)
        } finally {
            LiveConnectionService.autoTimeoutMs = previousTimeout
        }
        ApplicationProvider.getApplicationContext<android.content.Context>().toString() // touch context unused-guard
    }

    @Test
    fun `zero timeout means no auto-stop`() {
        val previousTimeout = LiveConnectionService.autoTimeoutMs
        try {
            LiveConnectionService.autoTimeoutMs = 0
            val controller = Robolectric.buildService(LiveConnectionService::class.java)
            val service = controller.create().startCommand(0, 1).get()
            shadowOf(Looper.getMainLooper()).idle()
            val shadow = shadowOf(service as Service)
            assertFalse(shadow.isStoppedBySelf, "0 = stay connected until toggled off (§24.5 vc35)")
        } finally {
            LiveConnectionService.autoTimeoutMs = previousTimeout
        }
    }
}
