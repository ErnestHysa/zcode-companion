package com.zcode.android.features.files

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceFileEntry
import com.zcode.android.features.files.port.FilesPort
import com.zcode.android.features.files.ui.FilesScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C6-state (gaps-and-polish Phase 2F, P1): no SavedStateHandle/rememberSaveable
 * anywhere — the files viewer state (open file + navigation stack) died on process
 * death. Pinned: the viewer survives the restore.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class StateRestorationTest {

    @get:Rule
    val rule = createComposeRule()

    private val restorer = StateRestorationTester(rule)

    private val port = object : FilesPort {
        override fun workspaceRoot(): String = "/ws/demo"
        override suspend fun list(path: String): FilesPort.ListingResult = FilesPort.ListingResult(
            listOf(WorkspaceFileEntry("README.md", "/ws/demo/README.md", false, false)), null,
        )
        override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult =
            FilesPort.TextResult(null, "stub")
        override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult =
            FilesPort.DownloadResult(null, 0L, null)
    }

    @Test
    fun `the open file viewer survives process death`() {
        restorer.setContent { ZCodeTheme { FilesScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("files_row_/ws/demo/README.md").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("files_viewer", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertTrue("the viewer must survive the restore", rule.onAllNodesWithTag("files_viewer", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
