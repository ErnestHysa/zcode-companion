package com.zcode.android.core.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
