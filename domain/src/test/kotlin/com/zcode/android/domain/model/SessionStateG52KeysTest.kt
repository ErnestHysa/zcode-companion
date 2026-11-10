package com.zcode.android.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * G52 (vc52 Phase 11): the two silently-dropped session-state keys decode and
 * render — `modelTransition` (the registry-fallback marker) and
 * `workspaceHookAdmission` (the hook-trust admission plane), both byte-verified
 * on the shipped asar `12a2fc93…` (chunk-D5C6W4UB `oh` / `R$`). The goal/plan
 * nullable-key discipline: an explicit null is a DECODED fact (the desktop
 * CLEARED the marker); an absent key is not; a malformed KNOWN key drops + counts.
 */
class SessionStateG52KeysTest {

    private val transitionPatch = """
        {"modelTransition":{
            "eventId":"evt-1","origin":"registryFallback",
            "from":{"provider":"zai","model":"glm-5.1"},
            "to":{"provider":"zai","model":"glm-4.7-air"}}}
    """.trimIndent()

    private val admissionPatch = """
        {"workspaceHookAdmission":{"pendingCount":3,"bundleDigest":"abc123","workspaceIdentity":"/synthetic/ws-one"}}
    """.trimIndent()

    @Test
    fun `a seeded patch decodes both keys typed`() {
        val state = SessionProjectionState().mergedWith(Json.parseToJsonElement(transitionPatch) as JsonObject)
        val t = state.modelTransition
        assertTrue(t != null, "the transition decoded")
        assertEquals("evt-1", t!!.eventId)
        assertEquals("registryFallback", t.origin)
        assertEquals("zai", t.fromProvider)
        assertEquals("glm-5.1", t.fromModel)
        assertEquals("zai", t.toProvider)
        assertEquals("glm-4.7-air", t.toModel)
        assertTrue(state.modelTransitionKeyPresent)

        val state2 = SessionProjectionState().mergedWith(Json.parseToJsonElement(admissionPatch) as JsonObject)
        val a = state2.workspaceHookAdmission
        assertTrue(a != null, "the admission decoded")
        assertEquals(3L, a!!.pendingCount)
        assertEquals("abc123", a.bundleDigest)
        assertEquals("/synthetic/ws-one", a.workspaceIdentity)
        assertTrue(state2.workspaceHookAdmissionKeyPresent)
    }

    @Test
    fun `an explicit null marks the key present and CLEARS a prior value`() {
        val seeded = SessionProjectionState().mergedWith(Json.parseToJsonElement(transitionPatch) as JsonObject)
        val cleared = seeded.mergedWith(Json.parseToJsonElement("""{"modelTransition":null}""") as JsonObject)
        assertNull(cleared.modelTransition, "the desktop cleared the marker after rendering")
        assertTrue(cleared.modelTransitionKeyPresent, "the null is a decoded fact, not an absent key")

        val seeded2 = SessionProjectionState().mergedWith(Json.parseToJsonElement(admissionPatch) as JsonObject)
        val cleared2 = seeded2.mergedWith(Json.parseToJsonElement("""{"workspaceHookAdmission":null}""") as JsonObject)
        assertNull(cleared2.workspaceHookAdmission)
        assertTrue(cleared2.workspaceHookAdmissionKeyPresent)
    }

    @Test
    fun `an absent key neither decodes nor marks presence`() {
        val state = SessionProjectionState().mergedWith(Json.parseToJsonElement("""{"revision":7}""") as JsonObject)
        assertNull(state.modelTransition)
        assertFalse(state.modelTransitionKeyPresent)
        assertFalse(state.workspaceHookAdmissionKeyPresent)
    }

    @Test
    fun `a malformed KNOWN key drops and counts (never kills the frame)`() {
        val before = SessionProjectionState.malformedKeyDrops
        val state = SessionProjectionState().mergedWith(
            Json.parseToJsonElement("""{"modelTransition":{"eventId":"x","origin":"registryFallback","from":{"provider":"p"}}}""") as JsonObject,
        )
        assertNull(state.modelTransition, "from.model missing → the key dropped")
        assertEquals(before + 1, SessionProjectionState.malformedKeyDrops)
    }

    @Test
    fun `the snapshot path decodes both keys (round-trip parity with the merge)`() {
        val snapshot = Json.parseToJsonElement(
            """{"modelTransition":{"eventId":"evt-2","origin":"registryFallback",
                "from":{"provider":"zai","model":"glm-5.1"},"to":{"provider":"zai","model":"glm-5.3"}},
               "workspaceHookAdmission":{"pendingCount":0,"bundleDigest":"d1"}}""",
        ) as JsonObject
        val state = SessionProjectionState.fromSnapshot(snapshot)
        assertEquals("evt-2", state.modelTransition?.eventId)
        assertEquals(0L, state.workspaceHookAdmission?.pendingCount)
        assertTrue(state.hasDecodedStateKeys)
    }

    @Test
    fun `the keys count toward hasDecodedStateKeys (an all-absent block stays distinct)`() {
        val state = SessionProjectionState().mergedWith(
            Json.parseToJsonElement("""{"modelTransition":null,"workspaceHookAdmission":null}""") as JsonObject,
        )
        assertTrue(state.hasDecodedStateKeys, "two null-but-present keys ARE decoded state")
    }
}
