package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I2.4 (Phase 6A) mock self-test: `compactionDemo` stamps three schema-faithful
 * `timelineMarker` rows (`marker.type == "compact"`) — the wire shape the domain
 * `CompactionNotice` derivation and the notice row consume on the rig.
 */
class MockZCodeCompactionSelfTest {

    @Test
    fun `compactionDemo stamps started - completedAuto-with-tokens - failed markers`() {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_compact")
        m.script.compactionDemo = true
        val rows = m.snapshotRowsForTest("sess-compact", 2)
        val markers = rows.filter { (it as JsonObject)["kind"]?.let { k -> k is JsonPrimitive && k.content == "timelineMarker" } == true }
            .map { it.jsonObject }
        assertEquals(3, markers.size, "started:auto, completed:auto, failed:manual")
        markers.forEach { row ->
            val marker = row["marker"]?.jsonObject
            assertNotNull(marker, "every timelineMarker row carries the marker object")
            assertEquals("compact", (marker["type"] as JsonPrimitive).content)
        }
        assertEquals("running", (markers[0]["marker"]!!.jsonObject["status"] as JsonPrimitive).content)
        assertEquals("auto", (markers[0]["marker"]!!.jsonObject["origin"] as JsonPrimitive).content)
        val completed = markers[1]["marker"]!!.jsonObject
        assertEquals("completed", (completed["status"] as JsonPrimitive).content)
        assertEquals("auto", (completed["origin"] as JsonPrimitive).content)
        assertEquals(180_000, (completed["tokensBefore"] as JsonPrimitive).content.toLong())
        assertEquals(42_000, (completed["tokensAfter"] as JsonPrimitive).content.toLong())
        val failed = markers[2]["marker"]!!.jsonObject
        assertEquals("failed", (failed["status"] as JsonPrimitive).content)
        assertEquals("manual", (failed["origin"] as JsonPrimitive).content)
        // The rows decode through the same JSON the phone's decoder reads (no schema fault).
        assertTrue(rows.all { it is JsonObject && (it["rowId"] as JsonPrimitive).content.toLong() > 0 })
        Json.parseToJsonElement(rows.toString())
    }
}
