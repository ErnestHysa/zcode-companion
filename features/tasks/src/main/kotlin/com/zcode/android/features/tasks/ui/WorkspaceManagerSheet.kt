package com.zcode.android.features.tasks.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.domain.model.WorkspaceOption
import com.zcode.android.features.tasks.R

/**
 * G41 (vc51) — the workspace MANAGER over the workspaces the phone already
 * RECEIVES (the companion shape: switch / reorder / rename-CLASS; NOT a folder
 * picker — the desktop's Projects add/open/remote wizard stays desktop-side):
 *  - SWITCH: tapping a row selects it (the switcher filter re-groups live);
 *  - REORDER: up/down arrows persist the display order (unlisted keys keep
 *    wire order);
 *  - RENAME-CLASS: a local display alias per workspace (persisted; cleared by
 *    emptying the field — the wire label stays the fallback).
 */
@Composable
fun WorkspaceManagerSheet(
    workspaces: List<WorkspaceOption>,
    aliases: Map<String, String>,
    selectedKey: String?,
    onSelect: (key: String?) -> Unit,
    onReorder: (keysInOrder: List<String>) -> Unit,
    onRename: (key: String, alias: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var order by remember(workspaces) { mutableStateOf(workspaces.map { it.key }) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_manager_title)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("workspace_manager"),
            ) {
                Text(
                    stringResource(R.string.workspace_manager_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                order.forEachIndexed { index, key ->
                    val ws = workspaces.firstOrNull { it.key == key } ?: return@forEachIndexed
                    val alias = aliases[key]?.takeIf { it.isNotBlank() }
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(key); onDismiss() }
                                .padding(vertical = 8.dp)
                                .testTag("workspace_manager_row_${key.hashCode()}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    alias ?: ws.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selectedKey == key) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    ws.path ?: key,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { renameFor = key; renameText = alias ?: "" },
                                modifier = Modifier.testTag("workspace_manager_rename_${key.hashCode()}"),
                            ) {
                                Text(stringResource(R.string.workspace_manager_rename))
                            }
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.workspace_manager_up),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable(enabled = index > 0) {
                                        if (index > 0) {
                                            val next = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                            order = next
                                            onReorder(next)
                                        }
                                    }
                                    .padding(4.dp)
                                    .testTag("workspace_manager_up_${key.hashCode()}"),
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.workspace_manager_down),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable(enabled = index < order.lastIndex) {
                                        if (index < order.lastIndex) {
                                            val next = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                            order = next
                                            onReorder(next)
                                        }
                                    }
                                    .padding(4.dp)
                                    .testTag("workspace_manager_down_${key.hashCode()}"),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
    if (renameFor != null) {
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.workspace_manager_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workspace_manager_rename_field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(renameFor!!, renameText.trim().takeIf { it.isNotEmpty() })
                        renameFor = null
                    },
                    modifier = Modifier.testTag("workspace_manager_rename_save"),
                ) {
                    Text(stringResource(R.string.workspace_manager_rename_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
