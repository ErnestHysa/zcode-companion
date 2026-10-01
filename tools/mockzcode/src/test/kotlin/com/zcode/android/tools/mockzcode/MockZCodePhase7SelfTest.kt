package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 7 mock self-tests: the command-effect machinery produces schema-faithful
 * shapes (state block in snapshots, revision bumps, pendingInteractions) — grounded
 * ONLY in the verified §9/§7.4.4 contracts (docs/protocol/v4-wire.md).
 * Synthetic content exclusively (INV-15/16).
 */
class MockZCodePhase7SelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_selftest7")
        script(m.script)
        return m
    }

    @Test
    fun `snapshot state block carries the verified A-side keys when scripted`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.controlCanStop = true
            s.controlStopState = "stoppable"
            s.inputRoutingMode = "enqueue"
            s.queueItems = listOf(MockZCode.QueueItem("q-1", "queued prompt"))
            s.pendingPermission = MockZCode.PendingPermission("i-perm", toolName = "Bash")
        }
        val b = assertNotNull(m.stateBlockForTest())
        val control = b["control"]!!.jsonObject
        assertEquals(true, (control["canStop"] as JsonPrimitive).content.toBoolean())
        assertEquals("stoppable", (control["stopState"] as JsonPrimitive).content)
        assertEquals("enqueue", (b["inputRouting"]!!.jsonObject["mode"] as JsonPrimitive).content)
        val queue = b["queue"]!!.jsonObject["items"] as JsonArray
        assertEquals("queued prompt", (queue[0].jsonObject["text"] as JsonPrimitive).content)
        val pending = b["pendingInteractions"] as JsonArray
        assertEquals(1, pending.size)
        assertEquals("permission", (pending[0].jsonObject["kind"] as JsonPrimitive).content)
        val payload = pending[0].jsonObject["payload"]!!.jsonObject
        assertEquals("Bash", (payload["toolName"] as JsonPrimitive).content)
        val options = payload["options"] as JsonArray
        assertEquals(listOf("allowOnce", "allowAlways", "deny"), options.map { (it.jsonObject["optionId"] as JsonPrimitive).content })
        assertEquals(100L, (b["revision"] as JsonPrimitive).content.toLong())
    }

    @Test
    fun `state block is null when not scripted (legacy snapshots unchanged)`() {
        val m = mock()
        assertEquals(null, m.stateBlockForTest())
    }

    @Test
    fun `userInput pending payload is schema-faithful (U9 plane)`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.pendingUserInput = MockZCode.PendingUserInput("i-u", options = listOf("a", "b"), freeText = false)
        }
        val pending = m.stateBlockForTest()!!["pendingInteractions"] as JsonArray
        assertEquals(1, pending.size)
        val payload = pending[0].jsonObject["payload"]!!.jsonObject
        assertEquals("userInput", (payload["kind"] as JsonPrimitive).content)
        assertEquals(false, (payload["freeText"] as JsonPrimitive).content.toBoolean())
        assertEquals(listOf("a", "b"), (payload["options"] as JsonArray).map { (it.jsonObject["optionId"] as JsonPrimitive).content })
    }

    @Test
    fun `resolved-elsewhere driver clears the pending interaction and bumps revision`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.pendingPermission = MockZCode.PendingPermission("i-race")
        }
        val before = m.revisionForTest()
        m.resolveInteractionElsewhere()
        assertTrue(m.revisionForTest() > before)
        assertEquals(null, m.pendingPermissionForTest())
        val pending = m.stateBlockForTest()!!["pendingInteractions"] as JsonArray
        assertEquals(0, pending.size)
    }
}
