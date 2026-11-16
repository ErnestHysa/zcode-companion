package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * p41 (pass-41) mock self-tests: MOCK_WS_TWO=1 must append a SECOND workspace row to
 * the relay's shared workspace view (bootstrap-response + workspace-list-response via
 * [MockZCode.relayWorkspacesView]). Until this arm both surfaces served exactly ONE
 * row, so the Manage sheet's reorder arrows were structurally disabled and the chips
 * row had no second chip (pass-40 "did NOT drive"). The second row seeds NO task, so
 * the home's task-key sections are untouched. Synthetic content only.
 */
class MockZCodePass41SecondWorkspaceSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 12 })
        return MockZCode(passHash = passHash, expectedSid = "d_p41")
    }

    private fun rows(view: JsonArray): List<JsonObject> =
        view.mapNotNull { it as? JsonObject }

    private fun str(row: JsonObject, key: String): String? =
        (row[key] as? JsonPrimitive)?.content

    @Test
    fun `default serves exactly the single synthetic-one row`() {
        val m = mock()
        val served = rows(m.relayWorkspacesView())
        assertEquals(1, served.size, "the default view stays single-row (old shapes unchanged)")
        assertEquals("/synthetic/ws-one", str(served[0], "workspacePath"))
        assertEquals("Synthetic One", str(served[0], "label"))
    }

    @Test
    fun `MOCK_WS_TWO appends the synthetic-two row with exact fields`() {
        val m = mock()
        m.script.relaySecondWorkspace = true
        val served = rows(m.relayWorkspacesView())
        assertEquals(2, served.size)
        assertEquals("Synthetic One", str(served[0], "label"), "the primary row is unchanged and FIRST")
        assertEquals("/synthetic/ws-two", str(served[1], "workspacePath"))
        assertEquals("Synthetic Two", str(served[1], "label"))
        assertEquals("local", str(served[1], "kind"))
        assertEquals("/synthetic/ws-two", str(served[1], "workspaceKey"))
    }

    @Test
    fun `the second row keys on its own path per the app decode - no task sections change`() {
        val m = mock()
        m.script.relaySecondWorkspace = true
        val served = rows(m.relayWorkspacesView())
        val keys = served.mapNotNull { str(it, "workspaceIdentity") ?: str(it, "workspacePath") }
        assertTrue("/synthetic/ws-two" in keys && "/synthetic/ws-one" in keys,
            "the app's decode keys on workspaceIdentity ?: workspacePath — both rows must resolve")
        assertTrue(m.script.bootstrapTasks.orEmpty().none { it.workspacePath == "/synthetic/ws-two" },
            "no task is seeded on ws-two (no F-P38-A ghost surface)")
    }
}
