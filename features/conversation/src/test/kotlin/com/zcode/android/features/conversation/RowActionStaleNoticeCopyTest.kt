package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.sendNoticeText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BT-1 (pass-0 Phase 7) — the stale ROW-ACTION banner rendered the SWITCH copy.
 * `rowActionNotice`'s STALE branch passes the correct per-path message ("try again")
 * with the SHARED `SendNotice.Key.SESSION_CHANGED`, but the banner renderer resolves
 * BY KEY (`notice_session_changed` = "…— try the **switch** again.") and discards the
 * message — so every stale row action (feedback/retry/fork/rewind) rendered switch
 * copy (pass-37 live receipt on the feedback rail). The fix: the row-action stale
 * carries its OWN key (`ROW_ACTION_STALE` → `notice_row_action_stale`).
 *
 * This file: the preserved switch-copy regression arm + the source shape (both
 * compile against the pre-fix tree; the source pin is the RED). The behavioral
 * no-switch pin lives at the ViewModel level (Pass0Phase7ComposerUploadVmTest —
 * the feedback-stale notice); the new-key render pin lands with the fix
 * (Pass0Phase7RowActionStaleRenderTest — it names the new enum).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RowActionStaleNoticeCopyTest {

    @get:Rule
    val rule = createComposeRule()

    private fun rendered(notice: SendNotice): String {
        lateinit var text: String
        rule.setContent { ZCodeTheme { text = sendNoticeText(notice) } }
        rule.waitForIdle()
        return text
    }

    @Test
    fun `N-BT-1 - the switch surfaces keep their switch copy (regression arm)`() {
        val text = rendered(
            SendNotice(
                "The session changed on the desktop — try the switch again.",
                SendNotice.Kind.WARNING,
                SendNotice.Key.SESSION_CHANGED,
            ),
        )
        assertTrue(
            "the mode/effort/model stale banner is a real switch face: $text",
            text.contains("switch", ignoreCase = true),
        )
    }

    @Test
    fun `N-BT-1 - rowActionNotice emits the ROW-ACTION stale key, and the four call sites stay on the helper`() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        val source = java.io.File(
            root,
            "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ConversationViewModel.kt",
        ).readText()
        val helperRegion = source.substringAfter("private fun rowActionNotice(")
            .substringBefore("\n    /**")
        assertTrue(
            "rowActionNotice's STALE branch must emit its own ROW_ACTION stale key (the shared SESSION_CHANGED key renders switch copy BY KEY)",
            "SendNotice.Key.ROW_ACTION_STALE" in helperRegion,
        )
        assertTrue(
            "the honest per-path copy invites a retry",
            "try again" in helperRegion,
        )
        val callSites = Regex("""rowActionNotice\(""").findAll(source).count() - 1
        assertTrue(
            "the four row-action call sites (retry/fork/rewind/feedback) must stay on the helper (found $callSites)",
            callSites >= 4,
        )
    }
}
