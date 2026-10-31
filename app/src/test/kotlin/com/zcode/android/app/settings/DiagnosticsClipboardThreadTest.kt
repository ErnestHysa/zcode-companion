package com.zcode.android.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowClipboardManager

/**
 * GAP-A5 (vc51 roadmap, sub-phase 1B; pass-1 full system ANR): "Copy diagnostics to
 * clipboard" ANRed the app — ClipboardManager.setPrimaryClip is a SYNCHRONOUS binder
 * IPC and it resumed on the Main dispatcher (SettingsScreen copyDiagnostics), so a
 * >5 s stall of the system clipboard service timed out input dispatch
 * ("Input dispatching timed out … Waited 5006ms").
 *
 * The pin drives the extracted write seam and asserts, through a recording shadow,
 * that setPrimaryClip NEVER runs on the main thread — whatever dispatcher the caller
 * uses. (The report build itself is already off-main since vc18; this is the write.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], shadows = [DiagnosticsClipboardThreadTest.RecordingClipboardShadow::class])
class DiagnosticsClipboardThreadTest {

    @Implements(ClipboardManager::class)
    class RecordingClipboardShadow : ShadowClipboardManager() {
        companion object {
            @JvmStatic
            var writtenOnThread: String? = null
            @JvmStatic
            var writeCount: Int = 0
        }

        override fun setPrimaryClip(clip: ClipData) {
            writtenOnThread = Thread.currentThread().name
            writeCount++
            super.setPrimaryClip(clip)
        }
    }

    /**
     * The caller's Main is a TEST main (unconfined — Robolectric's real main looper
     * would deadlock under runBlocking). The seam under test must move the write
     * OFF whatever Main it is handed: unconfined-main runs inline on the caller
     * thread, so a Main-dispatched write records the caller thread and FAILS.
     */
    @Before
    fun setUpDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `A5 - the diagnostics clipboard write happens OFF the main thread`() = runBlocking {
        val shadow = RecordingClipboardShadow.Companion
        shadow.writtenOnThread = null
        shadow.writeCount = 0
        val context = ApplicationProvider.getApplicationContext<Context>()

        val clipped = writeDiagnosticsClipboard(context, "diagnostic payload")

        assertEquals(false, clipped)
        assertEquals(1, shadow.writeCount.toLong())
        // Coroutine debug instrumentation decorates thread NAMES ("@coroutine#N"),
        // so the contract is asserted on the dispatcher pool, not on name equality:
        // a Main-dispatched write records the main thread here and FAILS (RED),
        // an IO-dispatched write records a DefaultDispatcher worker (GREEN).
        assertTrue(
            "setPrimaryClip is a synchronous binder IPC — it must run on Dispatchers.IO, " +
                "never Main (GAP-A5 ANR); wrote on ${shadow.writtenOnThread}",
            shadow.writtenOnThread?.contains("DefaultDispatcher") == true,
        )
    }

    @Test
    fun `A5 - a report over the clipboard budget is bounded and marked clipped`() = runBlocking {
        val shadow = RecordingClipboardShadow.Companion
        shadow.writtenOnThread = null
        shadow.writeCount = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oversized = "x".repeat(400_001)

        val clipped = writeDiagnosticsClipboard(context, oversized)

        assertTrue(clipped)
        assertEquals(1, shadow.writeCount.toLong())
        assertTrue(
            "the oversized write must ALSO be off-main (GAP-A5); wrote on ${shadow.writtenOnThread}",
            shadow.writtenOnThread?.contains("DefaultDispatcher") == true,
        )
    }
}
