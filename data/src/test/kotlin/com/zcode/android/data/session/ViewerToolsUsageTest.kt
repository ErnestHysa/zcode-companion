package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.core.transport.TransportFailure
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.AppUsageSnapshot
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GAP-5 wire leg (docs/protocol/v4-wire.md §12.1) over the REAL RelayTransport ↔
 * MockZCode (INV-15: synthetic payloads only). Pins the phone call shape — the
 * `zcode-agent` channel method `getAppUsageStats` with array-wrapped
 * `{range, timeZone}` (v4-wire.md §7 convention) — and the result decode into
 * the domain model from the GENERATED fixture.
 */
class ViewerToolsUsageTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    private val sid: String get() = "d_gap5_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var tools: ViewerTools

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun fixtureObject(name: String): kotlinx.serialization.json.JsonObject {
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
                                id = "ep-gap5",
                                kind = EndpointKind.RELAY_ZAI,
                                displayName = "Gap5MockBook",
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
    fun `appUsageStats rides getAppUsageStats and decodes the fixture snapshot`() {
        val scripted = fixtureObject("app-usage-full")
        connect { m -> m.script.appUsageStats = scripted }
        runBlocking {
            val snapshot = tools.appUsageStats("7d", "Europe/London")
            assertEquals("7d", snapshot.range)
            assertEquals("agent-db", snapshot.source)
            assertEquals(184600L, snapshot.summary.totalTokens)
            assertEquals(2, snapshot.heatmap.weeks.size)
        }
    }

    @Test
    fun `appUsageStats with no scripted result fails with a typed fault`() {
        connect()
        runBlocking {
            assertFailsWith<TransportFailure> { tools.appUsageStats("all", "Europe/London") }
        }
    }

    @Test
    fun `decoded zero snapshot carries the honest empty face`() {
        connect { m -> m.script.appUsageStats = fixtureObject("app-usage-zero") }
        runBlocking {
            val snapshot = tools.appUsageStats("all", "Europe/London")
            assertEquals(0L, snapshot.summary.totalTokens)
            assertEquals(null, snapshot.summary.favoriteModel)
            assertTrue(snapshot.tools.isEmpty())
        }
    }
}
