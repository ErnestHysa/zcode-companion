package com.zcode.android.core.ui.components

import java.text.NumberFormat
import java.util.Locale

/**
 * GAP-1 (android-native-gaps.md) — context-usage label formatting, ported from the
 * desktop's `formatCompactTokenNumber` (packages/ui/src/lib/tokenNumberFormat.ts,
 * 29628c9) and the contextUsage panel label (contextUsage.tsx:104-116):
 *  - token counts ≥ 1000 use the locale's COMPACT notation with max 1 fraction
 *    digit (en: "148.2K" / "1M"; zh: 万/亿 — the desktop deliberately keeps this
 *    locale-aware), smaller counts stay standard;
 *  - the label is `used/size (pct%)` with a 1-fraction-digit percent formatter.
 * Pure JVM — callers inject the locale so tests are deterministic.
 */
fun formatCompactTokenCount(value: Long, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getCompactNumberInstance(locale, NumberFormat.Style.SHORT)
    format.maximumFractionDigits = 1
    format.minimumFractionDigits = 0
    return format.format(value)
}

/** `used/size (pct%)` — the desktop contextUsage panel label format. */
fun contextUsageLabel(usedTokens: Long, maxTokens: Long, locale: Locale = Locale.getDefault()): String {
    val percent = NumberFormat.getPercentInstance(locale)
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
    val percentFormat = NumberFormat.getPercentInstance(locale)
    percentFormat.maximumFractionDigits = 1
    val compactChars = NumberFormat.getIntegerInstance(locale).format(chars)
    return "$sourceLabel · $compactChars (${percentFormat.format(percent)})"
}
