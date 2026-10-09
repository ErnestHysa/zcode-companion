package com.zcode.android.features.terminal.ui

import androidx.compose.ui.res.stringResource
import com.zcode.android.features.terminal.R

import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.zcode.android.core.common.ansi.AnsiParser
import com.zcode.android.features.terminal.port.TerminalPort

/**
 * §21 view-only terminal surface. Streaming output with ANSI SGR colors, bounded
 * ring (512 KiB — the dropped-bytes counter renders honestly when nonzero),
 * autoscroll + pause, search. NO keyboard input is offered anywhere (INV-9).
 * Every §14.4-adjacent state renders (INV-11): idle/attaching/live/exited/
 * refused/capability-off/opt-in-required.
 */
@Composable
fun TerminalScreen(
    port: TerminalPort,
    modifier: Modifier = Modifier,
) {
    val state by port.state.collectAsState()
    val snapshot by port.snapshot.collectAsState()
    val optIn by port.optIn.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val matches = remember(searchQuery, snapshot) {
        if (searchMode) port.search(searchQuery) else emptyList()
    }

    // §19.2 scroll anchoring: autoscroll only while at bottom and not paused.
    // §30.5: reduced motion → instant snap (no animation).
    val reducedMotion = com.zcode.android.core.ui.components.rememberReducedMotion()
    LaunchedEffect(snapshot.plainText.size, paused) {
        if (!paused && snapshot.plainText.isNotEmpty() && !searchMode) {
            if (reducedMotion) listState.scrollToItem(snapshot.plainText.size - 1)
            else listState.animateScrollToItem(snapshot.plainText.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Header: VIEW-ONLY indicator + state line (§21: visible persistent indicator).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.terminal_view_only_badge),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            when (val s = state) {
                is TerminalPort.TerminalState.Live ->
                    Text("live${s.shell?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                is TerminalPort.TerminalState.Exited ->
                    Text("exited (${s.code ?: "no status"})", style = MaterialTheme.typography.bodySmall)
                TerminalPort.TerminalState.Attaching -> Text("attaching…", style = MaterialTheme.typography.bodySmall)
                TerminalPort.TerminalState.Idle -> Text("idle", style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }
            Spacer(Modifier.weight(1f))
            AssistChip(onClick = { paused = !paused }, label = { Text(stringResource(if (paused) R.string.terminal_resume else R.string.terminal_pause)) })
            AssistChip(onClick = { searchMode = !searchMode }, label = { Text(stringResource(R.string.terminal_search)) })
        }

        when {
            !optIn -> OptInGate(
                enabled = optIn,
                onToggle = { enabled -> scope.launch { port.setOptIn(enabled) } },
                onContinue = { scope.launch { port.attach() } },
            )
            state is TerminalPort.TerminalState.CapabilityOff -> StateCard(
                stringResource(R.string.terminal_not_exposed),
            )
            state is TerminalPort.TerminalState.Refused -> StateCard(
                stringResource(R.string.terminal_refused, (state as TerminalPort.TerminalState.Refused).reason.take(140)),
                retry = { scope.launch { port.attach() } },
            )
            state is TerminalPort.TerminalState.Idle -> StateCard(stringResource(R.string.terminal_not_attached), retry = { scope.launch { port.attach() } }, retryLabel = stringResource(R.string.terminal_attach))
            state is TerminalPort.TerminalState.Attaching -> StateCard(stringResource(R.string.terminal_attaching))
            else -> {
                if (searchMode) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.terminal_search_output)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (searchQuery.isNotBlank()) {
                        Text("${matches.size} matching line(s)", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Surface(tonalElevation = 1.dp, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(6.dp)) {
                        items(snapshot.lines.size, key = { it }) { index ->
                            val spans = snapshot.lines[index]
                            val isMatch = searchMode && searchQuery.isNotBlank() &&
                                snapshot.plainText.getOrNull(index)?.lowercase()?.contains(searchQuery.lowercase()) == true
                            Text(
                                text = spans.toAnnotatedString(isMatch),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isMatch) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent)
                                    .semantics { contentDescription = "terminal line ${index + 1}" },
                            )
                        }
                        if (snapshot.droppedBytes > 0) {
                            item {
                                Text(
                                    "… ${snapshot.droppedBytes} earlier bytes dropped (ring bound 512 KiB) …",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // Detach affordance — the ONLY control that talks to the desktop, and
                // it is the protocol `dispose` (view-only: no write/resize exists).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { port.detach() }, label = { Text(stringResource(R.string.terminal_detach)) })
                }
            }
        }
    }
}

@Composable
private fun OptInGate(enabled: Boolean, onToggle: (Boolean) -> Unit, onContinue: () -> Unit) {
    StateCard(
        text = stringResource(R.string.terminal_consent_body) +
                "" +
            "your machine. It is view-only (nothing you type can reach the shell) and stays " +
            "off unless you enable it here.",
        extra = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = enabled, onCheckedChange = onToggle)
                Text(stringResource(R.string.terminal_enable), style = MaterialTheme.typography.bodyMedium)
            }
            if (enabled) {
                Button(onClick = onContinue) { Text(stringResource(R.string.terminal_attach)) }
            }
        },
    )
}

@Composable
private fun StateCard(text: String, retryLabel: String? = null, retry: (() -> Unit)? = null, extra: @Composable (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra?.invoke()
        retry?.let { Button(onClick = it) { Text(retryLabel ?: "") } }
    }
}

/** ANSI spans → AnnotatedString (palette indices < 16 resolve to M3-safe hues). */
private fun List<AnsiParser.Span>.toAnnotatedString(highlight: Boolean): AnnotatedString = buildAnnotatedString {
    for (span in this@toAnnotatedString) {
        val style = SpanStyle(
            color = span.fg?.let { terminalColor(it) } ?: Color.Unspecified,
            background = when {
                highlight -> Color(0x6655AA88)
                else -> span.bg?.let { terminalColor(it) } ?: Color.Unspecified
            },
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
            textDecoration = if (span.underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        )
        pushStyle(style)
        append(span.text)
        pop()
    }
}

/** Palette 0–15 → ANSI-default hues; packed RGB (≥ 0x1000000) passes through. */
private fun terminalColor(value: Int): Color = when {
    value >= 0x1000000 -> Color(value)
    else -> when (value) {
        0 -> Color(0xFF666666); 1 -> Color(0xFFCD3131); 2 -> Color(0xFF0DBC79)
        3 -> Color(0xFFE5E510); 4 -> Color(0xFF2472C8); 5 -> Color(0xFFBC3FBC)
        6 -> Color(0xFF11A8CD); 7 -> Color(0xFFE5E5E5); 8 -> Color(0xFF767676)
        9 -> Color(0xFFF14C4C); 10 -> Color(0xFF23D18B); 11 -> Color(0xFFF5F543)
        12 -> Color(0xFF3B8EEA); 13 -> Color(0xFFD670D6); 14 -> Color(0xFF29B8DB)
        else -> Color(0xFFFFFFFF) // 15
    }
}
