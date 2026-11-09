package com.zcode.android.app.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.zcode.android.app.R

/**
 * F-34-E (vc52 Phase 8, P2) + F-33-G — notification access is a FIRST-CLASS state. On
 * Android 13+ a fresh install has POST_NOTIFICATIONS `granted=false` (importance NONE) and
 * the app never asked: the broker re-posted approvals into a blocked sink, zero delivered,
 * nothing said — while Settings showed "Approval requests ON". For a remote-approval
 * companion, a silently lost approval alert is the worst failure mode.
 */
object NotificationsGate {

    /** Android's own verdict: the runtime grant (API 33+) AND the app-level system switch. */
    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** API 33+ without the grant: the runtime prompt is still the way in. */
    fun needsRuntimeGrant(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** The app's own system notification page (the way back after a denial / a system toggle). */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * The live enabled-state, re-read on every ON_RESUME (the user toggles it in the system
 * page and comes back — there is no broadcast for it). The host Activity is the lifecycle
 * owner (the composition's context is the Activity in production and in tests).
 */
@Composable
fun rememberNotificationsEnabled(recheckKey: Any? = null): Boolean {
    val context = LocalContext.current
    var enabled by remember(recheckKey) { mutableStateOf(NotificationsGate.enabled(context)) }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = NotificationsGate.enabled(context)
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return enabled
}

/**
 * The persistent "Notifications are off — Turn on" row (Tasks + Settings). Tapping opens
 * the app's system notification page — after a denial the runtime prompt cannot reappear,
 * and the system page is the one place both a denial and a system-level block are undone.
 */
@Composable
fun NotificationsOffRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag("notifications_off_row"),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.notifications_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(R.string.notifications_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(
                onClick = { runCatching { context.startActivity(NotificationsGate.settingsIntent(context)) } },
                modifier = Modifier.testTag("notifications_off_turn_on"),
            ) {
                Text(stringResource(R.string.notifications_off_action))
            }
        }
    }
}
