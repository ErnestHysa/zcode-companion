package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.features.conversation.R

/** G19 (vc51 Phase 4D): the conversation share modes beyond the transcript text. */
enum class ShareMode { PLAIN_TEXT, MARKDOWN }

/**
 * G19 (vc51 Phase 4D): the share MODE chooser — the conv_share chip used to fire the
 * plain-text ACTION_SEND directly. The sheet offers ≥2 modes (plain text + the markdown
 * export); the read-only-timeline/LINK modes stay a dated boundary (they need the
 * desktop's hosted share endpoint — a phone cannot serve it).
 */
@Composable
fun ShareModeSheet(
    onMode: (ShareMode) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_mode_title)) },
        text = {
            Column {
                ShareModeRow(
                    label = stringResource(R.string.share_mode_text),
                    detail = stringResource(R.string.share_mode_text_detail),
                    tag = "share_mode_text",
                    onClick = { onMode(ShareMode.PLAIN_TEXT) },
                )
                ShareModeRow(
                    label = stringResource(R.string.share_mode_markdown),
                    detail = stringResource(R.string.share_mode_markdown_detail),
                    tag = "share_mode_markdown",
                    onClick = { onMode(ShareMode.MARKDOWN) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ShareModeRow(label: String, detail: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
