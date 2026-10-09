package com.zcode.android.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ZCodeTheme — M3 scheme + extended semantic tokens (§18.5; ui-ux design system v2).
 * Dark follows the system; light/dark are designed pairs. Dynamic color is not
 * applied (ui-ux color-system rule: status tokens stay fixed; reconsider at Phase 11
 * behind an ADR).
 */
@Composable
fun ZCodeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val tokens = if (dark) ZCodeColorsDark else ZCodeColorsLight
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = ZCodeTypographyLight.m3,
        content = {
            CompositionLocalProvider(LocalZCodeColors provides tokens) { content() }
        },
    )
}

/** Extended semantic tokens outside the M3 scheme (status/timeline/code chrome). */
val LocalZCodeColors = staticCompositionLocalOf { ZCodeColorsLight }

/** Convenience accessors for the compact type scale (ui-ux/typography.md). */
object ZCodeText {
    val typography = ZCodeTypographyLight
}
