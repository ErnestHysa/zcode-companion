package com.zcode.android.domain

import com.zcode.android.domain.model.CodingPlanResetStatus
import com.zcode.android.domain.model.ResetOpportunityResult
import com.zcode.android.domain.model.ResetUseResult
import com.zcode.android.domain.model.accountAccessFromProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GAP-4 (android-native-gaps.md; docs/protocol/v4-wire.md §13.2; ADR-020) — the
 * reset-plane decode pinned to the GENERATED phase-4 fixtures, plus the
 * request-side accountAccess DERIVATION: the descriptor is built ONLY from the
 * desktop's builtin provider-id grammar (`account:<family>-<planKind>`,
 * model-provider-types.ts:7-16) — unknown ids derive NOTHING (null → no reset
 * surface, never guessed; ADR-020 §why: the descriptor is a credential-free
 * plan-scope tag, validated against zcodeProviderAccountAccessSchema by the
 * fixture generator).
 */
class CodingPlanResetDecodeTest {

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
    fun decodesResetStatus_matchingFixtureValues() {
        val s = CodingPlanResetStatus.decode(fixture("reset-status-full"))
        assertEquals(listOf(1791937200000L, 1791940800000L), s.fiveHourExpires)
        assertEquals(listOf(1792538400000L), s.weekExpires)
        assertEquals(1791922800000L, s.latestFiveHourUsedAt)
        assertNull(s.latestWeekUsedAt)
        assertEquals(true, s.hasUnreadHistory)
    }

    @Test
    fun decodesEmptyResetStatus() {
        val s = CodingPlanResetStatus.decode(fixture("reset-status-empty"))
        assertTrue(s.fiveHourExpires.isEmpty())
        assertTrue(s.weekExpires.isEmpty())
        assertNull(s.latestFiveHourUsedAt)
        assertNull(s.latestWeekUsedAt)
        assertEquals(false, s.hasUnreadHistory)
    }

    @Test
    fun decodesOpportunityAndUseResults() {
        val granted = ResetOpportunityResult.decode(fixture("reset-opportunity-granted"))
        assertEquals(true, granted.granted)
        assertNull(granted.nextTryAt)
        val denied = ResetOpportunityResult.decode(fixture("reset-opportunity-denied"))
        assertEquals(false, denied.granted)
        assertEquals(1791934200000L, denied.nextTryAt)
        assertEquals(true, ResetUseResult.decode(fixture("reset-use-result")).used)
    }

    @Test
    fun accountAccess_derivesFromBuiltinProviderIdGrammar() {
        // zai individual (the fixture's request descriptor case)
        val zaiIndividual = accountAccessFromProviderId("account:zai-individual-coding-plan", entitled = true)!!
        assertEquals("zhipu-account", zaiIndividual["type"]!!.toString().trim('"'))
        assertEquals("zai", zaiIndividual["accountType"]!!.toString().trim('"'))
        assertEquals("individual-coding-plan", zaiIndividual["mode"]!!.toString().trim('"'))
        assertEquals("true", zaiIndividual["entitled"]!!.toString())
        // bigmodel team
        val bmTeam = accountAccessFromProviderId("account:bigmodel-team-coding-plan", entitled = false)!!
        assertEquals("bigmodel", bmTeam["accountType"]!!.toString().trim('"'))
        assertEquals("team-coding-plan", bmTeam["mode"]!!.toString().trim('"'))
        assertEquals("false", bmTeam["entitled"]!!.toString())
        // start plans
        assertEquals(
            "start-plan",
            accountAccessFromProviderId("account:zai-start-plan", true)!!["mode"]!!.toString().trim('"'),
        )
        assertEquals(
            "individual-coding-plan",
            accountAccessFromProviderId("account:bigmodel-individual-coding-plan", true)!!["mode"]!!.toString().trim('"'),
        )
    }

    @Test
    fun accountAccess_unknownIdsDeriveNothing_neverGuessed() {
        assertNull(accountAccessFromProviderId("custom-openai-key", true))
        assertNull(accountAccessFromProviderId("", true))
        assertNull(accountAccessFromProviderId("account:zai-unknown-plan", true))
        assertNull(accountAccessFromProviderId("account:anthropic-coding-plan", true))
    }
}
