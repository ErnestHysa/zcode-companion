package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.features.conversation.R
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

/**
 * GAP-10 (§11.4) — the edit sheet + its explicit confirm step: the desktop
 * re-asks the turn ("用行内编辑文本替换该轮"), so the confirmed text goes out
 * only behind the second dialog (the roadmap's "confirm dialog present"). The
 * field starts pre-filled with the row's current text (desktop inline-editor
 * parity). The projection changes ONLY when the authoritative churn arrives —
 * this dialog touches nothing locally.
 */
@Composable
fun EditUserQueryDialog(
    initialText: String,
    onConfirm: (newText: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // B2.13: keyed on the row's current text — reopening with different text re-seeds.
    var text by remember(initialText) { mutableStateOf(initialText) }
    var confirmOpen by remember { mutableStateOf(false) }
    // B3-sheets: ONE sheet — the edit, then an in-sheet review step (the old edit →
    // confirm double modal is gone; the confirm is still a deliberate second tap).
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (confirmOpen) {
                Column(Modifier.testTag("edit_confirm_dialog")) {
                    Text(stringResource(R.string.edit_confirm_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.edit_confirm_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmOpen = false }) { Text(stringResource(R.string.action_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { confirmOpen = false; onConfirm(text.trim()) },
                            modifier = Modifier.testTag("edit_confirm_button"),
                        ) { Text(stringResource(R.string.edit_confirm_button)) }
                    }
                }
            } else {
                Text(stringResource(R.string.edit_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_text_field"),
                    maxLines = 6,
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("edit_dismiss")) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { confirmOpen = true },
                        enabled = text.isNotBlank() && text != initialText,
                        modifier = Modifier.testTag("edit_review"),
                    ) { Text(stringResource(R.string.edit_review)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
