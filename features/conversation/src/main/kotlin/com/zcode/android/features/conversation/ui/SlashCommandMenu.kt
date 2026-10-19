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
import com.zcode.android.core.ui.components.friendlyFaultMessage
import com.zcode.android.core.ui.components.minRowTouchTarget
import com.zcode.android.features.conversation.port.ConversationPort
import androidx.compose.ui.semantics.Role

/**
 * GAP-12 + Phase 4C (C2): the composer "/" catalog menu at the DESKTOP's shape
 * (slashCommandPanelSections.tsx) — THREE sections: Commands (the wire catalog's
 * builtins AND the user's custom/plugin commands from `commands.list`), Skills and
 * Subagents. Picking a command inserts `/name` (+ the arg-hint form); a skill inserts
 * the `$skill` mention and an agent the `@agent` mention (the desktop's vocabulary —
 * the "/" panel is the execution entry). A failing section shows its own error; the
 * others still render. [extras] == null keeps the legacy single-section menu.
 */
@Composable
fun SlashCommandMenu(
    commands: List<ConversationPort.SlashCommandView>,
    query: String,
    onPick: (inserted: String) -> Unit,
    modifier: Modifier = Modifier,
    extras: ConversationPort.SlashExtras? = null,
) {
    // query = the raw token after "/" ("" = bare slash → whole catalog).
    val token = query.removePrefix("/").lowercase()
    val matches = { name: String -> token.isEmpty() || name.lowercase().startsWith(token) }
    // Commands section: the workspace catalog's builtin + custom entries; the user's
    // `commands.list` entries (source user|plugin) append — a CLI catalog entry WINS a
    // same-named custom command (SlashCommandPlugin.tsx: "CLI 已提供同名命令时以 CLI 为准").
    val catalog = commands.filter { matches(it.name) }
    val userCommands = (extras?.userCommands ?: emptyList())
        .filter { cmd -> matches(cmd.name) && catalog.none { it.name.equals(cmd.name, ignoreCase = true) } }
    val skills = (extras?.skills ?: emptyList()).filter { matches(it.name) }
    val agents = (extras?.agents ?: emptyList()).filter { matches(it.name) }
    // A section FILTERED OUT (token non-empty, no rows, not loading/errored) vanishes;
    // a genuine empty state (bare "/") keeps its header + note.
    val filtering = token.isNotEmpty()
    val showSkills = extras != null && (skills.isNotEmpty() || extras.loading || extras.skillsError != null || !filtering)
    val showAgents = extras != null && (agents.isNotEmpty() || extras.agentsError != null || !filtering)
    if (catalog.isEmpty() && userCommands.isEmpty() &&
        (extras == null || (skills.isEmpty() && agents.isEmpty()))
    ) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("slash_menu"),
    ) {
        Column {
            if (extras != null && (catalog.isNotEmpty() || userCommands.isNotEmpty())) {
                SectionHeader(stringResourceSlashCommands(), tag = "commands")
            }
            (catalog + userCommands).forEach { command ->
                MenuRow(
                    primary = "/${command.name}",
                    primaryTag = "slash_command_${command.name}",
                    detail = listOfNotNull(command.description, command.inputHint).joinToString(" "),
                    onPick = {
                        onPick(if (command.inputHint != null) "/${command.name} " else "/${command.name}")
                    },
                )
            }
            if (showSkills) {
                SectionHeader(stringResourceSlashSkills(), tag = "skills")
                when {
                    extras.loading -> MenuNote(
                        stringResourceSlashLoading(),
                        tag = "slash_skills_loading",
                    )
                    extras.skillsError != null -> MenuNote(
                        friendlyFaultMessage(extras.skillsError),
                        tag = "slash_skills_error",
                    )
                    skills.isEmpty() -> MenuNote(
                        stringResourceSlashEmptySkills(),
                        tag = "slash_skills_empty",
                    )
                    else -> skills.forEach { skill ->
                        MenuRow(
                            primary = "\$${skill.name}",
                            primaryTag = "slash_skill_${skill.name}",
                            detail = listOfNotNull(skill.scope.takeIf { it.isNotBlank() }, skill.description).joinToString(" · "),
                            onPick = { onPick("\$${skill.name} ") },
                        )
                    }
                }
                if (showAgents) {
                    SectionHeader(stringResourceSlashAgents(), tag = "agents")
                    when {
                        extras.agentsError != null -> MenuNote(
                            friendlyFaultMessage(extras.agentsError),
                            tag = "slash_agents_error",
                        )
                        agents.isEmpty() -> MenuNote(
                            stringResourceSlashEmptyAgents(),
                            tag = "slash_agents_empty",
                        )
                        else -> agents.forEach { agent ->
                            MenuRow(
                                primary = "@${agent.name}",
                                primaryTag = "slash_agent_${agent.name}",
                                detail = agent.description,
                                onPick = { onPick("@${agent.name} ") },
                            )
                        }
                    }
                }
            }
        }
    }
}

// Direct stringResource calls (the feature's own R); wrapped for readability below.
@Composable private fun stringResourceSlashCommands() = androidx.compose.ui.res.stringResource(com.zcode.android.features.conversation.R.string.slash_section_commands)
@Composable private fun stringResourceSlashSkills() = androidx.compose.ui.res.stringResource(com.zcode.android.features.conversation.R.string.slash_section_skills)
@Composable private fun stringResourceSlashAgents() = androidx.compose.ui.res.stringResource(com.zcode.android.features.conversation.R.string.slash_section_agents)
@Composable private fun stringResourceSlashLoading() = androidx.compose.ui.res.stringResource(com.zcode.android.features.conversation.R.string.slash_section_loading)
@Composable private fun stringResourceSlashEmptySkills() = androidx.compose.ui.res.stringResource(com.zcode.android.features.conversation.R.string.slash_section_empty_skills)
@Composable private fun stringResourceSlashEmptyAgents() = androidx.compose.ui.res.stringResource(com.zcode.android.features.conversation.R.string.slash_section_empty_agents)

@Composable
private fun SectionHeader(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("slash_section_$tag"),
    )
}

@Composable
private fun MenuRow(
    primary: String,
    primaryTag: String,
    detail: String,
    onPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .minRowTouchTarget()
            .clickable(role = Role.Button, onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("slash_menu_item"),
    ) {
        Text(
            text = primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(112.dp).testTag(primaryTag),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MenuNote(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(tag),
    )
}
