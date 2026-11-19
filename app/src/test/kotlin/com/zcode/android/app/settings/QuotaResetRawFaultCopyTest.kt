package com.zcode.android.app.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
 * N-BO-1 (pass-0 Phase 7) — the quota-reset failure face interpolated the RAW fault
 * code ("Reset failed · fault.mock.unscripted:useCodingPlanReset", pass-13 live
 * receipt at the old `quota_reset_error + " · " + err` interpolation). The G66/K9
 * fault→human mapping must cover this surface: the rendered face carries human copy,
 * never `fault.*`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class QuotaResetRawFaultCopyTest {

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

    /** The CodingPlanQuotaResetUiTest RecordingPort, reduced to the failure flow. */
    private class FaultingUsePort(
        private val entitlementSnapshot: UsageEntitlement,
        private val statuses: MutableList<CodingPlanResetStatus>,
        private val useError: String,
    ) : CodingPlanPort {
        override suspend fun entitlement(): CodingPlanPort.Snapshot =
            CodingPlanPort.Snapshot(entitlementSnapshot, null)

        override suspend fun resetStatus(providerId: String, entitled: Boolean): CodingPlanPort.ResetStatusResult {
            val next = statuses.removeFirstOrNull()
                ?: return CodingPlanPort.ResetStatusResult(null, "unscripted status")
            return CodingPlanPort.ResetStatusResult(next, null)
        }

        override suspend fun requestOpportunity(
            providerId: String,
            entitled: Boolean,
            idempotencyKey: String,
        ): ResetOpportunityResult? = ResetOpportunityResult(granted = true, nextTryAt = null)

        override suspend fun useReset(
            providerId: String,
            entitled: Boolean,
            idempotencyKey: String,
            resetType: String,
        ): ResetUseResult = throw IllegalStateException(useError)

        override suspend fun markHistoryRead(providerId: String, entitled: Boolean) = Unit
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

    @Test
    fun `N-BO-1 - the quota reset failure face renders human copy, never the raw fault code`() {
        val port = FaultingUsePort(
            entitlementSnapshot = fixtureEntitlement("entitlement-full"),
            statuses = mutableListOf(
                fixtureStatus("reset-status-available"),
                fixtureStatus("reset-status-available"),
            ),
            useError = "fault.mock.unscripted:useCodingPlanReset",
        )
        setContent(port)
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("quota_reset_chip_fiveHour").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("quota_reset_chip_fiveHour").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("quota_reset_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("quota_reset_confirm_input").performTextInput("RESET")
        rule.onNodeWithTag("quota_reset_confirm").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("quota_reset_error").fetchSemanticsNodes().isNotEmpty()
        }
        val raw = rule.onAllNodesWithText("fault.", substring = true).fetchSemanticsNodes()
        assertEquals(
            "the reset failure face interpolated the RAW fault code (fault.mock.unscripted:useCodingPlanReset)",
            0,
            raw.size,
        )
        val human = rule.onAllNodesWithText("couldn't complete this request", substring = true)
            .fetchSemanticsNodes()
        assertTrue(
            "the fault→human mapping copy must render instead (${human.size} nodes)",
            human.isNotEmpty(),
        )
    }
}
