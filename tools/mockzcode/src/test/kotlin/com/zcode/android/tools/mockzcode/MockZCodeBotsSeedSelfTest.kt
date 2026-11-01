package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G30 (vc51 Phase 4B) mock self-test: the bots-viewer seed covers two providers and both
 * mode faces (task + draft, one disabled), and the scripted list is what the
 * `bots.list` leg replays (unscripted → the honest fault the viewer degrades on).
 */
class MockZCodeBotsSeedSelfTest {

    @Test
    fun `the seed covers two providers and both mode faces`() {
        val bots = botsViewerSeed()["bots"]!!.jsonArray
        assertEquals(2, bots.size)
        val providers = bots.map { (it.jsonObject["provider"] as JsonPrimitive).content }
        assertTrue("telegram" in providers && "discord" in providers)
        val modes = bots.map { (it.jsonObject["mode"] as JsonPrimitive).content }
        assertTrue("task" in modes && "draft" in modes)
        val enabled = bots.map { (it.jsonObject["enabled"] as JsonPrimitive).content }
        assertTrue("true" in enabled && "false" in enabled)
    }

    @Test
    fun `unscripted list stays null (the honest fault path)`() {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_bots")
        assertNull(m.script.botsList)
    }
}
