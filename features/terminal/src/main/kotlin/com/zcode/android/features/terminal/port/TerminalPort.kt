package com.zcode.android.features.terminal.port

import com.zcode.android.core.common.ansi.AnsiParser
import kotlinx.coroutines.flow.StateFlow

/**
 * Consumer-side port for the §21 view-only terminal (INV-2/INV-6: no transport
 * types). SECURITY NORMATIVE (§21/§13.3): the terminal is an arbitrary-shell
 * surface — the feature renders ONLY when (1) the endpoint hello declares
 * `localTerminal === true`, (2) the user opted IN via Settings (default OFF),
 * and (3) the attach authorization probe succeeds; any refusal renders the
 * disabled/fallback state. There is NO write path anywhere in this port.
 */
interface TerminalPort {

    /** User opt-in (per §21 "explicit opt-in setting"); default FALSE. */
    val optIn: StateFlow<Boolean>
    suspend fun setOptIn(enabled: Boolean)

    /** View state machine (§14.4-adjacent; every state renders honestly, INV-11). */
    val state: StateFlow<TerminalState>

    /** Decoded output snapshot (bounded ring 512 KiB — §21/§28.7). */
    val snapshot: StateFlow<TerminalSnapshot>

    /** Attach (create + read-only event listens). Returns the resulting state. */
    suspend fun attach(): TerminalState

    /** Detach + dispose (desktop owns the shell lifecycle — attach/detach only). */
    fun detach()

    /** Case-insensitive ring search → matching line indexes. */
    fun search(query: String): List<Int>

    sealed class TerminalState {
        data object Idle : TerminalState()
        data object Attaching : TerminalState()
        data class Live(val shell: String?) : TerminalState()
        data class Exited(val code: Int?) : TerminalState()
        /** Endpoint refused the terminal channel for this connection (§13.3 fallback). */
        data class Refused(val reason: String) : TerminalState()
        /** hello capability `localTerminal` absent → feature hidden (§39.3). */
        data object CapabilityOff : TerminalState()
        /** Opt-in off — the default, safe state (§21: never enabled silently). */
        data object OptInRequired : TerminalState()
    }

    data class TerminalSnapshot(
        val lines: List<List<AnsiParser.Span>>,
        val plainText: List<String>,
        val ringBytes: Int,
        val droppedBytes: Long,
    )
}
