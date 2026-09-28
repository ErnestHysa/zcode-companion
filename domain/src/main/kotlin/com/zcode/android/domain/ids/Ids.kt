package com.zcode.android.domain.ids

/**
 * Typed identity values — architectural.md §9.13/§16: "ids are typed value classes to
 * prevent identifier mix-ups"; one identifier is never used as another. All wrappers
 * are zero-cost inline value classes over their raw wire form.
 */

/** Android-side endpoint id (uuid v4 at first pairing, §12.5). */
@JvmInline
value class EndpointId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "EndpointId must not be blank" }
    }
}

/**
 * Workspace isolation identity — INV-12: "Workspace identity is never a filesystem
 * path; paths are display-only metadata". Identity key rule (§7.11, verbatim):
 * `workspaceIdentity?.trim() ?: workspacePath`. Paths are display-only metadata.
 */
@JvmInline
value class WorkspaceIdentityKey(val raw: String) {
    init {
        require(raw.isNotBlank()) { "WorkspaceIdentityKey must not be blank" }
    }

    companion object {
        /** The §7.11 identity-key derivation, ported verbatim. */
        fun of(workspaceIdentity: String?, workspacePath: String): WorkspaceIdentityKey {
            val identity = workspaceIdentity?.trim()
            return WorkspaceIdentityKey(if (identity.isNullOrEmpty()) workspacePath else identity)
        }
    }
}

/** A ZCode task/session id (`sess_…`-class ids; task-list ids are service-level). */
@JvmInline
value class SessionId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "SessionId must not be blank" }
    }
}

/**
 * Client-generated uuid v7 command id (§9.13/§17.5) — STABLE across retries
 * (idempotency key, INV-4). Callers obtain it once from `UuidV7` and hold it in the
 * outbox; this wrapper adds type safety, never re-minting.
 */
@JvmInline
value class CommandId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "CommandId must not be blank" }
    }
}

/** v4 permission/user-input interaction instance id (§9.13: approval-card identity). */
@JvmInline
value class InteractionId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "InteractionId must not be blank" }
    }
}

/** Connection-scoped server-issued id (§9.13); regenerated per connect. */
@JvmInline
value class ConnectionId(val raw: String)

/** Conversation row identity (monotonic per session, never reused — rows.ts). */
@JvmInline
value class RowId(val raw: Long)

/** Task identity namespacing (§9.13 rules): (endpoint, workspace identity, session). */
data class TaskKey(val endpointId: EndpointId, val workspace: WorkspaceIdentityKey, val sessionId: SessionId)
