package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonPrimitive

/**
 * I2.4 (Phase 6A): the context-compaction lifecycle notice, derived from a
 * [ConversationRow.TimelineMarker] whose `marker.type == "compact"`.
 *
 * Wire (desktop 3.14.5 host `projectMarker`): `{type:"compact", origin, status,
 * tokensBefore?, tokensAfter?}`. Phase mapping is the renderer's own
 * (`chat.contextCompaction.*` keys): `running → started`, `noop → skipped`,
 * `cancelled → interrupted`, `failed → failed`, otherwise `completedAuto` when
 * `origin == "auto"` else `completed`. Unknown statuses fall into the terminal
 * `completed` arm so a future state renders a quiet notice, never a crash (INV-8).
 */
data class CompactionNotice(
    val phase: Phase,
    val origin: String?,
    val tokensBefore: Long?,
    val tokensAfter: Long?,
) {
    enum class Phase { STARTED, SKIPPED, INTERRUPTED, FAILED, COMPLETED_AUTO, COMPLETED }

    /** Still in flight — the row animates/announces as live. */
    val running: Boolean get() = phase == Phase.STARTED

    companion object {
        const val MARKER_TYPE = "compact"

        fun from(row: ConversationRow.TimelineMarker): CompactionNotice? {
            if (row.markerType != MARKER_TYPE) return null
            val status = (row.marker["status"] as? JsonPrimitive)?.content
            val origin = (row.marker["origin"] as? JsonPrimitive)?.content
            val phase = when (status) {
                "running" -> Phase.STARTED
                "noop" -> Phase.SKIPPED
                "cancelled" -> Phase.INTERRUPTED
                "failed" -> Phase.FAILED
                else -> if (origin == "auto") Phase.COMPLETED_AUTO else Phase.COMPLETED
            }
            return CompactionNotice(
                phase = phase,
                origin = origin,
                tokensBefore = (row.marker["tokensBefore"] as? JsonPrimitive)?.content?.toLongOrNull(),
                tokensAfter = (row.marker["tokensAfter"] as? JsonPrimitive)?.content?.toLongOrNull(),
            )
        }
    }
}
