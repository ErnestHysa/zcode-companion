package com.zcode.android.features.terminal

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * G104 (pass-0 Phase 9) copy census: the SHIPPED COPY contradicted ADR-022 —
 * "It is view-only (nothing you type can reach the shell)" (consent body) and
 * "Nothing can be typed into it" (the Settings sub-copy) described the pre-ADR
 * posture the accepted ADR superseded. With the tier shipped, the copy states
 * the honest, gated posture.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TerminalInputCopyCensusTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(dir, relative)
    }

    @Test
    fun `the Settings sub-copy no longer claims nothing can be typed`() {
        val settings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertFalse(
            "the absolute view-only lie is gone: ${settings.contains("Nothing can be typed into it")}",
            settings.contains("Nothing can be typed into it"),
        )
    }

    @Test
    fun `the consent body states the honest gated posture`() {
        val body = context.getString(R.string.terminal_consent_body)
        assertTrue("still names the read posture: $body", body.contains("view-only"))
        assertFalse(
            "the absolute 'nothing you type can reach the shell' claim is gone: $body",
            body.contains("nothing you type can reach the shell"),
        )
        assertTrue("names the credential gate: $body", body.contains("credential"))
        assertTrue("names the arm: $body", body.contains("arm"))
    }

    @Test
    fun `the armed-tier strings exist (badge banner arm composer ctrl-c)`() {
        val strings = repoFile("features/terminal/src/main/res/values/strings.xml").readText()
        for (name in listOf(
            "terminal_input_armed_badge", "terminal_input_armed_banner",
            "terminal_arm_input", "terminal_composer_label", "terminal_ctrl_c",
        )) {
            assertTrue("missing $name", strings.contains("name=\"$name\""))
        }
        for (name in listOf("terminal_arm_input", "terminal_composer_label", "terminal_input_armed_banner")) {
            assertTrue("missing zh-rCN $name", repoFile("features/terminal/src/main/res/values-zh-rCN/strings.xml").readText().contains("name=\"$name\""))
        }
    }
}
