package com.zcode.android.domain.model

/**
 * pass-0 Phase 10 — G89: the hook review-reason enum DECODED. The desktop's
 * review-reason display vocabulary (asar 3.18.1 `ec475e6e…`, the 12
 * `settings.hooks.review.reason.*` i18n keys — the keys are the spec; the EN
 * values are the copies below) had NO phone counterpart: the review card said
 * only "N pending · M hooks". The wire itself carries NO reason field (the
 * `workspaceHookReview` payload schema has none) — the desktop COMPUTES these
 * reasons from review-state transitions, so the phone derives the two states it
 * can observe truthfully (stale items + the expired flow) and stays SILENT on
 * every state it cannot observe (never an invented reason).
 *
 * saveHooks AUTHORING stays desktop-only (the dated no-action decision in the
 * ledger): the phone's review flow is answer-only (ADR-024).
 */
object HookReviewReasons {

    /** The 12-value review-reason vocabulary → the desktop's EN display copies. */
    val LABELS: Map<String, String> = mapOf(
        "review_superseded" to "Review was updated — please confirm again",
        "snapshot_mismatch" to "Hook configuration changed — review required",
        "bundle_changed" to "Hook configuration changed — review required",
        "config_unreadable" to "Hook configuration could not be read",
        "config_write_failed" to "Failed to write Hook configuration",
        "config_rebuild_failed" to "Failed to rebuild Hook configuration",
        "trust_store_corrupt" to "Trust store is corrupted — review required",
        "blocked_by_policy" to "Blocked by policy",
        "policy_requires_pretrust" to "Policy requires pre-established trust",
        "interaction_timeout" to "Review timed out",
        "host_unavailable" to "This connection cannot review this Hook",
        "rejected" to "Request rejected",
    )

    fun label(reason: String): String? = LABELS[reason]

    /**
     * The per-item typed reason (what the phone CAN observe): a `stale_digest`
     * trust state means the hook configuration changed since trust — the
     * desktop's `bundle_changed`/`snapshot_mismatch` family copy. All other
     * trust states keep their existing faces (trusted/pending render their own
     * chips; unknown stays inert per ADR-024).
     */
    fun itemReason(item: WorkspaceHookReview.Item): String? =
        when (item.trustState) {
            "stale_digest" -> LABELS["bundle_changed"]
            else -> null
        }

    /**
     * The flow-level typed reason: past `deadlineAt` with pending items = the
     * desktop's `interaction_timeout` ("Review timed out"). Anything else
     * (superseded/rejected/host-unavailable) is NOT observable phone-side and
     * stays null — never an invented reason.
     */
    fun flowReason(review: WorkspaceHookReview, nowMs: Long): String? {
        val expired = review.deadlineAtMs in 1..nowMs
        return if (expired && review.summary.pendingCount > 0) LABELS["interaction_timeout"] else null
    }
}
