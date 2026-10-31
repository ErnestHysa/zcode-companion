package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.domain.model.BackgroundBashOutput
import com.zcode.android.domain.model.BackgroundBashOutputUnavailable
import com.zcode.android.domain.model.BackgroundWork
import com.zcode.android.features.conversation.R
import kotlinx.coroutines.launch

/**
 * G15 (vc51) — the background-work plane the wire always carried and the phone
 * never surfaced beyond a summary count: the desktop composer's
 * [ConversationBackgroundWorkTrigger] count pill (tap → the manager), the work
 * list with cancel for cancellable entries (cancelBackgroundWork was wired but
 * unreachable), and the READ-ONLY output viewer over `backgroundBashOutputV4`
 * (background-bash-output.ts: a bounded ≤8 KiB snapshot + truncated flag).
 */

@Composable
fun BackgroundWorkIndicator(
    running: Int,
    works: List<BackgroundWork>,
    outputProvider: suspend (workId: String) -> Any?,
    onCancel: (workId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.testTag("background_work_indicator"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { sheetOpen = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.background_work_count, running),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.background_work_view),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (sheetOpen) {
        BackgroundWorkSheet(
            works = works,
            outputProvider = outputProvider,
            onCancel = onCancel,
            onDismiss = { sheetOpen = false },
        )
    }
}

private class OutputRequest(val workId: String, val result: Any?)

@Composable
private fun BackgroundWorkSheet(
    works: List<BackgroundWork>,
    outputProvider: suspend (workId: String) -> Any?,
    onCancel: (workId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var viewer by remember { mutableStateOf<OutputRequest?>(null) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag("background_work_sheet"),
        ) {
            Text(
                stringResource(R.string.background_work_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            if (works.isEmpty()) {
                Text(
                    stringResource(R.string.background_work_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            works.forEach { work ->
                BackgroundWorkRow(work, outputProvider, onCancel, viewer, onViewer = { viewer = it })
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BackgroundWorkRow(
    work: BackgroundWork,
    outputProvider: suspend (workId: String) -> Any?,
    onCancel: (workId: String) -> Unit,
    viewer: OutputRequest?,
    onViewer: (OutputRequest?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    work.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(work.kind, work.status).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (work.cancellable == true) {
                TextButton(
                    onClick = { onCancel(work.workId) },
                    modifier = Modifier.testTag("background_work_cancel_${work.workId}"),
                ) {
                    Text(stringResource(R.string.background_work_cancel))
                }
            }
        }
        // The read-only output viewer — bash-class works only (the desktop reads
        // background SHELL output; other work kinds have no output plane).
        if (work.kind == "bash" || work.kind == null) {
            TextButton(
                onClick = {
                    scope.launch { onViewer(OutputRequest(work.workId, outputProvider(work.workId))) }
                },
                modifier = Modifier.testTag("background_work_output_${work.workId}"),
            ) {
                Text(stringResource(R.string.background_work_view_output))
            }
            viewer?.takeIf { it.workId == work.workId }?.let { state ->
                OutputViewer(state.result)
            }
        }
    }
}


@Composable
private fun OutputViewer(result: Any?) {
    when (result) {
        is BackgroundBashOutput -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("background_work_viewer"),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        result.output.ifBlank { stringResource(R.string.background_work_output_empty) },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Text(
                    listOfNotNull(
                        result.status,
                        result.outputPath,
                        if (result.truncated) stringResource(R.string.background_work_truncated) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        is BackgroundBashOutputUnavailable ->
            Text(
                stringResource(R.string.background_work_unavailable, result.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("background_work_viewer"),
            )
        else ->
            Text(
                stringResource(R.string.background_work_output_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("background_work_viewer"),
            )
    }
}
