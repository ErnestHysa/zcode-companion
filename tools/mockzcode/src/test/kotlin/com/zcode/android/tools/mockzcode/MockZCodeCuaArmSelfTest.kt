package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G18 (vc51 Phase 4D) mock self-test: the CUA arm's rows use the shipped desktop CUA
 * tool-name prefixes (mcp__computer-use__*) and the screenshot row's output carries a
 * real PNG (magic-checked) — the frame the phone renders inside the expanded card.
 */
class MockZCodeCuaArmSelfTest {

    @Test
    fun `the cua arm appends three consecutive computer-use rows with a png frame`() {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_cua")
        armCuaRigForTest(m)
        val rows = m.appendedRowsForTest().values.toList()
        assertEquals(3, rows.size)
        val names = rows.mapNotNull { (it["toolName"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
        assertTrue(names.all { it.startsWith("mcp__computer-use__") }, "shipped CUA prefixes: $names")
        val screenshot = rows.first().let { (it["outputPreview"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
        assertTrue("\"screenshot\"" in screenshot, "first row keys=${rows.first().keys} outputPreview=${rows.first()["outputPreview"]}")
        // the embedded base64 decodes to a PNG (magic)
        val b64 = Regex("[A-Za-z0-9+/=]{60,}").find(screenshot)!!.value
        val bytes = java.util.Base64.getDecoder().decode(b64)
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
    }
}
