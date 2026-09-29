package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.Correlation
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.RecoveryAction

/**
 * §33 close-code / relay-error-code → AppError mapping (normative table, §33.1 rules +
 * §11.4 transport behaviors + §15.3 takeover UX). Distinct categories are never
 * conflated: unknown ≠ unsupported ≠ incompatible (§33 final rule).
 */
object ErrorMapping {

    // ---------------------------------------------------- WS close codes (§33)

    fun closeCode(code: Int, correlation: Correlation = Correlation()): AppError = when (code) {
        4000, 4004 -> AppError(
            category = ErrorCategory.TRANSPORT,
            userMessage = "Connection lost — reconnecting…",
            developerDiagnostic = "ws close code=$code (direct-server class)",
            retryable = AppError.Retryable.AUTO,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONNECT,
            correlation = correlation,
        )
        4009 -> AppError(
            category = ErrorCategory.SESSION,
            userMessage = "Another ZCode Remote page took over this connection",
            developerDiagnostic = "ws close 4009 SessionConflict (single-terminal rule, §15.3)",
            retryable = AppError.Retryable.USER,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONNECT,
            correlation = correlation,
        )
        4010 -> AppError(
            category = ErrorCategory.TRANSPORT,
            userMessage = "Desktop unreachable — wake the Mac, relaunch ZCode",
            developerDiagnostic = "ws close 4010 DesktopDisconnected",
            retryable = AppError.Retryable.AUTO,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONNECT,
            correlation = correlation,
        )
        4011 -> AppError(
            category = ErrorCategory.AUTH,
            userMessage = "Pairing expired — scan the QR again",
            developerDiagnostic = "ws close 4011 SessionExpired",
            retryable = AppError.Retryable.NO,
            recoverable = true,
            autoRecovery = RecoveryAction.RE_PAIR,
            correlation = correlation,
        )
        4012 -> AppError(
            category = ErrorCategory.SESSION,
            userMessage = "This workspace was closed on the desktop",
            developerDiagnostic = "ws close 4012 WorkspaceClosed",
            retryable = AppError.Retryable.NO,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONCILE,
            correlation = correlation,
        )
        4013 -> AppError(
            category = ErrorCategory.AUTH,
            userMessage = "Pairing expired — scan the QR again",
            developerDiagnostic = "ws close 4013 InvalidMobileConnection",
            retryable = AppError.Retryable.NO,
            recoverable = true,
            autoRecovery = RecoveryAction.RE_PAIR,
            correlation = correlation,
        )
        else -> AppError(
            category = ErrorCategory.TRANSPORT,
            userMessage = "Connection lost — reconnecting…",
            developerDiagnostic = "ws close code=$code (unclassified close)",
            retryable = AppError.Retryable.AUTO,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONNECT,
            correlation = correlation,
        )
    }

    // ---------------------------------------------------- relay error frames (§6.2)

    fun relayError(code: String, message: String?, correlation: Correlation = Correlation()): AppError = when (code) {
        "AUTH_FAILED" -> AppError(
            category = ErrorCategory.AUTH,
            userMessage = "Pairing expired — scan the QR again",
            developerDiagnostic = "relay AUTH_FAILED",
            retryable = AppError.Retryable.NO,
            recoverable = true,
            autoRecovery = RecoveryAction.RE_PAIR,
            correlation = correlation,
        )
        // Round-6 field finding: the relay pushes this when the DESKTOP's own relay
        // session died (observed alongside bridge desktop-disconnected storms; the
        // #328 class where terminal bridge attempts wedge the desktop's session).
        "DEVICE_OFFLINE" -> AppError(
            category = ErrorCategory.TRANSPORT,
            userMessage = "The desktop lost its relay connection — restart ZCode on your Mac, then retry",
            developerDiagnostic = "relay DEVICE_OFFLINE (desktop relay session gone)",
            retryable = AppError.Retryable.USER,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONCILE,
            correlation = correlation,
        )
        "KICKED" -> AppError(
            category = ErrorCategory.SESSION,
            userMessage = "Another ZCode Remote page took over this connection",
            developerDiagnostic = "relay KICKED (takeover, §15.3)",
            retryable = AppError.Retryable.USER,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONNECT,
            correlation = correlation,
        )
        "INTERNAL" -> AppError(
            category = ErrorCategory.SERVER,
            userMessage = "ZCode relay error — retrying",
            developerDiagnostic = "relay INTERNAL (observed in the wild, §33)",
            retryable = AppError.Retryable.AUTO,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONNECT,
            correlation = correlation,
        )
        "WRONG_PARAM" -> AppError(
            category = ErrorCategory.INTERNAL,
            userMessage = "Unexpected error (code)",
            developerDiagnostic = "relay WRONG_PARAM — client-constructed request rejected (client bug class)",
            retryable = AppError.Retryable.NO,
            recoverable = false,
            autoRecovery = RecoveryAction.LOCAL_REPORT,
            correlation = correlation,
        )
        else -> AppError(
            category = ErrorCategory.INTERNAL,
            userMessage = "Something went wrong",
            developerDiagnostic = "relay error code=$code (unclassified)",
            retryable = AppError.Retryable.NO,
            recoverable = false,
            autoRecovery = null,
            correlation = correlation,
        )
    }

    // ---------------------------------------------------- transport-local sources

    fun protocolFault(diagnostic: String, correlation: Correlation = Correlation()): AppError = AppError(
        category = ErrorCategory.PROTOCOL,
        userMessage = "Sync problem — refreshing",
        developerDiagnostic = diagnostic,
        retryable = AppError.Retryable.AUTO,
        recoverable = true,
        autoRecovery = RecoveryAction.SNAPSHOT_REPLACE,
        correlation = correlation,
    )

    fun serializationFault(diagnostic: String, correlation: Correlation = Correlation()): AppError = AppError(
        category = ErrorCategory.SERIALIZATION,
        userMessage = "Something went wrong",
        developerDiagnostic = diagnostic,
        retryable = AppError.Retryable.NO,
        recoverable = true,
        autoRecovery = RecoveryAction.SNAPSHOT_REPLACE,
        correlation = correlation,
    )

    fun networkUnreachable(diagnostic: String, correlation: Correlation = Correlation()): AppError = AppError(
        category = ErrorCategory.NETWORK,
        userMessage = "Network unavailable — check your connection",
        developerDiagnostic = diagnostic,
        retryable = AppError.Retryable.AUTO,
        recoverable = true,
        autoRecovery = RecoveryAction.RECONNECT,
        correlation = correlation,
    )

    fun timeout(what: String, timeoutMs: Long, correlation: Correlation = Correlation()): AppError = AppError(
        category = ErrorCategory.TIMEOUT,
        userMessage = "Taking too long — retrying",
        developerDiagnostic = "$what timed out after ${timeoutMs}ms",
        retryable = AppError.Retryable.AUTO,
        recoverable = true,
        autoRecovery = RecoveryAction.RECONNECT,
        correlation = correlation,
    )

    /** Wire-version / app_version gate (§39): hard compat screen, never silent retry. */
    fun versionIncompatible(diagnostic: String, correlation: Correlation = Correlation()): AppError = AppError(
        category = ErrorCategory.VERSION_INCOMPAT,
        userMessage = "Update ZCode desktop to a compatible version",
        developerDiagnostic = diagnostic,
        retryable = AppError.Retryable.NO,
        recoverable = false,
        autoRecovery = RecoveryAction.COMPAT_SCREEN,
        correlation = correlation,
    )

    fun degradedBudget(diagnostic: String, correlation: Correlation = Correlation()): AppError = AppError(
        category = ErrorCategory.TRANSPORT,
        userMessage = "Still offline — showing cached state",
        developerDiagnostic = diagnostic,
        retryable = AppError.Retryable.USER,
        recoverable = true,
        autoRecovery = RecoveryAction.RECONNECT,
        correlation = correlation,
    )

    /** Representative constructors for categories whose sources land in later phases. */
    fun sessionUnavailable(correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.SESSION,
        userMessage = "This session is no longer available",
        developerDiagnostic = "sessionUnavailable -32004",
        retryable = AppError.Retryable.NO,
        recoverable = true,
        autoRecovery = RecoveryAction.RECONCILE,
        correlation = correlation,
    )

    /**
     * `workspace-bridge-error {reason}` (Phase 6 field finding — the reason was
     * previously DISCARDED, blinding field diagnostics). Reason → category per the
     * §33 table and the verified bridge-failure enum (relay-control-plane.md):
     *
     *  - session-conflict            → SESSION + autoRecovery RECONNECT — the takeover
     *                                  marker (§15.3; the pairing port maps this to
     *                                  TakenOver and the recovery loop STOPS).
     *  - session-not-found/-expired,
     *    workspace-closed            → SESSION, transient (desktop may re-hydrate) —
     *                                  the recovery loop RETRIES within budget.
     *  - invalid-mobile-connection   → AUTH (4013 class → re-pair).
     *  - desktop-disconnected,
     *    desktop-bootstrap-timeout,
     *    connection-recovery-timeout,
     *    relay-unavailable           → TRANSPORT (retryable).
     *  - anything else               → PROTOCOL (surface, never guess).
     */
    fun bridgeError(reason: String, correlation: Correlation = Correlation()) = AppError(
        category = when (reason) {
            "session-conflict" -> ErrorCategory.SESSION
            "session-not-found", "session-expired", "workspace-closed" -> ErrorCategory.SESSION
            "invalid-mobile-connection" -> ErrorCategory.AUTH
            "desktop-disconnected", "desktop-bootstrap-timeout",
            "connection-recovery-timeout", "relay-unavailable" -> ErrorCategory.TRANSPORT
            else -> ErrorCategory.PROTOCOL
        },
        userMessage = when (reason) {
            "session-conflict" -> "Another ZCode Remote page took over this connection"
            "session-not-found", "session-expired" -> "The desktop workspace is not ready yet — reconnecting…"
            "workspace-closed" -> "That workspace was closed on the desktop"
            "invalid-mobile-connection" -> "Pairing expired — scan the QR again"
            "desktop-disconnected" -> "Desktop unreachable — wake the Mac, relaunch ZCode"
            else -> "Connection problem — retrying"
        },
        developerDiagnostic = "workspace-bridge-error: $reason",
        retryable = AppError.Retryable.AUTO,
        recoverable = true,
        autoRecovery = when (reason) {
            // The RECONNECT marker is the TAKEOVER marker (§15.3) — it must be UNIQUE
            // to session-conflict: the recovery loop stops only on it; every transient
            // reason keeps retrying within the budget.
            "session-conflict" -> RecoveryAction.RECONNECT
            "invalid-mobile-connection" -> RecoveryAction.RE_PAIR
            else -> RecoveryAction.RECONCILE
        },
        correlation = correlation,
    )

    fun taskRejected(message: String, correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.TASK,
        userMessage = "Conversation changed — please try again",
        developerDiagnostic = message,
        retryable = AppError.Retryable.USER,
        recoverable = true,
        autoRecovery = RecoveryAction.RECONCILE,
        correlation = correlation,
    )

    fun permissionStale(correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.PERMISSION,
        userMessage = "This request was already handled",
        developerDiagnostic = "interaction resolved/expired elsewhere",
        retryable = AppError.Retryable.NO,
        recoverable = true,
        autoRecovery = RecoveryAction.STALE_MARK,
        correlation = correlation,
    )

    fun storageQuarantine(correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.STORAGE,
        userMessage = "Local data problem — cache reset",
        developerDiagnostic = "store corruption; quarantine+reinit (§32.3)",
        retryable = AppError.Retryable.NO,
        recoverable = true,
        autoRecovery = RecoveryAction.QUARANTINE_REINIT,
        correlation = correlation,
    )

    fun androidPermission(what: String, correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.ANDROID,
        userMessage = "Permission needed — open Settings",
        developerDiagnostic = what,
        retryable = AppError.Retryable.USER,
        recoverable = true,
        autoRecovery = RecoveryAction.SETTINGS_DEEP_LINK,
        correlation = correlation,
    )

    fun notificationBlocked(correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.NOTIFICATION,
        userMessage = "Notifications are blocked — enable them in Settings",
        developerDiagnostic = "channel blocked",
        retryable = AppError.Retryable.NO,
        recoverable = true,
        autoRecovery = RecoveryAction.IN_APP_BANNER,
        correlation = correlation,
    )

    fun unsupportedFeature(feature: String, correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.UNSUPPORTED_FEATURE,
        userMessage = "",
        developerDiagnostic = "capability absent: $feature — hide quietly, never error",
        retryable = AppError.Retryable.NO,
        recoverable = true,
        autoRecovery = RecoveryAction.FEATURE_DISABLE,
        correlation = correlation,
    )

    fun cancelled(correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.CANCELLED,
        userMessage = "",
        developerDiagnostic = "cancelled by user/scope",
        retryable = AppError.Retryable.NO,
        recoverable = false,
        autoRecovery = RecoveryAction.SILENT,
        correlation = correlation,
    )

    fun authzRefusal(what: String, correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.AUTHZ,
        userMessage = "This connection doesn't allow that",
        developerDiagnostic = what,
        retryable = AppError.Retryable.NO,
        recoverable = true,
        autoRecovery = RecoveryAction.FEATURE_DISABLE,
        correlation = correlation,
    )

    fun internal(diagnostic: String, correlation: Correlation = Correlation()) = AppError(
        category = ErrorCategory.INTERNAL,
        userMessage = "Unexpected error (code)",
        developerDiagnostic = diagnostic,
        retryable = AppError.Retryable.NO,
        recoverable = false,
        autoRecovery = RecoveryAction.LOCAL_REPORT,
        correlation = correlation,
    )
}

