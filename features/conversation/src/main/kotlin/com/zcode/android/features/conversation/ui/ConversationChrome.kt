package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.features.conversation.R

/**
 * B2.9 (gaps-and-polish Phase 3C): the conversation's unread marker was a bare 16dp dot
 * with no count or semantics — the desktop badge carries a NUMBER. Zero → no badge.
 */
@Composable
fun UnreadBadge(unread: Int, modifier: Modifier = Modifier) {
    if (unread <= 0) return
    BadgedBox(
        badge = {
            Badge(modifier = modifier.testTag("conversation_unread_badge")) {
                Text(unread.toString())
            }
        },
    ) {
        // A small anchor so the badge offsets from real content in any host.
        Box(Modifier.padding(2.dp))
    }
}

/**
 * B2.12 (gaps-and-polish Phase 3C): the pinned-strip stack above the timeline was
 * unbounded in a non-scrollable column — stale/attention/queue/history/load-older
 * together can squeeze the timeline to zero. Status strips collapse to [budget];
 * "+N more" expands. BLOCKING interaction cards (approvals / questions) are composed
 * OUTSIDE this budget — approval first (§18.2) — and the timeline keeps its weight.
 */
@Composable
fun StripBudget(
    strips: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    budget: Int = 2,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        val shown = if (expanded || strips.size <= budget) strips else strips.take(budget)
        for (strip in shown) strip()
        if (!expanded && strips.size > budget) {
            val hidden = strips.size - budget
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("strip_budget_more")) {
                    Text(
                        stringResource(R.string.strip_budget_more, hidden),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
