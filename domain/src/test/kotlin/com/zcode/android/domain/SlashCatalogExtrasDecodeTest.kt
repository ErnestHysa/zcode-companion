package com.zcode.android.domain

import com.zcode.android.domain.model.SlashCatalogExtras
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C2 (gaps-and-polish Phase 4C): the two channel reads behind the slash panel's
 * extra sections — `commands.list` → CommandsListResult {commands, userCommands,
 * pluginCommands, capability} (command-types.ts) and `subagents.list` →
 * AgentsListResult {agents, userAgents, pluginAgents, capability}
 * (subagents-types.ts). Disabled entries are dropped (desktop filters `enabled`);
 * `argumentHint` rides as the input hint; scope/source stay raw.
 */
class SlashCatalogExtrasDecodeTest {

    @Test
    fun `commands list yields enabled user and plugin commands with hints`() {
        val obj = Json.parseToJsonElement(
            """{"commands":[{"name":"compact","description":"cli builtin"}],
                "userCommands":[
                  {"id":"u1","name":"deploy","description":"Deploy","argumentHint":"[env]","enabled":true,"source":"user","scope":"project","prompt":"","content":"","filePath":"/x","agentSource":"zcode","location":"project"},
                  {"id":"u2","name":"old","description":"disabled","enabled":false,"source":"user","scope":"global","prompt":"","content":"","filePath":"/y","agentSource":"zcode","location":"user"}],
                "pluginCommands":[{"id":"p1","name":"lint","enabled":true,"source":"plugin","pluginName":"linter","pluginMarketplace":"m","pluginEnabled":true,"prompt":"","content":""}],
                "capability":{"supported":true}}""",
        ).jsonObject
        val cmds = SlashCatalogExtras.decodeCommands(obj)
        assertEquals(listOf("deploy", "lint"), cmds.map { it.name })
        assertEquals("[env]", cmds[0].inputHint)
        assertEquals("user", cmds[0].source)
        assertEquals("plugin", cmds[1].source)
        assertEquals("", cmds[1].description)
    }

    @Test
    fun `subagents list yields enabled agents with scope`() {
        val obj = Json.parseToJsonElement(
            """{"agents":[
                 {"id":"a1","name":"Explore","description":"search","systemPrompt":"","scope":"built-in","enabled":true},
                 {"id":"a2","name":"disabled","description":"x","systemPrompt":"","scope":"user","enabled":false},
                 {"id":"a3","name":"reviewer","description":"reviews","systemPrompt":"","scope":"project"}],
                "userAgents":[],"pluginAgents":[],"capability":{"supported":true}}""",
        ).jsonObject
        val agents = SlashCatalogExtras.decodeAgents(obj)
        assertEquals(listOf("Explore", "reviewer"), agents.map { it.name })
        assertEquals("built-in", agents[0].scope)
        assertEquals("reviews", agents[1].description)
    }

    @Test
    fun `missing arrays decode to empty lists`() {
        assertEquals(0, SlashCatalogExtras.decodeCommands(Json.parseToJsonElement("""{"capability":{}}""").jsonObject).size)
        assertEquals(0, SlashCatalogExtras.decodeAgents(Json.parseToJsonElement("""{}""").jsonObject).size)
    }

    // G25 (vc51 Phase 4B): the VIEWER decode keeps disabled agents (the slash-panel
    // decode filters them); absent `enabled` means enabled (desktop contract).
    @Test
    fun `decodeAgentsFull keeps disabled agents and defaults absent enabled to true`() {
        val obj = Json.parseToJsonElement("""
            {"agents":[
              {"name":"Explore","description":"search","scope":"built-in"},
              {"name":"docs-writer","description":"docs","scope":"project","enabled":false},
              {"description":"broken"}
            ]}
        """.trimIndent()).jsonObject
        val agents = SlashCatalogExtras.decodeAgentsFull(obj)
        assertEquals(2, agents.size)
        assertEquals("Explore", agents[0].name)
        assertEquals(true, agents[0].enabled)
        assertEquals("docs-writer", agents[1].name)
        assertEquals(false, agents[1].enabled)
        assertEquals("project", agents[1].scope)
        // and the slash-panel decode still filters the disabled one
        assertEquals(1, SlashCatalogExtras.decodeAgents(obj).size)
    }
}
