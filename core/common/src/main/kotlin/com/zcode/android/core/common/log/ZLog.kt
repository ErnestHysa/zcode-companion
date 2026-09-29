package com.zcode.android.core.common.log

/**
 * Central redaction logging sink — architectural.md §34 + INV-3.
 *
 * Design rules (normative):
 *  - ONE sink; redaction is centralized here, never per-callsite discipline. Every
 *    event name, detail string, and context value passes through the scrubbers before
 *    it reaches the output or the diagnostics ring.
 *  - Secrets are registered exactly once where they are handled (e.g. the pairing
 *    hash at seal time, a proof at signing time); from then on NO output can contain
 *    them, regardless of the callsite.
 *  - Structural scrubbers additionally redact key-value shapes (`pass_hash=…`,
 *    `"proof":"…"`) even for values that were never registered — defense in depth.
 *  - Levels: DEBUG = protocol raw frames / stream chunks (debug builds only, never
 *    persisted); INFO = lifecycle; WARN = recoverable anomalies; ERROR = unrecoverable
 *    (mirrors the ZCode AGENTS.md logger rules, adopted verbatim per §34).
 *  - Diagnostics ring: last [RING_CAPACITY] redacted entries, exportable by explicit
 *    user action (Settings → Export diagnostics, later phase).
 *  - INV-3: credentials/secrets never enter ordinary logs, backups, crash payloads,
 *    or telemetry — the property test proves no registered secret substring survives.
 */
class ZLog(
    private val minLevel: LogLevel = LogLevel.INFO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (LogEntry) -> Unit = {},
    private val ringCapacity: Int = RING_CAPACITY,
) {

    init {
        require(ringCapacity in 1..100_000) { "ring capacity out of bounds (INV-5)" }
    }

    enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    /** Structured correlation context (§34) — ids only, never payload content. */
    data class LogContext(
        val endpointId: String? = null,
        val connectionId: String? = null,
        val sessionId: String? = null,
        val commandId: String? = null,
    ) {
        fun toMap(): Map<String, String> = buildMap {
            endpointId?.let { put("endpointId", it) }
            connectionId?.let { put("connectionId", it) }
            sessionId?.let { put("sessionId", it) }
            commandId?.let { put("commandId", it) }
        }
    }

    data class LogEntry(
        val timestampMs: Long,
        val level: LogLevel,
        val event: String,
        val details: String?,
        val context: Map<String, String>,
    )

    private val secrets = HashSet<String>()
    private val ring = ArrayDeque<LogEntry>(ringCapacity)
    private val ringLock = Any()

    /** Child view bound to a correlation context — the same sink, same scrubbers. */
    fun with(context: LogContext): Bound = Bound(context)

    inner class Bound(private val context: LogContext) {
        fun debug(event: String, details: String? = null) = log(LogLevel.DEBUG, event, details, context)
        fun info(event: String, details: String? = null) = log(LogLevel.INFO, event, details, context)
        fun warn(event: String, details: String? = null) = log(LogLevel.WARN, event, details, context)
        fun error(event: String, details: String? = null) = log(LogLevel.ERROR, event, details, context)
    }

    /**
     * Register a secret value. From this call on, every occurrence of the value in any
     * logged string is replaced. Blank values are ignored (they cannot be scrubbed and
     * would destroy output); callers must never construct blank secrets.
     */
    fun registerSecret(value: String) {
        if (value.isBlank()) return
        synchronized(secrets) { secrets.add(value) }
    }

    fun debug(event: String, details: String? = null, context: LogContext = LogContext()) = log(LogLevel.DEBUG, event, details, context)
    fun info(event: String, details: String? = null, context: LogContext = LogContext()) = log(LogLevel.INFO, event, details, context)
    fun warn(event: String, details: String? = null, context: LogContext = LogContext()) = log(LogLevel.WARN, event, details, context)
    fun error(event: String, details: String? = null, context: LogContext = LogContext()) = log(LogLevel.ERROR, event, details, context)

    fun log(level: LogLevel, event: String, details: String? = null, context: LogContext = LogContext()) {
        if (level.ordinal < minLevel.ordinal) return
        val entry = LogEntry(
            timestampMs = clock(),
            level = level,
            event = scrub(event),
            details = details?.let(::scrub)?.take(MAX_DETAIL_CHARS),
            context = context.toMap().mapValues { (_, v) -> scrub(v) },
        )
        sink(entry)
        synchronized(ringLock) {
            if (ring.size >= ringCapacity) ring.removeFirst()
            ring.addLast(entry)
        }
    }

    /** Redacted diagnostics export (§34: explicit user action, later phase UI). */
    fun exportDiagnostics(): String = synchronized(ringLock) {
        ring.joinToString("\n") { e ->
            val ctx = if (e.context.isEmpty()) "" else " " + e.context.entries.joinToString(" ") { "${it.key}=${it.value}" }
            val det = e.details?.let { " :: $it" } ?: ""
            "${e.timestampMs} ${formatTs(e.timestampMs)} [${e.level}] ${e.event}$ctx$det"
        }
    }

    /** Human-readable wall-clock stamp (API 26+ java.time; core is JVM-pure). */
    private fun formatTs(epochMs: Long): String = try {
        java.time.Instant.ofEpochMilli(epochMs).toString()
    } catch (e: Exception) {
        "?" // never let formatting break logging
    }

    fun ringSize(): Int = synchronized(ringLock) { ring.size }

    /** Round-7b: copy of the ring for structured report builders (counters/timeline). */
    fun snapshot(): List<LogEntry> = synchronized(ringLock) { ring.toList() }

    // ------------------------------------------------------------- scrubbers

    private fun scrub(input: String): String {
        var out = input
        synchronized(secrets) {
            for (s in secrets) {
                if (out.contains(s)) out = out.replace(s, REDACTED)
            }
        }
        for (p in STRUCTURAL_PATTERNS) {
            out = p.replace(out, "$1$2$REDACTED")
        }
        return out
    }

    companion object {
        /** Diagnostics ring bound (§34: last 500 events). */
        const val RING_CAPACITY = 500
        const val REDACTED = "[REDACTED]"
        private const val MAX_DETAIL_CHARS = 400

        /**
         * Key-value shapes whose VALUES are secret-shaped. Keys follow the verified
         * naming (pass_hash / proof / token / nonce / authorization / api_key classes).
         * Applied after exact-secret replacement; match is case-insensitive on the key.
         */
        private val STRUCTURAL_PATTERNS: List<Regex> = listOf(
            // pass_hash=xyz  "proof":"xyz"  token: xyz  Authorization: Bearer xyz ...
            // \b after the key so longer words sharing the prefix (e.g. "proofreading")
            // survive; the optional scheme word (bearer/basic) sits between key and value.
            Regex(
                """(?i)\b(pass[_-]?hash|proof|token|nonce|authorization|password|api[_-]?key|secret)(["']?\s*[:=]\s*["']?(?:bearer|basic)?\s*)([A-Za-z0-9+/_\-.=~]{4,})"""
            ),
        )
    }
}
