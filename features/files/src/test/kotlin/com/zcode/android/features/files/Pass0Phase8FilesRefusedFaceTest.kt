package com.zcode.android.features.files

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceFileEntry
import com.zcode.android.domain.model.WorkspaceTextSlice
import com.zcode.android.features.files.port.FilesPort
import com.zcode.android.features.files.ui.FilesScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P0-C (pass-0 Phase 8; 3 receipts — Parts A, E.A2.6, F.A2.5): the text viewer
 * rendered a SILENT BLANK PANEL on a refused read — `readText` returning a null slice
 * (fault.file.notFound / a desktop refusal) left `textState` empty and the viewer
 * rendered its empty LazyColumn with only the Back/Download chips: no error, no retry.
 * The fix: a typed error face with a Retry affordance inside the viewer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase8FilesRefusedFaceTest {

    @get:Rule
    val rule = createComposeRule()

    /** readText refuses every read (the receipted fault class: slice null + error set). */
    private class RefusingPort : FilesPort {
        var readCalls = 0
        override fun workspaceRoot(): String = "/ws/demo"

        override suspend fun list(path: String): FilesPort.ListingResult =
            FilesPort.ListingResult(
                listOf(WorkspaceFileEntry("Main.kt", "/ws/demo/Main.kt", false, false)),
                null,
            )

        override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult {
            readCalls++
            return FilesPort.TextResult(slice = null, error = "fault.file.notFound")
        }

        override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult =
            FilesPort.DownloadResult(null, 0, "refused")
    }

    private val fake = RefusingPort()

    @Test
    fun `F-P0-C - a refused read renders the typed error face with retry - never a silent blank panel`() {
        rule.setContent { ZCodeTheme { FilesScreen(port = fake) } }
        rule.waitForIdle()
        rule.onNodeWithText("Main.kt").performClick()
        rule.waitForIdle()
        assertEquals(1, fake.readCalls)
        // The typed error face renders INSIDE the viewer — the blank-panel face is dead.
        rule.onNodeWithTag("files_text_error").assertIsDisplayed()
        // The retry affordance is present and fires the read again.
        rule.onNodeWithTag("files_text_retry", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("files_text_retry", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue("the retry re-fires the refused read", fake.readCalls >= 2)
    }

    @Test
    fun `F-P0-C - a recovered read clears the error face and renders the slice`() {
        val flaky = object : FilesPort {
            var calls = 0
            override fun workspaceRoot(): String = "/ws/demo"

            override suspend fun list(path: String): FilesPort.ListingResult =
                FilesPort.ListingResult(
                    listOf(WorkspaceFileEntry("Main.kt", "/ws/demo/Main.kt", false, false)),
                    null,
                )

            override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult {
                calls++
                return if (calls == 1) {
                    FilesPort.TextResult(slice = null, error = "fault.file.notFound")
                } else {
                    FilesPort.TextResult(
                        WorkspaceTextSlice(
                            path = path, content = "fun main() {}", offset = 0,
                            bytesRead = 13, totalBytes = 13, truncated = false, isBinary = false,
                        ),
                        null,
                    )
                }
            }

            override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult =
                FilesPort.DownloadResult(File("/tmp/x"), 1, null)
        }
        rule.setContent { ZCodeTheme { FilesScreen(port = flaky) } }
        rule.waitForIdle()
        rule.onNodeWithText("Main.kt").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("files_text_error").assertIsDisplayed()
        // The retry succeeds: the error face clears and the content renders.
        rule.onNodeWithTag("files_text_retry", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("files_text_error").assertCountEquals(0)
        rule.onNodeWithText("fun main() {}").assertIsDisplayed()
    }
}
