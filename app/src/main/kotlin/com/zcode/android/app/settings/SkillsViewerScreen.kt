package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.domain.model.SkillCatalog
import kotlinx.coroutines.launch
import com.zcode.android.core.ui.components.ZCodeButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import com.zcode.android.core.ui.components.EmptyState
import com.zcode.android.core.ui.components.ErrorState
import com.zcode.android.core.ui.components.LoadingSkeleton
import com.zcode.android.core.ui.components.friendlyFaultMessage

/**
 * GAP-8 (android-native-gaps.md; docs/protocol/v4-wire.md §12.4) — the
 * read-only SKILLS CATALOG viewer (Settings → Insights): the
 * `skills/referenceCatalog` projection (name/description/source) with a search
 * box LOCAL to the list. `enabled` is literal true on this plane — the viewer
 * renders NO enable/disable affordance (the field is decoded nowhere).
 */
interface SkillsPort {
    data class Result(val catalog: SkillCatalog?, val error: String?)

    suspend fun catalog(): Result
}

@Composable
fun SkillsViewerScreen(
    port: SkillsPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<SkillsPort.Result?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.catalog(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.skills_title),
                onBack = onBack,
                backTestTag = "skills_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when {
                loading && result == null ->
                    LoadingSkeleton(Modifier.fillMaxSize().testTag("skills_loading"), rows = 4)
                result?.error != null ->
                    ErrorState(
                        title = stringResource(R.string.skills_unavailable),
                        message = friendlyFaultMessage(result?.error),
                        modifier = Modifier.testTag("skills_error"),
                        onRetry = { loading = true; scope.launch { result = port.catalog(); loading = false } },
                    )
                else -> {
                    val catalog = result?.catalog ?: return@Column
                    val filtered = remember(catalog, query) {
                        if (query.isBlank()) catalog.skills
                        else catalog.skills.filter {
                            it.name.contains(query, ignoreCase = true) ||
                                it.description.contains(query, ignoreCase = true) ||
                                (it.pluginName?.contains(query, ignoreCase = true) == true)
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("skills_search"),
                        placeholder = { Text(stringResource(R.string.skills_search_hint)) },
                    )
                    if (catalog.skills.isEmpty()) {
                        EmptyState(title = stringResource(R.string.skills_empty), modifier = Modifier.testTag("skills_empty"))
                        return@Column
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("skills_list")) {
                        items(filtered, key = { it.id }) { skill ->
                            SkillLine(skill)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.skills_search_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp).testTag("skills_search_empty"),
                                )
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillLine(skill: SkillCatalog.SkillEntry) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("skill_row_${skill.id}"),
    ) {
        Text(
            skill.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            skill.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // source = the payload's scope (+ plugin name when plugin-sourced)
            buildString {
                append(skill.scope)
                skill.pluginName?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("skill_source_${skill.id}"),
        )
    }
}
