package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GAP-6 wire legs (docs/protocol/v4-wire.md §12.2) over the REAL RelayTransport ↔
 * MockZCode (INV-15: synthetic payloads only). Pins:
 *  - `zcode-agent` → `listAutomations` with the workspace-target form;
 *  - the `off-peak-task` channel `list` call (NO args — Arr([]) like
 *    model-selection.getView) and the CLIENT-side projection the desktop's own
 *    protocol handler applies (workspace filter + createdAt DESC + cap 20,
 *    zcodeAgentService.ts:2666-2671).
 */
class ViewerToolsAutomationsTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    private val sid: String get() = "d_gap6_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var tools: ViewerTools

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun fixture(name: String): kotlinx.serialization.json.JsonObject {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase3-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return Json.parseToJsonElement(data).jsonObject
    }

    private fun connect(configure: (MockZCode) -> Unit = {}) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                mock = MockZCode(passHash = passHash, expectedSid = sid)
                configure(mock)
                val url = mock.start()
                transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
                runBlocking {
                    withTimeout(30_000) {
                        transport.connect(
                            EndpointDescriptor(
                                id = "ep-gap6",
                                kind = EndpointKind.RELAY_ZAI,
                                displayName = "Gap6MockBook",
                                wsUrl = url,
                                workspaceKey = "synthetic-ws",
                                workspacePath = "/synthetic/ws",
                                deviceSid = sid,
                                deviceMid = "mock-desktop-mid",
                            ),
                            signer(),
                        )
                    }
                }
                tools = ViewerTools(transport)
                return
            } catch (e: Exception) {
                runBlocking { runCatching { transport.close("connect retry") } }
                runCatching { mock.stop() }
                if (attempt >= 2) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun signer(): CredentialSigner = CredentialSigner { nonce, role, sid ->
        MockZCode.hmacProof(passHash, nonce, role, sid)
    }

    @Test
    fun `automations rides listAutomations and decodes the fixture list`() {
        val scripted = fixture("automation-list-mixed")["automations"]!! as JsonArray
        connect { m -> m.script.listAutomations = scripted }
        runBlocking {
            val rows = tools.automations("/synthetic/ws", "synth-ws-identity-key")
            assertEquals(3, rows.size)
            assertEquals("auto-1", rows[0].automationId)
            assertEquals("daily", rows[0].scheduleRule?.unit)
        }
    }

    @Test
    fun `automations with no scripted result fails with a typed fault`() {
        connect()
        runBlocking {
            com.zcode.android.core.transport.TransportFailure::class.let {
                try {
                    tools.automations("/synthetic/ws", null)
                    throw AssertionError("expected TransportFailure")
                } catch (e: com.zcode.android.core.transport.TransportFailure) {
                    // expected
                }
            }
        }
    }

    @Test
    fun `offPeakTasks filters to the workspace, sorts newest-first and caps at 20`() {
        val scripted = fixture("offpeak-list-mixed")["tasks"]!! as JsonArray
        connect { m ->
            // 22 synthetic same-workspace tasks + the fixture rows (one in /other/ws).
            val bulk = JsonArray((0 until 22).map { i ->
                kotlinx.serialization.json.buildJsonObject {
                    put("offPeakTaskId", kotlinx.serialization.json.JsonPrimitive("bulk-$i"))
                    put("title", kotlinx.serialization.json.JsonPrimitive("Bulk $i"))
                    put("status", kotlinx.serialization.json.JsonPrimitive("queued"))
                    put("createdAt", kotlinx.serialization.json.JsonPrimitive(1_791_000_000_000L + i))
                    put("prompt", kotlinx.serialization.json.JsonPrimitive("p"))
                    put("permissionMode", kotlinx.serialization.json.JsonPrimitive("build"))
                    put("workspaceKey", kotlinx.serialization.json.JsonPrimitive("/synthetic/ws"))
                    put("workspacePath", kotlinx.serialization.json.JsonPrimitive("/synthetic/ws"))
                    put("queuedAt", kotlinx.serialization.json.JsonPrimitive(1_791_000_000_000L + i))
                }
            })
            m.script.offPeakTaskList = JsonArray(bulk + scripted)
        }
        runBlocking {
            val rows = tools.offPeakTasks("/synthetic/ws")
            assertEquals(20, rows.size)
            // The /other/ws fixture row must be filtered OUT.
            assertTrue(rows.none { it.workspaceKey == "/other/ws" })
            // Newest-first: the fixture rows carry the newest createdAt values,
            // then the bulk block fills the 20-cap (25 same-workspace rows → 20:
            // 3 fixture + bulk-21…bulk-5).
            assertEquals("opt-1", rows.first().offPeakTaskId)
            assertEquals("opt-4", rows[2].offPeakTaskId)
            assertEquals("bulk-21", rows[3].offPeakTaskId)
            assertEquals("bulk-5", rows.last().offPeakTaskId)
        }
    }

    @Test
    fun `offPeakTasks with no scripted result fails with a typed fault`() {
        connect()
        runBlocking {
            try {
                tools.offPeakTasks("/synthetic/ws")
                throw AssertionError("expected TransportFailure")
            } catch (e: com.zcode.android.core.transport.TransportFailure) {
                // expected
            }
        }
    }
}
