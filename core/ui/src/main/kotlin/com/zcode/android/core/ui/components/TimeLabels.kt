package com.zcode.android.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.zcode.android.core.ui.R

/**
 * §31 relative time at the UI boundary: ICU plurals + localized strings from
 * resources. Computation matches the ui-ux spec shape ("2m", "3h", "Yesterday",
 * "2d"); the words come from resources so locale rules own the rendering.
 * Returns null beyond the window (caller omits the element).
 */
@Composable
fun relativeTimeLabel(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
    if (thenMs <= 0) return null
    val delta = nowMs - thenMs
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        delta < 0 -> null
        delta < minute -> stringResource(R.string.relative_now)
        delta < hour -> pluralStringResource(R.plurals.relative_minutes, (delta / minute).toInt(), delta / minute)
        delta < day -> pluralStringResource(R.plurals.relative_hours, (delta / hour).toInt(), delta / hour)
        delta < 2 * day -> stringResource(R.string.relative_yesterday)
        delta < 7 * day -> pluralStringResource(R.plurals.relative_days, (delta / day).toInt(), delta / day)
        else -> null
    }
}
