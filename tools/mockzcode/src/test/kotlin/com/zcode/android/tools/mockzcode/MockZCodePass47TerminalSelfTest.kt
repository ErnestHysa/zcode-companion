package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-47 mock self-test (the TERMINAL plane axis — create/write/resize/dispose had
 * ZERO report hits in 46 passes): `terminalSeedOutput` is the MOCK_TERMINAL_OUT arm
 * (per-listen seed, plain|ansi modes) and `helloLocalTerminal` is the
 * MOCK_TERMINAL_CAP_OFF gate (the hello's localTerminal bit) — both pure seams the
 * request sites read env into, so the arm logic is testable without sockets.
 */
class MockZCodePass47TerminalSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 47 })
        return MockZCode(passHash = passHash, expectedSid = "d_selftest_p47")
    }

    private fun drain(m: MockZCode): List<String> {
        val out = ArrayList<String>()
        synchronized(m.script.terminalOutput) {
            while (true) out.add(m.script.terminalOutput.removeFirstOrNull() ?: break)
        }
        return out
    }

    @Test
    fun `plain mode seeds the banner lines in order`() {
        val m = mock()
        m.script.terminalSeedOutput("plain")
        val lines = drain(m)
        assertEquals(4, lines.size)
        assertEquals("rig-p47 workspace /synthetic/ws-one\n", lines.first())
        assertTrue(lines.all { it.endsWith("\n") }, "every chunk is newline-terminated (the ring line-buffer joins unterminated pushes)")
        assertTrue(lines.none { it.contains('\u001B') }, "plain carries no SGR escapes")
    }

    @Test
    fun `ansi mode seeds SGR-bearing lines`() {
        val m = mock()
        m.script.terminalSeedOutput("ansi")
        val lines = drain(m)
        assertEquals(4, lines.size)
        assertTrue(lines.first().contains("\u001B[1m"), "the header is bold")
        assertTrue(lines.any { it.contains("\u001B[32m") }, "the echo line is green")
    }

    @Test
    fun `blank and unknown modes fall back to plain`() {
        val m = mock()
        m.script.terminalSeedOutput("")
        assertEquals("rig-p47 workspace /synthetic/ws-one\n", drain(m).first())
        m.script.terminalSeedOutput("nonsense")
        assertTrue(drain(m).none { it.contains('\u001B') }, "unknown falls back to plain")
        m.script.terminalSeedOutput(null)
        assertEquals(4, drain(m).size)
    }

    @Test
    fun `queue drains FIFO across seeds`() {
        val m = mock()
        m.script.terminalSeedOutput("plain")
        m.script.terminalSeedOutput("ansi")
        val lines = drain(m)
        assertEquals(8, lines.size)
        assertEquals("rig-p47 workspace /synthetic/ws-one\n", lines.first())
        assertTrue(lines[4].contains("\u001B[1m"), "the second seed's header follows in order")
    }

    @Test
    fun `helloLocalTerminal gates only on the exact cap-off arm`() {
        val m = mock()
        assertTrue(m.script.helloLocalTerminal(null), "no arm → exposed (the standing shape)")
        assertTrue(m.script.helloLocalTerminal(""), "blank → exposed")
        assertTrue(m.script.helloLocalTerminal("0"), "not-1 → exposed")
        assertEquals(false, m.script.helloLocalTerminal("1"), "the cap-off arm hides the capability")
        assertEquals(false, m.script.helloLocalTerminal(" 1 "), "the arm trims")
    }
}
