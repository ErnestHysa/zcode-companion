package com.zcode.android.features.conversation

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.SendNoticeBar
import com.zcode.android.features.conversation.ui.sendNoticeContentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BE-4 (vc52 Phase 10): SendNoticeBar painted the text AND the ✕ in
 * onErrorContainer for EVERY kind — the INFO success "Trusted N hook(s) — the
 * session continues." rendered in error red on the neutral surface (GAP-A15 fixed
 * the CONTAINER only; the content colour stayed error). Colour is now by KIND via
 * the pinned seam: INFO reads onSurfaceVariant; WARNING/ERROR keep onErrorContainer.
 * (Compose semantics cannot read a painted text colour under Robolectric — the seam
 * pin IS the honest unit face; the LIVE face is the exit-gate leg.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SendNoticeColourTest {

    @get:Rule
    val rule = createComposeRule()

    private fun scheme(): ColorScheme {
        lateinit var captured: ColorScheme
        rule.setContent { ZCodeTheme { captured = MaterialTheme.colorScheme } }
        rule.waitForIdle()
        return captured
    }

    @Test
    fun `INFO content colour is the neutral onSurfaceVariant - NOT error red`() {
        val s = scheme()
        assertEquals(s.onSurfaceVariant, sendNoticeContentColor(SendNotice.Kind.INFO, s))
        assertNotEquals("the recorded face: INFO in error red", s.onErrorContainer, sendNoticeContentColor(SendNotice.Kind.INFO, s))
    }

    @Test
    fun `ERROR and WARNING keep the error-container content colour`() {
        val s = scheme()
        assertEquals(s.onErrorContainer, sendNoticeContentColor(SendNotice.Kind.ERROR, s))
        assertEquals(s.onErrorContainer, sendNoticeContentColor(SendNotice.Kind.WARNING, s))
    }

    @Test
    fun `the three kinds resolve to distinct faces (INFO vs the error family)`() {
        val s = scheme()
        assertNotEquals(sendNoticeContentColor(SendNotice.Kind.INFO, s), sendNoticeContentColor(SendNotice.Kind.ERROR, s))
    }

    private fun setContentFor(kind: SendNotice.Kind) {
        rule.setContent {
            ZCodeTheme { SendNoticeBar(SendNotice("Trusted 2 hook(s) — the session continues.", kind), onDismiss = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("send_notice").assertExists()
    }

    @Test fun `the composed bar renders for INFO`() = setContentFor(SendNotice.Kind.INFO)
    @Test fun `the composed bar renders for WARNING`() = setContentFor(SendNotice.Kind.WARNING)
    @Test fun `the composed bar renders for ERROR`() = setContentFor(SendNotice.Kind.ERROR)
}
