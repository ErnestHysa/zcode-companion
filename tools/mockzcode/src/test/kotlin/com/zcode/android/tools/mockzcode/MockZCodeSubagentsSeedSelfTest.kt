package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G25 (vc51 Phase 4B) mock self-test: the relay arming seeds a subagents list across
 * scopes with a DISABLED entry, so the phone's read-only subagents viewer renders both
 * state-badge faces on the rig (the desktop filters `enabled:false` from the slash
 * panel — the VIEWER decode keeps them).
 */
class MockZCodeSubagentsSeedSelfTest {

    @Test
    fun `the relay arming seeds subagents across scopes with a disabled entry`() {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val mock = MockZCode(passHash = passHash, expectedSid = "d_sa")
        // the SAME seed the relay arming runs at server start
        mock.script.subagentsList = subagentsViewerSeed()
        val agents = mock.script.subagentsList!!["agents"]!!.jsonArray
        assertEquals(3, agents.size)
        val names = agents.map { (it.jsonObject["name"] as JsonPrimitive).content }
        assertTrue("Explore" in names && "Plan" in names && "docs-writer" in names)
        val disabled = agents.filter { (it.jsonObject["enabled"] as JsonPrimitive).content == "false" }
        assertEquals(listOf("project"), disabled.map { (it.jsonObject["scope"] as JsonPrimitive).content })
    }
}
