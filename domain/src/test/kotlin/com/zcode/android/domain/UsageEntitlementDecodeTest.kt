package com.zcode.android.domain

import com.zcode.android.domain.model.UsageEntitlement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GAP-3 (android-native-gaps.md; docs/protocol/v4-wire.md §13.1; ADR-020) — the
 * coding-plan entitlement decode pinned to the GENERATED phase-4 fixtures. The
 * payload types are desktop TypeScript interfaces (usage-quota.ts /
 * usage-stats.ts:73-89), so the decode is INV-8-tolerant (optional fields may be
 * absent; unknown fields ride the raw object) while REQUIRED faces fail closed.
 * `type` and `unavailableReason` stay RAW — unknown future values display
 * verbatim, never guessed (§9 policy).
 */
class UsageEntitlementDecodeTest {

    private fun fixture(name: String) = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
        .map { it.resolve("fixtures/protocol/phase4-wire-fixtures.json") }
        .firstOrNull { it.isFile }
        ?.let { file ->
            val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
                as kotlinx.serialization.json.JsonArray
            cases.map { it.jsonObject }
                .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        }
        ?.let { Json.parseToJsonElement(it).jsonObject }
        ?: error("phase4-wire-fixtures.json not found")

    @Test
    fun decodesEntitlementFull_matchingFixtureValues() {
        val e = UsageEntitlement.decode(fixture("entitlement-full"))
        assertEquals(1791933600000L, e.generatedAt)
        assertEquals(1791933600123L, e.serverTime)
        assertEquals(true, e.authenticated)
        assertNull(e.unavailableReason)
        assertEquals("account:zai-individual-coding-plan", e.providerId)
        assertEquals("Z.ai Coding Plan", e.providerName)
        assertEquals("glm-coding-pro", e.planLevel)
        // remaining block (UsageEntitlementRemaining)
        assertEquals(320.0, e.remainingCount)
        assertEquals(true, e.remainingIsShow)
        assertEquals(64.0, e.remainingPercentage)
        assertEquals(1791951600000L, e.remainingNextResetTime)
        // four limits decoded in payload order, fields verbatim
        assertEquals(4, e.limits.size)
        val fiveHour = e.limits[0]
        assertEquals("TOKENS_LIMIT", fiveHour.type)
        assertEquals(3.0, fiveHour.unit)
        assertEquals(5.0, fiveHour.number)
        assertEquals(36.0, fiveHour.usage)
        assertEquals(64.0, fiveHour.remaining)
        assertEquals(36.0, fiveHour.percentage)
        assertEquals(1791951600000L, fiveHour.nextResetTime)
        assertEquals(2, fiveHour.usageDetails.size)
        assertEquals("glm-5.3", fiveHour.usageDetails[0].modelCode)
        assertEquals("GLM-5.3", fiveHour.usageDetails[0].displayName)
        assertEquals(30.0, fiveHour.usageDetails[0].usage)
        assertNull(fiveHour.usageDetails[1].displayName)
        assertEquals("TOKENS_LIMIT", e.limits[1].type)
        assertEquals(6.0, e.limits[1].unit)
        assertNull(e.limits[1].number)
        assertEquals("TIME_LIMIT", e.limits[2].type)
        assertEquals(1, e.limits[2].usageDetails.size)
        // the deliberately-unmatched unit-9 limit still DECODES (raw fields kept)
        assertEquals(9.0, e.limits[3].unit)
        // MCP aggregate rides the separate mcpQuota field (NOT in limits[])
        val mcp = e.mcpAggregate
        assertEquals("MCP_USAGE_LIMIT", mcp!!.type)
        assertEquals(22.0, mcp.percentage)
        assertEquals(1792020000000L, mcp.nextResetTime)
        assertEquals("web-reader", mcp.usageDetails[0].modelCode)
    }

    @Test
    fun zaiCreditLimit_decodesRaw_andEmptyDetails() {
        val e = UsageEntitlement.decode(fixture("entitlement-zai-credit"))
        // zai team backends emit CREDIT_LIMIT — kept RAW (the presentation layer
        // treats it as the token category; the decode never renames wire values)
        assertEquals("CREDIT_LIMIT", e.limits[0].type)
        assertEquals(100.0, e.limits[0].percentage)
        assertTrue(e.limits[0].usageDetails.isEmpty())
        assertEquals("account:zai-team-coding-plan", e.providerId)
        assertNull(e.remainingCount)
        assertNull(e.remainingPercentage)
    }

    @Test
    fun noPlanPayload_decodesUnavailableFace() {
        val e = UsageEntitlement.decode(fixture("entitlement-no-plan"))
        assertEquals("no_plan", e.unavailableReason)
        assertNull(e.providerId)
        assertNull(e.planLevel)
        assertTrue(e.limits.isEmpty())
        assertNull(e.mcpAggregate)
        assertNull(e.remainingCount)
    }

    @Test
    fun optionalAbsence_tolerated_unknownFieldsIgnored() {
        // minimal legal snapshot: only the required faces; unknown fields ignored
        val minimal = com.zcode.android.domain.model.UsageEntitlement.decode(
            Json.parseToJsonElement(
                """{"generatedAt": 5, "authenticated": false,
                   "provider": null, "remaining": null, "subscription": null,
                   "quota": null, "someFutureField": {"x": 1}}""",
            ).jsonObject,
        )
        assertEquals(5L, minimal.generatedAt)
        assertEquals(false, minimal.authenticated)
        assertNull(minimal.unavailableReason)
        assertNull(minimal.serverTime)
        assertNull(minimal.planLevel)
        assertTrue(minimal.limits.isEmpty())
        assertNull(minimal.mcpAggregate)
        // percentage absent on a limit → null (the presentation gate, not a decode error)
        val noPercentage = UsageEntitlement.decode(
            Json.parseToJsonElement(
                """{"generatedAt": 6, "authenticated": true,
                   "provider": {"id": "account:zai-start-plan", "name": "Z.ai Start Plan"},
                   "remaining": null, "subscription": null,
                   "quota": {"level": null, "limits": [
                     {"type": "TOKENS_LIMIT", "unit": 3, "number": 5, "usageDetails": []}]}}""",
            ).jsonObject,
        )
        assertNull(noPercentage.limits[0].percentage)
        assertNull(noPercentage.limits[0].nextResetTime)
    }
}
