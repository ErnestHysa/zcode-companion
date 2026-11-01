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
fun ZCodeTheme(
    themeMode: com.zcode.android.core.ui.theme.ThemeMode = com.zcode.android.core.ui.theme.ThemeMode.SYSTEM,
    /** G38 (vc51): the IN-APP text-scale multiplier on top of the OS sp scale. */
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    // H1.4-phase1: the override wins; SYSTEM keeps the pre-4F behavior.
    // THEME-ZAI (vc51): the two brand palettes sit beside Light/Dark.
    val dark = themeMode.resolveDark()
    val zai = themeMode == ThemeMode.ZAI_LIGHT || themeMode == ThemeMode.ZAI_DARK
    val tokens = when {
        themeMode == ThemeMode.ZAI_LIGHT -> ZCodeColorsZaiLight
        themeMode == ThemeMode.ZAI_DARK -> ZCodeColorsZaiDark
        dark -> ZCodeColorsDark
        else -> ZCodeColorsLight
    }
    val scheme = when {
        themeMode == ThemeMode.ZAI_LIGHT -> LightZaiScheme
        themeMode == ThemeMode.ZAI_DARK -> DarkZaiScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = ZCodeTypographyLight.m3.scaledBy(fontScale),
        shapes = ZCodeShapes,
        content = {
            CompositionLocalProvider(LocalZCodeColors provides tokens) { content() }
        },
    )
}

/** G38 (vc51): scale EVERY style's sp size + line height by the in-app multiplier. */
fun androidx.compose.material3.Typography.scaledBy(factor: Float): androidx.compose.material3.Typography {
    if (factor == 1f) return this
    fun scale(style: androidx.compose.ui.text.TextStyle): androidx.compose.ui.text.TextStyle =
        style.copy(
            fontSize = style.fontSize * factor,
            lineHeight = style.lineHeight * factor,
        )
    return copy(
        displayLarge = scale(displayLarge),
        displayMedium = scale(displayMedium),
        displaySmall = scale(displaySmall),
        headlineLarge = scale(headlineLarge),
        headlineMedium = scale(headlineMedium),
        headlineSmall = scale(headlineSmall),
        titleLarge = scale(titleLarge),
        titleMedium = scale(titleMedium),
        titleSmall = scale(titleSmall),
        bodyLarge = scale(bodyLarge),
        bodyMedium = scale(bodyMedium),
        bodySmall = scale(bodySmall),
        labelLarge = scale(labelLarge),
        labelMedium = scale(labelMedium),
        labelSmall = scale(labelSmall),
    )
}

/** Extended semantic tokens outside the M3 scheme (status/timeline/code chrome). */
val LocalZCodeColors = staticCompositionLocalOf { ZCodeColorsLight }

/** Convenience accessors for the compact type scale (ui-ux/typography.md). */
object ZCodeText {
    val typography = ZCodeTypographyLight
}

/** H1.4-phase1: the phone's first theme override (the desktop's themeMode). */
enum class ThemeMode { LIGHT, DARK, SYSTEM, ZAI_LIGHT, ZAI_DARK }

/**
 * The ONE resolution of a theme mode to a dark flag (U7): the scheme and the window's
 * system bars must agree, so both read it here. The override wins; SYSTEM reads the system.
 */
@Composable
fun ThemeMode.resolveDark(): Boolean = when (this) {
    ThemeMode.LIGHT, ThemeMode.ZAI_LIGHT -> false
    ThemeMode.DARK, ThemeMode.ZAI_DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}
