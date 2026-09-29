
package com.zcode.android.core.common.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-7 diagnostics-enhancement checks (export format, DEBUG capture, big ring). */
class ZLogDiagnosticsTest {

    @Test
    fun `export carries readable ISO timestamps and DEBUG entries at DEBUG minLevel`() {
        val log = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = {}, ringCapacity = 5_000)
        log.debug("relay.debugProbe", "visible at DEBUG")
        log.info("app.start", "visible always")
        val export = log.exportDiagnostics()
        assertTrue(export.contains("relay.debugProbe"), "DEBUG entries must be captured at DEBUG minLevel")
        assertTrue(export.contains("app.start"))
        // Readable ISO stamp accompanies the epoch ms on every line.
        val lines = export.lines().filter { it.isNotBlank() }
        assertTrue(lines.all { it.contains("T") && it.contains("Z") && it.contains("[") }, lines.firstOrNull().orEmpty())
    }

    @Test
    fun `entries capture the originating thread name`() {
        val log = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = {})
        log.info("thread.probe", "x")
        val entry = log.snapshot().last()
        assertTrue(!entry.thread.isNullOrBlank(), "thread must be captured")
    }

    @Test
    fun `snapshot returns structured entries for report builders`() {
        val log = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = {})
        log.with(ZLog.LogContext(connectionId = "relay-9")).info("hub.phase", "transport X → Y")
        val snap = log.snapshot()
        assertTrue(snap.isNotEmpty())
        val entry = snap.last()
        assertEquals("hub.phase", entry.event)
        assertEquals("relay-9", entry.context["connectionId"])
    }

    @Test
    fun `5000-entry ring capacity is accepted (INV-5 upper region)`() {
        val log = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = {}, ringCapacity = 5_000)
        repeat(6_000) { i -> log.info("e$i") }
        assertEquals(5_000, log.ringSize())
        val export = log.exportDiagnostics()
        assertTrue(export.contains("e5999"), "newest entries retained")
        assertTrue(!export.contains("e0\n"), "oldest entries evicted")
    }
}
