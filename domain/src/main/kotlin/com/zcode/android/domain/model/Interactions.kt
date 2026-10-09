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
data class PermissionOption(val optionId: String, val label: String? = null) {
    /** §20.4 semantics classification; [Semantics.OTHER] = inert by construction. */
    val semantics: Semantics by lazy { Semantics.of(optionId) }

    /** True only for RECOGNIZED allow-capable options (INV-14 tripwire). */
    val allowCapable: Boolean
        get() = semantics == Semantics.ALLOW_ONCE || semantics == Semantics.ALLOW_ALWAYS || semantics == Semantics.ALLOW_PROJECT

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
            fun of(optionId: String): Semantics =
                entries.firstOrNull { optionId in it.optionIds } ?: OTHER
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
    val raw: JsonObject? = null,
)

/**
 * Interaction card state machine (§14.4.3) as a STORED field: the runtime state
 * machine wiring is Phase 5.4; the store records the lifecycle column.
 */
enum class InteractionLifecycle {
    REQUESTED, DISPLAYED, SUBMITTING, RESOLVED, EXPIRED, CANCELLED, STALE, FAILED;
}
