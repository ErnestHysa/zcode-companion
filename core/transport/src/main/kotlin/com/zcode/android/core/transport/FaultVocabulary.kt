package com.zcode.android.core.transport

/**
 * G79 (vc52 Phase 13): the desktop's `fault.*` vocabulary, phone-phrased. The
 * audit's count: 54 real wire codes clustered in the planes the phone consumes
 * (subscription.* ×8 + subscribe.unknown; attachment.*; connection.*; command.*
 * incl. the three *Rejected families; runtime.hookBlocked;
 * bashOutput.connectionUntrusted; conversation.rowsRangeConnectionUntrusted;
 * terminal.*). Every v4 fault reached the phone as a `refused: fault.<code>: …`
 * string with no per-family wording and no recovery hint — a silent refusal.
 *
 * Pure (no Android types): code extraction from the refusal detail, the
 * KNOWN-code table, and the family classification. The localized copy lives at
 * the UI boundary ([com.zcode.android.core.common.error.UserMessageKey.FAULT_*];
 * the RAW code rides the message args — support keeps it).
 */
object FaultVocabulary {

    /** The phone's receive families (one user copy per family; the code stays raw). */
    enum class Family { SUBSCRIPTION, ATTACHMENT, CONNECTION, COMMAND, RUNTIME, UNTRUSTED, TERMINAL }

    private val SUBSCRIPTION_CODES = setOf(
        "subscription.contentRejected",
        "subscription.initialFrameStagingOverflow",
        "subscription.notOwned",
        "subscription.recoveryFailed",
        "subscription.recoveryFrameTimedOut",
        "subscription.resyncGenerationMismatch",
        "subscription.runtimeRestarted",
        "subscription.transportReplaced",
        "subscribe.unknown",
    )

    private val ATTACHMENT_CODES = setOf(
        "attachment.previewNotMedia",
        "attachment.previewTooLarge",
        "attachment.shareReadNotAuthorized",
        "attachment.shareStatNotFound",
        "attachment.checksumUnavailable",
    )

    private val CONNECTION_CODES = setOf(
        "connection.clientChanged",
        "connection.closed",
        "connection.flowControlForbidden",
        "connection.handshakeRequired",
        "connection.helloRequired",
        "connection.invalidConnectionId",
    )

    /** The command plane incl. the three *Rejected families (prefix-matched). */
    private val COMMAND_CODES = setOf(
        "command.capabilityUnsupported",
        "command.inputDiscardedOnRestart",
        "command.queryForeignWorkspace",
        "command.queryUnavailable",
        "command.clientMismatch",
    )
    private val COMMAND_REJECTED_PREFIXES = listOf(
        "command.backgroundWorkCancelRejected.",
        "command.savedWorkflowStartRejected.",
        "command.workflowRunResumeRejected.",
    )

    private val RUNTIME_CODES = setOf("runtime.hookBlocked")
    private val UNTRUSTED_CODES = setOf(
        "bashOutput.connectionUntrusted",
        "conversation.rowsRangeConnectionUntrusted",
    )
    /** Terminal-plane refusals (the channel's own connection-untrusted class). */
    private val TERMINAL_CODES = setOf(
        "terminal.connectionLost",
        "terminal.connectionUntrusted",
    )

    private val FAMILY_BY_CODE: Map<String, Family> = buildMap {
        SUBSCRIPTION_CODES.forEach { put(it, Family.SUBSCRIPTION) }
        ATTACHMENT_CODES.forEach { put(it, Family.ATTACHMENT) }
        CONNECTION_CODES.forEach { put(it, Family.CONNECTION) }
        COMMAND_CODES.forEach { put(it, Family.COMMAND) }
        RUNTIME_CODES.forEach { put(it, Family.RUNTIME) }
        UNTRUSTED_CODES.forEach { put(it, Family.UNTRUSTED) }
        TERMINAL_CODES.forEach { put(it, Family.TERMINAL) }
    }

    private val FAULT_CODE = Regex("""fault\.([A-Za-z0-9_.\-]+)""")

    /** Extracts the `fault.<code>` from a refusal detail (null = no fault code). */
    fun codeFromRefusal(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        val m = FAULT_CODE.find(detail) ?: return null
        val code = m.groupValues[1].trimEnd('.', ':')
        return code.takeIf { it.isNotBlank() }
    }

    /** The family classification; null = unknown (never guessed — INV-8). */
    fun familyOf(code: String): Family? {
        FAMILY_BY_CODE[code]?.let { return it }
        if (COMMAND_REJECTED_PREFIXES.any { code.startsWith(it) }) return Family.COMMAND
        return null
    }

    fun isKnown(code: String): Boolean = familyOf(code) != null
}
