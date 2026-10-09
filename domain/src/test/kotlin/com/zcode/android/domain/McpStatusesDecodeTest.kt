package com.zcode.android.domain

import com.zcode.android.domain.model.McpStatuses
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GAP-9 (the project roadmap; docs/protocol/v4-wire.md §12.5) — the MCP
 * server status decode pinned to the GENERATED fixtures. Shape fidelity: the
 * STRICT zcodeMcpListResultSchema record face (status/transport/toolCount/
 * updatedAt + error/failureKind/protocolEra), status values rendered VERBATIM
 * (closed wire enums kept RAW — no invented states), and the empty-record face.
 */
class McpStatusesDecodeTest {

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
    fun decodesStatuses_matchingFixtureValues() {
        val statuses = McpStatuses.decode(fixture("mcp-status-mixed")).statuses
        assertEquals(4, statuses.size)
        val connected = statuses["context7"]!!
        assertEquals("connected", connected.status)
        assertEquals("stdio", connected.transport)
        assertEquals(8, connected.toolCount)
        assertEquals("modern", connected.protocolEra)
        assertNull(connected.error)
        val failed = statuses["search"]!!
        assertEquals("failed", failed.status)
        assertEquals("connection_timeout", failed.failureKind)
        assertEquals("connect timeout after 10s", failed.error)
        assertEquals("http", failed.transport)
        assertEquals("disabled", statuses["docs"]!!.status)
        val untrusted = statuses["internal-api"]!!
        assertEquals("untrusted", untrusted.status)
        assertEquals("official_origin_untrusted", untrusted.failureKind)
    }

    @Test
    fun decodesEmptyStatusRecord() {
        assertTrue(McpStatuses.decode(fixture("mcp-status-empty")).statuses.isEmpty())
    }
}
