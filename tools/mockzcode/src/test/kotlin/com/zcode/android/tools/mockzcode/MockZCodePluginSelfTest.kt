package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-023 (Phase 5D) mock leg: the scripted plugins list round-trips the toggle state. */
class MockZCodePluginSelfTest {

    @Test
    fun `scripted plugins list carries id and enabled for the switch`() {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_plugin")
        m.script.listPlugins = kotlinx.serialization.json.buildJsonObject {
            put("plugins", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive("p1"))
                put("name", kotlinx.serialization.json.JsonPrimitive("Demo"))
                put("enabled", kotlinx.serialization.json.JsonPrimitive(false))
                put("source", kotlinx.serialization.json.JsonPrimitive("local"))
                put("marketplace", kotlinx.serialization.json.JsonPrimitive("core"))
                put("skillRootCount", kotlinx.serialization.json.JsonPrimitive(0))
                put("commandRootCount", kotlinx.serialization.json.JsonPrimitive(0))
                put("mcpServerNames", kotlinx.serialization.json.JsonArray(emptyList()))
                put("rootPath", kotlinx.serialization.json.JsonPrimitive("/plugins/p1"))
            })))
        }
        val plugins = (m.script.listPlugins!!["plugins"] as kotlinx.serialization.json.JsonArray)
        assertEquals(1, plugins.size)
        val p1 = plugins[0] as kotlinx.serialization.json.JsonObject
        assertEquals("p1", (p1["id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("false", (p1["enabled"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(m.script.listPlugins != null)
    }
}
