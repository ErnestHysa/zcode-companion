package com.zcode.android.domain.model

import com.zcode.android.domain.ids.InteractionId
import kotlinx.serialization.json.JsonObject

/**
 * Permission / user-input interaction models — §16 interaction store + §20.
 *
 * FAIL-CLOSED option semantics (§20.4, INV-14 — normative): only RECOGNIZED option
 * ids map to allow/deny semantics; an unknown option id is inert ("unsupported
 * option"), NEVER actionable, and never maps to an allow decision. There is no code
 * path that reaches an allow decision without a protocol-valid option id.
 */
data class PermissionOption(val optionId: String, val label: String? = null, val kind: String? = null) {
    /** §20.4 semantics classification; [Semantics.OTHER] = inert by construction. */
    val semantics: Semantics by lazy { Semantics.of(optionId, kind, label) }

    /** True only for RECOGNIZED allow-capable options (INV-14 tripwire). */
    val allowCapable: Boolean
        get() = semantics == Semantics.ALLOW_ONCE || semantics == Semantics.ALLOW_ALWAYS || semantics == Semantics.ALLOW_PROJECT

    /**
     * G76 (vc52 Phase 12): the SCOPE the runtime named (the desktop's KAn name
     * table, lowercased) — the wire has NO scope field; scope rides the option
     * the runtime sends. Null = the unscoped face (per-kind description).
     */
    val scope: PermissionScope? by lazy { PermissionScope.of(label) }

    enum class Semantics(val optionIds: Set<String>) {
        ALLOW_ONCE(setOf("allowOnce")),
        ALLOW_ALWAYS(setOf("allowAlways")),
        ALLOW_PROJECT(setOf("allow_project")),
        /** workflowRefine carries free-text refinement (§7.4.4) — actionable, not allow. */
        REFINE(setOf("workflowRefine")),
        DENY(setOf("deny")),
        /** Unknown/unrecognized — inert; NEVER mapped to allow (§20.4). */
        OTHER(emptySet());

        companion object {
            /**
             * G76: the legacy id table first; then the CURRENT ship's `kind` field
             * (Nk: allowOnce|allowAlways|deny|custom) — the scoped always-options
             * carry opaque optionIds, and `kind` is the desktop's OWN classification.
             * `custom` maps to allow ONLY for a runtime-named scoped label (the
             * fullAccess arm); everything else stays OTHER (fail closed, INV-14).
             */
            fun of(optionId: String, kind: String? = null, label: String? = null): Semantics =
                entries.firstOrNull { optionId in it.optionIds } ?: when (kind) {
                    "allowOnce" -> ALLOW_ONCE
                    "allowAlways" -> ALLOW_ALWAYS
                    "deny" -> DENY
                    "custom" -> if (optionId == "fullAccess" || PermissionScope.of(label) != null) ALLOW_ALWAYS else OTHER
                    else -> OTHER
                }
        }
    }
}

/**
 * G76: the desktop's scoped-option NAME table (renderer KAn, lowercased keys) —
 * the wire carries no scope field; the scope is WHICH option the runtime names.
 */
enum class PermissionScope {
    PROJECT, SESSION, CUA_PROJECT, FULL_ACCESS;

    companion object {
        fun of(label: String?): PermissionScope? {
            val n = label?.trim()?.replace(Regex("\\s+"), " ")?.lowercase() ?: return null
            return when {
                n == "full access" -> FULL_ACCESS
                n.contains("computer use") && n.contains("project") -> CUA_PROJECT
                n.contains("this project") -> PROJECT
                n.contains("this session") -> SESSION
                else -> null
            }
        }
    }
}

/** Verified PermissionRequestedPayload shape (§20.1); options are an OPEN set. */
data class PermissionRequest(
    val interactionId: InteractionId,
    /** Session-event-plane request id (§9.13: mapped, never conflated with interactionId). */
    val requestId: String? = null,
    val sessionId: String? = null,
    val taskId: String? = null,
    val toolName: String,
    val riskLevel: String? = null,
    val reason: String? = null,
    val input: JsonObject? = null,
    val options: List<PermissionOption>,
    val suggestedPermissionUpdates: JsonObject? = null,
    val fullAccessSupported: Boolean? = null,
    val createdAtMs: Long,
    val resolvedAtMs: Long? = null,
    /** G77 (vc52 Phase 7): the decoded autoResolution deadline (visibleCountdown only). */
    val autoResolutionDeadlineMs: Long? = null,
    val raw: JsonObject? = null,
)

/** `userInput.requested` plane (§20.1/U9). */
data class UserInputRequest(
    val interactionId: InteractionId,
    val sessionId: String? = null,
    val taskId: String? = null,
    val prompt: String? = null,
    val options: List<PermissionOption> = emptyList(),
    val createdAtMs: Long,
    val resolvedAtMs: Long? = null,
    /** G77 (vc52 Phase 7): the decoded autoResolution deadline (visibleCountdown only). */
    val autoResolutionDeadlineMs: Long? = null,
    val raw: JsonObject? = null,
)

/**
 * Interaction card state machine (§14.4.3) as a STORED field: the runtime state
 * machine wiring is Phase 5.4; the store records the lifecycle column.
 */
enum class InteractionLifecycle {
    REQUESTED, DISPLAYED, SUBMITTING, RESOLVED, EXPIRED, CANCELLED, STALE, FAILED;

    /** F-36-B/F-33-E: this lifecycle is "open attention" (the inbox/badge set). */
    val isOpenAttention: Boolean get() = this in OPEN_ATTENTION

    /** F-36-B: this lifecycle is terminal (the card can never submit again). */
    val isTerminal: Boolean get() = this in TERMINAL

    companion object {
        /**
         * F-36-B/F-33-E (vc52 Phase 7): the ONE open-attention predicate — badge,
         * inbox, the Room `observeOpen` selection, the widget's broker mirror and
         * the notification repost share THIS set (never copy-pasted logic).
         * STALE/EXPIRED/FAILED/CANCELLED are terminal and excluded even when their
         * resolution stamp is absent (the pass-36 ghost's exact shape).
         */
        val OPEN_ATTENTION: Set<InteractionLifecycle> = setOf(REQUESTED, DISPLAYED, SUBMITTING)

        /** The terminal complement (§14.4.3): no submit is legal from any of these. */
        val TERMINAL: Set<InteractionLifecycle> =
            entries.toSet() - OPEN_ATTENTION
    }
}
