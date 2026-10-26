package com.zcode.android.core.common.error

/**
 * Universal error model — architectural.md §33.1 (normative).
 *
 * Every error crossing a module boundary is an [AppError]; bare exceptions never
 * cross (§41.10). Categories map to the verified protocol/transport facts; user
 * messages follow the §33 category patterns and never contain hashes/paths unless
 * the user's own action displayed them.
 */
data class AppError(
    val category: ErrorCategory,
    /** English FALLBACK only (logs, diagnostics). The UI localizes [messageKey]. */
    val userMessage: String,
    val developerDiagnostic: String,
    val retryable: Retryable,
    val recoverable: Boolean,
    val autoRecovery: RecoveryAction? = null,
    val correlation: Correlation = Correlation(),
    val safeToExpose: Boolean = true,
    /**
     * C5-i18n: the user-facing message as a KEY (+ [messageArgs]) resolved per locale at
     * the UI boundary (core/ui `errorUserMessage`). Pure layers never hold Android
     * resources, so the taxonomy carries keys, not prose.
     */
    val messageKey: UserMessageKey? = null,
    val messageArgs: List<String> = emptyList(),
) {
    enum class Retryable { NO, AUTO, USER }
}

/** C5-i18n: the §33 user-message vocabulary — one key per distinct message the mapping table emits. */
enum class UserMessageKey {
    CONNECTION_LOST_RECONNECTING,
    SESSION_TAKEN_OVER,
    DESKTOP_UNREACHABLE,
    PAIRING_EXPIRED,
    WORKSPACE_CLOSED,
    DIRECT_TOKEN_REJECTED,
    LOCAL_NETWORK_BLOCKED,
    ENDPOINT_CERT_CHANGED,
    ENDPOINT_UNENCRYPTED,
    DESKTOP_RELAY_LOST,
    DESKTOP_APPROVAL_TIMEOUT,
    RELAY_ERROR_RETRYING,
    UNEXPECTED_ERROR_CODE,
    SOMETHING_WENT_WRONG,
    SYNC_PROBLEM_REFRESHING,
    NETWORK_UNAVAILABLE,
    TAKING_TOO_LONG,
    UPDATE_DESKTOP,
    STILL_OFFLINE_CACHED,
    SESSION_UNAVAILABLE,
    WORKSPACE_NOT_READY,
    CONNECTION_PROBLEM_RETRYING,
    CONVERSATION_CHANGED,
    REQUEST_ALREADY_HANDLED,
    LOCAL_DATA_RESET,
    PERMISSION_NEEDED,
    NOTIFICATIONS_BLOCKED,
    NOT_ALLOWED,
    NONE,
}

/** §33 categories — contiguous, mapped 1:1 to the taxonomy table. */
enum class ErrorCategory {
    /** AUTH_FAILED, close 4011, 4013 → re-pair flow. */
    AUTH,
    /** Server refuses channel/role → feature-disable. */
    AUTHZ,
    /** Socket, DNS (incl. observed ENOTFOUND), TLS → reconnect/failover. */
    TRANSPORT,
    /** Captive portal, no route → auto w/ backoff. */
    NETWORK,
    /** Parse failure, unresolvable seq gap, messageTooLarge → snapshot replace. */
    PROTOCOL,
    /** Unknown shape breaking contract → report + continue degraded. */
    SERIALIZATION,
    /** Relay INTERNAL (observed in the wild) → auto limited. */
    SERVER,
    /** sessionUnavailable (-32004), close 4009/4012 class. */
    SESSION,
    /** Command rejected (CAS, invalid state) → reconcile. */
    TASK,
    /** Interaction invalid/expired → stale-mark. */
    PERMISSION,
    /** DataStore/Room failure → quarantine+reinit. */
    STORAGE,
    /** Camera denied, FGS revoked, VPN kills socket → settings deep-link. */
    ANDROID,
    /** Notification channel blocked → banner in-app. */
    NOTIFICATION,
    /** app_version gate, capability absent → compat screen / feature-hide. */
    VERSION_INCOMPAT,
    /** Capability flag off → hide quietly (NOT an error surface). */
    UNSUPPORTED_FEATURE,
    /** Query 10 s (verified), auth 15 s → auto limited. */
    TIMEOUT,
    /** User/scope cancellation → silent. */
    CANCELLED,
    /** Bugs → crash-safe report. */
    INTERNAL,
}

/** What the error model instructs the owner to do next (autoRecovery column of §33). */
enum class RecoveryAction {
    NONE,
    /** transport: reconnect with backoff, probe failover candidates. */
    RECONNECT,
    /** auth: re-pair flow (re-scan QR). */
    RE_PAIR,
    /** protocol: resync / snapshot replace — never merge ambiguous state. */
    SNAPSHOT_REPLACE,
    /** authz/unsupported: disable or hide the feature, degrade the surface only. */
    FEATURE_DISABLE,
    /** task: reconcile with authoritative projection, then surface retry. */
    RECONCILE,
    /** permission: mark card stale; submission disabled until authoritative state. */
    STALE_MARK,
    /** storage: quarantine + reinit (cache only; never the credential store). */
    QUARANTINE_REINIT,
    /** android: deep-link to the relevant settings surface. */
    SETTINGS_DEEP_LINK,
    /** notification: banner inside the app. */
    IN_APP_BANNER,
    /** versionIncompat: hard compat screen with download guidance. */
    COMPAT_SCREEN,
    /** silent — cancelled flows never surface. */
    SILENT,
    /** internal: crash-safe local report, no third-party SDK (§34). */
    LOCAL_REPORT,
}

/** Correlation ids (§33) — redacted per §34; ids only, never payload content. */
data class Correlation(
    val endpointId: String? = null,
    val connectionId: String? = null,
    val sessionId: String? = null,
    val commandId: String? = null,
)


/**
 * FIELD (2026-10-01, vc22 device): TRUE when this error is a SERVER-SPOKE
 * verdict — the desktop/relay received the request and refused it (fault.*
 * channel errors, task rejections, session unavailable). Authoritative
 * evidence: the outbox maps these to FAILED_TERMINAL (rejected), never to the
 * §15.5 UNKNOWN/retry class. Transport/network/timeout categories mean the
 * outcome is genuinely UNKNOWN.
 */
val AppError.serverSpoke: Boolean
    get() = category in setOf(
        ErrorCategory.TASK,
        ErrorCategory.PROTOCOL,
        ErrorCategory.SERVER,
        ErrorCategory.SESSION,
        ErrorCategory.PERMISSION,
        ErrorCategory.AUTHZ,
        ErrorCategory.AUTH,
        ErrorCategory.VERSION_INCOMPAT,
    )
