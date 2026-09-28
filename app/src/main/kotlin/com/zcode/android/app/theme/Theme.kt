package com.zcode.android.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Minimal M3 theme (§18.5 restrained: dark default follows system, restrained brand
 * fallback, no custom decoration). The full theme work lands with the Phase 6 shell.
 */
private val DarkScheme = darkColorScheme()
private val LightScheme = lightColorScheme()

@Composable
fun ZCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
