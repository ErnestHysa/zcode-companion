package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
) {
    var text by remember { mutableStateOf(initialText) }
    var confirmOpen by remember { mutableStateOf(false) }
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            modifier = Modifier.testTag("edit_confirm_dialog"),
            title = { Text(stringResource(R.string.edit_confirm_title)) },
            text = { Text(stringResource(R.string.edit_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmOpen = false; onConfirm(text.trim()) },
                    modifier = Modifier.testTag("edit_confirm_button"),
                ) { Text(stringResource(R.string.edit_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_title)) },
        text = {
            Column {
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirmOpen = true },
                enabled = text.isNotBlank() && text != initialText,
                modifier = Modifier.testTag("edit_review"),
            ) { Text(stringResource(R.string.edit_review)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("edit_dismiss")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
