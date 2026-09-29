
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
    fun `5000-entry ring capacity is accepted (INV-5 upper region)`() {
        val log = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = {}, ringCapacity = 5_000)
        repeat(6_000) { i -> log.info("e$i") }
        assertEquals(5_000, log.ringSize())
        val export = log.exportDiagnostics()
        assertTrue(export.contains("e5999"), "newest entries retained")
        assertTrue(!export.contains("e0\n"), "oldest entries evicted")
    }
}
