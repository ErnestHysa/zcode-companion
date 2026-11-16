package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pass-33 rig self-tests — the row-kind render-matrix arm (Part AJ's fresh recon):
 *
 * MOCK_ROW_KINDS=1 seeds the never-driven row-kind faces into the snapshot:
 *
 * 1. The OPENABLE artifact row (`rig-p33-report.html`) with REAL bytes seeded under
 *    its ref — the preview card's Open affordance downloads via readFileRange, which
 *    (unlike readTextFile) has NO default-content fallback, so the bytes seed is the
 *    difference between the viewer handoff and a silent download failure.
 * 2. The QUIET-fallback artifact row (`rig-p33-blob.xyz`) — unknown extension, ref
 *    deliberately unseeded (the download-failure path is a rig target, not a bug).
 * 3. The unknown-kind tolerance row (`futureWidget`) — the INV-8 §19.5 face.
 * 4. A NON-compact timelineMarker (`checkpoint`) — the generic QuietMetaRow face.
 * 5. A STREAMING reasoning row — the auto-expanded thinking face.
 *
 * Default (unarmed) serves none of them. Composes with MOCK_HOOKS/MOCK_COMPACT.
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass33RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 33 }), expectedSid = "d_p33")
        m.activeSessionForTest("sess-p33")
        block?.let { m.script.it() }
        return m
    }

    private fun kinds(rows: JsonArray): List<String> =
        rows.map { ((it as JsonObject)["kind"] as JsonPrimitive).content }

    private fun firstRow(rows: JsonArray, kind: String): JsonObject? =
        rows.firstOrNull { ((it as JsonObject)["kind"] as JsonPrimitive).content == kind } as JsonObject?

    // ---- the arm ----

    @Test
    fun `unarmed default serves no row-kind demo faces`() {
        val m = mock()
        val rows = m.snapshotRowsForTest("sess-p33", 6)
        assertFalse(kinds(rows).contains("artifact"), "artifact rows ride the arm only")
        assertFalse(kinds(rows).contains("futureWidget"), "the unknown-kind row rides the arm only")
        assertFalse(m.script.rowKindsDemo)
    }

    @Test
    fun `armed arm serves all five faces`() {
        val m = mock { rowKindsDemo = true }
        val rows = m.snapshotRowsForTest("sess-p33", 6)
        val all = kinds(rows)
        // 1+2(+2b): three artifact rows.
        val artifacts = rows.filter { ((it as JsonObject)["kind"] as JsonPrimitive).content == "artifact" }.map { it as JsonObject }
        assertEquals(3, artifacts.size, "the openable + the quiet-fallback + the openable-unserved artifact rows")
        val openable = artifacts.first()
        assertEquals("rig-p33-report.html", ((openable["displayName"]) as JsonPrimitive).content)
        assertEquals("rig-p33-report.html", ((openable["ref"]) as JsonPrimitive).content)
        val quiet = artifacts[1]
        assertEquals("rig-p33-blob.xyz", ((quiet["displayName"]) as JsonPrimitive).content)
        val unserved = artifacts[2]
        assertEquals("rig-p33-missing.html", ((unserved["displayName"]) as JsonPrimitive).content)
        assertEquals("rig-p33-missing.html", ((unserved["ref"]) as JsonPrimitive).content)
        // 3: the unknown-kind row (raw shape — the app's decoder routes it to Unknown).
        assertTrue(all.contains("futureWidget"), "the unknown-kind tolerance row")
        // 4: the non-compact marker.
        val marker = firstRow(rows, "timelineMarker")
        assertEquals("checkpoint", (((marker!!["marker"]) as JsonObject)["type"] as JsonPrimitive).content)
        // 5: the streaming reasoning row.
        val streaming = rows.lastOrNull { ((it as JsonObject)["kind"] as JsonPrimitive).content == "reasoning" } as JsonObject?
        assertEquals("streaming", ((streaming!!["state"]) as JsonPrimitive).content)
    }

    @Test
    fun `openable artifact bytes are seeded for the download affordance`() {
        val m = mock { rowKindsDemo = true }
        m.snapshotRowsForTest("sess-p33", 6)
        val bytes = m.script.fileBytes["rig-p33-report.html"]
        assertTrue(bytes != null && bytes.isNotEmpty(), "readFileRange has no default-content fallback — the ref MUST carry real bytes")
        assertTrue(String(bytes, Charsets.UTF_8).contains("<h1>"), "the seeded payload is the HTML report")
        assertFalse(m.script.fileBytes.containsKey("rig-p33-blob.xyz"), "the quiet artifact's ref stays UNSEEDED (the download-failure face is the rig target)")
        assertFalse(m.script.fileBytes.containsKey("rig-p33-missing.html"), "the openable-unserved artifact's ref stays UNSEEDED — the card renders but readFileRange faults (stage-1 of the silent open failure)")
    }

    @Test
    fun `artifact rows carry every fail-closed decoder field`() {
        val m = mock { rowKindsDemo = true }
        val rows = m.snapshotRowsForTest("sess-p33", 6)
        val openable = firstRow(rows, "artifact")!!
        for (field in listOf("artifactVersionId", "logicalArtifactKey", "displayName", "artifactType", "mimeType", "sizeBytes", "sha256", "ref")) {
            assertTrue(openable[field] is JsonPrimitive, "required field $field present (the domain decoder fails closed without it)")
        }
        assertEquals(openable["sizeBytes"], JsonPrimitive(m.script.fileBytes["rig-p33-report.html"]!!.size.toLong()),
            "sizeBytes mirrors the seeded bytes (the card's size detail)")
    }

    @Test
    fun `the unknown-kind row keeps full base identity`() {
        val m = mock { rowKindsDemo = true }
        val rows = m.snapshotRowsForTest("sess-p33", 6)
        val unknown = firstRow(rows, "futureWidget")!!
        for (field in listOf("rowId", "turnId", "kind", "createdAt", "createdAtSeq")) {
            assertTrue(unknown[field] is JsonPrimitive, "base field $field present")
        }
    }
}
