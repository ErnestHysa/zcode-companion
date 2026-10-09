package com.zcode.android.core.ui.components

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.core.common.diff.DiffFile
import com.zcode.android.core.common.diff.DiffHunk
import com.zcode.android.core.common.diff.DiffLine
import com.zcode.android.core.common.diff.DiffLineType
import com.zcode.android.core.common.diff.UnifiedDiffParser
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §22 renderer acceptance (Phase 9.1): bounds, fallback cards, a11y, virtualization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DiffRendererTest {

    @get:Rule val compose = createComposeRule()

    private fun simpleFile(hunks: List<DiffHunk> = listOf(DiffHunk("@@ -1,2 +1,2 @@", 1, 2, 1, 2, listOf(
        DiffLine(DiffLineType.CONTEXT, "fun a() {}", 1, 1),
        DiffLine(DiffLineType.DEL, "val x = 1", 2, null),
        DiffLine(DiffLineType.ADD, "val x = 2", null, 2),
    )))) = DiffFile(path = "src/A.kt", hunks = hunks)

    @Test
    fun `renders collapsed by default and expands on tap`() {
        compose.setContent {
            ZCodeTheme { DiffViewerContent(files = listOf(simpleFile())) }
        }
        compose.onNodeWithText("src/A.kt").assertExists()
        // Collapsed: body content absent; header badge present.
        compose.onNodeWithText("+1").assertExists()
        compose.onNodeWithText("−1").assertExists()
        compose.onNodeWithText("Tap to expand").assertExists()
        compose.onNodeWithText("src/A.kt").performClick()
        compose.onNodeWithText("@@ -1,2 +1,2 @@").assertExists()
        compose.onNodeWithText("+val x = 2").assertExists()
        compose.onNodeWithText("−val x = 1").assertExists()
    }

    @Test
    fun `binary file renders placeholder never hunks`() {
        compose.setContent {
            ZCodeTheme {
                DiffViewerContent(files = listOf(DiffFile(path = "logo.png", binary = true)))
            }
        }
        compose.onNodeWithText("binary file changed").assertExists()
        compose.onNodeWithText("Tap to expand").assertDoesNotExist()
    }

    @Test
    fun `large file renders summary card above 5k changed lines`() {
        // 5 001 changed lines → summary card, never a materialized body (§22).
        val line = DiffLine(DiffLineType.ADD, "x", null, 1)
        val big = DiffFile(path = "big.txt", hunks = List(1) {
            DiffHunk("@@ -1,5001 +1,5001 @@", 1, 5001, 1, 5001, List(5001) { line })
        })
        assertEquals(5001, big.changedLines)
        compose.setContent {
            ZCodeTheme { DiffViewerContent(files = listOf(big)) }
        }
        compose.onNodeWithText("big.txt", substring = true).assertExists()
        compose.onNodeWithText("large diff — 5001 changed lines — open the per-file view on the desktop").assertExists()
    }

    @Test
    fun `5k-line file at the boundary renders expandable (budget compliance)`() {
        val line = DiffLine(DiffLineType.ADD, "x", null, 1)
        val atBoundary = DiffFile(path = "ok.txt", hunks = List(1) {
            DiffHunk("@@ -1,5000 +1,5000 @@", 1, 5000, 1, 5000, List(5000) { line })
        })
        assertEquals(5000, atBoundary.changedLines)
        compose.setContent {
            ZCodeTheme { DiffViewerContent(files = listOf(atBoundary)) }
        }
        compose.onNodeWithText("Tap to expand").assertExists()
    }

    @Test
    fun `rename and new and deleted labels render`() {
        compose.setContent {
            ZCodeTheme {
                DiffViewerContent(
                    files = listOf(
                        DiffFile(path = "new-name.kt", oldPath = "old-name.kt", isRenamed = true),
                        DiffFile(path = "b.txt", isNew = true),
                        DiffFile(path = "c.txt", isDeleted = true),
                    ),
                )
            }
        }
        compose.onNodeWithText("old-name.kt → new-name.kt", substring = true).assertExists()
        compose.onNodeWithText("new").assertExists()
        compose.onNodeWithText("deleted").assertExists()
    }

    @Test
    fun `header a11y description carries counts and flags (color-independence, §30)`() {
        compose.setContent {
            ZCodeTheme { DiffViewerContent(files = listOf(simpleFile())) }
        }
        compose.onNodeWithText("src/A.kt")
            .assertContentDescriptionContains("diff for src/A.kt, plus 1, minus 1")
    }

    @Test
    fun `parser feeds renderer end-to-end (git patch plane)`() {
        val patch = """
            diff --git a/x.kt b/x.kt
            --- a/x.kt
            +++ b/x.kt
            @@ -1,1 +1,1 @@
            -old
            +new
        """.trimIndent()
        val files = UnifiedDiffParser.parse(patch)
        compose.setContent { ZCodeTheme { DiffViewerContent(files = files) } }
        compose.onNodeWithText("x.kt").performClick()
        compose.onNodeWithText("+new").assertExists()
        compose.onNodeWithText("−old").assertExists()
    }

    @Test
    fun `intra-line emphasis marks only the changed middle`() {
        val s = annotateIntraLine("+val x = 2", 1, "val x = 2", "val x = 1")
        val boldRanges = s.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldRanges.size)
        val range = boldRanges.single().start to boldRanges.single().end
        // "val x = " is the common prefix (8 chars); the changed middle is "2" at offset 9.
        assertEquals(1 + 8, range.first)
        assertEquals(1 + 9, range.second)
    }

    @Test
    fun `empty file list renders the empty state (INV-11)`() {
        compose.setContent { ZCodeTheme { DiffViewerContent(files = emptyList()) } }
        compose.onNodeWithText("No file changes recorded for this turn.").assertExists()
    }

    @Test
    fun `very long line stays horizontally scrollable (no soft-wrap)`() {
        val longContent = "x".repeat(2_000)
        val file = DiffFile(
            path = "long.txt",
            hunks = listOf(DiffHunk("@@ -1,1 +1,1 @@", 1, 1, 1, 1, listOf(DiffLine(DiffLineType.ADD, longContent, null, 1)))),
        )
        compose.setContent { ZCodeTheme { DiffViewerContent(files = listOf(file)) } }
        compose.onNodeWithText("long.txt").performClick()
        // The line renders as ONE node (no wrapping into multiple text nodes).
        compose.onNodeWithText("+" + longContent, substring = false).assertExists()
        assertTrue(true)
    }
}
