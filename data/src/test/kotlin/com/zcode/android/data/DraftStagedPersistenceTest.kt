package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.DraftRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.repo.DraftRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N-BH-2 (vc52 Phase 10): staged composer attachment refs ride the SAME draft row as
 * the text draft — the eager upload is already server-side at Done, so after process
 * death the chip RESTORES from the persisted refs instead of silently orphaning the
 * upload (the recorded kill -9 receipt: the text draft survived, the staged sketch
 * chip did not).
 *
 * The core pins are the CLOBBER CONTRACT (rule 14 — real Room, the GAP-A2
 * full-row-upsert class): a text save must never wipe the staged refs and a staged
 * save must never wipe the text. Plus the full round-trip and the discard semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DraftStagedPersistenceTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var repo: DraftRepositoryImpl

    private val scope = DraftRepository.DraftScope(
        endpointId = EndpointId("ep"),
        workspace = WorkspaceIdentityKey("ws"),
        taskId = "task-1",
    )

    private fun ref(uploadId: String, ref: String, fileName: String = "sketch-1.png", mime: String = "image/png", bytes: Long = 8_400) =
        DraftRepository.StagedAttachment(uploadId, fileName, mime, bytes, ref)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = DraftRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a text save never clobbers the staged refs (the GAP-A2 class, real DB)`() = runTest {
        repo.save(scope, "drafting…", atMs = 1_000)
        repo.saveStaged(scope, listOf(ref("u1", "att-ref-1")), atMs = 1_100)
        // Every keystroke re-saves the text — the naive full-row upsert wiped the
        // sibling column; the read-modify-write keeps the refs.
        repeat(3) { i -> repo.save(scope, "drafting… $i", atMs = 1_200L + i) }
        assertEquals(listOf(ref("u1", "att-ref-1")), repo.observeStaged(scope).first())
        assertEquals("drafting… 2", repo.observe(scope).first())
    }

    @Test
    fun `a staged save never clobbers the text`() = runTest {
        repo.save(scope, "keep this text", atMs = 1_000)
        repo.saveStaged(scope, listOf(ref("u1", "att-ref-1"), ref("u2", "att-ref-2", "doc.pdf", "application/pdf", 42_000)), atMs = 1_100)
        assertEquals("keep this text", repo.observe(scope).first())
        assertEquals(
            listOf(ref("u1", "att-ref-1"), ref("u2", "att-ref-2", "doc.pdf", "application/pdf", 42_000)),
            repo.observeStaged(scope).first(),
        )
    }

    @Test
    fun `staged refs round-trip through the persisted row (rule 13)`() = runTest {
        val refs = listOf(
            ref("u1", "att-ref-1"),
            ref("u2", "att-ref-2", fileName = "данные.json", mime = "application/json", bytes = 1_234_567),
            ref("u3", "att-ref-3", fileName = "odd \"quoted\".png", mime = "image/png", bytes = 0L),
        )
        repo.saveStaged(scope, refs, atMs = 1_000)
        assertEquals(refs, repo.observeStaged(scope).first(), "full fidelity incl. unicode/quotes/zero bytes")
        // A DIFFERENT task's row is untouched (per-task keying).
        val other = DraftRepository.DraftScope(scope.endpointId, scope.workspace, "task-2")
        assertEquals(emptyList(), repo.observeStaged(other).first())
    }

    @Test
    fun `replacing the staged list reflects removals and empty clears`() = runTest {
        repo.saveStaged(scope, listOf(ref("u1", "att-ref-1"), ref("u2", "att-ref-2")), atMs = 1_000)
        repo.saveStaged(scope, listOf(ref("u2", "att-ref-2")), atMs = 1_100) // u1 removed by the user
        assertEquals(listOf(ref("u2", "att-ref-2")), repo.observeStaged(scope).first())
        repo.saveStaged(scope, emptyList(), atMs = 1_200) // the send consumed the last one
        assertEquals(emptyList(), repo.observeStaged(scope).first())
        assertEquals("", repo.observe(scope).first(), "no text row minted by a staged-only save")
    }

    @Test
    fun `clear discards text AND staged refs (explicit discard only)`() = runTest {
        repo.save(scope, "gone", atMs = 1_000)
        repo.saveStaged(scope, listOf(ref("u1", "att-ref-1")), atMs = 1_100)
        repo.clear(scope)
        assertEquals(null, repo.observe(scope).first())
        assertEquals(emptyList(), repo.observeStaged(scope).first())
    }

    @Test
    fun `a fresh DB row has no staged refs and corrupted JSON degrades to empty`() = runTest {
        assertTrue(repo.observeStaged(scope).first().isEmpty(), "no row → no refs")
        repo.save(scope, "t", atMs = 1_000)
        assertTrue(repo.observeStaged(scope).first().isEmpty(), "a row without the column value → no refs")
        // Corrupted payload: safe-reject (INV-8 class — never crashes the read).
        db.openHelper.writableDatabase.execSQL(
            """UPDATE drafts SET attachmentsJson = '{not json' WHERE taskId = 'task-1'""",
        )
        assertEquals(emptyList(), repo.observeStaged(scope).first())
    }
}
