package com.zcode.android.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/**
 * B2.15/J4 (Phase 3C): relative labels never refreshed — nothing recomposed on the
 * clock. ONE ticking "now" (minute cadence) is provided at the app root; every
 * relative-time site reads it through [relativeTimeLabelLive], so a "2 min ago" label
 * becomes "3 min ago" without a scroll or a data change. Default = the wall clock, so
 * previews/tests without a provider still render.
 */
val LocalRelativeNow: androidx.compose.runtime.ProvidableCompositionLocal<Long> =
    androidx.compose.runtime.compositionLocalOf { System.currentTimeMillis() }

@Composable
fun ProvideRelativeNow(intervalMs: Long = 60_000L, content: @Composable () -> Unit) {
    val now by androidx.compose.runtime.produceState(initialValue = System.currentTimeMillis(), key1 = intervalMs) {
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalRelativeNow provides now, content = content)
}

/** [relativeTimeLabel] against the provided ticking clock. */
@Composable
fun relativeTimeLabelLive(thenMs: Long): String? = relativeTimeLabel(thenMs, LocalRelativeNow.current)
