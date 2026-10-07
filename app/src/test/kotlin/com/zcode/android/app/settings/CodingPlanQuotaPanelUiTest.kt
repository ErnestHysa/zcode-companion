package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.UsageEntitlement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * GAP-3 (android-native-gaps.md; docs/protocol/v4-wire.md §13.1; ADR-020) — the
 * Coding-Plan quota section rendered from the GENERATED entitlement fixtures.
 * Desktop parity (ChatCodingPlanUsageRemainingPanel): the four meters (5-hour /
 * weekly / tool-calls / MCP) each render remaining-% + reset-time; the
 * unavailableReason face renders verbatim; and the roadmap's accept criterion —
 * channel absence/teardown degrades to a HIDDEN panel (the desktop's own panel
 * returns null without state, CodingPlanContextUsage.tsx:206-208). Interactions
 * (refresh) stay in the instrumented suite; these Robolectric tests pin
 * RENDERED STATE.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class CodingPlanQuotaPanelUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun fixedLocale() {
        Locale.setDefault(Locale.US)
    }

    // The fixture timestamps were generated around this base instant
    // (2026-10-13T23:20Z); pass it as nowMs with UTC so labels are deterministic.
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

    private fun fakePort(
        entitlement: UsageEntitlement?,
        error: String? = null,
    ) = object : CodingPlanPort {
        override suspend fun entitlement(): CodingPlanPort.Snapshot =
            CodingPlanPort.Snapshot(entitlement, error)
    }

    private fun neverSettlingPort() = object : CodingPlanPort {
        override suspend fun entitlement(): CodingPlanPort.Snapshot =
            kotlinx.coroutines.awaitCancellation()
    }

    private fun setContent(port: CodingPlanPort) {
        rule.setContent {
            ZCodeTheme {
                CodingPlanQuotaSection(port = port, nowMs = nowMs, zone = TimeZone.getTimeZone("UTC"))
            }
        }
    }

    private fun renderedText(tag: String): String =
        renderedTextDeep(rule.onAllNodesWithTag(tag)[0].fetchSemanticsNode())

    private fun renderedTextDeep(node: androidx.compose.ui.semantics.SemanticsNode): String {
        val own = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        return own.joinToString(" ") { it.text }.ifBlank {
            node.children.joinToString(" ") { renderedTextDeep(it) }
        }
    }

    @Test
    fun rendersAllFourMeters_fromFullFixture() {
        setContent(fakePort(fixtureEntitlement("entitlement-full")))
        rule.onNodeWithTag("quota_section").assertExists()
        // remaining-% labels (percentage is the USED share — displayed inverted)
        assertEquals("64%", renderedText("quota_value_fiveHour"))
        assertEquals("49%", renderedText("quota_value_weekly"))
        assertEquals("88%", renderedText("quota_value_toolCalls"))
        assertEquals("78%", renderedText("quota_value_mcp"))
        // reset-time labels: five-hour = HH:mm; weekly/tools/MCP = "MMM d"
        assertEquals("04:20", renderedText("quota_reset_fiveHour"))
        assertEquals("Oct 20", renderedText("quota_reset_weekly"))
        assertEquals("Nov 12", renderedText("quota_reset_toolCalls"))
        assertEquals("Oct 14", renderedText("quota_reset_mcp"))
        // the deliberately-unmatched unit-9 limit renders NO meter (exact lookups)
        rule.onAllNodesWithTag("quota_meter_unit9").assertCountEquals(0)
    }

    @Test
    fun zaiCreditLimit_rendersInvertedAndToolsMeters_weeklyAbsent() {
        setContent(fakePort(fixtureEntitlement("entitlement-zai-credit")))
        // CREDIT_LIMIT 5h face at percentage 100 (used) → remaining 0%
        assertEquals("0%", renderedText("quota_value_fiveHour"))
        assertEquals("55%", renderedText("quota_value_toolCalls"))
        // no unit-6 limit in this payload → the weekly meter renders nowhere
        rule.onAllNodesWithTag("quota_meter_weekly").assertCountEquals(0)
    }

    @Test
    fun noPlanPayload_rendersUnavailableFace_notMeters() {
        setContent(fakePort(fixtureEntitlement("entitlement-no-plan")))
        rule.onNodeWithTag("quota_unavailable").assertExists()
        // the wire's unavailableReason renders verbatim
        val text = renderedText("quota_unavailable")
        assertEquals(true, "no_plan" in text)
        rule.onAllNodesWithTag("quota_meter_fiveHour").assertCountEquals(0)
    }

    @Test
    fun channelFailure_degradesToHiddenPanel() {
        // roadmap accept: "channel absence/teardown degrades to a hidden panel"
        setContent(fakePort(null, error = "fault.channel.refused:usage-stats"))
        rule.onAllNodesWithTag("quota_section").assertCountEquals(0)
        rule.onAllNodesWithTag("quota_unavailable").assertCountEquals(0)
    }

    @Test
    fun loadingFace_rendersWhileTheReadIsInFlight() {
        setContent(neverSettlingPort())
        rule.onNodeWithTag("quota_loading").assertExists()
    }
}
