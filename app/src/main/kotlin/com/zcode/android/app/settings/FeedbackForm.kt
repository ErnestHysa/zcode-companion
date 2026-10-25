package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * H1.6 (Phase 6B): the in-app feedback form (desktop `feedback.*` ticketed reporting).
 * The phone holds NO credentials — the HOST is the submitter: the `feedback` channel's
 * `create` rides to the desktop, which adds its device-mid + auth and POSTs
 * /feedback/ticket. The form collects the desktop's four ticket types (bug / suggestion
 * / slow performance / how-to help), a required description and an optional contact.
 */
fun interface FeedbackSender {
    suspend fun send(type: String, description: String, contact: String?): FeedbackResult
}

sealed interface FeedbackResult {
    data class Ticket(val id: String, val status: String) : FeedbackResult
    data class Failed(val reason: String?) : FeedbackResult
}

@Composable
fun FeedbackFormDialog(
    onDismiss: () -> Unit,
    sender: FeedbackSender,
    modifier: Modifier = Modifier,
) {
    // Wire values (feedback.type.* enum): bug | feature | performance | usage.
    val types = listOf(
        "bug" to R.string.settings_feedback_type_bug,
        "feature" to R.string.settings_feedback_type_feature,
        "performance" to R.string.settings_feedback_type_performance,
        "usage" to R.string.settings_feedback_type_usage,
    )
    var type by remember { mutableStateOf("bug") }
    var description by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FeedbackResult?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("feedback_dialog"),
        title = { Text(stringResource(R.string.settings_feedback_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val r = result
                when {
                    r is FeedbackResult.Ticket -> Text(
                        stringResource(R.string.settings_feedback_submitted, r.id),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("feedback_result"),
                    )
                    r is FeedbackResult.Failed -> Text(
                        stringResource(R.string.settings_feedback_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("feedback_result"),
                    )
                    else -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            types.forEach { (value, label) ->
                                FilterChip(
                                    selected = type == value,
                                    onClick = { type = value },
                                    label = { Text(stringResource(label)) },
                                    modifier = Modifier.testTag("feedback_type_$value"),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource(R.string.settings_feedback_description)) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth().testTag("feedback_description"),
                        )
                        OutlinedTextField(
                            value = contact,
                            onValueChange = { contact = it },
                            label = { Text(stringResource(R.string.settings_feedback_contact)) },
                            modifier = Modifier.fillMaxWidth().testTag("feedback_contact"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && result == null && description.isNotBlank(),
                modifier = Modifier.testTag("feedback_submit"),
                onClick = {
                    busy = true
                    scope.launch {
                        result = try {
                            sender.send(type, description.trim(), contact.trim().ifBlank { null })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            FeedbackResult.Failed(e.message)
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.settings_feedback_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("feedback_close")) {
                Text(stringResource(R.string.feedback_close))
            }
        },
    )
}
