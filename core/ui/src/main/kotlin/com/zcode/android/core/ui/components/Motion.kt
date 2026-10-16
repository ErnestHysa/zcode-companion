package com.zcode.android.core.ui.components

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * §30 reduced-motion support: the system "remove animations" switch (animator
 * duration scale 0, a11y "Animation removed") must disable non-essential motion.
 * Non-essential motion in this app = scroll snap animations + (any future) shimmer;
 * essential state changes stay — they are information, not decoration.
 *
 * The flag is read from the platform setting, not a custom preference, so the
 * OS-level accessibility switch is honored (§30 "honor system settings").
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { isReducedMotion(context) }
}

fun isReducedMotion(context: android.content.Context): Boolean =
    runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

// ---------------------------------------------------------------- B3-motion + K2

/**
 * The motion vocabulary (B3-motion + K2) — the desktop's shipped numbers from
 * `styles.css`, DESIGN.md's principle ("fast and low-drama; subtle fade, zoom and
 * directional slide; never springy in the workspace"). No spring eases exist in this
 * app (MotionCensusTest gates it). Every consumer goes through [rememberMotionSpec]
 * so the system reduced-motion switch is ONE kill-switch for all of it.
 */
object ZCodeMotion {
    /** `zcode-stream-text-in`: rows/streams ARRIVE — the desktop's identity animation. */
    val StreamIn: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val StreamInMs: Int = 900

    /** Per-marker stagger (`--zcode-stream-animation-delay`). */
    const val StreamStaggerMs: Int = 40

    /** Collapsible sections: 300ms ease-in-out. */
    val Collapse: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    const val CollapseMs: Int = 300

    /** Draft-prompt waterfall: 260ms. */
    val Waterfall: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    const val WaterfallMs: Int = 260

    /** NavHost shared-axis: ≈300ms fade + a short directional slide. */
    const val NavMs: Int = 300
    const val NavSlideFraction: Int = 16
}

/** Reduced-motion-aware durations: everything collapses to 0ms under the system switch. */
@Immutable
data class MotionSpec(val enabled: Boolean) {
    fun ms(base: Int): Int = if (enabled) base else 0
}

@Composable
fun rememberMotionSpec(): MotionSpec {
    val reduced = rememberReducedMotion()
    return remember(reduced) { MotionSpec(enabled = !reduced) }
}

/** Navigation transitions for the NavHost (forward: slide in from the end; pop: from the start). */
@Immutable
data class NavTransitions(
    val enter: EnterTransition,
    val exit: ExitTransition,
    val popEnter: EnterTransition,
    val popExit: ExitTransition,
)

@Composable
fun rememberNavTransitions(): NavTransitions {
    val spec = rememberMotionSpec()
    return remember(spec) {
        if (!spec.enabled) {
            NavTransitions(EnterTransition.None, ExitTransition.None, EnterTransition.None, ExitTransition.None)
        } else {
            val ms = ZCodeMotion.NavMs
            val f = ZCodeMotion.NavSlideFraction
            NavTransitions(
                enter = fadeIn(tween(ms)) + slideInHorizontally(tween(ms, easing = ZCodeMotion.Waterfall)) { it / f },
                exit = fadeOut(tween(ms)) + slideOutHorizontally(tween(ms, easing = ZCodeMotion.Waterfall)) { -it / f },
                popEnter = fadeIn(tween(ms)) + slideInHorizontally(tween(ms, easing = ZCodeMotion.Waterfall)) { -it / f },
                popExit = fadeOut(tween(ms)) + slideOutHorizontally(tween(ms, easing = ZCodeMotion.Waterfall)) { it / f },
            )
        }
    }
}

/**
 * Collapsible content (expand/collapse, "view more", tool-card bodies): 300ms
 * ease-in-out `animateContentSize`; the identity modifier under reduced motion.
 */
@Composable
fun Modifier.animateCollapse(): Modifier {
    val spec = rememberMotionSpec()
    return if (spec.enabled) {
        this.animateContentSize(tween(ZCodeMotion.CollapseMs, easing = ZCodeMotion.Collapse))
    } else {
        this
    }
}

/**
 * Row stream-in (K2): the row fades in and settles up 8dp over 900ms
 * `cubic-bezier(0.16, 1, 0.3, 1)`, staggered by [index]. Runs once per [key] — callers
 * pass a `seen` set so rows scrolled back into view do not re-arrive. Identity under
 * reduced motion.
 */
@Immutable
class ArrivalTracker internal constructor(private val seen: MutableSet<Any>) {
    /** true iff [key] had not arrived before (and records it). */
    fun arrive(key: Any): Boolean = seen.add(key)
}

@Composable
fun rememberArrivalTracker(): ArrivalTracker {
    val seen = remember { mutableSetOf<Any>() }
    return remember(seen) { ArrivalTracker(seen) }
}

@Composable
fun Modifier.streamIn(key: Any, index: Int, tracker: ArrivalTracker): Modifier {
    val spec = rememberMotionSpec()
    val firstArrival = remember(key) { tracker.arrive(key) }
    if (!spec.enabled || !firstArrival) return this
    var target by remember(key) { mutableStateOf(0f) }
    LaunchedEffect(key) { target = 1f }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = ZCodeMotion.StreamInMs,
            delayMillis = (index.coerceAtLeast(0) * ZCodeMotion.StreamStaggerMs).coerceAtMost(400),
            easing = ZCodeMotion.StreamIn,
        ),
        label = "streamIn",
    )
    val density = LocalDensity.current
    return this.graphicsLayer {
        alpha = progress
        translationY = with(density) { 8.dp.toPx() } * (1f - progress)
    }
}

/** Haptic ticks on send / pick / copy (B3: "zero haptics"). Not motion — never gated. */
@Immutable
class ZCodeHaptics internal constructor(private val perform: (HapticFeedbackType) -> Unit) {
    /** A light tick: pick, copy, toggle. */
    fun tick() = perform(HapticFeedbackType.TextHandleMove)

    /** A firmer confirm: send, approve. */
    fun confirm() = perform(HapticFeedbackType.LongPress)
}

@Composable
fun rememberZCodeHaptics(): ZCodeHaptics {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { ZCodeHaptics { type -> haptic.performHapticFeedback(type) } }
}
