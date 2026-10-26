package com.zcode.android.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.UserMessageKey
import com.zcode.android.core.ui.R

/**
 * §33 category → localized user copy at the UI boundary. The data/domain layers
 * stay pure (they may not hold Android resources), so error surfaces translate the
 * AppError CATEGORY into localized copy here and keep the model's `userMessage`
 * only as a same-language detail line. The mapping covers every §33 category —
 * an unmapped category degrades to the model message, never to a bare crash.
 */
@Composable
fun errorUserMessage(error: AppError): String {
    val key = error.messageKey
    if (key != null) {
        val res = userMessageRes(key)
        if (res != 0) return stringResource(res, *error.messageArgs.toTypedArray())
    }
    return errorCategoryMessage(error)
}

/** C5-i18n: the specific §33 message (key + args) — the stress-timed copy, per locale. */
fun userMessageRes(key: UserMessageKey): Int = when (key) {
    UserMessageKey.CONNECTION_LOST_RECONNECTING -> R.string.err_msg_connection_lost_reconnecting
    UserMessageKey.SESSION_TAKEN_OVER -> R.string.err_msg_session_taken_over
    UserMessageKey.DESKTOP_UNREACHABLE -> R.string.err_msg_desktop_unreachable
    UserMessageKey.PAIRING_EXPIRED -> R.string.err_msg_pairing_expired
    UserMessageKey.WORKSPACE_CLOSED -> R.string.err_msg_workspace_closed
    UserMessageKey.DIRECT_TOKEN_REJECTED -> R.string.err_msg_direct_token_rejected
    UserMessageKey.LOCAL_NETWORK_BLOCKED -> R.string.err_msg_local_network_blocked
    UserMessageKey.ENDPOINT_CERT_CHANGED -> R.string.err_msg_endpoint_cert_changed
    UserMessageKey.ENDPOINT_UNENCRYPTED -> R.string.err_msg_endpoint_unencrypted
    UserMessageKey.DESKTOP_APPROVAL_TIMEOUT -> R.string.err_desktop_approval_timeout
    UserMessageKey.DESKTOP_RELAY_LOST -> R.string.err_msg_desktop_relay_lost
    UserMessageKey.RELAY_ERROR_RETRYING -> R.string.err_msg_relay_error_retrying
    UserMessageKey.UNEXPECTED_ERROR_CODE -> R.string.err_msg_unexpected_error_code
    UserMessageKey.SOMETHING_WENT_WRONG -> R.string.err_msg_something_went_wrong
    UserMessageKey.SYNC_PROBLEM_REFRESHING -> R.string.err_msg_sync_problem_refreshing
    UserMessageKey.NETWORK_UNAVAILABLE -> R.string.err_msg_network_unavailable
    UserMessageKey.TAKING_TOO_LONG -> R.string.err_msg_taking_too_long
    UserMessageKey.UPDATE_DESKTOP -> R.string.err_msg_update_desktop
    UserMessageKey.STILL_OFFLINE_CACHED -> R.string.err_msg_still_offline_cached
    UserMessageKey.SESSION_UNAVAILABLE -> R.string.err_msg_session_unavailable
    UserMessageKey.WORKSPACE_NOT_READY -> R.string.err_msg_workspace_not_ready
    UserMessageKey.CONNECTION_PROBLEM_RETRYING -> R.string.err_msg_connection_problem_retrying
    UserMessageKey.CONVERSATION_CHANGED -> R.string.err_msg_conversation_changed
    UserMessageKey.REQUEST_ALREADY_HANDLED -> R.string.err_msg_request_already_handled
    UserMessageKey.LOCAL_DATA_RESET -> R.string.err_msg_local_data_reset
    UserMessageKey.PERMISSION_NEEDED -> R.string.err_msg_permission_needed
    UserMessageKey.NOTIFICATIONS_BLOCKED -> R.string.err_msg_notifications_blocked
    UserMessageKey.NOT_ALLOWED -> R.string.err_msg_not_allowed
    UserMessageKey.NONE -> R.string.err_msg_none
}

/** Category-level copy (the §33 pattern) — the fallback when no specific key exists. */
@Composable
fun errorCategoryMessage(error: AppError): String = when (error.category) {
    ErrorCategory.AUTH -> stringResource(R.string.error_auth)
    ErrorCategory.AUTHZ -> stringResource(R.string.error_authz)
    ErrorCategory.TRANSPORT -> stringResource(R.string.error_transport)
    ErrorCategory.NETWORK -> stringResource(R.string.error_network)
    ErrorCategory.PROTOCOL -> stringResource(R.string.error_protocol)
    ErrorCategory.SERIALIZATION -> stringResource(R.string.error_serialization)
    ErrorCategory.SERVER -> stringResource(R.string.error_server)
    ErrorCategory.SESSION -> stringResource(R.string.error_session)
    ErrorCategory.TASK -> stringResource(R.string.error_task)
    ErrorCategory.PERMISSION -> stringResource(R.string.error_permission)
    ErrorCategory.STORAGE -> stringResource(R.string.error_storage)
    ErrorCategory.ANDROID -> stringResource(R.string.error_android)
    ErrorCategory.NOTIFICATION -> stringResource(R.string.error_notification)
    ErrorCategory.VERSION_INCOMPAT -> stringResource(R.string.error_version_incompat)
    ErrorCategory.UNSUPPORTED_FEATURE -> stringResource(R.string.error_unsupported_feature)
    ErrorCategory.TIMEOUT -> stringResource(R.string.error_timeout)
    ErrorCategory.CANCELLED -> stringResource(R.string.error_cancelled)
    ErrorCategory.INTERNAL -> stringResource(R.string.error_internal)
}

/** Non-composable variant for coroutine/dialog contexts holding a Context. */
fun errorMessage(context: android.content.Context, error: AppError): String {
    val key = error.messageKey
    if (key != null) {
        val res = userMessageRes(key)
        if (res != 0) return context.getString(res, *error.messageArgs.toTypedArray())
    }
    return errorCategoryMessageText(context, error)
}

private fun errorCategoryMessageText(context: android.content.Context, error: AppError): String = when (error.category) {
    ErrorCategory.AUTH -> context.getString(R.string.error_auth)
    ErrorCategory.AUTHZ -> context.getString(R.string.error_authz)
    ErrorCategory.TRANSPORT -> context.getString(R.string.error_transport)
    ErrorCategory.NETWORK -> context.getString(R.string.error_network)
    ErrorCategory.PROTOCOL -> context.getString(R.string.error_protocol)
    ErrorCategory.SERIALIZATION -> context.getString(R.string.error_serialization)
    ErrorCategory.SERVER -> context.getString(R.string.error_server)
    ErrorCategory.SESSION -> context.getString(R.string.error_session)
    ErrorCategory.TASK -> context.getString(R.string.error_task)
    ErrorCategory.PERMISSION -> context.getString(R.string.error_permission)
    ErrorCategory.STORAGE -> context.getString(R.string.error_storage)
    ErrorCategory.ANDROID -> context.getString(R.string.error_android)
    ErrorCategory.NOTIFICATION -> context.getString(R.string.error_notification)
    ErrorCategory.VERSION_INCOMPAT -> context.getString(R.string.error_version_incompat)
    ErrorCategory.UNSUPPORTED_FEATURE -> context.getString(R.string.error_unsupported_feature)
    ErrorCategory.TIMEOUT -> context.getString(R.string.error_timeout)
    ErrorCategory.CANCELLED -> context.getString(R.string.error_cancelled)
    ErrorCategory.INTERNAL -> context.getString(R.string.error_internal)
}

// ---------------------------------------------------------------- K9-errorcopy

/**
 * K9-errorcopy: a port's RAW error string (desktop `fault.*` codes, transport
 * internals like "channel closed: no outbound sink attached") classified into the
 * §33 vocabulary. The raw string stays in the diagnostics ring (the transport logs
 * every frame) and never reaches an error state on screen.
 */
fun faultMessageRes(raw: String?): Int {
    val r = raw.orEmpty().lowercase()
    return when {
        r.isBlank() -> R.string.err_msg_something_went_wrong
        r.startsWith("fault.") && (r.contains("unscripted") || r.contains("unsupported") || r.contains("not_found") || r.contains("notfound") || r.contains("unknown method") || r.contains("no handler")) ->
            R.string.err_fault_unavailable
        r.startsWith("fault.") -> R.string.err_fault_desktop
        r.contains("timed out") || r.contains("timeout") -> R.string.err_msg_taking_too_long
        r.contains("channel closed") || r.contains("outbound sink") || r.contains("not connected") || r.contains("socket") || r.contains("eof") || r.contains("disconnected") ->
            R.string.err_msg_connection_lost_reconnecting
        r.contains("refused") || r.contains("denied") || r.contains("unauthori") || r.contains("forbidden") || r.contains("403") -> R.string.err_msg_not_allowed
        r.contains("unreachable") || r.contains("network") || r.contains("dns") -> R.string.err_msg_network_unavailable
        else -> R.string.err_fault_desktop
    }
}

@Composable
fun friendlyFaultMessage(raw: String?): String = stringResource(faultMessageRes(raw))

fun friendlyFaultMessage(context: android.content.Context, raw: String?): String = context.getString(faultMessageRes(raw))
