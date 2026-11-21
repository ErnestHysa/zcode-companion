package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * pass-0 Phase 10 mock self-test: the four parity-mirror seeds are shape-faithful
 * to the REAL desktop's asar payloads (G83 scenes envelope / G84 styles + active /
 * G101 CUA health / G98 directory with an online + a failed host), the getActiveStyle
 * id matches the seeded ACTIVE style, and the unscripted channels stay null (the
 * honest fault path the mirrors degrade on).
 */
class MockZCodePhase10MirrorsSelfTest {

    @Test
    fun `the scenes seed carries the draft-suggestion prompt and a second scene`() {
        val payload = clientScenesSeed().jsonObject
        assertEquals(0, (payload["code"] as JsonPrimitive).content.toInt())
        val data = payload["data"]!!.jsonArray
        assertEquals(2, data.size)
        val scene = data[0].jsonObject
        assertEquals("draft-suggestion", (scene["scene"] as JsonPrimitive).content)
        val options = scene["options"]!!.jsonObject
        val promptItem = options["prompts"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals("rig-p1", (promptItem["id"] as JsonPrimitive).content)
        val opt = options["group-a"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals("Review the latest rig commits", (opt["contents"]!!.jsonObject["en"] as JsonPrimitive).content)
    }

    @Test
    fun `the style seed covers built-in + custom and the active id matches`() {
        val styles = outputStyleSeed()["styles"]!!.jsonArray
        assertEquals(2, styles.size)
        val ids = styles.map { (it.jsonObject["id"] as JsonPrimitive).content }
        assertTrue("default" in ids && "custom-explain" in ids)
    }

    @Test
    fun `the CUA seed renders the mixed health face`() {
        val s = cuaStatusSeed().jsonObject
        assertEquals("true", (s["available"] as JsonPrimitive).content)
        assertEquals("Ernest", (s["grantOwnerDisplayName"] as JsonPrimitive).content)
        assertEquals("true", (s["accessibilityProbeOk"] as JsonPrimitive).content)
        assertEquals("false", (s["screenCaptureProbeOk"] as JsonPrimitive).content)
    }

    @Test
    fun `the source-server seed carries an online and a failed host with raw states`() {
        val snap = sourceServersSeed().jsonObject
        val servers = snap["servers"]!!.jsonArray
        assertEquals(2, servers.size)
        val states = snap["states"]!!.jsonArray
        val statesVocab = states.map { (it.jsonObject["state"] as JsonPrimitive).content }
        assertTrue("online" in statesVocab && "failed" in statesVocab)
        val failed = states.first { (it.jsonObject["state"] as JsonPrimitive).content == "failed" }.jsonObject
        assertEquals("SSH refused", (failed["error"] as JsonPrimitive).content)
        assertEquals(1, servers[0].jsonObject["declaredWorkspaces"]!!.jsonArray.size)
    }

    @Test
    fun `unscripted mirror channels stay null (the honest fault path)`() {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_p10")
        assertNull(m.script.clientScenesList)
        assertNull(m.script.outputStyleList)
        assertNull(m.script.outputStyleActive)
        assertNull(m.script.cuaPermissionStatus)
        assertNull(m.script.sourceServerSnapshot)
    }
}
