package com.zcode.android.features.tasks.port

import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.OffPeakTaskRow

/**
 * GAP-6 consumer-side port (INV-2/INV-6): the Tasks-tab read-only automations +
 * off-peak lists. The write half (create/edit/trigger/delete/enable — the
 * roadmap's Deferred scope) exists NOWHERE in this port or its implementation.
 */
interface AutomationsPort {

    /**
     * @param automations null = unavailable (§13.3 hidden fallback); empty = the
     *   workspace genuinely has none (empty state).
     * @param offPeak same semantics, workspace-filtered by the implementation.
     */
    data class Lists(
        val automations: List<AutomationRow>?,
        val offPeak: List<OffPeakTaskRow>?,
        val error: String?,
    )

    suspend fun lists(): Lists
}
