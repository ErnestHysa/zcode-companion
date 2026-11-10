package com.zcode.android.app.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * N-BI-3 (vc52 Phase 10): at 0 bytes free the export failure toast read
 * `Export failed: /data/user/0/com.zcode.android.app/cache/shared/zcode-diagnostics-1794143803559.`
 * — honest, but leaking an internal path and truncating mid-extension. The user copy
 * is TYPED now (storage-full vs generic); the path lives in the diagnostics ring only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class DiagnosticsExportFailureCopyTest {

    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

    /** The recorded failure shape: FileOutputStream's ENOSPC, path in the message. */
    private val recorded = java.io.FileNotFoundException(
        "/data/user/0/com.zcode.android.app/cache/shared/zcode-diagnostics-1794143803559.txt: open failed: ENOSPC (No space left on device)",
    )

    @Test
    fun `the recorded ENOSPC shape maps to the storage copy`() {
        assertEquals(R.string.settings_export_failed_storage, diagnosticsExportFailureCopy(recorded))
    }

    @Test
    fun `a wrapped ENOSPC cause still maps to the storage copy`() {
        val wrapped = RuntimeException("export pipeline failed", java.io.IOException("write failed: ENOSPC (No space left on device)"))
        assertEquals(R.string.settings_export_failed_storage, diagnosticsExportFailureCopy(wrapped))
    }

    @Test
    fun `the plain no-space wording maps to the storage copy`() {
        assertEquals(
            R.string.settings_export_failed_storage,
            diagnosticsExportFailureCopy(java.io.IOException("No space left on device")),
        )
    }

    @Test
    fun `any other failure maps to the generic copy`() {
        assertEquals(R.string.settings_export_failed_generic, diagnosticsExportFailureCopy(IllegalStateException("boom")))
        assertEquals(
            R.string.settings_export_failed_generic,
            diagnosticsExportFailureCopy(java.io.IOException("Permission denied")),
        )
    }

    @Test
    fun `the LIVE 0-byte shape - a cache-path FileNotFound maps to the storage copy`() {
        // The recorded 0-byte receipt: the open() fails ENOENT because the cache DIR
        // could not be created — the message carries the cache path, not ENOSPC.
        val live = java.io.FileNotFoundException(
            "/data/user/0/com.zcode.android.app/cache/shared/zcode-diagnostics-1794320947531.txt: open failed: ENOENT (No such file or directory)",
        )
        assertEquals(R.string.settings_export_failed_storage, diagnosticsExportFailureCopy(live))
    }

    @Test
    fun `a FileNotFound OUTSIDE the app cache stays generic`() {
        assertEquals(
            R.string.settings_export_failed_generic,
            diagnosticsExportFailureCopy(java.io.FileNotFoundException("/sdcard/missing.txt: open failed: ENOENT")),
        )
    }

    @Test
    fun `a non-IOException that merely mentions ENOSPC is NOT the storage class`() {
        assertEquals(
            R.string.settings_export_failed_generic,
            diagnosticsExportFailureCopy(IllegalArgumentException("ENOSPC is a posix code")),
        )
    }

    @Test
    fun `neither user copy carries a path, the raw message or a format hole`() {
        for (res in listOf(R.string.settings_export_failed_storage, R.string.settings_export_failed_generic)) {
            val copy = context.getString(res)
            assertFalse(copy.contains("/"), "no filesystem path in user copy: $copy")
            assertFalse(copy.contains("%"), "no unfilled format arg: $copy")
            assertFalse(copy.contains("zcode-diagnostics"), "no internal file name: $copy")
        }
        assertEquals("Not enough free storage to export diagnostics.", context.getString(R.string.settings_export_failed_storage))
    }
}
