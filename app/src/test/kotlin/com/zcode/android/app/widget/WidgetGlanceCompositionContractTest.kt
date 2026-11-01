package com.zcode.android.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RIG-4's rig-caught render defect (vc51 Phase 5): binding the glance widget on the
 * rig crashed its provideGlance composition with
 * `IllegalStateException: CompositionLocal LocalContext not present` at
 * PendingApprovalsWidget.Content — the composable read
 * `androidx.compose.ui.platform.LocalContext` (the COMPOSE-UI local, provided only in
 * real UI compositions), which is NEVER present inside a Glance composition. The
 * launcher showed the "Problem loading widget" placeholder and the tap intent was
 * dead. The contract: the widget composition threads the provideGlance `context`
 * PARAMETER and never reads a compose-UI composition local. (Structural pin — the
 * Glance composition has no Robolectric harness; the live rendering + tap deep-link
 * leg is the exit-gate receipt.)
 */
class WidgetGlanceCompositionContractTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    private val source: String by lazy {
        File(root, "app/src/main/kotlin/com/zcode/android/app/widget/PendingApprovalsWidget.kt").readText()
    }

    @Test
    fun `the widget composition never reads the compose-UI LocalContext (the crash vector)`() {
        assertFalse(
            "PendingApprovalsWidget must not read androidx.compose.ui.platform.LocalContext — " +
                "it is absent in Glance compositions (the rig-caught 'Problem loading widget' crash)",
            Regex("""androidx\.compose\.ui\.platform\.LocalContext""").containsMatchIn(source),
        )
    }

    @Test
    fun `the composition threads the provideGlance context parameter into Content`() {
        assertTrue(
            "provideGlance must thread its context parameter into the composition (no composition-local read)",
            Regex("""provideContent \{ Content\(context,""").containsMatchIn(source),
        )
    }
}
