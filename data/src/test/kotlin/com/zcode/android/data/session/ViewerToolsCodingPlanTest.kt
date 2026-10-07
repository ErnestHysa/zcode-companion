package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.core.transport.TransportFailure
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.UsageEntitlement
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
 * GAP-3 wire leg (ADR-020; docs/protocol/v4-wire.md §13.1) over the REAL
 * RelayTransport ↔ MockZCode (INV-15: synthetic payloads only). Pins the phone
 * call shape — the `usage-stats` channel method `getEntitlementSnapshot` with
 * the §7 ARRAY-wrapped arg convention — and the result decode into the domain
 * model from the GENERATED fixture. The §13.3 fallback (channel refused →
 * TransportFailure) is the hidden-panel trigger the UI layer translates.
 */
class ViewerToolsCodingPlanTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    private val sid: String get() = "d_gap4_${System.identityHashCode(this)}"

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
            .map { it.resolve("fixtures/protocol/phase4-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase4-wire-fixtures.json not found")
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
                                id = "ep-gap4",
                                kind = EndpointKind.RELAY_ZAI,
                                displayName = "Gap4MockBook",
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
    fun `entitlement rides usage-stats getEntitlementSnapshot and decodes the fixture`() {
        val scripted = fixtureObject("entitlement-full")
        connect { m ->
            m.script.usageEntitlement = scripted
            m.script.refuseChannels = emptySet()
        }
        runBlocking {
            val entitlement = tools.entitlement()
            assertEquals("account:zai-individual-coding-plan", entitlement.providerId)
            assertEquals("glm-coding-pro", entitlement.planLevel)
            assertEquals(4, entitlement.limits.size)
            assertEquals("MCP_USAGE_LIMIT", entitlement.mcpAggregate!!.type)
            // call-shape pin: the §7 convention wraps promise args in an ARRAY
            val calls = mock.script.usageStatsCalls
            assertEquals(1, calls.size)
            val (method, argKind, params) = calls[0]
            assertEquals("getEntitlementSnapshot", method)
            assertEquals("arr", argKind)
            assertTrue(params.isEmpty(), "params should be the empty request object: $params")
        }
    }

    @Test
    fun `entitlement with no scripted result fails with a typed fault`() {
        connect()
        runBlocking {
            assertFailsWith<TransportFailure> { tools.entitlement() }
        }
    }

    @Test
    fun `refused usage-stats channel surfaces the typed refusal`() {
        connect { m -> m.script.refuseChannels = setOf("usage-stats") }
        runBlocking {
            val failure = assertFailsWith<TransportFailure> { tools.entitlement() }
            assertTrue(
                (failure.message ?: "").contains("usage-stats"),
                "refusal detail expected: ${failure.message}",
            )
        }
    }

    @Test
    fun `decoded fixture carries the unavailable face fields verbatim`() {
        connect { m -> m.script.usageEntitlement = fixtureObject("entitlement-no-plan") }
        runBlocking {
            val entitlement = tools.entitlement()
            assertEquals("no_plan", entitlement.unavailableReason)
            assertEquals(null, entitlement.providerId)
            assertTrue(entitlement.limits.isEmpty())
        }
    }

    // ---------------------------------------------------------------- GAP-4 reset words (ADR-020, §13.2)

    @Test
    fun `reset status rides getCodingPlanResetStatus with the derived scope descriptor`() {
        connect { m ->
            m.script.usageEntitlement = fixtureObject("entitlement-full")
            m.script.codingPlanResetStatus = fixtureObject("reset-status-full")
        }
        runBlocking {
            val e = tools.entitlement()
            val s = tools.resetStatus(e.providerId!!, entitled = true)
            assertEquals(listOf(1791937200000L, 1791940800000L), s.fiveHourExpires)
            assertEquals(1791922800000L, s.latestFiveHourUsedAt)
            assertEquals(true, s.hasUnreadHistory)
            // call shape: the scope request carries the builtin provider id and
            // the DERIVED accountAccess descriptor (== the generated fixture case)
            val (method, argKind, params) = mock.script.usageStatsCalls.last()
            assertEquals("getCodingPlanResetStatus", method)
            assertEquals("arr", argKind)
            assertEquals(
                "account:zai-individual-coding-plan",
                (params["preferredProviderId"] as kotlinx.serialization.json.JsonPrimitive).content,
            )
            assertEquals(fixtureObject("account-access-zai-individual"), params["accountAccess"])
        }
    }

    @Test
    fun `useReset rides useCodingPlanReset with idempotencyKey and resetType - history read returns Unit`() {
        connect { m -> m.script.resetUse = fixtureObject("reset-use-result") }
        runBlocking {
            val result = tools.useReset(
                "account:zai-individual-coding-plan", true, "synthetic-idempotency-key-0", "FIVE_HOUR",
            )
            assertEquals(true, result.used)
            // markCodingPlanResetHistoryRead must tolerate the Promise<void> reply
            tools.markResetHistoryRead("account:zai-individual-coding-plan", true)
            val calls = mock.script.usageStatsCalls
            assertEquals(2, calls.size)
            val (useMethod, useArgKind, useParams) = calls[0]
            assertEquals("useCodingPlanReset", useMethod)
            assertEquals("arr", useArgKind)
            assertEquals("FIVE_HOUR", (useParams["resetType"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(
                "synthetic-idempotency-key-0",
                (useParams["idempotencyKey"] as kotlinx.serialization.json.JsonPrimitive).content,
            )
            assertEquals(fixtureObject("account-access-zai-individual"), useParams["accountAccess"])
            assertEquals("markCodingPlanResetHistoryRead", calls[1].first)
        }
    }

    @Test
    fun `opportunity evaluation rides requestCodingPlanResetOpportunity`() {
        connect { m -> m.script.resetOpportunity = fixtureObject("reset-opportunity-denied") }
        runBlocking {
            val result = tools.resetOpportunity(
                "account:zai-individual-coding-plan", true, "synthetic-idempotency-key-1",
            )
            assertEquals(false, result.granted)
            assertEquals(1791934200000L, result.nextTryAt)
            assertEquals("requestCodingPlanResetOpportunity", mock.script.usageStatsCalls.last().first)
        }
    }
}
