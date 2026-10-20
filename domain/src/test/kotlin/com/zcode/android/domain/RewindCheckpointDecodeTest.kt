package com.zcode.android.domain

import com.zcode.android.domain.model.CheckpointDiff
import com.zcode.android.domain.model.CheckpointMeta
import com.zcode.android.domain.model.CheckpointRestore
import com.zcode.android.domain.model.RewindPreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3.11 (Phase 5B) decode pins: the rewind preview (`nt` — canApply + safe/unsafe/ignored
 * with the desktop's reason vocabulary) and the git-checkpoint shapes (isGitCheckpointMeta,
 * the diff's repoRelativePath files, the restore success/conflicts result).
 */
class RewindCheckpointDecodeTest {

    @Test
    fun `rewind preview decodes canApply and the three file classes`() {
        val p = RewindPreview.decode(
            Json.parseToJsonElement(
                """{"canApply":false,
                  "safeFiles":[{"action":"restore","path":"src/version.ts","operationCount":1,"toolNames":["edit"]},
                               {"action":"delete","path":"scratch/probe.txt","operationCount":1,"toolNames":[]}],
                  "unsafeFiles":[{"path":"src/config.ts","reason":"external_modified","currentHash":"a","expectedHash":"b",
                                  "message":"Modified after the checkpoint","toolNames":[]}],
                  "ignoredFiles":[{"path":"tmp/build.log","reason":"bash_ignored","operationCount":1,"toolNames":["bash"]}]}""",
            ).jsonObject,
        )
        assertFalse(p.canApply, "the desktop's gate rides the preview")
        assertEquals(listOf("src/version.ts", "scratch/probe.txt"), p.safeFiles.map { it.path })
        assertEquals("delete", p.safeFiles[1].action)
        assertEquals("external_modified", p.unsafeFiles.single().reason)
        assertEquals("Modified after the checkpoint", p.unsafeFiles.single().message)
        assertEquals("bash_ignored", p.ignoredFiles.single().reason)
    }

    @Test
    fun `checkpoint meta diff and restore decode tolerantly`() {
        val meta = CheckpointMeta.decode(
            Json.parseToJsonElement(
                """{"checkpointId":"ckpt-1","workspacePath":"/ws","repoRoot":"/repo","workspaceInRepoPath":".",
                     "createdAt":1700000000000,"refName":"refs/zcode/checkpoints/ckpt-1","commitOid":"deadbeef","scope":"workspace"}""",
            ).jsonObject,
        )!!
        assertEquals("ckpt-1", meta.checkpointId)
        assertEquals(1700000000000, meta.createdAtMs)
        assertNull(CheckpointMeta.decode(Json.parseToJsonElement("""{"refName":"no id"}""").jsonObject))
        assertEquals(listOf("src/a.ts"), CheckpointDiff.decode(Json.parseToJsonElement("""{"files":[{"kind":"modified","repoRelativePath":"src/a.ts"}]}""").jsonObject).files)
        val ok = CheckpointRestore.decode(Json.parseToJsonElement("""{"success":true,"restoredPaths":["a","b"]}""").jsonObject)
        assertTrue(ok.success)
        assertEquals(listOf("a", "b"), ok.restoredPaths)
        val conflict = CheckpointRestore.decode(Json.parseToJsonElement("""{"success":false,"conflicts":["src/config.ts"]}""").jsonObject)
        assertFalse(conflict.success)
        assertEquals(listOf("src/config.ts"), conflict.conflicts)
    }
}
