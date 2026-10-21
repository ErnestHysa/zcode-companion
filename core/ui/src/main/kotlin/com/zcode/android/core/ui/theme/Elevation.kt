package com.zcode.android.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * L5-chips (Phase 6C) elevation tokens — the N1 decision: the desktop's overlays are
 * SHADOW-led (box-shadow ×70), the phone rendered them tonal-only/zero and overlays
 * read as inert labels. Overlays ride a shadow elevation; surfaces inside them stay
 * tonal (the established `surfaceContainer*` ladder carries the fill).
 */
object ZCodeElevation {
    /** Bottom sheets and dialogs — raised chrome above the screen. */
    val sheet: Dp = 6.dp

    /** Small floating overlays (menus, popups) — one step below the sheet. */
    val overlay: Dp = 3.dp
}
