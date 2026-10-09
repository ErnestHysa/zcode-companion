package com.zcode.android.data.session

import com.zcode.android.core.common.ansi.AnsiParser
import com.zcode.android.core.common.ansi.SgrState
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.AppTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * View-only terminal controller (§21, Phase 9.3) — binds the transport's
 * terminal channel surface. PRODUCT RULES (normative):
 *  - the phone may `create` + `dispose` and READ the data/exit events ONLY —
 *    the transport type exposes no `write`/`resize` (INV-9 by construction);
 *  - output is a bounded RING (512 KiB, drop-oldest — §21/§28.7), decoded to
 *    styled lines off the caller's thread at snapshot time (SGR state carries
 *    across appends/chunks);
 *  - authorization/capability failures are FIRST-CLASS states (§13.3 fallback:
 *    the feature degrades, the connection never does).
 */
class TerminalController(
    private val transport: AppTransport,
    private val log: ZLog = ZLog(),
) {

    companion object {
        /** §21: ring buffer 512 KiB (bytes of UTF-8 output, drop-oldest by lines). */
        const val RING_MAX_BYTES = 512 * 1024
        /** Terminal grid offered at create (view-only stream — rows/cols matter little; keep sane). */
        const val COLS = 120
        const val ROWS = 32
    }

    sealed class State {
        data object Idle : State()
        data object Attaching : State()
        /** Live + optional exit code already seen (the exit event may race the frame). */
        data class Live(val terminalId: String, val shell: String?) : State()
        data class Exited(val terminalId: String, val code: Int?) : State()
        /** Endpoint refused the channel (role gate / unknown channel) — §13.3 fallback. */
        data class Refused(val reason: String) : State()
        /** HostCapabilities.localTerminal absent (§39.3: feature hidden). */
        data object CapabilityOff : State()
    }

    /** Immutable render snapshot: decoded styled lines + ring health counters. */
    data class Snapshot(
        val lines: List<List<AnsiParser.Span>> = emptyList(),
        val plainText: List<String> = emptyList(),
        val ringBytes: Int = 0,
        val droppedBytes: Long = 0,
        val totalAppendedBytes: Long = 0,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var terminalId: String? = null
    private var dataListener: Int? = null
    private var exitListener: Int? = null

    /** Raw ring: line-buffered UTF-8 text + running byte count (drop-oldest by lines). */
    private val ringLines = ArrayDeque<String>()
    private var ringBytes = 0
    private var droppedBytes = 0L
    private var totalAppendedBytes = 0L
    private var carry = StringBuilder()
    private var sgrState = SgrState()

    /**
     * Attach: capability gate (localTerminal) → create → per-id event listens.
     * Any channel refusal lands as [State.Refused] (feature-disabled fallback).
     */
    suspend fun attach(cwd: String? = null): State {
        if (_state.value is State.Live || _state.value is State.Attaching) return _state.value
        detach(silent = true)
        // §39.3 capability gate: required hello bit must be EXACTLY true (§7.4.1).
        val localTerminal = transport.capabilities.value?.host?.localTerminal
        if (localTerminal != true) {
            log.info("terminal.capabilityOff", "localTerminal=$localTerminal")
            _state.value = State.CapabilityOff
            return _state.value
        }
        _state.value = State.Attaching
        _snapshot.value = Snapshot()
        synchronized(this) {
            ringLines.clear(); ringBytes = 0; droppedBytes = 0; totalAppendedBytes = 0
            carry = StringBuilder(); sgrState = SgrState()
        }
        _state.value = try {
            val result = transport.terminalCreate(COLS, ROWS, cwd)
            val id = result.strOrNull("id") ?: throw IllegalStateException("terminal.create result missing id")
            terminalId = id
            dataListener = transport.terminalListenData(id) { text -> onText(text) }
            exitListener = transport.terminalListenExit(id) { code ->
                val current = terminalId
                if (current != null) {
                    _state.value = State.Exited(current, code)
                }
            }
            log.info("terminal.attached", "id=$id")
            State.Live(id, result.strOrNull("shell"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("terminal.refused", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            State.Refused(e.message ?: "terminal channel refused")
        }
        return _state.value
    }

    /** Best-effort background dispose scope (the desktop owns the shell lifecycle — §21). */
    private val disposeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
    )

    fun detach(silent: Boolean = false) {
        val id = terminalId
        dataListener?.let { transport.disposeListener(it) }
        exitListener?.let { transport.disposeListener(it) }
        dataListener = null; exitListener = null
        if (id != null && !silent) {
            disposeScope.launch { runCatching { transport.terminalDispose(id) } }
        }
        terminalId = null
        if (!silent) _state.value = State.Idle
    }

    /** Ring append (drop-oldest by lines at 512 KiB, INV-5) + snapshot decode. */
    private fun onText(text: String) {
        val added = text.toByteArray(Charsets.UTF_8).size
        synchronized(this) {
            totalAppendedBytes += added
            carry.append(text)
            var newlineIdx = carry.indexOf('\n')
            while (newlineIdx >= 0) {
                val line = carry.substring(0, newlineIdx)
                ringLines.addLast(line)
                ringBytes += line.toByteArray(Charsets.UTF_8).size + 1
                carry.delete(0, newlineIdx + 1)
                newlineIdx = carry.indexOf('\n')
            }
            while (ringBytes > RING_MAX_BYTES && ringLines.isNotEmpty()) {
                val dropped = ringLines.removeFirst()
                ringBytes -= dropped.toByteArray(Charsets.UTF_8).size + 1
                droppedBytes += dropped.toByteArray(Charsets.UTF_8).size + 1
            }
        }
        publishSnapshot()
    }

    /** Decode the ring (plain stream, §21: cursor-motion sequences consumed). */
    private fun publishSnapshot() {
        val lines: List<String> = synchronized(this) {
            val list = ringLines.toList()
            if (carry.isNotEmpty()) list + carry.toString() else list
        }
        var state = SgrState()
        val decoded = ArrayList<List<AnsiParser.Span>>(lines.size)
        for (line in lines) {
            val chunk = line + "\n"
            decoded.add(AnsiParser.decode(chunk, state).spans)
            state = AnsiParser.stateAfter(chunk, state)
        }
        _snapshot.value = Snapshot(
            lines = decoded,
            plainText = lines,
            ringBytes = ringBytes,
            droppedBytes = droppedBytes,
            totalAppendedBytes = totalAppendedBytes,
        )
    }

    /** Case-insensitive plain-text search → matching line indexes (UI highlights). */
    fun search(query: String): List<Int> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return _snapshot.value.plainText
            .withIndex()
            .filter { it.value.lowercase().contains(q) }
            .map { it.index }
    }

    private fun kotlinx.serialization.json.JsonObject.strOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
