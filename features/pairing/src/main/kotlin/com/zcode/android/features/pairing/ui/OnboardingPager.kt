package com.zcode.android.features.pairing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.zcode.android.features.pairing.R

/**
 * H1.5 (Phase 6B): the short post-pairing pass — "what your phone can do" over the
 * features that already exist but were undiscoverable (approvals, live work,
 * automations/idle-time, usage). Skippable at every page; finishing (Skip or
 * Get started) is one-time (the pref gates the route, never re-shown).
 */
private data class OnboardingPage(val titleRes: Int, val bodyRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun OnboardingPager(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = listOf(
        OnboardingPage(R.string.onboarding_approvals_title, R.string.onboarding_approvals_body, Icons.Outlined.FactCheck),
        OnboardingPage(R.string.onboarding_live_title, R.string.onboarding_live_body, Icons.Outlined.Visibility),
        OnboardingPage(R.string.onboarding_automations_title, R.string.onboarding_automations_body, Icons.Outlined.Schedule),
        OnboardingPage(R.string.onboarding_usage_title, R.string.onboarding_usage_body, Icons.Outlined.Insights),
    )
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    Column(modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onFinish, modifier = Modifier.testTag("onboarding_skip")) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("onboarding_pager"),
        ) { page ->
            val p = pages[page]
            Column(
                Modifier.fillMaxSize().testTag("onboarding_page_$page"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(p.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(p.titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(p.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = listOf(0, 1, 2, 3).joinToString("  ") { i -> if (i == pager.currentPage) "●" else "○" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (last) {
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth().testTag("onboarding_done"),
            ) {
                Text(stringResource(R.string.onboarding_done))
            }
        } else {
            OutlinedButton(
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().testTag("onboarding_next"),
            ) {
                Text(stringResource(R.string.onboarding_next))
            }
        }
    }
}
