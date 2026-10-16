package com.zcode.android.features.git

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.git.ui.GitScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K1.2 + N10-radius (Phase 2A, P1; pixel-found N1): the Git "Retry" button rendered as a
 * FULL PILL (M3's 50% Button default). Pixel pin: with the desktop `lg` step (8dp) the
 * flat top edge reaches x = 8dp+1; a full pill leaves that pixel empty.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GitRetryPillPinTest {

    @get:Rule
    val rule = createComposeRule()

    private class FailingChangesPort : GitPort {
        override suspend fun summary(): GitSummary? = GitSummary(
            repoRoot = "/ws", branchName = "main", trackingBranchName = "origin/main",
            headRefType = "branch", ahead = 0, behind = 0, isDirty = false,
            isGitAvailable = true, isRepository = true,
        )
        override suspend fun changes(sourceId: String): List<GitChangeEntry> = throw IllegalStateException("git channel refused")
        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null
        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage = GitPort.CommitsPage(emptyList<GitCommit>(), false)
        override fun workspacePath(): String? = "/ws"
    }

    @Test
    fun `git Retry is a desktop-radius control - not a full pill`() {
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            ZCodeTheme { GitScreen(port = FailingChangesPort()) }
        }
        rule.waitForIdle()
        val img = rule.onNodeWithTag("git_changes_retry").captureToImage()
        val pm = img.toPixelMap()
        val x = (8f * density + 1).toInt()
        val topCenter = pm[img.width / 2, 0].toArgb()
        assertEquals("the flat top edge must reach x=${x}px (a full pill leaves it empty)", topCenter, pm[x, 0].toArgb())
        assertNotEquals("the corner must still be rounded", topCenter, pm[0, 0].toArgb())
    }
}
