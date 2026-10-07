package com.zcode.android.core.ui.components

import java.text.DecimalFormat
import java.util.Locale

/**
 * GAP-1 (android-native-gaps.md) — context-usage label formatting, ported from the
 * desktop's `formatCompactTokenNumber` (packages/ui/src/lib/tokenNumberFormat.ts,
 * 29628c9) and the contextUsage panel label (contextUsage.tsx:104-116).
 *
 * Observable contract (the desktop's own comment): token counts ≥ 1000 render in
 * the locale's compact form — English K/M/B with ≤ 1 fraction digit ("148.2K",
 * "1M"), Chinese 万/亿 ("14.8万") — smaller counts stay plain. Ported as a
 * deterministic decision table (the `messageTimeLabel` precedent) so JVM tests
 * and the Robolectric UI leg agree; callers inject the locale.
 */
fun formatCompactTokenCount(value: Long, locale: Locale = Locale.getDefault()): String {
    val frac = DecimalFormat("#,##0.#") // ≤ 1 fraction digit, locale grouping
    return when {
        value < 0 -> "-${formatCompactTokenCount(-value, locale)}"
        locale.language == "zh" -> when {
            value < 10_000 -> frac.format(value.toLong())
            value < 100_000_000 -> trimZero(frac.format(value / 10_000.0)) + "万"
            else -> trimZero(frac.format(value / 100_000_000.0)) + "亿"
        }
        value < 1_000 -> frac.format(value.toLong())
        value < 1_000_000 -> trimZero(frac.format(value / 1_000.0)) + "K"
        value < 1_000_000_000 -> trimZero(frac.format(value / 1_000_000.0)) + "M"
        else -> trimZero(frac.format(value / 1_000_000_000.0)) + "B"
    }
}

private fun trimZero(formatted: String): String =
    if (formatted.endsWith(".0")) formatted.removeSuffix(".0") else formatted

/** `used/size (pct%)` — the desktop contextUsage panel label format. */
fun contextUsageLabel(usedTokens: Long, maxTokens: Long, locale: Locale = Locale.getDefault()): String {
    val percent = java.text.NumberFormat.getPercentInstance(locale)
    percent.maximumFractionDigits = 1
    return "${formatCompactTokenCount(usedTokens, locale)}/${formatCompactTokenCount(maxTokens, locale)}" +
        " (${percent.format(usedTokens.toDouble() / maxTokens.toDouble())})"
}

/** Breakdown row label — source label + compact chars + the source's ratio. */
fun contextUsageBreakdownRowLabel(
    sourceLabel: String,
    chars: Long,
    percent: Double,
    locale: Locale = Locale.getDefault(),
): String {
    val percentFormat = java.text.NumberFormat.getPercentInstance(locale)
    percentFormat.maximumFractionDigits = 1
    val charsLabel = NumberFormat.getIntegerInstance(locale).format(chars)
    return "$sourceLabel · $charsLabel (${percentFormat.format(percent)})"
}
