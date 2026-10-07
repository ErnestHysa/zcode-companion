package com.zcode.android.data.session

import com.zcode.android.core.transport.AppTransport
import com.zcode.android.domain.model.AppUsageSnapshot

/**
 * Phase 3 read-only viewer tool plane (android-native-gaps.md GAP-5…GAP-9;
 * docs/protocol/v4-wire.md §12) — the ONE place the viewer transport surfaces
 * map onto domain models (§40.2). READ-ONLY: every method here is an R-class
 * read; the write half of these channels is Deferred scope and exists nowhere
 * in this class or the transport's typed surface (INV-9). Transport failures
 * propagate as [com.zcode.android.core.transport.TransportFailure]; the port
 * layer translates them into the §13.3 hidden/disabled fallbacks.
 */
class ViewerTools(private val transport: AppTransport) {

    // ---------------------------------------------------------------- GAP-5: usage

    /**
     * `getAppUsageStats` → CLI `v4/usage/stats` (v4-wire.md §12.1). [range] is
     * "all"|"7d"|"30d" (verified enum); [timeZone] is the CLIENT's IANA zone
     * (the desktop passes Intl.DateTimeFormat().resolvedOptions().timeZone,
     * useUsageStats.ts:210-213) — null lets the host fall back to its own zone.
     */
    suspend fun appUsageStats(range: String, timeZone: String? = null): AppUsageSnapshot =
        AppUsageSnapshot.decode(transport.appUsageStats(range, timeZone))
}
