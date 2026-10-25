package com.zcode.android.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F6 (gaps-and-polish Phase 2G, P1; precision per M6.2): release infra —
 * (1) the adaptive icon's MONOCHROME layer (Android 13+ themed icons) is declared in
 * BOTH launcher icons; (2) R8 is ON (`isMinifyEnabled = false` shipped a 53MB debug
 * APK — the release build must shrink); (3) the triage keels over on neither.
 * (The baseline.prof leg is device-bound — see the phase record.)
 */
class ReleaseInfraTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    @Test
    fun `F6_1 - both launcher icons declare the monochrome layer`() {
        for (icon in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val s = File(root, "app/src/main/res/mipmap-anydpi-v26/$icon").readText()
            assertTrue("$icon must declare <monochrome>", Regex("""<monochrome\s+android:drawable=""").containsMatchIn(s))
        }
    }

    @Test
    fun `F6_2 - release builds through R8 with shrinkResources`() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue("isMinifyEnabled = true for release", Regex("""isMinifyEnabled\s*=\s*true""").containsMatchIn(gradle))
        assertTrue("shrinkResources for release", Regex("""isShrinkResources\s*=\s*true""").containsMatchIn(gradle))
        assertTrue("the optimized proguard profile", Regex("""getDefaultProguardFile\("proguard-android-optimize\.txt"\)""").containsMatchIn(gradle))
        val rules = File(root, "app/proguard-rules.pro").readText()
        assertTrue("kotlinx-serialization keep rules (the wire models)", "serializer(" in rules && "-keep,includedescriptorclasses" in rules)
    }

    /**
     * F-CRASH (field 2026-10-25, live-reproduced on the upgrade rig): the protobuf
     * runtime builds each message's schema by reflecting the message's DECLARED
     * FIELD NAMES (`MessageSchema` → `getDeclaredField("schemaVersion_")` …). R8
     * renames those fields, so the FIRST parse of a stored proto file crashes the
     * app at launch — invisible to every fresh install (nothing to parse), biting
     * only devices UPGRADED from an older build with data on disk (the field
     * report: 1.0.x → 1.2.0 over a stored credential envelope). The rules must
     * keep the protobuf runtime and every generated message's fields, verbatim.
     */
    @Test
    fun `protobuf keep rules survive R8 - stored envelopes parse on upgrade (F-CRASH)`() {
        val rules = File(root, "app/proguard-rules.pro").readText()
        assertTrue(
            "the protobuf runtime must be kept whole (its schema machinery reflects by name)",
            Regex("""-keep\s+class\s+com\.google\.protobuf\.\*\*\s*\{\s*\*;\s*\}""").containsMatchIn(rules),
        )
        assertTrue(
            "generated message fields must keep their names (schemaVersion_, entries, …)",
            Regex("""-keepclassmembers\s+class\s+\*\s+extends\s+com\.google\.protobuf\.GeneratedMessageLite\s*\{\s*<fields>;\s*\}""").containsMatchIn(rules),
        )
    }
}
