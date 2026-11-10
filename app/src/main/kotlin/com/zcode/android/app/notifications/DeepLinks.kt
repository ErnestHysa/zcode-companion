package com.zcode.android.app.notifications

import android.content.Intent
import android.net.Uri

/**
 * §24.7 deep-link INTAKE: `zcode://task/{taskId}#interaction={interactionId}` plus the
 * connection-scoped `zcode://connection` (LEDGER-SMALLS a — the Connection-lost shade
 * entry's tap target). Pure parser (MainActivity hands it the intent; the nav graph
 * resolves against the authoritative projection). Malformed shapes return null — never
 * crash, never partially offer a link.
 */
object DeepLinks {
    const val SCHEME = "zcode"
    const val HOST = "task"
    const val HOST_CONNECTION = "connection"
    const val FRAGMENT_PREFIX = "interaction="

    sealed interface Candidate {
        data class Task(val taskId: String, val interactionId: String?) : Candidate

        /** LEDGER-SMALLS (a): routed to the connection screen (the shortcut seam). */
        data object Connection : Candidate
    }

    fun intake(intent: Intent?): Candidate? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        return intake(intent.data)
    }

    /**
     * N-BI-4 (vc52 Phase 10): the intake VERDICT — a `zcode://` VIEW intent that
     * fails the parse is a REJECTED link (the user tapped something broken: a
     * widget, a share, a browser link) and deserves feedback; a non-zcode / non-VIEW
     * intent is simply not a link (a launcher start must never toast). Routing is
     * unchanged — [intake] still decides what is offered.
     */
    sealed interface Verdict {
        data class Accepted(val candidate: Candidate) : Verdict
        data class Rejected(val reason: String) : Verdict
        data object NotALink : Verdict
    }

    fun classify(intent: Intent?): Verdict {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return Verdict.NotALink
        return classify(intent.data)
    }

    fun classify(data: Uri?): Verdict {
        if (data == null || data.scheme != SCHEME) return Verdict.NotALink
        intake(data)?.let { return Verdict.Accepted(it) }
        val reason = when {
            data.host == HOST_CONNECTION -> "connection-with-path"
            data.host != HOST -> "unknown-host"
            data.pathSegments.isNullOrEmpty() -> "empty-task-id"
            data.pathSegments!!.size > 1 -> "extra-path-segments"
            data.pathSegments!!.first().isBlank() -> "blank-task-id"
            data.pathSegments!!.first().length > 512 -> "task-id-too-long"
            else -> "malformed"
        }
        return Verdict.Rejected(reason)
    }

    fun intake(data: Uri?): Candidate? {
        if (data == null) return null
        if (data.scheme != SCHEME) return null
        if (data.host == HOST_CONNECTION) {
            // strict like the task host: the sink sends the bare authority only
            if (!data.pathSegments.isNullOrEmpty()) return null
            return Candidate.Connection
        }
        if (data.host != HOST) return null
        val taskId = data.pathSegments?.firstOrNull() ?: return null
        // Same §24.7 sanity class as the domain parser (ids are opaque tokens).
        if (taskId.isBlank() || taskId.length > 512) return null
        if (data.pathSegments!!.size > 1) return null
        val interactionId = data.fragment
            ?.takeIf { it.startsWith(FRAGMENT_PREFIX) }
            ?.removePrefix(FRAGMENT_PREFIX)
            ?.takeUnless { it.isBlank() || it.length > 512 }
        return Candidate.Task(taskId, interactionId)
    }
}

/**
 * The pending-link slot (§24.7 "deep link re-validated after pairing"): an UNPAIRED
 * device KEEPS the offered link so the nav graph re-resolves it once pairing
 * completes; consumption clears it exactly once. Own state only — trivially
 * unit-testable without the store graph.
 */
class PendingDeepLink {
    private val _value = kotlinx.coroutines.flow.MutableStateFlow<DeepLinks.Candidate?>(null)
    val value: kotlinx.coroutines.flow.StateFlow<DeepLinks.Candidate?> get() = _value

    fun offer(candidate: DeepLinks.Candidate?) {
        _value.value = candidate
    }

    fun consume() {
        _value.value = null
    }
}
