package com.zcode.android.features.git

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.CheckpointMeta
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.git.ui.GitScreen
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A3.11 (Phase 5B) UI pins: the checkpoints card creates (the meta lands in the session
 * list with the created notice), the restore offers ONLY for a non-head checkpoint behind
 * the warned confirm, the conflict result surfaces the second (force) confirm, and the
 * empty face renders when creation is refused.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class CheckpointsCardUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private var createdCount = 0
    private var conflictOnFirstRestore = false

    private fun port(hostCheckpoints: List<CheckpointMeta> = emptyList()): GitPort = object : GitPort {
        override suspend fun listCheckpoints(): List<CheckpointMeta> = hostCheckpoints
        override suspend fun summary(): GitSummary? = null
        override suspend fun changes(sourceId: String) = emptyList<GitChangeEntry>()
        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null
        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage? = null
        override fun workspacePath(): String? = "/ws"
        override suspend fun createCheckpoint(): CheckpointMeta {
            calls += "create"
            createdCount += 1
            return CheckpointMeta("ckpt-$createdCount", "ref", "oid", 1_700_000_000_000L + createdCount)
        }
        override suspend fun diffCheckpoints(fromCheckpointId: String, toCheckpointId: String): com.zcode.android.domain.model.CheckpointDiff {
            calls += "diff:$fromCheckpointId:$toCheckpointId"
            return com.zcode.android.domain.model.CheckpointDiff(listOf("src/version.ts", "scratch/probe.txt"))
        }
        override suspend fun restoreCheckpoints(fromCheckpointId: String, toCheckpointId: String, force: Boolean): com.zcode.android.domain.model.CheckpointRestore {
            calls += "restore:$fromCheckpointId:$toCheckpointId:force=$force"
            return if (!force && conflictOnFirstRestore) com.zcode.android.domain.model.CheckpointRestore(false, emptyList(), listOf("src/config.ts"))
            else com.zcode.android.domain.model.CheckpointRestore(true, listOf("src/a.ts"), emptyList())
        }
    }

    private fun setContent() {
        calls.clear()
        rule.setContent { ZCodeTheme { GitScreen(port = port()) } }
        rule.waitForIdle()
    }

    @Test
    fun `create lands the snapshot in the list and restore is offered for the older one behind the warning`() {
        setContent()
        rule.onNodeWithTag("checkpoints_empty").assertIsDisplayed()
        rule.onNodeWithTag("checkpoint_create").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_create").performClick(); rule.waitForIdle()
        assertEquals(listOf("create", "create"), calls)
        rule.onNodeWithTag("checkpoint_id_0").assertIsDisplayed()
        // only the OLDER checkpoint (index 1) offers restore (the head has nothing newer)
        rule.onAllNodesWithTag("checkpoint_restore_0").assertCountEquals(1)
        rule.onAllNodesWithTag("checkpoint_restore_1").assertCountEquals(0)
        rule.onNodeWithTag("checkpoint_restore_0").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_restore_confirm").assertIsDisplayed()
        assertTrue("nothing fired before the confirm", calls.none { it.startsWith("restore") })
        rule.onNodeWithTag("checkpoint_restore_confirm").performClick(); rule.waitForIdle()
        // N-CA-1 (Phase 4): the confirm now fetches the confirmed pair's diff BEFORE the
        // restore — the diff wire rides every confirm open.
        assertEquals(listOf("create", "create", "diff:ckpt-1:ckpt-2", "restore:ckpt-1:ckpt-2:force=false"), calls)
    }

    @Test
    fun `a conflict result surfaces the second force confirm`() {
        conflictOnFirstRestore = true
        setContent()
        rule.onNodeWithTag("checkpoint_create").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_create").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_restore_0").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_restore_confirm").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_force_confirm").assertIsDisplayed()
        rule.onNodeWithTag("checkpoint_force_confirm").performClick(); rule.waitForIdle()
        assertTrue("force rides the SECOND confirm only", "restore:ckpt-1:ckpt-2:force=true" in calls)
    }

    @Test
    fun `the force confirm executes the CONFIRMED snapshot pair on a 3-item list - F-P45-A`() {
        // F-P45-A: with >=3 checkpoints the force handler RECOMPUTED from = getOrNull(1)
        // (a hard-coded index matching the confirmed pair only at exactly two checkpoints)
        // — the confirmed restore-to-ckpt-1 executed as restore-to-ckpt-2. Hydrate THREE
        // host checkpoints (newest-first: ckpt-3/ckpt-2/ckpt-1), restore row 1 (whose
        // confirmed from is ckpt-1), hit the conflict, and Overwrite: the force wire ids
        // must be EXACTLY the confirmed pair (ckpt-1 -> ckpt-3), never the recomputed one.
        conflictOnFirstRestore = true
        val hydrated = listOf(
            CheckpointMeta("ckpt-3", null, null, 3_000L),
            CheckpointMeta("ckpt-2", null, null, 2_000L),
            CheckpointMeta("ckpt-1", null, null, 1_000L),
        )
        rule.setContent { ZCodeTheme { GitScreen(port = port(hostCheckpoints = hydrated)) } }
        rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_restore_1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_restore_confirm").assertIsDisplayed()
        rule.onNodeWithTag("checkpoint_restore_confirm").performClick(); rule.waitForIdle()
        // the confirmed (non-force) leg already carries the right pair
        assertTrue("confirm leg pair", "restore:ckpt-1:ckpt-3:force=false" in calls)
        rule.onNodeWithTag("checkpoint_force_confirm").assertIsDisplayed()
        rule.onNodeWithTag("checkpoint_force_confirm").performClick(); rule.waitForIdle()
        assertTrue(
            "the force wire ids must equal the CONFIRMED pair (RED pre-fix: getOrNull(1) recomputed ckpt-2)",
            "restore:ckpt-1:ckpt-3:force=true" in calls,
        )
    }

    @Test
    fun `the restore confirm names the diff files - N-CA-1`() {
        // N-CA-1: the card docstring promised "the confirm names BOTH ids and the diff's
        // files" but diffText was only ever null-cleared and diffCheckpoints had ZERO UI
        // callers (0 wire lines across both pass-45 gens). The confirm must fetch the
        // diff and render the files under the body.
        setContent()
        rule.onNodeWithTag("checkpoint_create").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_create").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_restore_0").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("checkpoint_restore_confirm").assertIsDisplayed()
        // the diff wire FIRES with the confirmed pair
        rule.waitUntil(5_000) { calls.any { it.startsWith("diff:ckpt-1:ckpt-2") } }
        // and the files render under the body
        rule.onNodeWithTag("checkpoint_diff_files", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("src/version.ts", substring = true, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("scratch/probe.txt", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }
}
