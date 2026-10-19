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
 * behind an ADR). Shapes are the desktop radius scale ([ZCodeShapes], K1.2).
 */
@Composable
fun ZCodeTheme(themeMode: com.zcode.android.core.ui.theme.ThemeMode = com.zcode.android.core.ui.theme.ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    // H1.4-phase1: the override wins; SYSTEM keeps the pre-4F behavior.
    val dark = when (themeMode) {
        com.zcode.android.core.ui.theme.ThemeMode.LIGHT -> false
        com.zcode.android.core.ui.theme.ThemeMode.DARK -> true
        com.zcode.android.core.ui.theme.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val tokens = if (dark) ZCodeColorsDark else ZCodeColorsLight
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = ZCodeTypographyLight.m3,
        shapes = ZCodeShapes,
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

/** H1.4-phase1: the phone's first theme override (the desktop's themeMode). */
enum class ThemeMode { LIGHT, DARK, SYSTEM }
