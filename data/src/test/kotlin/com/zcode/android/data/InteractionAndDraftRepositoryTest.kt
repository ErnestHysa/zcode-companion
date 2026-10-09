package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.DraftRepositoryImpl
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.UserInputRequest
import com.zcode.android.domain.repo.DraftRepository
import com.zcode.android.domain.repo.InteractionRepository
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

/** §17.3 interaction store + §17.4 drafts — bounds, TTL sweeps, scope round-trips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class InteractionAndDraftRepositoryTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var interactions: InteractionRepositoryImpl
    private lateinit var drafts: DraftRepositoryImpl
    private val ep = EndpointId("ep")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        interactions = InteractionRepositoryImpl(db)
        drafts = DraftRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private fun permission(id: String, createdAt: Long) = PermissionRequest(
        interactionId = InteractionId(id),
        sessionId = "s1",
        toolName = "Bash",
        riskLevel = "medium",
        reason = "run tests",
        options = listOf(PermissionOption("allowOnce"), PermissionOption("deny"), PermissionOption("someFuture")),
        createdAtMs = createdAt,
        raw = kotlinx.serialization.json.Json.parseToJsonElement("""{"interactionId":"$id"}""") as kotlinx.serialization.json.JsonObject,
    )

    @Test
    fun `permission upsert idempotent by interactionId - open query - resolve`() = runTest {
        interactions.upsertPermission(permission("i1", 1), ep, InteractionLifecycle.DISPLAYED)
        interactions.upsertPermission(permission("i1", 1), ep, InteractionLifecycle.DISPLAYED) // duplicate
        assertEquals(1, interactions.count(ep))
        assertEquals(1, interactions.observeOpen(ep).first().size)

        interactions.markResolved(ep, InteractionId("i1"), atMs = 100, resolvedBySelf = true)
        assertEquals(0, interactions.observeOpen(ep).first().size)
        val record = interactions.observeOpen(ep).first()
        assertEquals(0, record.size)
    }

    @Test
    fun `sweep drops resolved older than 24h and enforces the 64 bound (17-3)`() = runTest {
        val now = 10L * 24 * 60 * 60 * 1000
        // 10 resolved-long-ago + 60 open → after TTL drop: 60; then +10 more open → cap trims
        repeat(10) {
            interactions.upsertPermission(permission("old$it", createdAt = 1), ep, InteractionLifecycle.RESOLVED)
            interactions.markResolved(ep, InteractionId("old$it"), atMs = 1, resolvedBySelf = false)
        }
        repeat(60) { interactions.upsertPermission(permission("open$it", createdAt = 2 + it.toLong()), ep, InteractionLifecycle.DISPLAYED) }

        val dropped = interactions.sweep(ep, nowMs = now)
        assertTrue(dropped >= 10, "resolved > 24 h dropped (dropped=$dropped)")
        assertEquals(60, interactions.count(ep))

        // flood past the 64 bound → trimmed to exactly 64
        repeat(10) { interactions.upsertPermission(permission("extra$it", createdAt = now), ep, InteractionLifecycle.DISPLAYED) }
        interactions.sweep(ep, nowMs = now)
        assertEquals(64, interactions.count(ep), "§17.3 bound ≤ 64 enforced")
    }

    @Test
    fun `user input requests persist with the same store mechanics`() = runTest {
        val req = UserInputRequest(
            interactionId = InteractionId("u1"), sessionId = "s1", prompt = "continue?",
            options = listOf(PermissionOption("allowOnce")), createdAtMs = 5,
        )
        interactions.upsertUserInput(req, ep, InteractionLifecycle.REQUESTED)
        val open = interactions.observeOpen(ep).first()
        assertEquals(1, open.size)
        assertEquals(InteractionRepository.InteractionRecord.Kind.USER_INPUT, open.single().kind)
    }

    @Test
    fun `drafts round-trip per scope - explicit discard only (17-4)`() = runTest {
        val scope = DraftRepository.DraftScope(ep, WorkspaceIdentityKey("ws"), "t1")
        val other = DraftRepository.DraftScope(ep, WorkspaceIdentityKey("ws"), "t2")

        assertTrue(drafts.observe(scope).first() == null)
        drafts.save(scope, "fix the", atMs = 1)
        drafts.save(scope, "fix the bug", atMs = 2) // keystroke overwrite
        assertEquals("fix the bug", drafts.observe(scope).first())
        assertTrue(drafts.observe(other).first() == null, "scopes are independent")

        drafts.clear(scope)
        assertTrue(drafts.observe(scope).first() == null)
    }
}
