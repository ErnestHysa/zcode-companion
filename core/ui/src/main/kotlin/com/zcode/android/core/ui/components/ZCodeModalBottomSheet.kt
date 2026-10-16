package com.zcode.android.core.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.theme.ZCodeRadius

/**
 * The app's ModalBottomSheet (B3-sheets): pickers and menus live in sheets, not stacked
 * dialogs. Shape = the scale's `xl` top corners (dialogs' step, K1.2), container =
 * `surfaceContainerLow` (the J5 sheet role), and `skipPartiallyExpanded` — an action
 * menu has no half-state worth stopping at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZCodeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = ZCodeRadius.xl, topEnd = ZCodeRadius.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        content = content,
    )
}
