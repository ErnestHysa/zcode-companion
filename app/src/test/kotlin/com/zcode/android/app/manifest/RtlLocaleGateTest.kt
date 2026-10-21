package com.zcode.android.app.manifest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M3-RTL (Phase 6D): the recorded decision — `supportsRtl` stays OFF while only LTR
 * locales ship. The tripwire: if an RTL locale directory ever appears in the app's
 * res/, the manifest MUST declare supportsRtl (and the AutoMirrored icon audit runs);
 * the build fails here instead of discovering the lock at runtime.
 */
class RtlLocaleGateTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    private val rtlDirs = Regex("""values-(ar|he|fa|ur)(-r[A-Z]{2})?$""")

    @Test
    fun `no RTL locale ships while supportsRtl is off - and the decision is recorded`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val rtlLocaleDirs = File(root, "app/src/main/res")
            .listFiles { f -> rtlDirs.containsMatchIn(f.name) }.orEmpty().toList()
        if (rtlLocaleDirs.isEmpty()) {
            assertTrue(
                "the M3-RTL decision must stay recorded next to the manifest",
                manifest.contains("M3-RTL"),
            )
            assertTrue(
                "supportsRtl must stay absent while only LTR locales ship (the recorded decision)",
                !manifest.contains("android:supportsRtl"),
            )
        } else {
            // An RTL locale landed: the gate flips — the flag must now be present.
            assertTrue(
                "RTL locale(s) ${rtlLocaleDirs.map { it.name }} shipped without android:supportsRtl — the M3-RTL decision requires the flag + the AutoMirrored icon audit first",
                manifest.contains("android:supportsRtl=\"true\""),
            )
        }
    }
}
