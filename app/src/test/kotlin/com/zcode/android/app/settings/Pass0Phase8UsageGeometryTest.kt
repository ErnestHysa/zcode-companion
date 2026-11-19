package com.zcode.android.app.settings

import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AppUsageSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * N-BP-1 + N-BP-2 (pass-0 Phase 8).
 *
 * N-BP-1: the Usage screen was the ONLY viewer with two `.padding(inner)` applications
 * (the PullToRefreshBox AND the inner Column) — a ~400px dead band under the top bar
 * that was ALSO a dead touch zone (8 receipted attempts, zero re-queries; drags starting
 * inside the band were swallowed). The pins: (a) the differential-inset geometry leg —
 * growing the top inset by Δ moves the content by exactly Δ (padding applied ONCE;
 * the pre-fix double application moves it 2Δ); (b) the Accept's drag leg — a swipe
 * STARTING inside the band region scrolls the list.
 *
 * N-BP-2: the daily-trend x-labels sliced the ISO date with `take(5)` — every row
 * rendered "2026-". The label must be the month-day slice.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class Pass0Phase8UsageGeometryTest {

    @get:Rule
    val rule = createAndroidComposeRule<FragmentActivity>()

    private fun fixtureUsage(name: String): AppUsageSnapshot {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase3-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return AppUsageSnapshot.decode(Json.parseToJsonElement(data).jsonObject)
    }

    private fun fakePort(snapshot: AppUsageSnapshot?) = object : UsagePort {
        override suspend fun appUsage(range: String): UsagePort.Result =
            UsagePort.Result(snapshot, null)
    }

    private fun composeHostView(): android.view.View {
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
        var view: android.view.View = content.getChildAt(0)
        while (view is ViewGroup && view.childCount > 0 && view.javaClass.simpleName != "AndroidComposeView") {
            view = view.getChildAt(0)
        }
        return view
    }

    private fun dispatchTopInsets(px: Int) {
        val host = composeHostView()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, px, 0, 0))
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
        rule.waitForIdle()
    }

    private fun summaryTop(): Float =
        rule.onNodeWithTag("usage_summary").fetchSemanticsNode().boundsInRoot.top

    private fun setUpScreen() {
        rule.setContent {
            ZCodeTheme {
                UsageScreen(port = fakePort(fixtureUsage("app-usage-full")))
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `N-BP-1 - the Scaffold inner padding is applied ONCE (differential-inset geometry leg)`() {
        setUpScreen()
        dispatchTopInsets(0)
        val base = summaryTop()
        dispatchTopInsets(100)
        val grown = summaryTop()
        val delta = grown - base
        assertTrue(
            "N-BP-1: the content moved ${delta}px for a 100px top inset — the double padding(inner) face (expected ~100)",
            delta in 80f..120f,
        )
    }

    @Test
    fun `N-BP-1 - a drag STARTING in the band region scrolls the list (dead-touch-zone leg)`() {
        setUpScreen()
        dispatchTopInsets(100)
        val before = summaryTop()
        // The band: the region just above the content's top (dead pre-fix). Swipe from
        // ~60px above the summary's top edge, inside the PullToRefreshBox.
        rule.onNodeWithTag("usage_pull").performTouchInput {
            val startX = (right - left) / 2f
            val startY = (before - top - 60f).coerceAtLeast(10f)
            swipeUp(start = androidx.compose.ui.geometry.Offset(startX, startY), end = androidx.compose.ui.geometry.Offset(startX, startY - 400f))
        }
        rule.waitForIdle()
        val after = summaryTop()
        assertTrue(
            "N-BP-1: the band drag did NOT scroll the list (before=$before after=$after) — the dead touch zone face",
            after < before,
        )
    }

    @Test
    fun `N-BP-2 - the daily-trend x-label renders the month-day slice, never the year prefix`() {
        setUpScreen()
        val rows = rule.onAllNodesWithTag("usage_trend_row_2026-10-05").fetchSemanticsNodes()
        assertEquals(1, rows.size)
        val text = rowTextDeep(rows[0])
        assertTrue(
            "N-BP-2: the trend label lost its month-day slice: \"$text\"",
            text.contains("10-05"),
        )
        assertFalse(
            "N-BP-2: the trend label renders the year prefix (the take(5) face): \"$text\"",
            text.contains("2026-"),
        )
    }

    private fun rowTextDeep(node: androidx.compose.ui.semantics.SemanticsNode): String {
        val own = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        return own.joinToString(" ") { it.text }.ifBlank {
            node.children.joinToString(" ") { rowTextDeep(it) }
        }
    }
}
