package com.zcode.android.core.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * M2 (gaps-and-polish Phase 2G): large screens had ZERO width discipline — prose ran
 * 915dp in landscape. The window width class is computed once at the activity
 * (androidx WindowSizeClass) and provided here; reader content caps at [ReaderMaxWidth]
 * and centers on wider windows. The task list stays a list at expanded width — the
 * two-pane list-detail split is Phase 4D's redesign cluster.
 */
val LocalWindowWidthClass = staticCompositionLocalOf { WindowWidthSizeClass.Compact }

/** The desktop reading ceiling (M2: ~60–75 chars ≈ 600dp). */
val ReaderMaxWidth: Dp = 600.dp

@Composable
fun isExpandedWidth(): Boolean =
    LocalWindowWidthClass.current == WindowWidthSizeClass.Expanded ||
        LocalConfiguration.current.screenWidthDp >= 840

/**
 * Reader content: capped at [max] and (as a sibling of a fillMaxWidth parent) centered —
 * apply inside a horizontally-centering parent (Column with CenterHorizontally).
 * A no-op when the window is not wider than [max].
 */
@Composable
fun Modifier.readerWidth(max: Dp = ReaderMaxWidth): Modifier {
    val config = LocalConfiguration.current
    return if (config.screenWidthDp > max.value) this.widthIn(max = max) else this
}
