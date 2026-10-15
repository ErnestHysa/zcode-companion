package com.zcode.android.features.files

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
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B2.16 (gaps-and-polish Phase 1E, P0): the download/notice state machine was
 * STRING-KEYED — display strings like "downloading…" doubled as state, and the
 * binary dialog routed on `downloadState.startsWith("binary")`. The states are
 * now typed; the pins lock every rendered branch (binary notice, failure,
 * downloaded bytes) through honest tags.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class FilesDownloadStateTest {

    private class FakeFilesPort : FilesPort {
        var binaryFile = "assets/data.bin"
        var downloadResult: DownloadResult = DownloadResult(file = null, bytes = 0, error = "boom")

        override fun workspaceRoot(): String? = "/ws"
        override suspend fun list(path: String): ListingResult {
            delay(20)
            return if (path == "/ws") {
                ListingResult(
                    listOf(
                        WorkspaceFileEntry("data.bin", "/ws/assets/data.bin", isDirectory = false, isSymbolicLink = false),
                        WorkspaceFileEntry("readme.md", "/ws/readme.md", isDirectory = false, isSymbolicLink = false),
                    ),
                    error = null,
                )
            } else {
                ListingResult(emptyList(), error = null)
            }
        }

        override suspend fun readText(path: String, offset: Long?, length: Long?): TextResult {
            delay(20)
            return if (path.endsWith(".bin")) {
                TextResult(
                    WorkspaceTextSlice(
                        path = path, content = "", offset = 0, bytesRead = 0,
                        totalBytes = 1024, truncated = false, isBinary = true,
                    ),
                    error = null,
                )
            } else {
                TextResult(
                    WorkspaceTextSlice(
                        path = path, content = "hello world", offset = 0, bytesRead = 11,
                        totalBytes = 11, truncated = false, isBinary = false,
                    ),
                    error = null,
                )
            }
        }

        override suspend fun download(path: String, maxBytes: Long): DownloadResult = downloadResult
    }

    private fun setContent(port: FakeFilesPort) {
        rule.setContent {
            ZCodeTheme { FilesScreen(port = port) }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("files_row_/ws/assets/data.bin").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @get:Rule
    val rule = createComposeRule()

    private fun openBinaryFile(port: FakeFilesPort) {
        rule.onNodeWithTag("files_row_/ws/assets/data.bin").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("files_binary_notice").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a binary file shows the use-download notice - typed, not a string prefix match`() {
        val port = FakeFilesPort()
        setContent(port)
        openBinaryFile(port)

        assertTrue(
            "the binary notice must render (typed Binary state)",
            rule.onAllNodesWithTag("files_binary_notice").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `backing out clears the notice - the dialog never string-matches`() {
        val port = FakeFilesPort()
        setContent(port)
        openBinaryFile(port)

        rule.onNodeWithText("Back").performClick()
        rule.waitForIdle()
        assertEquals(
            0,
            rule.onAllNodesWithTag("files_binary_notice").fetchSemanticsNodes().size,
        )
        assertEquals(
            "no download dialog while a viewer is open",
            0,
            rule.onAllNodesWithTag("files_download_dialog").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a failed download renders its honest error`() {
        val port = FakeFilesPort()
        setContent(port)
        openBinaryFile(port)

        rule.onNodeWithTag("files_download_button").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("files_download_state").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "the failure must surface (never silent)",
            rule.onAllNodesWithTag("files_download_state").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `a successful download reports the byte count`() {
        val port = FakeFilesPort()
        port.downloadResult = FilesPort.DownloadResult(file = File("/tmp/x"), bytes = 2048, error = null)
        setContent(port)
        openBinaryFile(port)

        rule.onNodeWithTag("files_download_button").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("downloaded 2048 bytes — share sheet opened")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
