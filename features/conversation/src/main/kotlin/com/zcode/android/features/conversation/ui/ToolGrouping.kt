package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.animateCollapse
import com.zcode.android.core.ui.theme.LocalZCodeColors
import com.zcode.android.core.ui.theme.ZCodeRadius
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.R
import kotlinx.serialization.json.JsonPrimitive

/**
 * I2.7+N2.4 (gaps-and-polish Phase 3C): tool-row grouping. The desktop groups tool
 * calls (`changesGroup` / `executeGroup` / explore) and carries metadata ("Explore ·
 * 2 searches"); the phone rendered every tool as a flat full-output row. The grouping
 * is a PURE map over the timeline — one [TimelineEntry] per LazyColumn item — so the
 * auto-scroll etiquette (H4) keeps its index space.
 *
 * Rules: only ≥2 CONSECUTIVE ToolCall rows of one [ToolCategory] whose status is
 * terminal group; RUNNING / PENDING_APPROVAL rows never hide inside a group (an approval
 * must always be visible, a live tool keeps its own card).
 */
sealed interface TimelineEntry {
    val key: Any

    data class Single(val row: ConversationRow) : TimelineEntry {
        override val key: Any get() = row.rowId
    }

    data class ToolGroup(val category: ToolCategory, val rows: List<ConversationRow.ToolCall>) : TimelineEntry {
        override val key: Any get() = "g-${rows.first().rowId}"
    }
}

/** Desktop grouping vocabulary (toolGroupingChanges/Explore/Terminal + a catch-all). */
enum class ToolCategory {
    CHANGES, EXECUTE, EXPLORE, OTHER;

    companion object {
        private val NON_FILE = listOf("todo", "plan", "task", "memory", "skill", "workflow", "mcp", "ask", "question")
        private val CHANGE_WORDS = listOf("write", "edit", "create", "delete", "move", "rename", "apply", "patch", "replace", "insert", "notebook", "save")
        private val EXECUTE_WORDS = listOf("bash", "shell", "exec", "run", "terminal", "command", "zsh", "powershell", "script")
        private val EXPLORE_WORDS = listOf("read", "search", "grep", "glob", "fetch", "find", "list", "ls", "web", "query", "lookup", "symbol", "ref", "cat", "view")

        fun of(toolName: String): ToolCategory {
            val n = toolName.lowercase()
            if (NON_FILE.any { it in n }) return OTHER
            if (CHANGE_WORDS.any { it in n }) return CHANGES
            if (EXECUTE_WORDS.any { it in n }) return EXECUTE
            if (EXPLORE_WORDS.any { it in n }) return EXPLORE
            return OTHER
        }
    }
}

private fun ConversationRow.ToolCall.isSettled(): Boolean = when (status) {
    ConversationRow.ToolCallStatus.SUCCESS,
    ConversationRow.ToolCallStatus.ERROR,
    ConversationRow.ToolCallStatus.CANCELLED,
    ConversationRow.ToolCallStatus.UNKNOWN,
    -> true
    else -> false
}

fun groupTimeline(rows: List<ConversationRow>): List<TimelineEntry> {
    val out = ArrayList<TimelineEntry>(rows.size)
    var i = 0
    while (i < rows.size) {
        val row = rows[i]
        if (row is ConversationRow.ToolCall && row.isSettled()) {
            val category = ToolCategory.of(row.toolName)
            var j = i + 1
            while (j < rows.size) {
                val next = rows[j]
                if (next is ConversationRow.ToolCall && next.isSettled() && ToolCategory.of(next.toolName) == category) j++ else break
            }
            if (j - i >= 2) {
                @Suppress("UNCHECKED_CAST")
                out.add(TimelineEntry.ToolGroup(category, rows.subList(i, j).toList() as List<ConversationRow.ToolCall>))
                i = j
                continue
            }
        }
        out.add(TimelineEntry.Single(row))
        i++
    }
    return out
}

/** Header facts for a group: counts (files for CHANGES, calls otherwise) + summed duration. */
data class ToolGroupUi(val count: Int, val fileCount: Int?, val durationSeconds: Double?) {
    companion object {
        fun of(rows: List<ConversationRow.ToolCall>): ToolGroupUi {
            val files = rows.mapNotNull { r ->
                val input = r.input ?: return@mapNotNull null
                listOf("file_path", "filePath", "path", "notebook_path").firstNotNullOfOrNull { k -> (input[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
            }.toSet()
            val timed = rows.filter { it.startedAt != null && it.endedAt != null && it.endedAt!! >= it.startedAt!! }
            val duration = if (timed.isEmpty()) null else timed.sumOf { it.endedAt!! - it.startedAt!! } / 1000.0
            val isChanges = rows.firstOrNull()?.let { ToolCategory.of(it.toolName) } == ToolCategory.CHANGES
            return ToolGroupUi(count = rows.size, fileCount = if (isChanges && files.isNotEmpty()) files.size else null, durationSeconds = duration)
        }
    }
}

/** One decimal, like the desktop ("Thought · 38 seconds" family — short form here). */
internal fun formatSeconds(seconds: Double): String = String.format(java.util.Locale.ROOT, "%.1f", seconds)

@Composable
private fun groupTitle(category: ToolCategory): String = stringResource(
    when (category) {
        ToolCategory.CHANGES -> R.string.tool_group_changes
        ToolCategory.EXECUTE -> R.string.tool_group_execute
        ToolCategory.EXPLORE -> R.string.tool_group_explore
        ToolCategory.OTHER -> R.string.tool_group_other
    },
)

@Composable
private fun groupCount(category: ToolCategory, ui: ToolGroupUi): String = when {
    category == ToolCategory.CHANGES && ui.fileCount != null -> pluralStringResource(R.plurals.tool_group_files, ui.fileCount, ui.fileCount)
    category == ToolCategory.EXECUTE -> pluralStringResource(R.plurals.tool_group_commands, ui.count, ui.count)
    category == ToolCategory.EXPLORE -> pluralStringResource(R.plurals.tool_group_searches, ui.count, ui.count)
    else -> pluralStringResource(R.plurals.tool_group_calls, ui.count, ui.count)
}

/**
 * The grouped block: a tappable header ("Explore · 2 searches · 3.0s") that expands to
 * the member cards. Collapsed by default — a settled group is history.
 */
@Composable
fun ToolGroupBlock(
    group: TimelineEntry.ToolGroup,
    modifier: Modifier = Modifier,
    fullOutputProvider: (suspend (ConversationRow.ToolCall) -> String?)? = null,
) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    val ui = ToolGroupUi.of(group.rows)
    val title = groupTitle(group.category)
    val count = groupCount(group.category, ui)
    val duration = ui.durationSeconds?.let { stringResource(R.string.tool_duration_seconds, formatSeconds(it)) }
    val summary = listOfNotNull(title, count, duration).joinToString(" · ")
    val expandWord = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand)
    Column(modifier = modifier.fillMaxWidth().animateCollapse()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(LocalZCodeColors.current.toolSurface, RoundedCornerShape(ZCodeRadius.lg))
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("tool_group_${group.key}")
                .semantics(mergeDescendants = true) { contentDescription = "$summary, $expandWord" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in group.rows) {
                    ToolCard(row = row, fullOutputProvider = fullOutputProvider)
                }
            }
        }
    }
}
