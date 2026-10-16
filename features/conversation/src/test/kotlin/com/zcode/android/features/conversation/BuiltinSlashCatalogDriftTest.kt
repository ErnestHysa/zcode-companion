package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.port.BuiltinSlashCatalog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A1.2 (gaps-and-polish Phase 1F, P0): the embedded catalog had drifted — 14
 * embedded vs 20 builtins in desktop 3.14.5 (missing /effort /goal /new /plugins
 * /resume /locale). The catalog is now GENERATED from the desktop's own source
 * (tools/drift/extract-builtin-slash-catalog.mjs) and this pin locks it against
 * the committed golden inventory (tools/drift/surface-inventory.json, the
 * extracted desktop list): name-for-name equality, count included.
 */
class BuiltinSlashCatalogDriftTest {

    private fun goldenInventory(): List<String> {
        val data = sequenceOf(File("."), File(".."), File("../.."), File("../../../.."))
            .map { it.resolve("tools/drift/surface-inventory.json") }
            .firstOrNull { it.isFile }
            ?: throw AssertionError("tools/drift/surface-inventory.json not found from any parent dir")
        val root = Json.parseToJsonElement(data.readText()).jsonObject
        // entries is a NAME-KEYED object (extract-surface.mjs shape).
        val entries = root["layers"]!!.jsonObject["slash"]!!.jsonObject["entries"]!!.jsonObject
        return entries.keys.toList()
    }

    @Test
    fun `the embedded catalog matches the extracted desktop list - name for name`() {
        val golden = goldenInventory()
        val embedded = BuiltinSlashCatalog.COMMANDS.map { it.name }

        assertEquals(
            "the embedded builtin catalog has drifted from the desktop (A1.2) — " +
                "golden=${golden.sorted()} embedded=${embedded.sorted()}",
            golden.sorted(),
            embedded.sorted(),
        )
        // (The inventory records the name SET alphabetically; the generated catalog
        // preserves the desktop's own source order — not comparable against it.)
    }

    @Test
    fun `the catalog is non-trivial (sanity against an empty regeneration)`() {
        org.junit.Assert.assertTrue(
            "the golden inventory must carry the full builtin set",
            goldenInventory().size >= 20,
        )
    }
}
