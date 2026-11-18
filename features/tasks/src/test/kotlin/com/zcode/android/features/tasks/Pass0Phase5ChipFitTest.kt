package com.zcode.android.features.tasks

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.tasks.ui.AutomationEditorDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * pass-0 Phase 5 — N-BK-2: the automation Edit sheet's schedule chips CLIP at phone
 * width ("Weekdays" rendered "Weekda", "Cron" rendered "Cr" — pass-4 G.A2; pass-16
 * S.A3 stamp 2 under the arms). The chip grid's fixed per-row chunking lays the chips
 * out at INTRINSIC width in an unweighted Row — whatever exceeds the pane paints past
 * its edge and clips (on-device font metrics are wider than Robolectric's, so the
 * live receipt clips while a raw-bounds probe alone stays green here).
 *
 * The pinned contract is therefore the WIDTH-CONSTRAINT mechanism that makes clipping
 * impossible at any font/density: every chip of a chunked row shares the row's width
 * EQUALLY (weight-constrained — an overflowing label then WRAPS, never paints past
 * the pane). Measured on the CHIP nodes (the automation_freq_* test anchors) — the
 * label text nodes carry intrinsic/wrapped widths by design. The on-device 395 dp
 * shape is receipted on the rig at the exit gate (the FormFactor precedent:
 * glyph-level checks on the rig). NOTE: an explicit width qualifier (@Config
 * qualifiers w*N*dp) makes THIS M3 dialog never idle under Robolectric (probed at
 * 395/411 dp) — the default display is the deterministic harness.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase5ChipFitTest {

    @get:Rule
    val rule = createComposeRule()

    private val firstRowTags = listOf("once", "hourly", "daily", "weekdays")
    private val allTags = firstRowTags + listOf("weekly", "monthly", "custom", "cron")

    /** The dialog window's own root (a Dialog is a second window — the widest root). */
    private fun dialogPaneWidth(): Float =
        rule.onAllNodes(isRoot()).fetchSemanticsNodes()
            .maxByOrNull { it.boundsInRoot.width }
            ?.boundsInRoot?.width
            ?: throw AssertionError("no dialog pane root found")

    private fun chipBounds(tag: String) =
        rule.onNodeWithTag("automation_freq_$tag").fetchSemanticsNode().boundsInRoot

    @Test
    fun `the frequency chips are width-constrained to the pane (no clip at any metrics)`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                AutomationEditorDialog(
                    existing = null,
                    busy = false,
                    modelPicker = { _, _, _ -> },
                    onDismiss = {},
                    onSave = {},
                )
            }
        }
        rule.waitForIdle()
        val paneWidth = dialogPaneWidth()
        val widths = firstRowTags.associateWith { chipBounds(it).width }
        // THE mechanism pin: the row's chips share the row width equally — an
        // intrinsic-width row is what overflows and clips on the device.
        val spread = (widths.values.maxOrNull() ?: 0f) - (widths.values.minOrNull() ?: 0f)
        assertTrue(
            spread <= 1.5f,
            "the schedule chips must share the row width equally (weight-constrained): $widths pane=$paneWidth",
        )
        // Every chip paints inside the pane.
        for (tag in allTags) {
            val b = chipBounds(tag)
            assertTrue(
                b.right <= paneWidth + 0.5f,
                "the automation_freq_$tag chip paints past the pane (right=${b.right} > pane=$paneWidth)",
            )
        }
    }
}
