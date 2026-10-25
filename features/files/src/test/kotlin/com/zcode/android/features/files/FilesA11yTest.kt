package com.zcode.android.features.files

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceFileEntry
import com.zcode.android.features.files.port.FilesPort
import com.zcode.android.features.files.ui.FilesScreen
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** B3-a11y (Phase 2E): files rows measured ~42dp; rows must be ≥48dp and carry a role. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class FilesA11yTest {

    @get:Rule
    val rule = createComposeRule()

    private val port = object : FilesPort {
        override fun workspaceRoot(): String = "/ws/demo"
        override suspend fun list(path: String): FilesPort.ListingResult = FilesPort.ListingResult(
            listOf(WorkspaceFileEntry("README.md", "/ws/demo/README.md", false, false)), null,
        )
        override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult = FilesPort.TextResult(null, null)
        override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult = FilesPort.DownloadResult(null, 0L, null)
    }

    @Test
    fun `file rows are 48dp targets with a role`() {
        rule.setContent { ZCodeTheme { FilesScreen(port = port) } }
        rule.waitForIdle()
        val row = rule.onNodeWithTag("files_row_/ws/demo/README.md").fetchSemanticsNode()
        val h = row.boundsInRoot.height / rule.density.density
        assertTrue("files row ${h}dp tall (needs 48)", h >= 48f)
        assertNotNull("files row must carry a role", row.config.getOrNull(SemanticsProperties.Role))
    }
}
