package com.zcode.android.domain.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.fail

/** Phase 9.5 — session `config` key decode (§10.3) with the graded INV-8 policy. */
class SessionConfigStateTest {

    @Test
    fun `decodes the verified config shape`() {
        val config = SessionConfigState.decodeOrNull(buildJsonObject {
            put("provider", "zai"); put("model", "glm-5.3"); put("thought", "standard")
            putJsonArray("thoughtLevels") { add(JsonPrimitive("standard")); add(JsonPrimitive("deep")) }
            put("followupMode", "queue"); put("mode", "build"); put("planEnabled", true)
            put("modelSelection", buildJsonObject { put("providerId", "zai"); put("modelId", "glm-5.3") })
        })!!
        assertEquals("zai", config.provider)
        assertEquals("glm-5.3", config.model)
        assertEquals(listOf("standard", "deep"), config.thoughtLevels)
        assertEquals("build", config.mode)
        assertTrue(config.modeIsSwitchable)
        assertEquals("zai", config.modelSelectionProviderId)
        assertTrue(config.planEnabled == true)
    }

    @Test
    fun `absent mode falls back to the verified default build`() {
        val config = SessionConfigState.decodeOrNull(buildJsonObject {
            put("provider", "p"); put("model", "m"); put("thought", "t")
        })!!
        assertEquals("build", config.mode)
    }

    @Test
    fun `unknown mode string is retained verbatim and flagged non-switchable (INV-8)`() {
        val config = SessionConfigState.decodeOrNull(buildJsonObject {
            put("provider", "p"); put("model", "m"); put("thought", "t"); put("mode", "collab-9")
        })!!
        assertEquals("collab-9", config.mode)
        assertFalse(config.modeIsSwitchable)
    }

    @Test
    fun `missing required field drops the key + counts (never kills the frame)`() {
        val before = SessionProjectionState.malformedKeyDrops
        val state = SessionProjectionState.fromSnapshot(buildJsonObject {
            put("config", buildJsonObject { put("provider", "p") }) // model/thought missing
            put("control", buildJsonObject {
                put("phase", "idle"); put("sessionEnded", false); put("canStop", false)
                put("stopState", "idle"); putJsonArray("activeWorks") {}
            })
        })
        assertNull(state.config, "malformed config key must DROP")
        assertEquals(before + 1, SessionProjectionState.malformedKeyDrops)
        // The frame survives: control decoded fine alongside the dropped key.
        assertEquals("idle", state.control?.phase)
    }

    @Test
    fun `key-level replacement merge updates config whole`() {
        val base = SessionProjectionState(config = SessionConfigState.decodeOrNull(buildJsonObject {
            put("provider", "zai"); put("model", "glm-5.3"); put("thought", "standard"); put("mode", "build")
        }))
        val merged = base.mergedWith(buildJsonObject {
            put("config", buildJsonObject {
                put("provider", "zai"); put("model", "glm-5.1"); put("thought", "deep"); put("mode", "plan")
            })
        })
        assertEquals("glm-5.1", merged.config?.model)
        assertEquals("plan", merged.config?.mode)
        assertEquals("deep", merged.config?.thought)
    }

    @Test
    fun `switchable modes are exactly the verified set (auto excluded)`() {
        assertEquals(listOf("build", "edit", "plan", "yolo"), SessionConfigState.SWITCHABLE_MODES)
        if ("auto" in SessionConfigState.SWITCHABLE_MODES) fail("auto must NOT be user-switchable (§10.3)")
    }
}
