package com.zcode.android.app.notifications

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R

/**
 * F-34-E (vc52 Phase 8): the post-pairing notification step — shown at the END of a
 * pairing when Android reports notifications off (on API 33+ that is EVERY fresh install,
 * POST_NOTIFICATIONS denied by default). One-line rationale, the runtime ask, and the
 * Live-connection offer in the same step (without it, Android 15+ freezes the app in the
 * background and nothing arrives at all — the pass-34 measurement).
 */
@Composable
fun NotificationSetupScreen(
    onContinue: () -> Unit,
    onStartLiveConnection: () -> Unit,
    liveConnectionRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var recheck by remember { mutableIntStateOf(0) }
    val enabled = rememberNotificationsEnabled(recheck)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        recheck++ // re-read Android's verdict (granted → enabled; denied → the off copy)
    }
    BackHandler(onBack = onContinue)
    Column(
        modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag("notification_setup"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.notif_setup_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.notif_setup_rationale),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("notification_setup_rationale"),
        )
        Spacer(Modifier.height(20.dp))
        if (enabled) {
            Text(
                stringResource(R.string.notif_setup_enabled),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("notification_setup_enabled"),
            )
        } else {
            Button(
                onClick = {
                    if (NotificationsGate.needsRuntimeGrant(context)) {
                        ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Below API 33 (or a system-level block): the app's notification page.
                        runCatching { context.startActivity(NotificationsGate.settingsIntent(context)) }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("notification_setup_allow"),
            ) {
                Text(stringResource(R.string.notif_setup_allow))
            }
            if (recheck > 0) {
                Spacer(Modifier.height(10.dp))
                NotificationsOffRow()
            }
        }
        Spacer(Modifier.height(20.dp))
        // The Live connection, offered in the SAME step, with its battery note. It only
        // means something once notifications can be delivered.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.notif_setup_live_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.notif_setup_live_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = liveConnectionRunning,
                enabled = enabled && !liveConnectionRunning,
                onCheckedChange = { want -> if (want) onStartLiveConnection() },
                modifier = Modifier.testTag("notification_setup_live"),
            )
        }
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("notification_setup_continue")) {
            Text(stringResource(if (enabled) R.string.notif_setup_continue else R.string.notif_setup_not_now))
        }
    }
}
