package com.zcode.android.features.terminal.port

import com.zcode.android.core.common.ansi.AnsiParser
import kotlinx.coroutines.flow.StateFlow

/**
 * Consumer-side port for the §21 terminal (INV-2/INV-6: no transport types).
 * SECURITY NORMATIVE (§21/§13.3 + ADR-022): the terminal is an arbitrary-shell
 * surface — the feature renders ONLY when (1) the endpoint hello declares
 * `localTerminal === true`, (2) the user opted IN via Settings (default OFF),
 * and (3) the attach authorization probe succeeds; any refusal renders the
 * disabled/fallback state. The read posture is view-only; the ONLY write path
 * is the ADR-022 armed composer (inputOptIn + per-attach arm gate `write`
 * and `resize` — both throw while disarmed, INV-9 as narrowed by the ADR).
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

    // ---------------------------------------------------------------- ADR-022 (Phase 5D): input

    /**
     * The four-layer input gate (ADR-022): hello `localTerminal` (1) + the view opt-in
     * (2) + the SECOND credential-confirmed input opt-in (3, default OFF) + the
     * PER-ATTACH ARM (4, auto-disarms on detach/exit/background/10-min idle). `write`
     * and `resize` are reachable ONLY while an arm is held (pinned: zero calls while
     * disarmed).
     */
    val inputOptIn: StateFlow<Boolean>
    val armed: StateFlow<Boolean>

    /** Flip the input opt-in; `granted=false` (credential refused) leaves it OFF. */
    suspend fun setInputOptIn(enabled: Boolean, granted: Boolean)

    /** Arm the attached session (no-op when the input opt-in is off or not Live). */
    suspend fun arm()

    /** Disarm now (detach/exit/background/expiry). */
    fun disarm()

    // ---------------------------------------------------------------- ADR-022 (G104 executed): the armed write tier

    /**
     * Write one keystroke chunk to the attached shell. Reachable ONLY while an
     * arm is held — the controller's typed gate throws while disarmed (the
     * TerminalArmGateTest pin: zero wire calls). Enter sends `\r`, Ctrl-C
     * sends `\u0003` — the composer composes these; nothing else exists.
     */
    suspend fun write(data: String)

    /** Resize the attached grid; rides the same arm (cosmetic, but still a write). */
    suspend fun resize(cols: Int, rows: Int)

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
