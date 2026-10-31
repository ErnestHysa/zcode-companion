package com.zcode.android.app.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.core.ui.theme.LocalZCodeColors
import com.zcode.android.domain.model.CodingPlanUsageSnapshot
import java.util.TimeZone

/**
 * G20 (vc51) — the coding-plan usage charts (the desktop's CodingPlanUsage
 * BarChart + CodingPlanUsageLineChart): a STACKED per-day band per model over
 * `modelUsage.modelDataList[].tokensUsage` and the per-day TOTALS line over
 * the same series. Channel refusal → the section renders NOTHING (the honest
 * fault the Usage screen already speaks on unscripted channels).
 */
@Composable
internal fun PlanUsageSection(port: UsagePort, range: String) {
    var snapshot by remember { mutableStateOf<CodingPlanUsageSnapshot?>(null) }
    LaunchedEffect(range) {
        snapshot = port.codingPlanUsage(range, TimeZone.getDefault().id)
    }
    val current = snapshot ?: return
    if (current.xTime.isEmpty() || current.models.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SectionTitle(stringResource(R.string.usage_plan_section), tag = "usage_plan_section")

        // The stacked bar chart: one band per model per day.
        val totals = current.dailyTotals
        val maxTotal = totals.maxOrNull() ?: return
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .testTag("usage_plan_bars"),
        ) {
            val days = current.xTime.size
            if (days == 0 || maxTotal <= 0) return@Canvas
            val slot = size.width / days
            val barWidth = (slot * 0.62f).coerceAtLeast(2f)
            current.xTime.forEachIndexed { dayIndex, _ ->
                var yBottom = size.height
                var bandIndex = 0
                current.models.forEach { model ->
                    val value = (model.tokensUsage.getOrNull(dayIndex) ?: 0L).toFloat()
                    if (value > 0f) {
                        val bandHeight = (value / maxTotal) * size.height
                        drawRoundRect(
                            color = chartColor(bandIndex),
                            topLeft = Offset(dayIndex * slot + (slot - barWidth) / 2f, yBottom - bandHeight),
                            size = Size(barWidth, bandHeight),
                            cornerRadius = CornerRadius(4f, 4f),
                        )
                        yBottom -= bandHeight
                    }
                    bandIndex++
                }
            }
        }

        // The totals line (the desktop's CodingPlanUsageLineChart shape).
        val accent = LocalZCodeColors.current.accent
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(top = 8.dp)
                .testTag("usage_plan_line"),
        ) {
            val days = totals.size
            if (days < 2 || maxTotal <= 0) return@Canvas
            val stepX = size.width / (days - 1)
            val path = Path()
            totals.forEachIndexed { index, total ->
                val x = index * stepX
                val y = size.height - (total.toFloat() / maxTotal) * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = accent, style = Stroke(width = 4f))
        }

        // The legend: one entry per model series.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("usage_plan_legend")) {
            current.models.forEachIndexed { index, model ->
                Row(Modifier.padding(end = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(chartColor(index)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        model.modelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Stable per-band palette (the appUsageChartPalette idea — one accent + hues). */
private fun chartColor(index: Int): androidx.compose.ui.graphics.Color = when (index % 4) {
    0 -> androidx.compose.ui.graphics.Color(0xFF7C9CFF)
    1 -> androidx.compose.ui.graphics.Color(0xFF5BC8AF)
    2 -> androidx.compose.ui.graphics.Color(0xFFE2B45A)
    else -> androidx.compose.ui.graphics.Color(0xFFB48CE0)
}
