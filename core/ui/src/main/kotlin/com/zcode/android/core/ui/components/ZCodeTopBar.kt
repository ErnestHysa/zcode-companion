package com.zcode.android.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.zcode.android.core.ui.R
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex

/**
 * The ONE app header (B3-chrome). Every screen hosts it as a `Scaffold` `topBar` — it
 * replaces the four hand-rolled `Row { IconButton(ArrowBack) ; Text(title) }` variants,
 * the manual `statusBarsPadding` (the M3 TopAppBar insets own the status bar), and the
 * duplicated stacked titles: one title, rendered here and nowhere else on the route.
 *
 * Scroll elevation: pass [rememberZCodeTopBarScroll] (and attach its
 * `nestedScrollConnection` to the Scaffold) — the pinned bar tints to
 * `surfaceContainer` while content scrolls under it (M3 `scrolledContainerColor`),
 * which is the J5-mapped designed pair, never the baseline grey.
 *
 * Back carries the shared `action_back` description (B3's hardcoded "Back" cDs die here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZCodeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backTestTag: String = "nav_back",
    titleTestTag: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (titleTestTag != null) Modifier.testTag(titleTestTag) else Modifier,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        modifier = modifier.semantics {
            // §30.1 chrome-first: M3 Scaffold subcomposes the BODY before the top bar,
            // so the raw semantics tree (and a naive traversal) reads rows before the
            // header actions. Marking the bar as a traversal group at index 0 makes
            // TalkBack read the header first, matching the visual order.
            isTraversalGroup = true
            traversalIndex = 0f
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag(backTestTag)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/** The scroll-elevation behavior every scrollable screen attaches to its Scaffold. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberZCodeTopBarScroll(): TopAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
