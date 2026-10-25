package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.EmptyState
import com.zcode.android.core.ui.components.LoadingSkeleton
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import com.zcode.android.core.ui.components.rememberZCodeHaptics
import com.zcode.android.domain.model.ModelOption
import com.zcode.android.domain.model.ModelProviderOption
import com.zcode.android.features.conversation.R

/**
 * B1+D4 (gaps-and-polish Phase 3A): the ONE model picker. A bottom sheet shared by the
 * conversation header chip and the new-task composer — two same-looking pickers with
 * opposite behaviors was the root of the named symptom (B1), so there is exactly one body
 * ([ModelPickerContent]) and two thin entry points.
 *
 * Rules, each pinned by `ModelPickerSheetTest`:
 *  - the highlight is LOCAL pending state, never the session echo (G2 live: tapping
 *    `glm-5.1-air` left the check on `glm-5.1` because `selected` derived from config);
 *  - the thought-level step is ATTACHED to the tapped row (it used to be a section
 *    appended below the whole list, often under the fold), with NO level pre-marked
 *    for a model that is not the current one (the old `• high` bullet marked the OLD
 *    session's level and read as a selection for the new model);
 *  - one tap when the model has a single effort level;
 *  - provider-grouped with sticky headers, a check icon (not a radio) on the active
 *    row, a "Current" tag, wire-fed badges (D4 `badgeLabel`/`labelBadge`), a search
 *    field, and 48dp rows (F3: composer rows measured 42dp live).
 *
 * The options are exactly the protocol's (§20.2 — never synthesized); switching still
 * rides the one `switchModelConfig` command (§10.3) through the caller's `onPick`.
 */
@Composable
fun ModelPickerSheet(
    current: Pair<String, String>?,
    currentThought: String?,
    thoughtLevels: List<String>,
    modelOptionsProvider: suspend () -> List<ModelProviderOption>,
    onPick: (provider: String, model: String, thought: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.switch_model_title),
    rowTagPrefix: String = "model_option_",
    titleTag: String = "model_picker_title",
    emptyTag: String = "model_picker_empty",
    keepCurrentOnEmpty: Boolean = false,
) {
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        ModelPickerContent(
            current = current,
            currentThought = currentThought,
            thoughtLevels = thoughtLevels,
            modelOptionsProvider = modelOptionsProvider,
            onPick = onPick,
            onDismiss = onDismiss,
            title = title,
            rowTagPrefix = rowTagPrefix,
            titleTag = titleTag,
            emptyTag = emptyTag,
            keepCurrentOnEmpty = keepCurrentOnEmpty,
        )
    }
}

/**
 * The picker BODY — hosted by [ModelPickerSheet] and embedded inline by the create-task
 * sheet (so create-task never stacks a second overlay: one sheet, two pages).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModelPickerContent(
    current: Pair<String, String>?,
    currentThought: String?,
    thoughtLevels: List<String>,
    modelOptionsProvider: suspend () -> List<ModelProviderOption>,
    onPick: (provider: String, model: String, thought: String?) -> Unit,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    rowTagPrefix: String = "model_option_",
    titleTag: String = "model_picker_title",
    emptyTag: String = "model_picker_empty",
    keepCurrentOnEmpty: Boolean = false,
) {
    val haptics = rememberZCodeHaptics()
    val providers by produceState<List<ModelProviderOption>?>(initialValue = null) {
        value = runCatching { modelOptionsProvider() }.getOrDefault(emptyList())
    }
    // B1: the pending pick is LOCAL state. The session echo only ever supplies the
    // starting point; from the first tap on, the sheet shows what the user chose.
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val levels = thoughtLevels.ifEmpty { listOfNotNull(currentThought) }
    val highlighted = pending ?: current

    fun tap(providerId: String, model: ModelOption) {
        haptics.tick()
        if (levels.size <= 1) {
            // One effort level (or none): a single tap completes the pick.
            onPick(providerId, model.id, levels.firstOrNull())
            onDismiss()
        } else {
            pending = providerId to model.id
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(titleTag),
        )
        when (val list = providers) {
            null -> LoadingSkeleton(rows = 4)
            emptyList<ModelProviderOption>() -> {
                EmptyState(
                    title = stringResource(R.string.composer_model_picker_empty),
                    modifier = Modifier.testTag(emptyTag),
                    message = if (keepCurrentOnEmpty) stringResource(R.string.switch_unavailable) else null,
                    action = if (keepCurrentOnEmpty && current != null) {
                        {
                            TextButton(onClick = {
                                onPick(current.first, current.second, currentThought)
                                onDismiss()
                            }) { Text(stringResource(R.string.switch_keep_current)) }
                        }
                    } else {
                        null
                    },
                )
            }
            else -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.model_picker_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("model_picker_search"),
                )
                val filtered = remember(list, query) { filterProviders(list, query) }
                if (filtered.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.model_picker_no_results, query),
                        modifier = Modifier.testTag("model_picker_no_results"),
                        icon = Icons.Outlined.Search,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .testTag("model_picker_list"),
                    ) {
                        for (provider in filtered) {
                            stickyHeader(key = "provider_${provider.providerId}") {
                                ProviderHeader(provider)
                            }
                            items(provider.models, key = { "${provider.providerId}/${it.id}" }) { model ->
                                val key = provider.providerId to model.id
                                ModelRow(
                                    model = model,
                                    isCurrent = key == current,
                                    highlighted = key == highlighted,
                                    tag = "$rowTagPrefix${model.id}",
                                    onTap = { tap(provider.providerId, model) },
                                )
                                // The thought step lives INSIDE the tapped row's item —
                                // never a section below the list.
                                if (pending == key && levels.size > 1) {
                                    ThoughtStep(
                                        modelId = model.id,
                                        levels = levels,
                                        markedLevel = if (key == current) currentThought else null,
                                        onPick = { level ->
                                            haptics.tick()
                                            onPick(provider.providerId, model.id, level)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Case-insensitive match on model id/label/badge or the provider's name/id. */
internal fun filterProviders(list: List<ModelProviderOption>, query: String): List<ModelProviderOption> {
    val q = query.trim()
    if (q.isEmpty()) return list
    return list.mapNotNull { provider ->
        val providerHit = (provider.providerName ?: provider.providerId).contains(q, ignoreCase = true)
        val models = if (providerHit) {
            provider.models
        } else {
            provider.models.filter { m ->
                m.id.contains(q, ignoreCase = true) ||
                    m.label.contains(q, ignoreCase = true) ||
                    (m.badge?.contains(q, ignoreCase = true) == true)
            }
        }
        if (models.isEmpty()) null else provider.copy(models = models)
    }
}

@Composable
private fun ProviderHeader(provider: ModelProviderOption) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("model_provider_${provider.providerId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = (provider.providerName ?: provider.providerId).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        provider.badge?.let { BadgeLabel(it) }
    }
}

@Composable
private fun ModelRow(
    model: ModelOption,
    isCurrent: Boolean,
    highlighted: Boolean,
    tag: String,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // F3: 48dp minimum — the composer rows measured 42dp on-device.
            .heightIn(min = 48.dp)
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(
                selected = highlighted,
                enabled = model.available,
                role = Role.RadioButton,
                onClick = onTap,
            )
            .alpha(if (model.available) 1f else 0.38f)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = model.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        model.badge?.let { BadgeLabel(it) }
        if (isCurrent) {
            BadgeLabel(
                text = stringResource(R.string.model_picker_current),
                modifier = Modifier.testTag("model_current_tag"),
                tonal = true,
            )
        }
        Spacer(Modifier.weight(1f))
        if (highlighted) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("model_check_${model.id}"),
            )
        }
    }
}

/**
 * The effort step attached under the tapped row. [markedLevel] is non-null ONLY when the
 * tapped row IS the current model — re-picking it to change effort is the one honest
 * case for a pre-marked chip.
 */
@Composable
private fun ThoughtStep(
    modelId: String,
    levels: List<String>,
    markedLevel: String?,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("model_thought_step_$modelId"),
    ) {
        Text(
            text = stringResource(R.string.model_picker_effort_caption),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (level in levels) {
                FilterChip(
                    selected = level == markedLevel,
                    onClick = { onPick(level) },
                    label = { Text(level) },
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .testTag("thought_option_$level"),
                )
            }
        }
    }
}

@Composable
private fun BadgeLabel(text: String, modifier: Modifier = Modifier, tonal: Boolean = false) {
    Surface(
        color = if (tonal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (tonal) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
