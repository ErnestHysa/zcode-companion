package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.RewindPreview
import com.zcode.android.features.conversation.ui.TurnSeparator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G72 (vc52 Phase 12) compose leg: the rewind change-summary sheet renders the
 * desktop's rewindDialog (chat.changeSummary.rewindDialog.*) — THREE titled
 * sections with counts, the LOCALIZED reason per unsafe/ignored file, "{n} edits"
 * per file, and every refusal face: cannotApply (Confirm disabled), noSafeFiles,
 * noUnsafeFiles. The restore only ever sends from the sheet's Confirm.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RewindChangeSummaryUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun turnRow(rowId: Long) = ConversationRow.Decoder.decode(
        Json.parseToJsonElement(
            """{"rowId":$rowId,"turnId":"t1","kind":"turnHeader","entityId":"ent-turn-$rowId",
                "createdAt":1700000000000,"createdAtSeq":$rowId,"origin":"userInput","state":"completedSuccess",
                "startedAt":1700000000000,
                "fileChanges":{"additions":3,"deletions":1,"files":2,"state":"active"},
                "actions":{"canRewindFiles":true}}""",
        ).jsonObject,
    ) as ConversationRow.TurnHeader

    private fun preview(
        canApply: Boolean = true,
        safe: Int = 2,
        unsafe: List<String> = listOf("external_modified", "checkpoint_missing"),
        ignored: Int = 1,
    ) = RewindPreview(
        canApply = canApply,
        safeFiles = (1..safe).map { RewindPreview.FileAction(if (it == 2) "delete" else "restore", "src/file$it.ts", it, listOf("edit")) },
        unsafeFiles = unsafe.mapIndexed { i, reason ->
            RewindPreview.UnsafeFile("src/unsafe$i.ts", reason, null, 2, emptyList())
        },
        ignoredFiles = (1..ignored).map { RewindPreview.IgnoredFile("tmp/build$it.log", "bash_ignored", 1) },
    )

    private fun open(p: RewindPreview) {
        calls.clear()
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TurnSeparator(
                    row = turnRow(30),
                    onRewindFiles = { calls.add("rewind") },
                    rewindPreviewProvider = { _, _ -> p },
                )
            }
        }
        rule.onNodeWithTag("row_turn_rewind").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("rewind_preview").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `both lists render with counts and the reason per file`() {
        open(preview())
        rule.onNodeWithTag("rewind_safe_title").assertIsDisplayed()
        rule.onNodeWithText("Safe to rewind 2").assertExists()
        rule.onNodeWithTag("rewind_unsafe_title").assertIsDisplayed()
        rule.onNodeWithText("Cannot safely rewind 2").assertExists()
        // The localized reason per unsafe file (the recorded external_modified face).
        rule.onNodeWithText("file changed since the checkpoint").assertExists()
        rule.onNodeWithText("checkpoint missing").assertExists()
        rule.onAllNodesWithTag("rewind_unsafe_external_modified").assertCountEquals(1)
        // "{n} edits" per safe file.
        rule.onNodeWithText("1 edit(s)", substring = true).assertExists()
    }

    @Test
    fun `the ignored section renders per-file with the bash reason`() {
        open(preview(ignored = 1))
        rule.onNodeWithTag("rewind_ignored_title").assertIsDisplayed()
        rule.onNodeWithText("Ignored 1").assertExists()
        rule.onNodeWithTag("rewind_ignored_file").assertExists()
        rule.onNodeWithText("bash/shell changes ignored").assertExists()
    }

    @Test
    fun `cannotApply renders the face and the Confirm never fires`() {
        open(preview(canApply = false))
        rule.onNodeWithTag("rewind_sheet_scroll").performScrollToNode(hasTestTag("rewind_cannot_apply"))
        rule.waitForIdle()
        rule.onNodeWithTag("rewind_cannot_apply").assertIsDisplayed()
        rule.onNodeWithText("This rewind cannot be applied safely — nothing will change.", substring = true).assertExists()
        rule.onNodeWithTag("rewind_sheet_scroll").performScrollToNode(hasTestTag("rewind_confirm"))
        rule.onNodeWithTag("rewind_confirm").performClick()
        rule.waitForIdle()
        assertTrue("a !canApply preview refuses to send — the desktop gate", calls.isEmpty())
    }

    @Test
    fun `noSafeFiles face renders when the safe list is empty`() {
        open(preview(safe = 0, unsafe = listOf("external_modified")))
        rule.onNodeWithTag("rewind_no_safe_files").assertIsDisplayed()
        rule.onNodeWithText("No files can be safely rewound.").assertExists()
    }

    @Test
    fun `noUnsafeFiles face renders when the unsafe list is empty`() {
        open(preview(unsafe = emptyList()))
        rule.onNodeWithTag("rewind_no_unsafe_files").assertIsDisplayed()
        rule.onNodeWithText("No unsafe files found.").assertExists()
    }

    @Test
    fun `the restore sends only from the Confirm (opening the sheet never sends)`() {
        open(preview())
        assertTrue("opening the sheet + loading the preview sends nothing", calls.isEmpty())
        rule.onNodeWithTag("rewind_sheet_scroll").performScrollToNode(hasTestTag("rewind_confirm"))
        rule.onNodeWithTag("rewind_confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf("rewind"), calls)
    }
}
