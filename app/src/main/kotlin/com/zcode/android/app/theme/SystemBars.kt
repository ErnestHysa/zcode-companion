package com.zcode.android.app.theme

import androidx.activity.ComponentActivity

/**
 * U7 (verification pass 2, 2026-10-21): the window's system-bar appearance must follow the
 * APP's resolved theme, not the system's. Seam added compile-only for the RED pin; the
 * fix commit implements it.
 */
fun ComponentActivity.applySystemBarsFor(dark: Boolean) {
    // no-op seam (RED)
}
