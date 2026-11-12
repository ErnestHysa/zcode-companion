package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R

/**
 * G43-P24 (vc52 Phase 13): the What's-new sheet — the desktop's post-update
 * release-notes surface. Shown ONCE after an update into a recorded version
 * (the record rides the install handoff), and openable any time from
 * Settings → About. The notes are the release's own meta text; an absent
 * record renders the honest empty face (never a fabricated changelog).
 */
@Composable
fun WhatsNewDialog(
    versionName: String?,
    notes: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("whats_new_dialog"),
        title = {
            Text(
                text = versionName?.let { stringResource(R.string.settings_whats_new) + " · $it" }
                    ?: stringResource(R.string.settings_whats_new),
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (notes.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.whats_new_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("whats_new_notes"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("whats_new_dismiss"),
            ) {
                Text(stringResource(R.string.whats_new_dismiss))
            }
        },
    )
}
