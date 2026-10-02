package com.zcode.android.app.notifications

import android.content.Intent
import android.net.Uri

/**
 * §24.7 deep-link INTAKE: `zcode://task/{taskId}#interaction={interactionId}`.
 * Pure parser (MainActivity hands it the intent; the nav graph resolves against
 * the authoritative projection). Malformed shapes return null — never crash,
 * never partially offer a link.
 */
object DeepLinks {
    const val SCHEME = "zcode"
    const val HOST = "task"
    const val FRAGMENT_PREFIX = "interaction="

    data class Candidate(val taskId: String, val interactionId: String?)

    fun intake(intent: Intent?): Candidate? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        return intake(intent.data)
    }

    fun intake(data: Uri?): Candidate? {
        if (data == null) return null
        if (data.scheme != SCHEME || data.host != HOST) return null
        val taskId = data.pathSegments?.firstOrNull() ?: return null
        // Same §24.7 sanity class as the domain parser (ids are opaque tokens).
        if (taskId.isBlank() || taskId.length > 512) return null
        if (data.pathSegments!!.size > 1) return null
        val interactionId = data.fragment
            ?.takeIf { it.startsWith(FRAGMENT_PREFIX) }
            ?.removePrefix(FRAGMENT_PREFIX)
            ?.takeUnless { it.isBlank() || it.length > 512 }
        return Candidate(taskId, interactionId)
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
