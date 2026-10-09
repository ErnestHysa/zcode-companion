package com.zcode.android.domain

import com.zcode.android.domain.model.RowDecodeException
import com.zcode.android.domain.model.SkillCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GAP-8 (the project roadmap; docs/protocol/v4-wire.md §12.4) — the skills
 * reference-catalog decode pinned to the GENERATED fixtures. Shape fidelity:
 * the STRICT zcodeSkillsReferenceCatalogResultSchema face (authority +
 * id/name/description/path/scope), `enabled` is z.literal(true) on this plane
 * so the model carries NO enabled field (a viewer must not render an enable
 * toggle from it), and pluginName when the skill comes from a plugin.
 */
class SkillCatalogDecodeTest {

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
    fun decodesCatalog_matchingFixtureValues() {
        val catalog = SkillCatalog.decode(fixture("skills-catalog-mixed"))
        assertEquals("workspace", catalog.authority)
        assertEquals(3, catalog.skills.size)
        val first = catalog.skills[0]
        assertEquals("skill-1", first.id)
        assertEquals("commit-helper", first.name)
        assertEquals("Drafts conventional commits.", first.description)
        assertEquals("workspace", first.scope)
        assertNull(first.pluginName)
        val pluginSkill = catalog.skills[2]
        assertEquals("plugin", pluginSkill.scope)
        assertEquals("commit-aid", pluginSkill.pluginName)
        // `enabled` is z.literal(true) on the reference plane — the model must NOT carry it.
        assertTrue(
            "no enabled field on SkillEntry",
            SkillCatalog.SkillEntry::class.java.declaredFields.none { it.name == "enabled" },
        )
    }

    @Test
    fun decodesEmptyCatalog() {
        val catalog = SkillCatalog.decode(fixture("skills-catalog-empty"))
        assertEquals("workspace", catalog.authority)
        assertTrue(catalog.skills.isEmpty())
    }

    @Test
    fun missingScope_throwsTypedFault() {
        val broken = fixture("skills-catalog-mixed").toMutableMap().apply {
            val arr = remove("skills") as kotlinx.serialization.json.JsonArray
            val first = (arr[0] as kotlinx.serialization.json.JsonObject).toMutableMap()
            first.remove("scope")
            put("skills", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(first))))
        }
        try {
            SkillCatalog.decode(kotlinx.serialization.json.JsonObject(broken))
            throw AssertionError("expected RowDecodeException")
        } catch (e: RowDecodeException) {
            assertTrue(e.message!!.contains("scope"))
        }
    }
}
