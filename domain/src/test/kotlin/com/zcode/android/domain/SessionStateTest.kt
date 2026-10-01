package com.zcode.android.domain

import com.zcode.android.domain.model.BackgroundWork
import com.zcode.android.domain.model.InputRouting
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.SessionControl
import com.zcode.android.domain.model.SessionProjectionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7 session-state projection (docs/protocol/v4-wire.md §9, verified snapshot.ts/
 * delta.ts @ 29628c9): decode + KEY-LEVEL merge semantics + INV-8 tolerance.
 */
class SessionStateTest {

    private fun snapshotJson(): String = """
    {
      "revision": 1042,
      "control": {"phase":"running","sessionEnded":false,"canStop":true,"stopState":"stoppable",
                  "activeWorks":[{"kind":"primaryTurn","foregroundExecutionId":"exec-1","startedAt":123.0}]},
      "inputRouting": {"mode":"enqueue"},
      "queue": {"items":[{"queueItemId":"q-1","text":"queued prompt","kind":"sendText","dispatch":{"state":"queued"}}],"autoDrain":true},
      "pendingInteractions": [
        {"interactionId":"i-perm","kind":"permission","anchorRowId":null,"createdAt":1700000000000.0,
         "payload":{"kind":"permission","toolCallId":"tc-1","toolName":"Bash","summary":"Run the build?",
                    "detail":{"command":"./gradlew build"},
                    "options":[{"optionId":"allowOnce","label":"Allow once","kind":"allowOnce"},
                               {"optionId":"deny","label":"Deny","kind":"deny"},
                               {"optionId":"fullAccess","label":"Full access","kind":"custom"}]}},
        {"interactionId":"i-user","kind":"userInput","anchorRowId":7,"createdAt":1700000000001.0,
         "payload":{"kind":"userInput","prompt":"Which database?","freeText":false,
                    "options":[{"optionId":"postgres","label":"Postgres"},{"optionId":"sqlite","label":"SQLite"}]}}
      ],
      "backgroundWorks": [
        {"workId":"w-1","kind":"bash","title":"long task","status":"running","cancellable":true,
         "startedAt":123.0,"anchorRowId":null}
      ]
    }
    """.trimIndent()

    @Test
    fun `snapshot decodes all verified state keys`() {
        val obj = Json.parseToJsonElement(snapshotJson()).let { it as kotlinx.serialization.json.JsonObject }
        val state = SessionProjectionState.fromSnapshot(obj)

        assertEquals(1042L, state.revision)
        val control = assertNotNull(state.control)
        assertEquals(true, control.canStop)
        assertEquals(SessionControl.StopState.STOPPABLE, control.stopState)
        assertEquals(1, control.activeWorks.size)
        assertEquals("exec-1", control.activeWorks.first().foregroundExecutionId)

        assertEquals(InputRouting.Mode.ENQUEUE, state.inputRouting?.mode)

        val queue = assertNotNull(state.queue)
        assertEquals(1, queue.items.size)
        assertEquals("queued prompt", queue.items.first().text)
        assertEquals("queued", queue.items.first().dispatchState)
        assertEquals(true, queue.autoDrain)

        val pending = assertNotNull(state.pendingInteractions)
        assertEquals(2, pending.size)
        val perm = pending[0]
        assertEquals(PendingInteraction.Kind.PERMISSION, perm.kind)
        val permPayload = assertNotNull(perm.permission)
        assertEquals("Bash", permPayload.toolName)
        assertEquals("Run the build?", permPayload.summary)
        assertEquals(3, permPayload.options.size)
        // §20.4: options classify by optionId — the custom "fullAccess" is INERT.
        assertTrue(permPayload.options[0].allowCapable)
        assertTrue(!permPayload.options[2].allowCapable)
        assertEquals(
            com.zcode.android.domain.model.PermissionOption.Semantics.OTHER,
            permPayload.options[2].semantics,
        )

        val user = pending[1]
        assertEquals(PendingInteraction.Kind.USER_INPUT, user.kind)
        val userPayload = assertNotNull(user.userInput)
        assertEquals("Which database?", userPayload.prompt)
        assertEquals(false, userPayload.freeText)
        assertEquals(2, userPayload.options.size)
        assertEquals(7L, user.anchorRowId)

        val works = assertNotNull(state.backgroundWorks)
        assertEquals("w-1", works.first().workId)
        assertEquals(true, works.first().cancellable)
    }


    @Test
    fun `patch merges KEY-LEVEL (Object-assign semantics) — whole replacement, never deep`() {
        val base = SessionProjectionState(
            queue = com.zcode.android.domain.model.QueueState(
                items = listOf(com.zcode.android.domain.model.QueueState.QueueItem("q-1", "old text", "sendText", "queued")),
                autoDrain = true,
                pauseReason = null,
            ),
            revision = 10,
        )
        val patch = buildJsonObject {
            put("revision", 11)
            putJsonObject("queue") {
                putJsonArray("items") {
                    add(
                        buildJsonObject {
                            put("queueItemId", "q-1")
                            put("text", "new text")
                            put("dispatch", buildJsonObject { put("state", "reserved") })
                        },
                    )
                }
                put("autoDrain", false)
            }
        }
        val merged = base.mergedWith(patch)
        assertEquals(11L, merged.revision)
        val queue = assertNotNull(merged.queue)
        assertEquals(1, queue.items.size) // whole-list replacement, not an append
        assertEquals("new text", queue.items.first().text)
        assertEquals("reserved", queue.items.first().dispatchState)
        assertEquals(false, queue.autoDrain)
    }

    @Test
    fun `patch with absent keys leaves them untouched`() {
        val base = SessionProjectionState(control = SessionControl("running", false, true, SessionControl.StopState.STOPPABLE, emptyList()))
        val merged = base.mergedWith(buildJsonObject { put("revision", 12) })
        assertEquals(12L, merged.revision)
        assertNotNull(merged.control) // untouched
    }

    @Test
    fun `unknown patch keys are ignored and COUNTED (INV-8)`() {
        val before = SessionProjectionState.ignoredKeys
        val merged = SessionProjectionState().mergedWith(
            buildJsonObject {
                put("workflowRuns", buildJsonObject { put("whatever", true) })
                put("usage", buildJsonObject { put("x", 1) })
            },
        )
        assertNull(merged.control)
        assertEquals(before + 2, SessionProjectionState.ignoredKeys)
    }

    @Test
    fun `malformed known key drops THAT KEY only — other keys still apply (INV-8 per-key isolation)`() {
        val before = SessionProjectionState.malformedKeyDrops
        val merged = SessionProjectionState().mergedWith(
            buildJsonObject {
                put("control", "not-an-object") // known key, wrong type
                put("revision", 5)
            },
        )
        assertNull(merged.control)
        assertEquals(5L, merged.revision)
        assertEquals(before + 1, SessionProjectionState.malformedKeyDrops)
    }

    @Test
    fun `unknown enum values decode to tolerant Unknown cases (never guessed)`() {
        val state = SessionProjectionState.fromSnapshot(
            buildJsonObject {
                putJsonObject("control") {
                    put("phase", "quantum")
                    put("canStop", true)
                    put("stopState", "vibrating")
                }
                putJsonObject("inputRouting") { put("mode", "teleport") }
            },
        )
        assertEquals(SessionControl.StopState.UNKNOWN, state.control?.stopState)
        assertEquals(InputRouting.Mode.UNKNOWN, state.inputRouting?.mode)
    }

    @Test
    fun `unknown pendingInteraction KIND is retained raw and NOT actionable`() {
        val state = SessionProjectionState.fromSnapshot(
            buildJsonObject {
                putJsonArray("pendingInteractions") {
                    add(
                        buildJsonObject {
                            put("interactionId", "i-hook")
                            put("kind", "workspaceHookReview")
                            put("payload", buildJsonObject { put("kind", "workspaceHookReview") })
                        },
                    )
                }
            },
        )
        val pending = assertNotNull(state.pendingInteractions)
        assertEquals(PendingInteraction.Kind.WORKSPACE_HOOK_REVIEW, pending.single().kind)
        assertNull(pending.single().permission)
        assertNull(pending.single().userInput)
    }

    @Test
    fun `background work decode tolerates missing workId (drops the entry)`() {
        val works = BackgroundWork.decodeList(
            kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("title", "no id") },
                buildJsonObject { put("workId", "ok"); put("title", "fine") },
            )),
        )
        assertEquals(1, works.size)
        assertEquals("ok", works.single().workId)
    }

    @Test
    fun `empty snapshot block decodes to an all-null state (legacy shape)`() {
        val state = SessionProjectionState.fromSnapshot(buildJsonObject { put("rows", buildJsonObject { }) })
        assertNull(state.control)
        assertNull(state.queue)
        assertNull(state.pendingInteractions)
        assertNull(state.revision)
    }
}
