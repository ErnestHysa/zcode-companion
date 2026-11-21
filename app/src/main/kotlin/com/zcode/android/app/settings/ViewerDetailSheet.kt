package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet

/**
 * pass-0 Phase 10 — G103: the catalog DETAIL tier. The six workspace-tool
 * viewers rendered lists whose rows were NON-CLICKABLE BY CONSTRUCTION; a tap
 * now opens this read-only bottom-sheet carrying the item's fields (the
 * G91/G82 viewer-family shape extended one tier deeper). The sheet renders
 * NOTHING but the fields it is given — every screen keeps its own honest
 * absent-face contract (no invented detail, no write affordance anywhere).
 */
data class ViewerDetailField(val label: String, val value: String?)

@Composable
fun ViewerDetailSheet(
    title: String,
    fields: List<ViewerDetailField>,
    tag: String,
    onDismiss: () -> Unit,
) {
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(tag)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag("${tag}_title"),
            )
            for (field in fields) {
                field.value?.takeIf { it.isNotBlank() }?.let { value ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            field.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .testTag("${tag}_label"),
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("${tag}_value"),
                        )
                    }
                }
            }
        }
    }
}
