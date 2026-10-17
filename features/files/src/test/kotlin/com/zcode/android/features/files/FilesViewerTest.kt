package com.zcode.android.features.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceFileEntry
import com.zcode.android.domain.model.WorkspaceTextSlice
import com.zcode.android.features.files.port.FilesPort
import com.zcode.android.features.files.ui.FilesScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * I3+L6.5+H2.5+C4 (gaps-and-polish Phase 3E): the file viewer stops being a paged
 * monospace slab — line numbers, scroll-end prefetch (no manual "Load more" tap),
 * an honest oversize cap, image preview — and the browser goes LIVE (a poll fallback
 * for the absent file-watcher channel) plus pull-to-refresh (the viewer screens were
 * the C4 "static screen" family).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class FilesViewerTest {

    @get:Rule
    val rule = createComposeRule()

    private fun slice(content: String, offset: Long, read: Long, total: Long, binary: Boolean = false) = WorkspaceTextSlice(
        path = "/f.txt", content = content, offset = offset, bytesRead = read,
        totalBytes = total, truncated = false, isBinary = binary,
    )

    /** Two-chunk file: the first slice hasMore, the second completes it. */
    private class TwoChunkPort : FilesPort {
        var listCalls = 0
        val reads = mutableListOf<Long>()
        override fun workspaceRoot(): String? = "/ws"
        override suspend fun list(path: String): FilesPort.ListingResult {
            listCalls++
            return FilesPort.ListingResult(
                listOf(
                    WorkspaceFileEntry("f.txt", "/f.txt", isDirectory = false, isSymbolicLink = false),
                    WorkspaceFileEntry("shot.png", "/shot.png", isDirectory = false, isSymbolicLink = false),
                ),
                null,
            )
        }

        override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult {
            reads.add(offset ?: 0)
            return when (offset ?: 0) {
                0L -> FilesPort.TextResult(slice("a\nb\nc\n", 0, 4, 10), null)
                else -> FilesPort.TextResult(slice("d\ne\nf\n", 4, 6, 10), null)
            }
        }

        override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult =
            FilesPort.DownloadResult(null, 0, "unused in this test")
    }

    private fun openViewer(port: FilesPort, entry: String = "/f.txt") {
        rule.setContent { ZCodeTheme { FilesScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("files_row_$entry").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `line numbers render in a gutter`() {
        openViewer(TwoChunkPort())
        rule.onNodeWithTag("files_line_no_0", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `scroll-end prefetch loads the next chunk without a Load more tap`() {
        val port = TwoChunkPort()
        openViewer(port)
        assertEquals(1, port.reads.size)
        rule.onNodeWithTag("files_viewer_list", useUnmergedTree = true)
            .performScrollToItem(port.reads.size * 1000)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertTrue("the second chunk must auto-load on scroll end: $reads", port.reads.size >= 2)
        assertTrue(rule.onAllNodesWithTag("files_load_more", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `oversize files show the honest cap copy instead of endless load-more`() {
        val port = object : TwoChunkPort() {
            override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult =
                FilesPort.TextResult(slice("x", 0, 1, totalBytes = 4L * 1024 * 1024), null)
        }
        openViewer(port)
        rule.onNodeWithTag("files_preview_cap", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("files_load_more", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `image files preview as images - not binary - use Download`() {
        val port = object : TwoChunkPort() {
            // A real 1x1 PNG.
            private val png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
            )

            override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult =
                FilesPort.DownloadResult(File.createTempFile("shot", ".png").apply { writeBytes(png) }, png.size.toLong(), null)
        }
        openViewer(port, entry = "/shot.png")
        rule.onNodeWithTag("files_image_preview", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("files_binary_notice", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the download path copy is localized - no literals remain`() {
        val src = File(repoRoot(), "features/files/src/main/kotlin/com/zcode/android/features/files/ui/FilesScreen.kt").readText()
        assertTrue("\"downloading" !in src)
        assertTrue("downloaded " !in src.replace("DownloadUi.Done", ""))
        assertTrue("share sheet opened" !in src)
    }

    @Test
    fun `C4 - the listing poll keeps the browser live`() {
        val port = TwoChunkPort()
        rule.setContent { ZCodeTheme { FilesScreen(port = port) } }
        rule.waitForIdle()
        val initial = port.listCalls
        assertTrue(initial >= 1)
        rule.mainClock.advanceTimeBy(2 * 5_000L + 500)
        rule.waitForIdle()
        assertTrue("the poll must re-list while the screen is open: ${port.listCalls}", port.listCalls >= initial + 2)
    }

    @Test
    fun `H2.5 - the viewer screens carry pull-to-refresh and the poll`() {
        val root = repoRoot()
        for (f in listOf(
            "features/files/src/main/kotlin/com/zcode/android/features/files/ui/FilesScreen.kt",
            "features/git/src/main/kotlin/com/zcode/android/features/git/ui/GitScreen.kt",
            "app/src/main/kotlin/com/zcode/android/app/settings/UsageScreen.kt",
            "app/src/main/kotlin/com/zcode/android/app/settings/McpViewerScreen.kt",
            "app/src/main/kotlin/com/zcode/android/app/settings/PluginsViewerScreen.kt",
            "app/src/main/kotlin/com/zcode/android/app/settings/SkillsViewerScreen.kt",
        )) {
            val src = File(root, f).readText()
            assertTrue("$f must wrap its content in PullToRefreshBox", "PullToRefreshBox(" in src)
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir)
    }
}
