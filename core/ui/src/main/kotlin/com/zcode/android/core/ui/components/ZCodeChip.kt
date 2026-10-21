package com.zcode.android.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * L5-chips (Phase 6C): THE chip — one visual language for every chip-shaped
 * affordance (the conversation header previously ran two: outlined-transparent
 * session chips two rows above filled-white bordered route chips). Language:
 * outlined + transparent, the selected state adds the subtle
 * `surfaceContainerHighest` fill, radius = the theme's chip step (`small`, 4dp —
 * [com.zcode.android.core.ui.theme.ZCodeRadius.sm]), label = labelMedium,
 * single-line, with the same interactive profile as the M3 chips it replaces.
 */
@Composable
fun ZCodeChip(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    testTag: String? = null,
    contentDescription: String? = null,
    labelMaxLines: Int = 1,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription?.let { this.contentDescription = it }
            }
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = labelMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
