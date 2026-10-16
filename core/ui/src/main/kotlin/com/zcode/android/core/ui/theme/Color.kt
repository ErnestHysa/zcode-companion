package com.zcode.android.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * ZCode color system — ui-ux/color-system.md v2 (designed light/dark PAIRS, not
 * inversions). Accent is indigo–cyan on cool slate neutrals; status colors are
 * semantic and ALWAYS pair with icon/structure (never color alone, §18.5/§30).
 *
 * Dynamic color is intentionally NOT applied: ui-ux rule — "dynamic color optional
 * for chrome only; status tokens stay fixed". The brand scheme is the designed pair;
 * reconsideration is a Phase 11 polish item behind an ADR.
 *
 * Surface ladder (J5): the M3 `surfaceContainer*` / `surfaceDim` / `surfaceBright`
 * roles are explicit tokens of the designed pair — never M3's neutral baseline. The
 * container steps reuse the chrome tokens they coincide with (light: surface → codeBg →
 * bg → toolSurface → surface2; dark: bg → surface → toolSurface → surface2 → a raised
 * step) so dialogs (surfaceContainerHigh), menus and sheets (surfaceContainerLow) paint
 * the same slate family as the screens around them. `outlineSoft` is the divider
 * variant — a softer weight than `outline`, which stays the control-border token.
 */
@Immutable
data class ZCodeColors(
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val accentMuted: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textInverse: Color,
    val statusRunning: Color,
    val statusWaiting: Color,
    val statusSuccess: Color,
    val statusError: Color,
    val statusInfo: Color,
    val railRunning: Color,
    val railWaiting: Color,
    val railDone: Color,
    val toolSurface: Color,
    val codeBg: Color,
    val codeBorder: Color,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val outline: Color,
    val outlineStrong: Color,
    val outlineSoft: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
)

/**
 * Light tokens (ui-ux/color-system.md — light is the primary designed pair).
 *
 * J6 contrast floor: every TEXT-role token clears WCAG AA small text (4.5:1) on
 * surface/bg/surface2 — `textMuted`, `statusWaiting` and `statusSuccess` were each
 * darkened one step (3.3–4.4:1 before). `statusWaiting` also clears AA on its own
 * 16%-tint AttentionStrip. The table is pinned by TokenContrastTest.
 */
val ZCodeColorsLight = ZCodeColors(
    accent = Color(0xFF3D5AFE),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE8ECFF),
    accentMuted = Color(0xFF5C6BC0),
    text = Color(0xFF12151C),
    textSecondary = Color(0xFF4A5568),
    textMuted = Color(0xFF656E82),
    textInverse = Color(0xFFFFFFFF),
    statusRunning = Color(0xFF3D5AFE),
    statusWaiting = Color(0xFF8F5800),
    statusSuccess = Color(0xFF1A7B4B),
    statusError = Color(0xFFC62828),
    statusInfo = Color(0xFF1565C0),
    railRunning = Color(0xFF3D5AFE),
    railWaiting = Color(0xFF8F5800),
    railDone = Color(0xFFB8C0D0),
    toolSurface = Color(0xFFF0F3FA),
    codeBg = Color(0xFFF7F8FC),
    codeBorder = Color(0xFFD5DBE7),
    bg = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEEF1F7),
    outline = Color(0xFFD5DBE7),
    outlineStrong = Color(0xFFB8C0D0),
    outlineSoft = Color(0xFFE6EAF2),
    surfaceDim = Color(0xFFE4E8F0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FC),
    surfaceContainer = Color(0xFFF4F6FA),
    surfaceContainerHigh = Color(0xFFF0F3FA),
    surfaceContainerHighest = Color(0xFFEEF1F7),
)

/** Dark tokens (ui-ux mockups 14/15 — deeper, cooler than generic gray). */
val ZCodeColorsDark = ZCodeColors(
    accent = Color(0xFF7C9CFF),
    onAccent = Color(0xFF0A1020),
    accentSoft = Color(0xFF1A2240),
    accentMuted = Color(0xFF9AA8FF),
    text = Color(0xFFE8ECF4),
    textSecondary = Color(0xFF9AA3B5),
    textMuted = Color(0xFF828B9E),
    textInverse = Color(0xFF0B0E14),
    statusRunning = Color(0xFF7C9CFF),
    statusWaiting = Color(0xFFE6A817),
    statusSuccess = Color(0xFF3DCF8E),
    statusError = Color(0xFFFF6B6B),
    statusInfo = Color(0xFF64B5F6),
    railRunning = Color(0xFF7C9CFF),
    railWaiting = Color(0xFFE6A817),
    railDone = Color(0xFF3A4558),
    toolSurface = Color(0xFF161D2A),
    codeBg = Color(0xFF0E121A),
    codeBorder = Color(0xFF2A3344),
    bg = Color(0xFF0B0E14),
    surface = Color(0xFF141A24),
    surface2 = Color(0xFF1C2433),
    outline = Color(0xFF2A3344),
    outlineStrong = Color(0xFF3A4558),
    outlineSoft = Color(0xFF1F2736),
    surfaceDim = Color(0xFF0B0E14),
    surfaceBright = Color(0xFF283140),
    surfaceContainerLowest = Color(0xFF0B0E14),
    surfaceContainerLow = Color(0xFF141A24),
    surfaceContainer = Color(0xFF161D2A),
    surfaceContainerHigh = Color(0xFF1C2433),
    surfaceContainerHighest = Color(0xFF243040),
)

/** M3 scheme mapped onto the token system (chrome roles only — status stays extended). */
val LightScheme: ColorScheme = lightColorScheme(
    primary = ZCodeColorsLight.accent,
    onPrimary = ZCodeColorsLight.onAccent,
    primaryContainer = ZCodeColorsLight.accentSoft,
    onPrimaryContainer = ZCodeColorsLight.text,
    inversePrimary = ZCodeColorsLight.accentMuted,
    secondary = ZCodeColorsLight.accentMuted,
    onSecondary = ZCodeColorsLight.onAccent,
    secondaryContainer = ZCodeColorsLight.accentSoft,
    onSecondaryContainer = ZCodeColorsLight.text,
    tertiary = ZCodeColorsLight.statusInfo,
    onTertiary = Color.White,
    background = ZCodeColorsLight.bg,
    onBackground = ZCodeColorsLight.text,
    surface = ZCodeColorsLight.surface,
    onSurface = ZCodeColorsLight.text,
    surfaceVariant = ZCodeColorsLight.surface2,
    onSurfaceVariant = ZCodeColorsLight.textSecondary,
    surfaceTint = ZCodeColorsLight.accent,
    inverseSurface = Color(0xFF2A3038),
    inverseOnSurface = Color(0xFFF1F3F7),
    error = ZCodeColorsLight.statusError,
    onError = Color.White,
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF8C1D18),
    outline = ZCodeColorsLight.outline,
    outlineVariant = ZCodeColorsLight.outlineSoft,
    scrim = Color(0xFF000000),
    // J5 — the surface ladder (dialogs/menus/sheets) is the designed pair, not M3 baseline.
    surfaceDim = ZCodeColorsLight.surfaceDim,
    surfaceBright = ZCodeColorsLight.surfaceBright,
    surfaceContainerLowest = ZCodeColorsLight.surfaceContainerLowest,
    surfaceContainerLow = ZCodeColorsLight.surfaceContainerLow,
    surfaceContainer = ZCodeColorsLight.surfaceContainer,
    surfaceContainerHigh = ZCodeColorsLight.surfaceContainerHigh,
    surfaceContainerHighest = ZCodeColorsLight.surfaceContainerHighest,
)

/** Dark scheme — designed pair, not an inversion (ui-ux color-system rule 3). */
val DarkScheme: ColorScheme = darkColorScheme(
    primary = ZCodeColorsDark.accent,
    onPrimary = ZCodeColorsDark.onAccent,
    primaryContainer = ZCodeColorsDark.accentSoft,
    onPrimaryContainer = ZCodeColorsDark.text,
    inversePrimary = ZCodeColorsDark.accentMuted,
    secondary = ZCodeColorsDark.accentMuted,
    onSecondary = ZCodeColorsDark.onAccent,
    secondaryContainer = ZCodeColorsDark.accentSoft,
    onSecondaryContainer = ZCodeColorsDark.text,
    tertiary = ZCodeColorsDark.statusInfo,
    onTertiary = Color(0xFF0B2540),
    background = ZCodeColorsDark.bg,
    onBackground = ZCodeColorsDark.text,
    surface = ZCodeColorsDark.surface,
    onSurface = ZCodeColorsDark.text,
    surfaceVariant = ZCodeColorsDark.surface2,
    onSurfaceVariant = ZCodeColorsDark.textSecondary,
    surfaceTint = ZCodeColorsDark.accent,
    inverseSurface = Color(0xFFE8ECF4),
    inverseOnSurface = Color(0xFF161A22),
    error = ZCodeColorsDark.statusError,
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF3B1216),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = ZCodeColorsDark.outline,
    outlineVariant = ZCodeColorsDark.outlineSoft,
    scrim = Color(0xFF000000),
    // J5 — the surface ladder (dialogs/menus/sheets) is the designed pair, not M3 baseline.
    surfaceDim = ZCodeColorsDark.surfaceDim,
    surfaceBright = ZCodeColorsDark.surfaceBright,
    surfaceContainerLowest = ZCodeColorsDark.surfaceContainerLowest,
    surfaceContainerLow = ZCodeColorsDark.surfaceContainerLow,
    surfaceContainer = ZCodeColorsDark.surfaceContainer,
    surfaceContainerHigh = ZCodeColorsDark.surfaceContainerHigh,
    surfaceContainerHighest = ZCodeColorsDark.surfaceContainerHighest,
)
