package com.zcode.android.data.session

import com.zcode.android.domain.model.CuaPermissionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * pass-0 Phase 10 — G101's decoder pin. `cua-permission` channel `getStatus`
 * (asar 3.18.1 `ec475e6e…`): the 9 typed fields (available/reason/grantOwner/
 * grantOwnerDisplayName/accessibility/accessibilityProbeOk/screenRecording/
 * screenCaptureProbeOk/idle). Only strict-true probes are true — the desktop's
 * own reads are `accessibility_probe_ok===!0` / `classification==="functional"`.
 * restartHelper stays UNCALLED (the dated no-action decision rides the ledger).
 */
class Pass0Phase10CuaStatusDecodeTest {

    @Test
    fun `keeps strict-true probes and the grant owner`() {
        val granted = """
            {"available":true,"grantOwner":"ernest","grantOwnerDisplayName":"Ernest",
             "accessibility":true,"accessibilityProbeOk":true,"screenRecording":true,
             "screenCaptureProbeOk":false,"idle":false}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        val s = CuaPermissionStatus.decode(granted)
        assertEquals(true, s.available)
        assertEquals("Ernest", s.grantOwnerDisplayName)
        assertEquals(true, s.accessibilityProbeOk)
        assertEquals(false, s.screenCaptureProbeOk)
        assertNull(s.reason)
    }

    @Test
    fun `the startup failure shape carries the typed reason and idle`() {
        val starting = """
            {"available":false,"reason":"ZCode Computer Use is starting up; retry in a moment.","idle":true}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        val s = CuaPermissionStatus.decode(starting)
        assertEquals(false, s.available)
        assertEquals("ZCode Computer Use is starting up; retry in a moment.", s.reason)
        assertEquals(true, s.idle)
        assertNull(s.grantOwner)
    }
}
