package com.zcode.android.features.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceFileEntry
import com.zcode.android.domain.model.WorkspaceTextSlice
import com.zcode.android.features.files.port.FilesPort
import com.zcode.android.features.files.ui.FilesScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G78 (vc52 Phase 11) UI legs: long-press a viewer line opens the comment dialog
 * (the desktop's codeViewer.comment entry point), the Send-to-conversation button
 * fires the callback with the file path + the ABSOLUTE line + the comment text, and
 * a blank comment never fires (the confirm stays disabled).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class LineCommentDialogUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class OneFilePort : FilesPort {
        override fun workspaceRoot(): String? = "/ws"
        override suspend fun list(path: String): FilesPort.ListingResult =
            FilesPort.ListingResult(
                listOf(WorkspaceFileEntry("README.md", "/ws/README.md", isDirectory = false, isSymbolicLink = false)),
                null,
            )

        override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult =
            FilesPort.TextResult(slice("fun main() {\n    println(\"hi\")\n}\n", 0, 32, 32), null)

        override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult =
            FilesPort.DownloadResult(null, 0, "unused in this test")
    }

    private fun setContent(comments: ArrayList<Triple<String, Int, String>>) {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                FilesScreen(
                    port = OneFilePort(),
                    modifier = Modifier,
                    onLineComment = { path, line, comment -> comments.add(Triple(path, line, comment)) },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `long-press a line opens the dialog and send fires the callback`() {
        val comments = ArrayList<Triple<String, Int, String>>()
        setContent(comments)
        rule.onNodeWithTag("files_row_/ws/README.md").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("files_line_row_0").performTouchInput {
    down(center)
    advanceEventTime(1_500)
    up()
}
        rule.waitForIdle()
        rule.onNodeWithTag("files_comment_dialog")
        rule.onNodeWithTag("files_comment_field").performTextInput("the loop overflows here")
        rule.onNodeWithTag("files_comment_send").performClick()
        rule.waitForIdle()
        assertEquals(1, comments.size)
        assertEquals("/ws/README.md", comments[0].first)
        assertEquals(1, comments[0].second)
        assertEquals("the loop overflows here", comments[0].third)
    }

    @Test
    fun `a blank comment keeps the confirm disabled, never an empty send`() {
        val comments = ArrayList<Triple<String, Int, String>>()
        setContent(comments)
        rule.onNodeWithTag("files_row_/ws/README.md").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("files_line_row_0").performTouchInput {
    down(center)
    advanceEventTime(1_500)
    up()
}
        rule.waitForIdle()
        rule.onNodeWithTag("files_comment_send").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("files_comment_dialog").assertCountEquals(1) // the dialog stays open
        assertEquals(0, comments.size)
    }
}

private fun slice(content: String, offset: Long, read: Long, total: Long) = WorkspaceTextSlice(
    path = "/ws/README.md", content = content, offset = offset, bytesRead = read,
    totalBytes = total, truncated = false, isBinary = false,
)
