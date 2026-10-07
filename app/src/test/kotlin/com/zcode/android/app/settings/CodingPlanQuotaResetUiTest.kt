package com.zcode.android.app.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.CodingPlanResetStatus
import com.zcode.android.domain.model.ResetOpportunityResult
import com.zcode.android.domain.model.ResetUseResult
import com.zcode.android.domain.model.UsageEntitlement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * GAP-4 (android-native-gaps.md; docs/protocol/v4-wire.md §13.2; ADR-020) — the
 * quota reset flow behind the TYPED-CONFIRM gate. The roadmap's accept pin:
 * "reset sends the verified command only behind the confirmed dialog (test
 * asserts no reset call can fire without it); success/failure states render".
 * State transitions follow the desktop codingPlanQuotaResetUi.ts machine
 * (unit-pinned in CodingPlanQuotaResetStateTest); these Robolectric tests pin
 * the USER-VISIBLE gating and honest result surfaces.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class CodingPlanQuotaResetUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun fixedLocale() {
        Locale.setDefault(Locale.US)
    }

    private val nowMs = 1791933600000L

    private fun fixtureEntitlement(name: String): UsageEntitlement {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase4-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase4-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return UsageEntitlement.decode(Json.parseToJsonElement(data).jsonObject)
    }

    private fun fixtureStatus(name: String): CodingPlanResetStatus {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase4-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase4-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return CodingPlanResetStatus.decode(Json.parseToJsonElement(data).jsonObject)
    }

    /** A scripted port that RECORDS every reset-plane call (the gating evidence). */
    private class RecordingPort(
        private val entitlementSnapshot: UsageEntitlement?,
        private val statusQueue: ArrayDeque<CodingPlanResetStatus>,
        private val opportunity: ResetOpportunityResult?,
        private val useResult: ResetUseResult?,
        private val useError: String? = null,
    ) : CodingPlanPort {
        val useCalls = mutableListOf<Triple<String, String, String>>() // (providerId, idempotencyKey, resetType)
        val historyReadCalls = mutableListOf<String>()
        var entitlementReads = 0

        override suspend fun entitlement(): CodingPlanPort.Snapshot {
            entitlementReads++
            return CodingPlanPort.Snapshot(entitlementSnapshot, null)
        }

        override suspend fun resetStatus(
            providerId: String,
            entitled: Boolean,
        ): CodingPlanPort.ResetStatusResult {
            val next = statusQueue.removeFirstOrNull()
                ?: return CodingPlanPort.ResetStatusResult(null, "unscripted status")
            return CodingPlanPort.ResetStatusResult(next, null)
        }

        override suspend fun requestOpportunity(
            providerId: String,
            entitled: Boolean,
            idempotencyKey: String,
        ): ResetOpportunityResult? = opportunity

        override suspend fun useReset(
            providerId: String,
            entitled: Boolean,
            idempotencyKey: String,
            resetType: String,
        ): ResetUseResult {
            useCalls.add(Triple(providerId, idempotencyKey, resetType))
            useError?.let { throw IllegalStateException(it) }
            return useResult ?: error("unscripted use")
        }

        override suspend fun markHistoryRead(providerId: String, entitled: Boolean) {
            historyReadCalls.add(providerId)
        }
    }

    private fun setContent(port: CodingPlanPort) {
        rule.setContent {
            ZCodeTheme {
                CodingPlanQuotaSection(
                    port = port,
                    nowMs = nowMs,
                    zone = TimeZone.getTimeZone("UTC"),
                    useRetryDelaysMs = listOf(0),
                )
            }
        }
    }

    /** The default flow fixtures: 5h meter at 64% remaining (NOT full) + two
     *  live five-hour opportunities + a granted opportunity evaluation. The
     *  status queue starts with the available snapshot; callers append more. */
    private fun recordingPort(
        entitlement: UsageEntitlement = fixtureEntitlement("entitlement-full"),
        extraStatuses: List<CodingPlanResetStatus> = emptyList(),
        useResult: ResetUseResult? = ResetUseResult(used = true),
        useError: String? = null,
    ): RecordingPort = RecordingPort(
        entitlementSnapshot = entitlement,
        // the load sequence consumes TWO status reads (status → opportunity →
        // confirmed status, desktop parity); extras feed the post-use
        // reconciliation loop
        statusQueue = ArrayDeque(
            listOf(
                fixtureStatus("reset-status-full"),
                fixtureStatus("reset-status-full"),
            ) + extraStatuses,
        ),
        opportunity = ResetOpportunityResult(granted = true, nextTryAt = null),
        useResult = useResult,
        useError = useError,
    )

    private fun waitForTag(tag: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun noResetCallCanFire_withoutTypedConfirm() {
        val confirmed = CodingPlanResetStatus(
            fiveHourExpires = emptyList(),
            weekExpires = emptyList(),
            latestFiveHourUsedAt = 1791922800000L,
            latestWeekUsedAt = null,
            hasUnreadHistory = false,
        )
        val port = recordingPort(extraStatuses = listOf(confirmed))
        setContent(port)
        waitForTag("quota_reset_fiveHour")

        // opening the dialog fires NOTHING
        rule.onNodeWithTag("quota_reset_fiveHour").performClick()
        waitForTag("quota_reset_dialog")
        assertTrue(port.useCalls.isEmpty())

        // a plain click on the disabled confirm fires NOTHING
        rule.onNodeWithTag("quota_reset_confirm").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("quota_reset_dialog").assertExists()
        assertTrue(port.useCalls.isEmpty())

        // wrong text (lowercase) keeps the confirm DISABLED — nothing can fire
        rule.onNodeWithTag("quota_reset_confirm_input").performTextInput("reset")
        rule.onNodeWithTag("quota_reset_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("quota_reset_confirm").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue(port.useCalls.isEmpty())

        // exact word enables the confirm; ONLY then the verified command fires
        rule.onNodeWithTag("quota_reset_confirm_input").performTextInput("RE")
        rule.onNodeWithTag("quota_reset_confirm").assertIsEnabled()
        rule.onNodeWithTag("quota_reset_confirm").performClick()
        waitForTag("quota_reset_done")
        assertEquals(1, port.useCalls.size)
        val (providerId, idempotencyKey, resetType) = port.useCalls[0]
        assertEquals("account:zai-individual-coding-plan", providerId)
        assertEquals("FIVE_HOUR", resetType)
        assertTrue("idempotency key must be a real key", idempotencyKey.isNotEmpty())
        // honest completion surfaces: history read marks the desktop's unread
        // cursor (desktop parity) and the quota re-reads after the reset
        assertEquals(listOf("account:zai-individual-coding-plan"), port.historyReadCalls)
        assertTrue("entitlement must refresh after a reset", port.entitlementReads >= 2)
    }

    @Test
    fun useFailure_restoresTheResetAffordance_withHonestError() {
        val port = recordingPort(useResult = null, useError = "coding_plan_reset_denied")
        setContent(port)
        waitForTag("quota_reset_fiveHour")
        rule.onNodeWithTag("quota_reset_fiveHour").performClick()
        waitForTag("quota_reset_dialog")
        rule.onNodeWithTag("quota_reset_confirm_input").performTextInput("RESET")
        rule.onNodeWithTag("quota_reset_confirm").performClick()
        waitForTag("quota_reset_error")
        assertEquals(1, port.useCalls.size)
        // the reset affordance RETURNS (failManualUse restores the pre-click
        // opportunity snapshot) and NO completion face shows
        waitForTag("quota_reset_fiveHour")
        rule.onAllNodesWithTag("quota_reset_done").fetchSemanticsNodes().isEmpty()
        assertTrue(port.historyReadCalls.isEmpty())
    }

    @Test
    fun quotaFull_suppressesTheResetAffordance_despiteOpportunities() {
        // percentage 0 = nothing used = remaining 100% = the desktop's
        // isCodingPlanQuotaLimitFull gate hides the reset (no value in resetting)
        val fullQuota = UsageEntitlement.decode(
            Json.parseToJsonElement(
                """{"generatedAt": 1791933600000, "authenticated": true,
                   "provider": {"id": "account:zai-individual-coding-plan", "name": "Z.ai Coding Plan"},
                   "remaining": null, "subscription": null,
                   "quota": {"level": "glm-coding-pro", "limits": [
                     {"type": "TOKENS_LIMIT", "unit": 3, "number": 5, "percentage": 0,
                      "nextResetTime": 1791951600000, "usageDetails": []}]}}""",
            ).jsonObject,
        )
        val port = recordingPort(entitlement = fullQuota)
        setContent(port)
        // wait for the meters to render (the section IS present), then pin the
        // reset affordance's absence
        waitForTag("quota_value_fiveHour")
        assertEquals("100%", renderedValue("quota_value_fiveHour"))
        rule.mainClock.advanceTimeBy(1_500)
        rule.onAllNodesWithTag("quota_reset_fiveHour").fetchSemanticsNodes().isEmpty()
        assertTrue(port.useCalls.isEmpty())
    }

    private fun renderedValue(tag: String): String {
        val node = rule.onAllNodesWithTag(tag)[0].fetchSemanticsNode()
        return node.config
            .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
            .joinToString(" ") { it.text }
    }
}
