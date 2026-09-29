package com.zcode.android.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.zcode.android.app.AppContainer

/**
 * Basic Phase 6 settings (ui-ux 13): paired desktop identity, connection entry,
 * diagnostics export, privacy note, about + licenses, unpair (danger, confirmed).
 * Full settings surface (notification levels, privacy toggles) is Phase 8/11.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    endpointName: String?,
    desktopVersion: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showUnpairConfirm by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }

            SectionLabel("Paired desktop")
            SettingsRow(
                title = endpointName ?: "Not paired",
                subtitle = desktopVersion?.let { "ZCode desktop $it" } ?: "Scan a QR code to pair",
                tag = "settings_endpoint",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel("Diagnostics")
            SettingsRow(
                title = "Export diagnostics",
                subtitle = "Recent connection events, redacted. No conversation content.",
                tag = "settings_export_diagnostics",
                onClick = { exportDiagnostics(container, context) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel("Privacy")
            SettingsRow(
                title = "No analytics. No tracking.",
                subtitle = "Data leaves this device only through your paired ZCode connection.",
                tag = "settings_privacy",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel("About")
            SettingsRow(
                title = "ZCode Companion",
                subtitle = "Unofficial native client for ZCode. Apache-2.0 components — see licenses.",
                tag = "settings_about",
            )
            SettingsRow(
                title = "Licenses",
                subtitle = "Open-source libraries used in this app",
                tag = "settings_licenses",
                onClick = { showLicenses(context) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Spacer(Modifier.height(16.dp))
            if (endpointName != null) {
                Button(
                    onClick = { showUnpairConfirm = true },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .testTag("settings_unpair"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Unpair this device", color = MaterialTheme.colorScheme.onError)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "After unpairing, also press Stop in the desktop's Mobile Remote Control dialog — closing the dialog does not stop remote control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair from ${endpointName ?: "this desktop"}?") },
            text = { Text("The pairing credential and cached data will be removed from this phone. You will need to scan the QR again to reconnect.") },
            confirmButton = {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                TextButton(
                    onClick = {
                        showUnpairConfirm = false
                        scope.launch { container.pairingPort.unpair() }
                    },
                    modifier = Modifier.testTag("settings_unpair_confirm"),
                ) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private fun exportDiagnostics(container: AppContainer, context: android.content.Context) {
    val text = container.log.exportDiagnostics()
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        putExtra(android.content.Intent.EXTRA_TITLE, "ZCode Companion diagnostics")
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Export diagnostics"))
}

private fun showLicenses(context: android.content.Context) {
    val text = buildString {
        appendLine("ZCode Companion — open-source components:")
        appendLine("• Kotlin/Jetbrains (Apache-2.0), Jetpack Compose & AndroidX (Apache-2.0)")
        appendLine("• OkHttp (Apache-2.0), Tink (Apache-2.0), ML Kit barcode (Apache-2.0), CameraX (Apache-2.0)")
        appendLine("• Protobuf (BSD), DataStore/Room (Apache-2.0), Material Icons (Apache-2.0)")
        appendLine("ZCode is a trademark of its owner; this is an unofficial companion client.")
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Licenses"))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    tag: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
