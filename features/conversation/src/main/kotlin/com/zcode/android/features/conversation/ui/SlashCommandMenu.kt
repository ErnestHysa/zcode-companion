package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.core.ui.components.minRowTouchTarget
import androidx.compose.ui.semantics.Role

/**
 * GAP-12 — the composer "/" catalog menu. The commands are the WIRE catalog
 * (workspace-config topic, §11.2): builtin + custom entries with name/description/
 * inputHint. Filter = prefix match on the token being typed after "/". Picking a
 * command with an args hint inserts `/name ` (the user types the args, desktop
 * mention-panel parity); without a hint it inserts `/name` ready to send.
 * An empty catalog (or no match) renders NOTHING — the desktop has no
 * builtin-only fallback (useSlashCommands.ts + EMPTY_SLASH_COMMANDS).
 */
@Composable
fun SlashCommandMenu(
    commands: List<ConversationPort.SlashCommandView>,
    query: String,
    onPick: (inserted: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // query = the raw token after "/" ("" = bare slash → whole catalog).
    val token = query.removePrefix("/").lowercase()
    val filtered = commands.filter { token.isEmpty() || it.name.lowercase().startsWith(token) }
    if (filtered.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("slash_menu"),
    ) {
        Column {
            filtered.forEach { command ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minRowTouchTarget()
                        .clickable(role = Role.Button) {
                            onPick(
                                if (command.inputHint != null) "/${command.name} " else "/${command.name}",
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("slash_menu_item"),
                ) {
                    Text(
                        text = "/${command.name}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(112.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(command.description, command.inputHint).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
