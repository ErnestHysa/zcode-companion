package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G42-P23 (vc52 Phase 12) mock self-test: the management verbs MUTATE the scripted
 * savedWorkflows fixture — delete removes the row, updateSavedWorkflowMeta rewrites
 * the description, move acks {ok:true} — so the phone's hub reflects the change on
 * reload (the rig-fidelity contract; unscripted = the typed fault).
 */
class MockZCodeWorkflowManagementSelfTest {

    private fun mock(withList: Boolean = true): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_selftest_g42")
        if (withList) {
            m.script.savedWorkflows = JsonObject(mapOf(
                "workflows" to JsonArray(listOf(
                    JsonObject(mapOf(
                        "name" to JsonPrimitive("release-notes"), "description" to JsonPrimitive("Draft the notes"),
                        "scope" to JsonPrimitive("project"), "path" to JsonPrimitive("/synthetic/ws/.zcode/workflows/release-notes.ts"),
                    )),
                    JsonObject(mapOf(
                        "name" to JsonPrimitive("lint-all"), "description" to JsonPrimitive("Lint everything"),
                        "scope" to JsonPrimitive("project"), "path" to JsonPrimitive("/synthetic/ws/.zcode/workflows/lint-all.ts"),
                    )),
                )),
                "invalid" to JsonArray(emptyList()),
                "dir" to JsonPrimitive("/synthetic/ws/.zcode/workflows"),
            ))
        }
        return m
    }

    private fun workflows(m: MockZCode): List<String> =
        ((m.script.savedWorkflows?.get("workflows") as? JsonArray) ?: JsonArray(emptyList()))
            .map { (it.jsonObject["name"] as JsonPrimitive).content }

    @Test
    fun `delete removes the row from the fixture and acks ok`() {
        val m = mock()
        m.workflowManagementPromiseForTest("deleteSavedWorkflow", JsonObject(mapOf("name" to JsonPrimitive("release-notes"))))
        assertEquals(listOf("lint-all"), workflows(m), "the fixture mutates — the reload reflects it")
    }

    @Test
    fun `updateMeta rewrites the description in the fixture`() {
        val m = mock()
        m.workflowManagementPromiseForTest(
            "updateSavedWorkflowMeta",
            JsonObject(mapOf(
                "name" to JsonPrimitive("lint-all"),
                "meta" to JsonObject(mapOf("description" to JsonPrimitive("Lint every module now"))),
            )),
        )
        val desc = ((m.script.savedWorkflows?.get("workflows") as? JsonArray) ?: JsonArray(emptyList()))
            .map { it.jsonObject }
            .first { (it["name"] as JsonPrimitive).content == "lint-all" }["description"]
        assertEquals("Lint every module now", (desc as JsonPrimitive).content)
    }

    @Test
    fun `move acks ok without breaking the fixture`() {
        val m = mock()
        m.workflowManagementPromiseForTest("moveSavedWorkflow", JsonObject(mapOf("name" to JsonPrimitive("lint-all"))))
        assertEquals(2, workflows(m).size)
    }

    @Test
    fun `unscripted fixtures refuse with the typed fault`() {
        val m = mock(withList = false)
        val error = m.workflowManagementPromiseForTest("deleteSavedWorkflow", JsonObject(mapOf("name" to JsonPrimitive("x"))))
        assertNotNull(error)
        assertTrue(error.contains("fault.mock.unscripted"))
    }
}
