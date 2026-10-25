package com.zcode.android.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The phone's radius system (K1.2 + N10-radius) — the desktop's shipped 5-step scale
 * (`--radius-xs .125rem` → `--radius-xl .75rem`, N1) as the ONE Shapes source.
 *
 * Nesting rule (the desktop's, K1): the first rounded container takes the largest step
 * and nested containers / controls step DOWN one level from their parent — dialogs and
 * sheets `xl`, a card inside them `lg`, a control inside the card `md`/`sm`, the
 * meter bar inside a control `xs`. Max radius is 12dp (N1): full-pill radii are not in
 * the language, which is why the M3 Button 50% default is replaced by [ZCodeButton]
 * (`ButtonDefaults.shape` is a hard `CornerFull`, not themeable through [Shapes]).
 *
 * Every `RoundedCornerShape` in main sources must come from here — either a
 * [androidx.compose.material3.MaterialTheme.shapes] role or a [ZCodeRadius] token
 * (ThemeShapesTest gates literal dp spellings repo-wide).
 */
object ZCodeRadius {
    /** 2dp — meters, progress segments, swatches inside controls. */
    val xs: Dp = 2.dp

    /** 4dp — chips, badges, text fields, small controls. */
    val sm: Dp = 4.dp

    /** 6dp — cards' inner panels, code/monospace blocks, inline tags. */
    val md: Dp = 6.dp

    /** 8dp — buttons, cards, tool rows, menus (the desktop `lg`). */
    val lg: Dp = 8.dp

    /** 12dp — dialogs, bottom sheets, the user bubble (the desktop `xl`, the maximum). */
    val xl: Dp = 12.dp
}

/**
 * M3 role mapping onto the scale: `extraSmall`=xs (snackbar/menu furniture), `small`=sm
 * (chips, fields), `medium`=md (cards' content), `large`=lg (buttons, FAB, cards),
 * `extraLarge`=xl (dialogs, sheets).
 */
val ZCodeShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(ZCodeRadius.xs),
    small = RoundedCornerShape(ZCodeRadius.sm),
    medium = RoundedCornerShape(ZCodeRadius.md),
    large = RoundedCornerShape(ZCodeRadius.lg),
    extraLarge = RoundedCornerShape(ZCodeRadius.xl),
)
