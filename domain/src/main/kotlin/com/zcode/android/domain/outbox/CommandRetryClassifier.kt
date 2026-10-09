package com.zcode.android.domain.outbox

/**
 * Retry classification — architectural.md §28.6 (idempotency classification table,
 * verbatim policy) + §17.5 retry rules. Pure domain; the outbox transport loop and
 * the UI consult this BEFORE any retransmit decision (INV-4 enforcement point).
 *
 * INV-4 core rule: NO path duplicates a server-contacted command. Concretely:
 *  - [RetryClass.SAFE_AGGRESSIVE_RETRY] types may retransmit automatically from
 *    UNKNOWN/FAILED_RETRYABLE (idempotent semantics, verified command family).
 *  - every other type auto-retries ONLY pre-transmit (state ACCEPTED_LOCALLY with
 *    no bytes ever sent); after server contact a retry is USER-GATED (explicit
 *    [OutboxStateMachine.Event.UserGatedRetry], same commandId).
 *  - reads (rowsRange/usage/snapshots) are SAFE_ALWAYS re-send but never enter the
 *    outbox (they are stateless queries, §7.4.3 "超时重发安全").
 */
object CommandRetryClassifier {

    enum class RetryClass {
        /** Auto-retry only before first transmit; user-gated after any server contact. */
        PRE_TRANSMIT_ONLY,
        /** Never auto-retried post-contact; explicit user action only (same commandId). */
        USER_GATED_AFTER_CONTACT,
        /** Idempotent semantics verified — aggressive retry safe even post-contact. */
        SAFE_AGGRESSIVE_RETRY,
    }

    /** §28.6 table + command.ts verified command set (v3.14.3, commit 29628c9). */
    private val classes: Map<String, RetryClass> = buildMap {
        // SAFE to retry aggressively (§28.6: stop/cancel idempotent family)
        put("stop", RetryClass.SAFE_AGGRESSIVE_RETRY)
        put("cancelBackgroundWork", RetryClass.SAFE_AGGRESSIVE_RETRY)
        put("cancelWorkflowRun", RetryClass.SAFE_AGGRESSIVE_RETRY)
        // PRE-TRANSMIT ONLY: duplicate submission is a duplicate action (no natural key)
        put("sendText", RetryClass.PRE_TRANSMIT_ONLY)
        put("createSession", RetryClass.PRE_TRANSMIT_ONLY)
        put("createSelectionSideSession", RetryClass.PRE_TRANSMIT_ONLY)
        put("forkAssistant", RetryClass.PRE_TRANSMIT_ONLY) // row-targeted (§28.6)
        put("editUserQuery", RetryClass.PRE_TRANSMIT_ONLY) // row-targeted; same duplicate class
        put("retryTurn", RetryClass.PRE_TRANSMIT_ONLY) // row-targeted; same duplicate class
        // USER-GATED after any server contact (§28.6: permission/user-input/queue/meta)
        for (t in listOf(
            "resolveInteraction", "renameSession", "deleteSession",
            "switchModelConfig", "switchCollaborationMode", "sendQueuedNow",
            "editQueueItem", "reorderQueueItem", "deleteQueueItem", "compact",
            "setAutoDrain", "setFollowupMode", "pauseGoal", "resumeGoal",
            "sendGoalCommand", "respondWorkspaceHookReview", "toggleWorkspaceHookReviewItem",
            "revokeWorkspaceHookTrust", "requestWorkspaceHookReview",
            "snoozeInteractionAutoResolution", "amendWorkflowRunSettings",
            "resumeWorkflowRun", "startSavedWorkflow",
        )) {
            put(t, RetryClass.USER_GATED_AFTER_CONTACT)
        }
    }

    fun classify(commandType: String): RetryClass =
        classes[commandType] ?: RetryClass.USER_GATED_AFTER_CONTACT // fail-closed default

    /**
     * May the runtime AUTO-retransmit this entry right now? (INV-4 enforcement.)
     * User-gated retry is never decided here — it is an explicit event.
     */
    fun canAutoRetry(commandType: String, state: OutboxStateMachine.State): Boolean = when (state) {
        // Pre-contact failure: every class may auto-retry (§17.5 pre-transmit rule).
        OutboxStateMachine.State.FAILED_RETRYABLE -> true
        // Outcome lost: only the verified-idempotent family auto-retries.
        OutboxStateMachine.State.UNKNOWN -> classify(commandType) == RetryClass.SAFE_AGGRESSIVE_RETRY
        else -> false
    }
}
