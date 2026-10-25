package com.zcode.android.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.R

/**
 * The state family (B3-states): ONE empty / error / loading / section-header vocabulary
 * for every screen (ui-ux §18.3: skeletons over spinners, honest empty states, errors
 * always carry a retry when one exists). Shimmer is reduced-motion-aware through
 * [rememberMotionSpec]. StatesDisciplineTest gates adoption on the viewers.
 */

/** Section heading inside a settings-style screen (replaces the hand-rolled SectionLabels). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** Honest empty state: icon + title + optional message + optional action slot. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = Icons.Outlined.Inbox,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/**
 * Error state: title in the error role, an optional friendly message, an optional
 * diagnostic detail line (muted; what the raw fault/transport string becomes under
 * K9-errorcopy), and a Retry whenever a retry exists (B3: errors never dead-end).
 */
@Composable
fun ErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    detail: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = stringResource(R.string.action_retry),
    retryTestTag: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!message.isNullOrBlank()) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (!detail.isNullOrBlank()) {
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRetry != null) {
            ZCodeOutlinedButton(
                onClick = onRetry,
                modifier = if (retryTestTag != null) Modifier.testTag(retryTestTag) else Modifier,
            ) { Text(retryLabel) }
        }
    }
}

/**
 * Loading skeleton: [rows] shimmering bars of varying width (no redundant "Loading…"
 * text — the skeleton IS the loading signal; TalkBack gets one `loading` description).
 */
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    val widths = listOf(1f, 0.7f, 0.85f, 0.6f, 0.9f)
    val loading = stringResource(R.string.state_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) { i -> SkeletonBar(Modifier.fillMaxWidth(widths[i % widths.size])) }
    }
}

/**
 * Placeholder skeleton bar (§18.3: skeletons over spinners). Pulses 1.8s (K2: the
 * desktop's image-loading shimmer); static under the system reduced-motion switch.
 */
@Composable
fun SkeletonBar(modifier: Modifier = Modifier) {
    val motion = rememberMotionSpec()
    val alpha: Float = if (motion.enabled) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(900, easing = ZCodeMotion.Collapse), RepeatMode.Reverse),
            label = "skeletonAlpha",
        )
        a
    } else {
        1f
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
    )
}
