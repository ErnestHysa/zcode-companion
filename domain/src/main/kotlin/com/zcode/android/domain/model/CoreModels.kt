package com.zcode.android.domain.model

import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import kotlinx.serialization.json.JsonObject

/**
 * Core §16 domain models: Endpoint, Workspace, Task, Session. Every model is an
 * immutable data class; optional fields stay nullable; status enums are tolerant
 * (unknown cases preserved as `UNKNOWN(raw)` — INV-8, §16 "status enum tolerant").
 */

enum class EndpointKind { RELAY_ZAI, DIRECT_SERVER }

/** §16 Endpoint — descriptor-level view; credential material never lives here (§11.3). */
data class Endpoint(
    val endpointId: EndpointId,
    val kind: EndpointKind,
    val displayName: String,
    val wsUrl: String,
    val pairedAtMs: Long,
    val deviceSid: String? = null,
    val deviceMid: String? = null,
    val desktopAppVersion: String? = null,
    /** Ordered failover candidates (LAN → tailnet → tunnel), §11.3. */
    val candidates: List<String> = emptyList(),
    /** Display-only workspace metadata from pairing (never identity, INV-12/16). */
    val workspacePath: String? = null,
    val workspaceIdentity: String? = null,
    val lastHealthyAtMs: Long? = null,
)

/** §16 Workspace — identity = (endpointId, workspaceIdentityKey), never a path. */
data class Workspace(
    val endpointId: EndpointId,
    val identityKey: WorkspaceIdentityKey,
    val displayLabel: String,
    val kind: WorkspaceKind,
    /** Display-only (INV-16). */
    val path: String? = null,
    val remoteSessionId: String? = null,
) {
    enum class WorkspaceKind { LOCAL, REMOTE, CONVERSATION }
}

/** §7.8-verified session status set + tolerant unknown (INV-8). */
enum class TaskStatus(val wire: String) {
    IDLE("idle"),
    RUNNING("running"),
    WAITING("waiting"),
    PAUSED("paused"),
    COMPLETED("completed"),
    ERROR("error"),
    UNKNOWN("");

    companion object {
        /** Tolerant parse: unknown wire values → UNKNOWN (raw preserved by caller). */
        fun fromWire(value: String?): TaskStatus =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** §16 Task — projection; Android never authoritative (INV-1). */
data class Task(
    val taskId: String,
    val title: String,
    val status: TaskStatus,
    val workspace: WorkspaceIdentityKey,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val mode: String? = null,
    val model: String? = null,
    val archivedAtMs: Long? = null,
    val summary: TaskSummary? = null,
    val pinned: Boolean = false,
    /** Raw status string when status == UNKNOWN (INV-8 raw retention). */
    val rawStatus: String? = null,
    /** Display-only workspace label from the task plane (INV-12: never an identity). */
    val workspaceLabel: String? = null,
) {
    /** Per-turn/summary file change rollup (TurnFileChangeSummary semantics, §22). */
    data class TaskSummary(val additions: Long, val deletions: Long, val files: Long)

    /** Archived tasks sort after live ones (§7.8 listTasks semantics). */
    val isArchived: Boolean get() = archivedAtMs != null
}

/** Protocol-level session info (§16 Session — strict-schema port vocabulary). */
data class Session(
    val sessionId: String,
    val workspace: WorkspaceIdentityKey,
    val status: TaskStatus,
    val mode: String?,
    val title: String?,
    val model: String? = null,
    val thoughtLevel: String? = null,
    val createdAtMs: Long? = null,
    val updatedAtMs: Long? = null,
)

/**
 * ModelOption / ModeOption (§16) — picker vocabulary; availability is
 * capability/snapshot-declared, never client-guessed (§42 INV-6).
 */
data class ModelOption(val id: String, val label: String, val available: Boolean = true)
data class ModeOption(val id: String, val label: String, val available: Boolean = true)

/**
 * NotificationRecord (§16) — broker-side dedupe/identity record. The broker itself is
 * Phase 8; the record model and its persistence contract belong to the §16 table.
 */
data class NotificationRecord(
    val notificationKey: String,
    val taskId: String?,
    val kind: Kind,
    val createdAtMs: Long,
    val seenAtMs: Long? = null,
    val interactionId: String? = null,
) {
    enum class Kind { APPROVAL_NEEDED, USER_INPUT_NEEDED, TASK_FINISHED, TASK_FAILED, CONNECTION_LOST }
}

/**
 * StreamingBuffer (§16) — per-row accumulated text, IN-MEMORY ONLY, never persisted
 * (§14.2 "Streaming deltas … never persisted"). Replaced wholesale per delta apply.
 */
data class StreamingBuffer(val rowId: Long, val text: String, val lastSeq: Long)

/**
 * Diff (§16) — reviewable file-change rollup; binary entries are non-renderable
 * placeholders (§22). Hunks live in the Phase 9 renderer's parser, not the cache.
 */
data class Diff(
    val patchHash: String,
    val files: List<FileChange>,
    val writeCount: Int? = null,
    val toolNames: List<String> = emptyList(),
) {
    data class FileChange(
        val path: String,
        val additions: Long,
        val deletions: Long,
        val binary: Boolean = false,
    )
}

/** FileRef (§16) — ephemeral per-view reference; nothing persists (§17.3). */
data class FileRef(val workspace: WorkspaceIdentityKey, val path: String, val sizeBytes: Long? = null, val mime: String? = null)

/** Attachment metadata (§16) — payloads live in cacheDir, LRU 128 MiB / TTL 7 d (§17.3). */
data class Attachment(
    val attachmentId: String,
    val mime: String,
    val sizeBytes: Long,
    val direction: Direction,
    val chunkCursor: Long? = null,
    val localUri: String? = null,
) {
    enum class Direction { UPLOAD, DOWNLOAD }
}

/** GitState (§16) — per-view summary from the git channel; nothing persists. */
data class GitState(val branch: String?, val statusSummary: String, val logHead: String? = null)

/**
 * ETag validator for task snapshots (§7.8 `getTaskSnapshotWithEtag`): cache validation
 * only, never an identity (§9.13). `raw` retains the header value verbatim.
 */
data class SnapshotEtag(val raw: String)

/**
 * App preferences (§17.2 DataStore-proto store; §27 user-visible controls). Defaults
 * are the §24.1/§27 privacy defaults: minimal lock-screen content, screenshots of
 * sensitive surfaces protected, cache on (UX cost warning ships with the toggle).
 * Schema is additive-first (§32.3): new fields optional, never renumbered.
 */
data class AppPreferences(
    /** §24.2 lock-screen visibility: minimal = category-only text (default). */
    val notificationContentLevel: NotificationContentLevel = NotificationContentLevel.MINIMAL,
    /** §27 recents screenshot protection (FLAG_SECURE toggle). */
    val screenshotProtection: Boolean = true,
    /** §27 conversation cache on/off (off = re-fetch always; UX cost warned). */
    val conversationCacheEnabled: Boolean = true,
    /** §25.3 auto-reconnect probe: the ONLY periodic work (≥15 min, network-constrained). */
    val autoReconnectProbe: Boolean = false,
) {
    enum class NotificationContentLevel { MINIMAL, CONTENT }
}

/** Workspace list entry as delivered by bootstrap / workspace-list-response (§9.5, Q9). */
data class WorkspaceListEntry(
    val workspaceKey: String,
    val workspacePath: String?,
    val label: String?,
    val raw: JsonObject? = null,
)


/**
 * A workspace offered by the paired desktop (bootstrap `workspaces[]`). Display
 * metadata only — identity is `key` (§7.11: workspaceIdentity?.trim() ||
 * workspacePath; INV-12: never a bare path where an identity exists).
 */
data class WorkspaceOption(val key: String, val label: String, val path: String?)
