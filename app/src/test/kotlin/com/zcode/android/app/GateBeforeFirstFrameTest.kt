package com.zcode.android.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C6-state (Phase 2F): the cold-start pairing flash — `startDestination` read a live
 * flow before §11.6's active-endpoint selection had run (`ZCodeApp.kt`), so a
 * DIRECT-only device briefly showed Pairing. Contract: the hub exposes `gateResolved`
 * (flipped in a `finally` after the selection, never stranded), and MainActivity holds
 * the splash screen on it — the first composed frame reads a settled gate.
 */
class GateBeforeFirstFrameTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    @Test
    fun `the hub resolves its gate in a finally after the active-endpoint selection`() {
        val hub = File(root, "app/src/main/kotlin/com/zcode/android/app/connect/ConnectionHub.kt").readText()
        assertTrue("gateResolved StateFlow", "val gateResolved: StateFlow<Boolean>" in hub)
        assertTrue(
            "the gate must flip in a finally around the selection (never stranded on failure)",
            Regex("""try \{\s*resolveActiveEndpoint\(\)\s*\} finally \{\s*_gateResolved\.value = true""").containsMatchIn(hub),
        )
    }

    @Test
    fun `MainActivity holds the splash until the gate resolves`() {
        val main = File(root, "app/src/main/kotlin/com/zcode/android/app/MainActivity.kt").readText()
        assertTrue("installSplashScreen must be captured", "val splash = installSplashScreen()" in main)
        assertTrue(
            "keep-on-screen must be bound to hub.gateResolved",
            Regex("""setKeepOnScreenCondition \{ !container\.hub\.gateResolved\.value \}""").containsMatchIn(main),
        )
        assertTrue("the hold must be set after hub.start()", main.indexOf("container.hub.start()") < main.indexOf("setKeepOnScreenCondition"))
    }
}
