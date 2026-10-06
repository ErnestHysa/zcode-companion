package com.zcode.android.core.ui.components

import java.time.ZoneId
import java.util.Locale

/**
 * GAP-13 (android-native-gaps.md): compact read-only message-time label, ported from
 * the desktop's `formatMessageTimeLabel` (packages/ui/src/v4/messageTimeLabel.ts,
 * 29628c9): createdAt is derived READ-ONLY — same local day → time only; yesterday →
 * "<Yesterday> <time>"; same year → "<Mon D> <time>"; older → "<yyyy Mon D> <time>".
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
    // Implemented in fix(GAP-13).
    return null
}
