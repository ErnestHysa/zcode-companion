package com.zcode.android.features.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * L2 (String-fix family, Phase 2D): the Terminal consent sentence was GLUED and
 * HALF-HARDCODED — `stringResource(terminal_consent_body) + "" + "your machine. It is
 * view-only…"` rendered "…at any time.your machine." (two sentences fused, the lead-in
 * lost its first half, and the second fragment could never localize).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TerminalConsentCopyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the consent body is one whole localized sentence`() {
        val body = context.getString(R.string.terminal_consent_body)
        assertTrue("the lead-in must carry 'on your machine': $body", body.contains("on your machine"))
        assertTrue("the view-only clause must be part of the resource: $body", body.contains("view-only"))
        assertFalse("no glued sentence boundary: $body", body.contains("time.your"))
    }

    @Test
    fun `the screen no longer concatenates hardcoded English onto the resource`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val src = File(dir, "features/terminal/src/main/kotlin/com/zcode/android/features/terminal/ui/TerminalScreen.kt").readText()
        assertFalse("hardcoded fragment glued onto terminal_consent_body", src.contains("\"your machine. It is view-only"))
        assertFalse("the `+ \"\"` no-op spacer must be gone", src.contains("+\n                \"\" +") || src.contains("+ \"\" +"))
    }
}
