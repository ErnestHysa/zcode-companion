package com.zcode.android.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.android.core.common.diff.DiffFile
import com.zcode.android.core.common.diff.DiffLine
import com.zcode.android.core.common.diff.DiffLineType
import androidx.compose.ui.res.stringResource
import com.zcode.android.core.ui.R

/**
 * §22 Diff renderer (ADR-010: custom Compose, no heavyweight dependency) — a
 * bounded, virtualized, per-file-card renderer over [DiffFile]s from the two
 * verified producer planes (v4 fileChanges pre-parsed hunks; git getDiff patch
 * text). Normative §22 rules implemented here:
 *  - per-file card COLLAPSED by default with a +N/−M badge;
 *  - monospace, green/red line backgrounds, line numbers in a fixed gutter,
 *    horizontal scroll (no soft-wrap for code);
 *  - hunk virtualization (LazyColumn of hunks);
 *  - > 5 000 changed lines per file → summary card (never materialize them all);
 *  - binary → placeholder card; colorblind-safe: +/− markers accompany color (§30).
 */
object DiffRendererDefaults {
    /** §22: > 5k changed lines per file → summary card (perf guard). */
    const val LARGE_FILE_LINES = 5_000
}

@Composable
fun DiffViewerContent(
    files: List<DiffFile>,
    modifier: Modifier = Modifier,
    emptyText: String = "No file changes recorded for this turn.",
) {
    if (files.isEmpty()) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            DiffFileCard(file)
        }
    }
}

@Composable
fun DiffFileCard(
    file: DiffFile,
    largeFileText: String = "large diff — %d changed lines — open the per-file view on the desktop",
    binaryText: String = "binary file changed",
) {
    var expanded by rememberSaveable(file.path, file.oldPath) { mutableStateOf(false) }
    val isLarge = file.changedLines > DiffRendererDefaults.LARGE_FILE_LINES
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateCollapse()) {
            // Header — the toggle target (≥48dp combined affordance, §30); the
            // a11y description carries the counts so color is never the only signal.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !file.binary && !isLarge) { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .semantics { contentDescription = diffA11yLabel(file) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = diffHeaderLabel(file),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (file.isRenamed) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.diff_renamed), style = MaterialTheme.typography.labelSmall)
                }
                if (file.isNew) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.diff_new), style = MaterialTheme.typography.labelSmall)
                }
                if (file.isDeleted) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.diff_deleted), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                if (!file.binary && !isLarge) {
                    DiffCountBadge(added = file.addedLines, removed = file.removedLines)
                }
            }
            when {
                file.binary -> Text(
                    binaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
                isLarge -> Text(
                    largeFileText.format(file.changedLines),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
                expanded -> DiffHunksList(file)
                else -> Text(
                    stringResource(R.string.diff_tap_to_expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

internal fun diffA11yLabel(file: DiffFile): String = buildString {
    append("diff for ").append(file.path)
    append(", plus ").append(file.addedLines).append(", minus ").append(file.removedLines)
    when {
        file.binary -> append(", binary file")
        file.isNew -> append(", new file")
        file.isDeleted -> append(", deleted file")
        file.isRenamed && file.oldPath != null -> append(", renamed from ").append(file.oldPath)
    }
}

internal fun diffHeaderLabel(file: DiffFile): String = when {
    file.isRenamed && file.oldPath != null -> "${file.oldPath} → ${file.path}"
    file.path.isNotBlank() -> file.path
    else -> "(unnamed file)"
}

@Composable
fun DiffCountBadge(added: Long, removed: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "+$added",
            color = DiffColors.addForeground(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "−$removed",
            color = DiffColors.removeForeground(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Virtualized hunk list (LazyColumn of hunks — §22 large-diff rule). */
@Composable
fun DiffHunksList(file: DiffFile, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        file.hunks.forEach { hunk ->
            // §22 intra-line word highlighting: pair each ADD with the nearest
            // preceding DEL and vice versa (render concern only — the wire stays raw).
            val counterpart = intraLineCounterparts(hunk.lines)
            item(key = "hunk-${hunk.oldStart}-${hunk.newStart}") {
                Text(
                    hunk.header,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            items(
                hunk.lines,
                key = { line ->
                    "l-${hunk.oldStart}-${hunk.newStart}-${line.oldNo ?: -1}-${line.newNo ?: -1}-${line.type}-${line.content.hashCode()}"
                },
                contentType = { line -> line.type },
            ) { line ->
                DiffLineRow(line, counterpart[line])
            }
        }
    }
}

/** line → its changed-counterpart content (ADD↔DEL neighbors), for intra-line emphasis. */
private fun intraLineCounterparts(lines: List<DiffLine>): Map<DiffLine, String> {
    val map = HashMap<DiffLine, String>()
    var lastDel: DiffLine? = null
    for (line in lines) {
        when (line.type) {
            DiffLineType.DEL -> lastDel = line
            DiffLineType.ADD -> {
                lastDel?.let {
                    map[line] = it.content
                    map[it] = line.content
                }
                lastDel = null
            }
            DiffLineType.CONTEXT -> lastDel = null
        }
    }
    return map
}

@Composable
fun DiffLineRow(line: DiffLine, counterpart: String? = null, modifier: Modifier = Modifier) {
    val bg = when (line.type) {
        DiffLineType.ADD -> DiffColors.addBackground()
        DiffLineType.DEL -> DiffColors.removeBackground()
        DiffLineType.CONTEXT -> Color.Transparent
    }
    val marker = when (line.type) {
        DiffLineType.ADD -> "+"
        DiffLineType.DEL -> "−"
        DiffLineType.CONTEXT -> " "
    }
    Row(modifier = modifier.fillMaxWidth().background(bg)) {
        // Fixed gutter: old/new line numbers (§22).
        Text(
            text = line.oldNo?.toString() ?: "",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp).padding(start = 4.dp),
            maxLines = 1,
        )
        Text(
            text = line.newNo?.toString() ?: "",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
            maxLines = 1,
        )
        // Code: monospace, marker + content with intra-line emphasis, horizontal
        // scroll (no soft-wrap for code, §22).
        Text(
            text = annotateIntraLine(marker + line.content, marker.length, line.content, counterpart),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 1.dp),
        )
    }
}

/**
 * §22 intra-line word highlighting: bold the changed MIDDLE of a changed line
 * (common prefix/suffix with its counterpart), leaving the unchanged edges plain.
 * Pure string math — safe for very long lines (O(n)).
 */
internal fun annotateIntraLine(
    full: String,
    markerOffset: Int,
    content: String,
    counterpart: String?,
): AnnotatedString {
    if (counterpart == null || content == counterpart) {
        return AnnotatedString(full)
    }
    val prefix = content.commonPrefixWith(counterpart).length
    val suffix = content.commonSuffixWith(counterpart).length.coerceAtMost(content.length - prefix)
    val midStart = markerOffset + prefix
    val midEnd = markerOffset + (content.length - suffix)
    return buildAnnotatedString {
        append(full)
        if (midEnd > midStart && midEnd - midStart < content.length) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), midStart, midEnd)
        }
    }
}

/**
 * Semantic diff palette. Translucent fixed hues read on both M3 themes; the +/−
 * markers and the a11y label carry the signal for colorblind users (§30).
 */
object DiffColors {
    @Composable fun addBackground(): Color = Color(0xFF2E7D32).copy(alpha = 0.28f)
    @Composable fun removeBackground(): Color = Color(0xFFC62828).copy(alpha = 0.28f)
    @Composable fun addForeground(): Color = Color(0xFF66A26E)
    @Composable fun removeForeground(): Color = Color(0xFFCF7B75)
}
