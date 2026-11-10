package com.zcode.android.core.protocol.v4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * G64 (vc52 Phase 11) decode pins: the 4th notify method — `v4/cua/permission-observation`
 * (byte-verified asar `12a2fc93…`, chunk-D5C6W4UB `dY`/`Kf`). The phone declared the
 * constant since Phase 3 and handled the payload nowhere (the recorded defect face:
 * a remote computer-use task blocked on macOS permissions showed the phone NOTHING).
 */
class CuaPermissionObservationDecodeTest {

    private fun payload(
        accessibility: String = "denied",
        screenRecording: String = "granted",
        turnId: String? = "turn-9",
    ): JsonObject = Json.parseToJsonElement(
        """
        {"schemaVersion":1,"eventId":"evt-cua-1","eventSeq":4,"occurredAt":1731234567890,
         "sessionId":"sess-cua-1",${if (turnId != null) "\"turnId\":\"$turnId\"," else ""}"toolCallId":"call-1",
         "permissionStatus":{"schemaVersion":1,"platform":"darwin","grantOwner":"com.zcode.cua-helper",
           "accessibility":"$accessibility","screenRecording":"$screenRecording"}}
        """.trimIndent(),
    ) as JsonObject

    @Test
    fun `the byte-verified payload decodes typed`() {
        val o = CuaPermissionObservation.decode(payload())
        assertEquals("evt-cua-1", o.eventId)
        assertEquals(4L, o.eventSeq)
        assertEquals(1731234567890L, o.occurredAtMs)
        assertEquals("sess-cua-1", o.sessionId)
        assertEquals("turn-9", o.turnId)
        assertEquals("call-1", o.toolCallId)
        assertEquals("denied", o.accessibility)
        assertEquals("granted", o.screenRecording)
        assertEquals("com.zcode.cua-helper", o.grantOwner)
    }

    @Test
    fun `turnId is optional (absent decodes null)`() {
        val o = CuaPermissionObservation.decode(payload(turnId = null))
        assertNull(o.turnId)
        assertTrue(o.blocked)
    }

    @Test
    fun `blocked is true unless BOTH permissions are granted`() {
        assertTrue(CuaPermissionObservation.decode(payload(accessibility = "denied")).blocked)
        assertTrue(CuaPermissionObservation.decode(payload(accessibility = "stale")).blocked)
        assertTrue(CuaPermissionObservation.decode(payload(screenRecording = "unknown")).blocked)
        assertFalse(CuaPermissionObservation.decode(payload(accessibility = "granted", screenRecording = "granted")).blocked)
    }

    @Test
    fun `a missing permissionStatus fails the decode (fail closed)`() {
        val bad = Json.parseToJsonElement("""{"schemaVersion":1,"eventId":"e","eventSeq":0,"occurredAt":0.0,"sessionId":"s","toolCallId":"t"}""") as JsonObject
        val threw = runCatching { CuaPermissionObservation.decode(bad) }
        assertTrue(threw.isFailure)
    }
}
