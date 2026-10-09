package com.zcode.android.domain

import com.zcode.android.domain.model.PluginsView
import com.zcode.android.domain.model.RowDecodeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GAP-7 (the project roadmap; docs/protocol/v4-wire.md §12.3) — the plugins
 * list decode pinned to the GENERATED fixtures. Shape fidelity: the STRICT
 * zcodePluginsListResultSchema face (id/name/enabled/source/marketplace +
 * root counts + mcpServerNames + diagnostics), the enabled STATE the viewer
 * displays, and the INV-8 unknown-field tolerance for the optional faces.
 */
class PluginsViewDecodeTest {

    private fun fixture(name: String) = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
        .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
        .firstOrNull { it.isFile }
        ?.let { file ->
            val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
                as kotlinx.serialization.json.JsonArray
            cases.map { it.jsonObject }
                .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        }
        ?.let { Json.parseToJsonElement(it).jsonObject }
        ?: error("phase3-wire-fixtures.json not found")

    @Test
    fun decodesPluginList_matchingFixtureValues() {
        val view = PluginsView.decode(fixture("plugins-list-mixed"))
        assertEquals(2, view.plugins.size)
        val commitAid = view.plugins[0]
        assertEquals("com.example.commit-aid", commitAid.id)
        assertEquals("commit-aid", commitAid.name)
        assertEquals("Conventional-commit helper", commitAid.description)
        assertEquals("1.4.0", commitAid.version)
        assertEquals(true, commitAid.enabled)
        assertEquals("marketplace", commitAid.source)
        assertEquals("example", commitAid.marketplace)
        assertEquals(1, commitAid.skillRootCount)
        assertEquals(3, commitAid.commandRootCount)
        assertEquals(listOf("git-extra"), commitAid.mcpServerNames)
        assertEquals("/plugins/commit-aid", commitAid.rootPath)
        val builtin = view.plugins[1]
        assertEquals(false, builtin.enabled)
        assertEquals("builtin", builtin.marketplace)
        assertEquals(0, builtin.skillRootCount)
        assertEquals(1, view.diagnostics.size)
        assertEquals("manifest.missing_description", view.diagnostics[0].code)
        assertEquals("warning", view.diagnostics[0].severity)
        assertEquals("builtin.core", view.diagnostics[0].pluginId)
    }

    @Test
    fun decodesEmptyPluginList() {
        val view = PluginsView.decode(fixture("plugins-list-empty"))
        assertTrue(view.plugins.isEmpty())
        assertTrue(view.diagnostics.isEmpty())
    }

    @Test
    fun missingEnabled_throwsTypedFault() {
        val broken = fixture("plugins-list-mixed").toMutableMap().apply {
            val arr = remove("plugins") as kotlinx.serialization.json.JsonArray
            val first = (arr[0] as kotlinx.serialization.json.JsonObject).toMutableMap()
            first.remove("enabled")
            put("plugins", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(first))))
        }
        try {
            PluginsView.decode(kotlinx.serialization.json.JsonObject(broken))
            throw AssertionError("expected RowDecodeException")
        } catch (e: RowDecodeException) {
            assertTrue(e.message!!.contains("enabled"))
        }
    }
}
