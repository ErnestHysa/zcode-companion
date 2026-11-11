package com.zcode.android.core.protocol.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * G68 (vc52 Phase 12) pin: the opt-in self-report payloads byte-match the desktop's
 * inbound control union (chunk-RB4EITEQ `oB`, asar 4a6080ed…): view-state {viewState:
 * {activeWorkspaceKey?, activeTaskId?, updatedAt}, deviceInfo?} and diagnostic
 * {event ∈ closed 7, timestamp, state?, previousState?, closeCode?, closeReason?,
 * wasClean?, wasPaired?, failureReason?, failureMessage?, visibilityState?, online?,
 * hiddenDurationMs?}. Unknown diagnostic events FAIL LOUDLY (never an invented event).
 */
class MobileSelfReportTest {

    @Test
    fun `view-state update carries the desktop's exact shape`() {
        val payload = MobileSelfReport.viewStateUpdate(
            activeWorkspaceKey = "synthetic-ws",
            activeTaskId = "task-9",
            updatedAtMs = 1_700_000_000_000,
            deviceInfo = MobileSelfReport.deviceInfo("android", "1.3.1", "Pixel"),
        )
        val json = Json.parseToJsonElement(payload.toString()).jsonObject
        assertEquals("mobile-view-state-update", json["zcode_type"]?.toString()?.trim('"'))
        val view = json["viewState"]!!.jsonObject
        assertEquals("synthetic-ws", view["activeWorkspaceKey"]?.toString()?.trim('"'))
        assertEquals("task-9", view["activeTaskId"]?.toString()?.trim('"'))
        assertEquals("1700000000000", view["updatedAt"]?.toString()?.trim('"'))
        val device = json["deviceInfo"]!!.jsonObject
        assertEquals("android", device["platform"]?.toString()?.trim('"'))
        assertEquals("1.3.1", device["version"]?.toString()?.trim('"'))
        assertEquals("Pixel", device["name"]?.toString()?.trim('"'))
    }

    @Test
    fun `view-state tolerates absent keys (optional faces stay absent)`() {
        val json = Json.parseToJsonElement(
            MobileSelfReport.viewStateUpdate(null, null, 5).toString(),
        ).jsonObject
        val view = json["viewState"]!!.jsonObject
        assertEquals(null, view["activeWorkspaceKey"])
        assertEquals(null, view["activeTaskId"])
        assertEquals("5", view["updatedAt"]?.toString()?.trim('"'))
    }

    @Test
    fun `socket-close diagnostic carries the failure facts`() {
        val json = Json.parseToJsonElement(
            MobileSelfReport.diagnostic(
                event = "socket-close",
                timestampMs = 1_700_000_000_000,
                state = "RECONNECTING",
                previousState = "CONNECTED",
                closeCode = 4000,
                closeReason = "normal",
                wasClean = true,
                wasPaired = true,
                online = true,
                hiddenDurationMs = 42_000,
            ).toString(),
        ).jsonObject
        assertEquals("mobile-diagnostic", json["zcode_type"]?.toString()?.trim('"'))
        assertEquals("socket-close", json["event"]?.toString()?.trim('"'))
        assertEquals("4000", json["closeCode"]?.toString()?.trim('"'))
        assertEquals("42000", json["hiddenDurationMs"]?.toString()?.trim('"'))
    }

    @Test
    fun `every one of the seven recorded events builds`() {
        for (event in MobileSelfReport.DIAGNOSTIC_EVENTS) {
            val json = Json.parseToJsonElement(
                MobileSelfReport.diagnostic(event, 1).toString(),
            ).jsonObject
            assertEquals(event, json["event"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `an unknown diagnostic event fails loudly (never an invented event)`() {
        assertFailsWith<IllegalArgumentException> {
            MobileSelfReport.diagnostic("made-up-event", 1)
        }
    }
}
