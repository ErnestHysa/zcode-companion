package com.zcode.android.app.settings

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FIELD (2026-10-01, vc20): diagnostics export went through Intent.EXTRA_TEXT —
 * the Binder transaction buffer (~1 MiB shared) rejects multi-MB reports with
 * TransactionTooLargeException, so the share sheet silently never appeared once
 * the 5,000-entry ring filled ("works right after app open, fails later").
 * The fix shares a FileProvider stream URI from cacheDir/shared/ — this suite
 * pins the file-write contract: content round-trips, URIs resolve, dir bounded.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class DiagnosticsExportTest {

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun writeDiagnosticsFile_roundTripsContent_andResolvesAsFileProviderUri() {
        val context = context()
        val report = "=== ZCode Companion diagnostics ===\n" + "x".repeat(2_500_000) // > 1 MiB binder budget
        val file = writeDiagnosticsFile(context, report)
        assertTrue(file.exists() && file.length() > 2_000_000L, "report must be written whole")
        assertEquals(report, file.readText())
        // The URI the share sheet receives must resolve through the provider —
        // this is what used to be a >1 MiB Binder extra (TransactionTooLargeException).
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        assertEquals("com.zcode.android.app.fileprovider", uri.authority)
        context.contentResolver.openInputStream(uri)!!.use { stream ->
            val bytes = stream.readBytes()
            assertEquals(report, String(bytes, Charsets.UTF_8))
        }
    }

    @Test
    fun writeDiagnosticsFile_keepsTheSharedDirBounded() {
        val context = context()
        repeat(8) { i -> writeDiagnosticsFile(context, "report $i") }
        val files = File(context.cacheDir, "shared").listFiles().orEmpty()
        assertTrue(files.size <= 5, "shared dir unbounded: ${files.size} files")
    }
}
