package com.zcode.android.core.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * GAP-13 (android-native-gaps.md): compact read-only message-time label, ported from
 * the desktop's `formatMessageTimeLabel` (packages/ui/src/v4/messageTimeLabel.ts,
 * 29628c9): createdAt is derived READ-ONLY — same local day → time only; yesterday →
 * "<Yesterday> <time>"; same year → "<Mon d> <time>"; older → "<yyyy Mon d> <time>".
 * Invalid/absent timestamps (≤ 0, NaN) → null: the caller omits the label entirely,
 * exactly like the desktop's null branch. Words stay in resources (§31); the pure
 * core receives the already-localized yesterday word so it is JVM-deterministic.
 */
fun messageTimeLabel(
    createdAtMs: Double,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    yesterdayWord: String = "Yesterday",
): String? {
    if (!createdAtMs.isFinite() || createdAtMs <= 0.0) return null
    val created = Instant.ofEpochMilli(createdAtMs.toLong()).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(created)
    val createdDate = created.toLocalDate()
    val nowDate = now.toLocalDate()
    return when {
        createdDate == nowDate -> time
        createdDate == nowDate.minusDays(1) -> "$yesterdayWord $time"
        createdDate.year == nowDate.year ->
            DateTimeFormatter.ofPattern("MMM d", locale).format(createdDate) + " " + time
        else ->
            DateTimeFormatter.ofPattern("yyyy MMM d", locale).format(createdDate) + " " + time
    }
}
