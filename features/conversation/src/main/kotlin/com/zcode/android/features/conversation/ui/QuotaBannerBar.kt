package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.domain.model.StartPlanQuotaBuckets
import com.zcode.android.features.conversation.R

/**
 * G11 (vc51) — the in-conversation quota banner (the desktop's
 * ConversationQuotaBanner.tsx): the phone had the ADR-020 Settings quota
 * section but no live mid-session warning. Low bucket = once per period
 * (the reminder key dedup, dismissible); exhausted = always shown. The chip
 * links to the quota screen.
 */
@Composable
fun QuotaBannerBar(
    banner: StartPlanQuotaBuckets.Verdict,
    onOpenQuotaScreen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag("quota_banner"),
        color = if (banner.exhausted) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).clickable { onOpenQuotaScreen() }.testTag("quota_banner_body")) {
                Text(
                    text = if (banner.exhausted) stringResource(R.string.quota_banner_exhausted)
                    else stringResource(R.string.quota_banner_low),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (banner.exhausted) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                banner.ratio?.let { ratio ->
                    Text(
                        text = stringResource(R.string.quota_banner_ratio, (ratio * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (banner.exhausted) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.quota_banner_open),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onOpenQuotaScreen() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("quota_banner_open"),
            )
            if (!banner.exhausted) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("quota_banner_dismiss")) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}
