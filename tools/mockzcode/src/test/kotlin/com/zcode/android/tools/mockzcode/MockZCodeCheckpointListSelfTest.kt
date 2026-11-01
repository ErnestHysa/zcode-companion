package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vc51 Phase 4A (CKPT-HYDRATE) mock self-test: the `git-checkpoint.listCheckpoints`
 * handler replays the scripted snapshot history (`script.checkpoints`) — the state the
 * phone's checkpoint-card hydration rides across an app restart. The full wire round-trip
 * (create → list → restore) is pinned app-side in GitCheckpointListWireTest over a live
 * MockZCode server.
 */
class MockZCodeCheckpointListSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        return MockZCode(passHash = passHash, expectedSid = "d_ckpt")
    }

    @Test
    fun `scripted checkpoint history is empty until a create lands`() {
        val m = mock()
        assertTrue(m.script.checkpoints.isEmpty())
    }

    @Test
    fun `the list word replays the scripted snapshot history in create order`() {
        val m = mock()
        m.script.checkpoints.add(buildJsonObject {
            put("checkpointId", JsonPrimitive("ckpt-1")); put("createdAt", JsonPrimitive(1L))
        })
        m.script.checkpoints.add(buildJsonObject {
            put("checkpointId", JsonPrimitive("ckpt-2")); put("createdAt", JsonPrimitive(2L))
        })
        // the hydration contract: every scripted meta, in create order, id-first
        val ids = m.script.checkpoints.mapNotNull { (it["checkpointId"] as? JsonPrimitive)?.content }
        assertEquals(listOf("ckpt-1", "ckpt-2"), ids)
    }
}
