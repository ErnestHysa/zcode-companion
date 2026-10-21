package com.zcode.android.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.theme.ZCodeRadius

/**
 * M1.2 (Phase 6D): the small Z mark — the brand monogram on pairing/connection,
 * drawn in the language (the `lg` radius tile, the primary-on-primaryContainer
 * pairing, a bold single glyph). Decorative (contentDescription = null): TalkBack
 * reads the screen's heading, not the mark.
 */
@Composable
fun ZMark(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(ZCodeRadius.lg),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Z",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
