package com.zcode.android.core.protocol.v4

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GAP-12 (android-native-gaps.md; docs/protocol/v4-wire.md §11.2) — the
 * workspace-config topic decode, pinned against the GENERATED golden fixtures
 * (rule 5). Snapshot: {protocolVersion:1, workspaceId, logEpoch, config:
 * {configOptions[], slashCommands[]}}; exactly one delta op `config.updated`.
 * Tolerance: unknown FIELDS ride `raw` (INV-8); slashCommand.source outside the
 * verified enum is retained RAW (never guessed); a missing REQUIRED field is a
 * typed fault (INV-14 fail closed — auxiliary surface, the frame is dropped).
 */
class V4WorkspaceConfigTest {

    private fun fixture(name: String): JsonObject {
        val file = sequenceOf(File("."), File(".."), File("../.."))
            .map { it.resolve("fixtures/protocol/phase2-wire-fixtures.json") }
            .firstOrNull { it.isFile }
            ?: error("phase2-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        return cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.jsonObject
    }

    @Test
    fun snapshotFixtureDecodes_catalogAndSlashCommands() {
        val snap = WorkspaceConfigSnapshot.decode(fixture("workspace-config-snapshot"))
        assertEquals(1L, snap.protocolVersion)
        assertEquals("synth-ws-identity-key", snap.workspaceId)
        assertEquals("cfg-epoch-1", snap.logEpoch)
        assertEquals(2, snap.config.configOptions.size)
        assertEquals("model", snap.config.configOptions[0].id)
        assertEquals(5, snap.config.slashCommands.size)
        val compact = snap.config.slashCommands.first { it.name == "compact" }
        assertEquals("builtin", compact.source)
        assertEquals("[instructions]", compact.inputHint)
        val custom = snap.config.slashCommands.first { it.name == "deploy" }
        assertEquals("custom", custom.source)
        assertEquals("<env>", custom.inputHint)
    }

    @Test
    fun legacyEntry_absentSource_staysNull_notGuessed() {
        val snap = WorkspaceConfigSnapshot.decode(fixture("workspace-config-snapshot"))
        val help = snap.config.slashCommands.first { it.name == "help" }
        assertEquals(null, help.source, "source absent = legacy payload — null, never defaulted")
    }

    @Test
    fun deltaFixture_configUpdatedWholeReplaces() {
        val delta = WorkspaceConfigDelta.decode(fixture("workspace-config-delta"))
        assertEquals("config.updated", delta.op)
        assertEquals(2, delta.config.slashCommands.size)
        assertEquals(0, delta.config.configOptions.size)
    }

    @Test
    fun unknownSource_retainedRaw_inV8() {
        val raw = Json.parseToJsonElement(
            """
            {"protocolVersion":1,"workspaceId":"ws","logEpoch":"e1","config":{
              "configOptions":[],
              "slashCommands":[{"name":"future","description":"d","source":"workspaceSkill"}]}}
            """.trimIndent(),
        ).jsonObject
        val snap = WorkspaceConfigSnapshot.decode(raw)
        assertEquals("workspaceSkill", snap.config.slashCommands.single().source)
    }

    @Test
    fun unknownFields_toleratedAndRetained() {
        val raw = Json.parseToJsonElement(
            """
            {"protocolVersion":1,"workspaceId":"ws","logEpoch":"e1","futureTopLevel":42,"config":{
              "configOptions":[],
              "slashCommands":[{"name":"a","description":"d","futureField":true}]}}
            """.trimIndent(),
        ).jsonObject
        val snap = WorkspaceConfigSnapshot.decode(raw)
        assertEquals("a", snap.config.slashCommands.single().name)
        assertTrue(snap.raw.containsKey("futureTopLevel"))
    }

    @Test
    fun missingRequiredField_typedFault_failClosed() {
        val raw = Json.parseToJsonElement("""{"protocolVersion":1,"workspaceId":"ws","logEpoch":"e1"}""").jsonObject
        assertFailsWith<com.zcode.android.core.protocol.bytes.ProtocolException> {
            WorkspaceConfigSnapshot.decode(raw)
        }
    }

    @Test
    fun unknownDeltaOp_typedFault_auxiliaryFrameDropped() {
        val raw = Json.parseToJsonElement("""{"op":"config.somethingNew","config":{}}""").jsonObject
        assertFailsWith<com.zcode.android.core.protocol.bytes.ProtocolException> {
            WorkspaceConfigDelta.decode(raw)
        }
    }

    @Test
    fun topicHelpers_matchDesktopConstruction() {
        assertEquals("workspace-config/ws-a", V4Topics.workspaceConfig("ws-a"))
        assertEquals("ws-a", parseWorkspaceConfigTopic("workspace-config/ws-a"))
        assertEquals(null, parseWorkspaceConfigTopic("conversation/ws-a"))
        assertEquals(null, parseWorkspaceConfigTopic("workspace-config/"))
    }
}
