package com.zcode.android.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * L5-chips (Phase 6C) elevation tokens — the N1 decision: the desktop's overlays are
 * shadow-led (box-shadow ×70), the phone rendered them tonal-only/zero and overlays
 * read as inert labels. M3's sheet/dialog surfaces expose only a tonal parameter, so
 * the tokens are applied as the tonal step wherever the API allows (the sheet) —
 * zero-elevation overlays are no longer in the language.
 */
object ZCodeElevation {
    /** Bottom sheets and dialogs — raised chrome above the screen. */
    val sheet: Dp = 6.dp

    /** Small floating overlays (menus, popups) — one step below the sheet. */
    val overlay: Dp = 3.dp
}
