package com.zcode.android.app.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * U7 (verification pass 2, 2026-10-21): the window's system-bar appearance follows the
 * APP's resolved theme, not the system's. `enableEdgeToEdge()`'s default detector reads
 * the SYSTEM uiMode, so an in-app Dark override on a light system kept dark status-bar
 * icons on the dark background (the clock nearly invisible) and the light nav-bar scrim
 * under the dark app. Re-invoked from the composition whenever the resolved dark flag
 * flips — enableEdgeToEdge is idempotent; the M3 scheme itself already recomposes.
 */
fun ComponentActivity.applySystemBarsFor(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(NAV_SCRIM_LIGHT, NAV_SCRIM_DARK) { dark },
    )
}

// androidx.activity's own (private) defaults — the translucent contrast scrims.
private val NAV_SCRIM_LIGHT = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NAV_SCRIM_DARK = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
