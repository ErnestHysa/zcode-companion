package com.zcode.android.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.zcode.android.core.ui.R

/**
 * L7 (Phase 6C): the section-scoped error boundary — the runtime half of robustness
 * (desktop `appError.*`: "this area broke; other areas keep working", retry-this-area,
 * stack captured to diagnostics). A crashed section renders the error card with a
 * Retry that re-runs its preparation; SIBLING sections are untouched.
 *
 * Engineering constraint (recorded): Compose cannot catch exceptions thrown during
 * COMPOSITION itself (a throwing composable corrupts the slot table — there is no
 * ErrorBoundary in Compose). The boundary therefore owns the RENDER-PREPARATION layer
 * — the pure derivation that turns wire state into what a section renders (timeline
 * grouping, markdown/diff parsing, projections). A crash there — the common class —
 * is contained; renderer-internal fallbacks (INV-8) cover the known-kinds.
 */
@Composable
fun <T> SectionBoundary(
    key: Any?,
    prepare: () -> T,
    onCrash: (Throwable) -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    var failure by remember(key) { mutableStateOf<Throwable?>(null) }
    var version by remember(key) { mutableStateOf(0) }
    val current = if (failure == null) {
        try {
            prepare()
        } catch (t: Throwable) {
            failure = t
            onCrash(t)
            null
        }
    } else null
    val f = failure
    if (f != null || current == null) {
        ErrorState(
            title = stringResource(R.string.section_broken_title),
            message = stringResource(R.string.section_broken_body),
            detail = f?.message,
            onRetry = {
                failure = null
                version += 1
            },
            retryTestTag = "section_boundary_retry",
            // The boundary's own tag comes FIRST so a caller's tag (later in the
            // chain) overrides it for per-section queries.
            modifier = Modifier.testTag("section_boundary").then(modifier),
        )
    } else {
        androidx.compose.runtime.key(version) {
            content(current)
        }
    }
}
