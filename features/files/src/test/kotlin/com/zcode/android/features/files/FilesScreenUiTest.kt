package com.zcode.android.features.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * GAP-15 (android-native-gaps.md): Files screen overhauled to desktop file-tree
 * affordances (WorkspaceFileTreeRowView family): per-file-type icons via the
 * extension/filename mapping, a chevron affordance on directories, a workspace
 * header, and BREADCRUMB navigation back up the visited chain — all on today's
 * existing port calls only (list/readText/download; INV-16 paths are tokens).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class FilesScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeFilesPort : FilesPort {
        var listCalls = 0
        var readCalls = 0
        override fun workspaceRoot(): String = "/ws/demo"

        override suspend fun list(path: String): FilesPort.ListingResult {
            listCalls++
            return when (path) {
                "/ws/demo" -> FilesPort.ListingResult(
                    listOf(
                        WorkspaceFileEntry("src", "/ws/demo/src", true, false),
                        WorkspaceFileEntry("logo.png", "/ws/demo/logo.png", false, false),
                        WorkspaceFileEntry("README.md", "/ws/demo/README.md", false, false),
                    ),
                    null,
                )
                "/ws/demo/src" -> FilesPort.ListingResult(
                    listOf(
                        WorkspaceFileEntry("main", "/ws/demo/src/main", true, false),
                        WorkspaceFileEntry("Main.kt", "/ws/demo/src/Main.kt", false, false),
                    ),
                    null,
                )
                "/ws/demo/src/main" -> FilesPort.ListingResult(
                    listOf(WorkspaceFileEntry("app.kt", "/ws/demo/src/main/app.kt", false, false)),
                    null,
                )
                else -> FilesPort.ListingResult(emptyList(), "not found: $path")
            }
        }

        override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult {
            readCalls++
            return FilesPort.TextResult(
                WorkspaceTextSlice(
                    path = path, content = "fun main() {}", offset = 0,
                    bytesRead = 13, totalBytes = 13, truncated = false, isBinary = false,
                ),
                null,
            )
        }

        override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult =
            FilesPort.DownloadResult(File("/tmp/x"), 1, null)
    }

    private fun setContent() {
        rule.setContent { ZCodeTheme { FilesScreen(port = fake) } }
        rule.waitForIdle()
    }

    private val fake = FakeFilesPort()

    @Test
    fun listing_showsFileTypeAffordances_dirsGetChevron_filesGetTypedIcon() {
        setContent()
        // Rows render…
        rule.onNodeWithText("src").assertIsDisplayed()
        rule.onNodeWithText("Main.kt").assertIsDisplayed()
        // …with kind semantics: folders announce themselves, typed files carry a kind too.
        rule.onNodeWithContentDescription("Folder", substring = true).assertExists()
        rule.onNodeWithContentDescription("Document", substring = true).assertExists()
        rule.onNodeWithContentDescription("Image", substring = true).assertExists()
    }

    @Test
    fun navigateIntoDir_openFile_viewerShowsText() {
        setContent()
        rule.onNodeWithText("src").performClick()
        rule.waitForIdle()
        // Breadcrumb grew: root + src segments are tappable.
        rule.onNodeWithTag("files_crumb_0", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Main.kt").performClick()
        rule.waitForIdle()
        assertEquals(1, fake.readCalls)
        rule.onNodeWithText("fun main() {}").assertIsDisplayed()
    }

    @Test
    fun breadcrumbTap_returnsToAncestor() {
        setContent()
        rule.onNodeWithText("src").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("main").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("app.kt").assertIsDisplayed()
        // Crumb 0 = the workspace root: tapping returns two levels in one tap.
        rule.onNodeWithTag("files_crumb_0", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("README.md").assertIsDisplayed()
        assertEquals(true, fake.listCalls >= 3)
    }

    @Test
    fun fileIconMapping_coversDesktopFamilies() {
        // Pure mapping table (port of the desktop resolveIconName families).
        assertEquals(FileKind.FOLDER, fileKind("anything", isDirectory = true))
        assertEquals(FileKind.IMAGE, fileKind("logo.png"))
        assertEquals(FileKind.IMAGE, fileKind("a.JPG"))
        assertEquals(FileKind.VIDEO, fileKind("clip.mp4"))
        assertEquals(FileKind.AUDIO, fileKind("tone.mp3"))
        assertEquals(FileKind.CODE, fileKind("Main.kt"))
        assertEquals(FileKind.CODE, fileKind("x.tsx"))
        assertEquals(FileKind.DATA, fileKind("cfg.json"))
        assertEquals(FileKind.DOCUMENT, fileKind("README.md"))
        assertEquals(FileKind.ARCHIVE, fileKind("bundle.tar.gz"))
        assertEquals(FileKind.DATABASE, fileKind("dump.sql"))
        assertEquals(FileKind.TERMINAL, fileKind("run.sh"))
        assertEquals(FileKind.DOCUMENT, fileKind("no-extension"))
        assertTrue(fileKind(".env.local") == FileKind.CONFIG)
        assertTrue(fileKind("Dockerfile") == FileKind.CONFIG)
    }
}
