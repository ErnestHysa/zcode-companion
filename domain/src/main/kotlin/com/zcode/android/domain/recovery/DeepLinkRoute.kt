package com.zcode.android.domain.recovery

/**
 * Deep-link routing safety — architectural.md §24.7 (normative) exercised by the
 * §15-S14 scenario. "A deep link is a routing hint, not an authorization": content
 * renders only after the endpoint is paired, connected, and the task is confirmed in
 * the authoritative projection. Every stale/invalid case resolves to an explicit,
 * graceful route — never a crash, never an empty screen (§15-S14).
 *
 * Pure decision logic: the Phase 8 broker/deep-link plumbing consumes [resolve].
 */
object DeepLinkRoute {

    /** `zcode://task/{taskId}#interaction={interactionId}` — parsed + validated shape. */
    data class DeepLink(val taskId: String, val interactionId: String?)

    enum class Outcome {
        /** Task confirmed in the authoritative projection → open it (interaction anchor if given). */
        OPEN_TASK,
        /** Task exists but the anchored interaction is stale/resolved → open at task, card shows resolved state. */
        OPEN_TASK_INTERACTION_STALE,
        /** Task archived/deleted on desktop (§15-S17) → "no longer available" + list, draft preserved. */
        TASK_GONE,
        /** id exists under ANOTHER paired endpoint → disambiguation. */
        WRONG_ENDPOINT,
        /** Device unpaired → pairing screen; link re-validated after pairing. */
        UNPAIRED,
        /** Malformed ids → reject (never crash). */
        MALFORMED,
    }

    sealed class Environment {
        data class Paired(
            val endpointId: String,
            val connected: Boolean,
            /** Authoritative projection says the task exists (cached counts as existing only when connected). */
            val taskExists: Boolean,
            val interactionKnown: Boolean?,
            /** taskId found under a different endpoint's projection. */
            val existsOnOtherEndpoint: Boolean,
        ) : Environment()

        data object Unpaired : Environment()
    }

    fun parse(taskId: String?, interactionId: String?): DeepLink? {
        if (taskId.isNullOrBlank() || taskId.length > 512) return null
        // Ids are opaque server tokens; reject control chars / whitespace / url tricks.
        if (taskId.any { it.isWhitespace() || it.isISOControl() || it == '/' || it == '?' || it == '#' }) return null
        if (interactionId != null) {
            if (interactionId.isBlank() || interactionId.length > 512) return null
            if (interactionId.any { it.isWhitespace() || it.isISOControl() || it == '/' || it == '?' || it == '#' }) return null
        }
        return DeepLink(taskId, interactionId)
    }

    fun resolve(link: DeepLink?, environment: Environment): Outcome {
        val parsed = link ?: return Outcome.MALFORMED
        return when (val env = environment) {
            Environment.Unpaired -> Outcome.UNPAIRED
            is Environment.Paired -> when {
                // Routing hint ≠ authorization: an offline app must not present cached
                // content as confirmable — require the authoritative projection (§24.7).
                !env.connected && !env.taskExists -> Outcome.TASK_GONE
                !env.taskExists && env.existsOnOtherEndpoint -> Outcome.WRONG_ENDPOINT
                !env.taskExists -> Outcome.TASK_GONE
                parsed.interactionId != null && env.interactionKnown == false -> Outcome.OPEN_TASK_INTERACTION_STALE
                else -> Outcome.OPEN_TASK
            }
        }
    }
}
