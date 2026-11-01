package com.zcode.android.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * App-level re-export of the core:ui ZCodeTheme (ADR-016 — the token system lives in
 * `core:ui` so every feature module renders the same designed pairs; this shim keeps
 * the app-level import path stable).
 */
@Composable
fun ZCodeTheme(
    themeMode: com.zcode.android.core.ui.theme.ThemeMode = com.zcode.android.core.ui.theme.ThemeMode.SYSTEM,
    /** G38 (vc51): the in-app text-scale multiplier (the shim forwards it). */
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    com.zcode.android.core.ui.theme.ZCodeTheme(themeMode = themeMode, fontScale = fontScale, content = content)
}
