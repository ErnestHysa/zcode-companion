package com.zcode.android.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.ui.R

/**
 * §33 category → localized user copy at the UI boundary. The data/domain layers
 * stay pure (they may not hold Android resources), so error surfaces translate the
 * AppError CATEGORY into localized copy here and keep the model's `userMessage`
 * only as a same-language detail line. The mapping covers every §33 category —
 * an unmapped category degrades to the model message, never to a bare crash.
 */
@Composable
fun errorUserMessage(error: AppError): String = when (error.category) {
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
fun errorMessage(context: android.content.Context, error: AppError): String = when (error.category) {
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
