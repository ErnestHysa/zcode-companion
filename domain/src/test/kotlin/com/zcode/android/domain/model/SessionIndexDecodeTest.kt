package com.zcode.android.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G13 (vc51) — the sessions-index decode pins (sessions-index.ts shapes):
 * snapshot/delta decode, the row-activity verdicts the task list renders, and
 * the INV-8 tolerance (a malformed entry drops THAT entry, never the feed).
 */
class SessionIndexDecodeTest {

    private fun summary(
        sessionId: String,
        phase: String = "running",
        ended: Boolean = false,
        perms: Int = 0,
        inputs: Int = 0,
    ) = buildJsonObject {
        put("sessionId", JsonPrimitive(sessionId))
        put("workspaceId", JsonPrimitive("ws-1"))
        put("title", JsonPrimitive("Task $sessionId"))
        put("phase", JsonPrimitive(phase))
        put("sessionEnded", JsonPrimitive(ended))
        put("hasBackgroundWork", JsonPrimitive(false))
        put("lastActivityAt", JsonPrimitive(1_700_000_000.0))
        put("createdAt", JsonPrimitive(1_690_000_000.0))
        putJsonArray("usage") {}
        put("pendingInteractionSummary", buildJsonObject {
            put("permissionCount", JsonPrimitive(perms))
            put("userInputCount", JsonPrimitive(inputs))
        })
    }

    @Test
    fun `snapshot decode maps sessions by id with the wire facts intact`() {
        val snap = SessionIndexEntry.decodeSnapshot(buildJsonObject {
            put("protocolVersion", JsonPrimitive(1))
            put("workspaceId", JsonPrimitive("ws-1"))
            put("logEpoch", JsonPrimitive("idx-epoch-1"))
            putJsonArray("sessions") {
                add(summary("task-a"))
                add(summary("task-b", phase = "waiting", inputs = 1))
            }
        })
        assertEquals(2, snap.sessions.size)
        val a = snap.sessions["task-a"]!!
        assertEquals("Task task-a", a.title)
        assertEquals("running", a.phase)
        assertEquals(1_700_000_000L, a.lastActivityAtMs)
        assertEquals(SessionIndexEntry.RowActivity.RUNNING, a.rowActivity)
        assertEquals(SessionIndexEntry.RowActivity.WAITING, snap.sessions["task-b"]!!.rowActivity)
    }

    @Test
    fun `a malformed entry drops that entry only - the feed survives (INV-8)`() {
        val snap = SessionIndexEntry.decodeSnapshot(buildJsonObject {
            put("protocolVersion", JsonPrimitive(1))
            put("workspaceId", JsonPrimitive("ws-1"))
            put("logEpoch", JsonPrimitive("idx-epoch-1"))
            putJsonArray("sessions") {
                add(summary("task-ok"))
                add(buildJsonObject { put("nope", JsonPrimitive(1)) })
            }
        })
        assertEquals(listOf("task-ok"), snap.sessions.keys.toList())
    }

    @Test
    fun `rowActivity verdicts - waiting beats running, ended ends, idle stays quiet`() {
        val running = SessionIndexEntry.decode(summary("t", phase = "running"))!!
        assertEquals(SessionIndexEntry.RowActivity.RUNNING, running.rowActivity)
        val waitingWhileRunning = SessionIndexEntry.decode(summary("t", phase = "running", perms = 1))!!
        assertEquals(SessionIndexEntry.RowActivity.WAITING, waitingWhileRunning.rowActivity)
        val ended = SessionIndexEntry.decode(summary("t", phase = "running", ended = true))!!
        assertEquals(SessionIndexEntry.RowActivity.ENDED, ended.rowActivity)
        val idle = SessionIndexEntry.decode(summary("t", phase = "idle"))!!
        assertEquals(SessionIndexEntry.RowActivity.IDLE, idle.rowActivity)
    }

    @Test
    fun `deltas decode - upserted conflates by sessionId, removed drops, unknown ignored`() {
        val upsert = SessionsIndexDelta.decode(buildJsonObject {
            put("op", JsonPrimitive("session.upserted"))
            put("session", summary("task-a", phase = "waiting", perms = 2))
        })
        assertTrue(upsert is SessionsIndexDelta.Upserted)
        assertEquals(SessionIndexEntry.RowActivity.WAITING, (upsert as SessionsIndexDelta.Upserted).session.rowActivity)
        val removed = SessionsIndexDelta.decode(buildJsonObject {
            put("op", JsonPrimitive("session.removed"))
            put("sessionId", JsonPrimitive("task-a"))
        })
        assertEquals(SessionsIndexDelta.Removed("task-a"), removed)
        assertNull(SessionsIndexDelta.decode(buildJsonObject { put("op", JsonPrimitive("future.op")) }))
    }
}
