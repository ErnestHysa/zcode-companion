package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-48 mock self-test (the catalog wire-words axis — commands.list / subagents /
 * bots list calls had ZERO arg-exact receipts in 47 passes): the p48 arms are the
 * three catalog channels' arg log lines (verified live, the ae091f81/7feb2695/
 * 17e008e2 template), MOCK_CATALOG_EMPTY's empty shapes (the three viewer screens'
 * never-driven empty faces), MOCK_TERMINAL_OUT=big (the ring-eviction face — the
 * pass-47 "not driven" leftover), and MOCK_TERMINAL_EXIT_CODE (the nonzero exit
 * face — pass 47 receipted only "exited (0)").
 */
class MockZCodePass48CatalogSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 48 })
        return MockZCode(passHash = passHash, expectedSid = "d_selftest_p48")
    }

    @Test
    fun `empty catalog shapes carry the decoder keys with empty arrays`() {
        val commands = emptyCommandsCatalog()
        assertEquals(0, (commands["userCommands"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(0, (commands["pluginCommands"] as kotlinx.serialization.json.JsonArray).size)

        val subagents = emptySubagentsCatalog()
        assertEquals(0, (subagents["agents"] as kotlinx.serialization.json.JsonArray).size)

        val bots = emptyBotsCatalog()
        assertEquals(0, (bots["bots"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `the seeded catalogs stay non-empty (no regression)`() {
        assertTrue(
            (subagentsViewerSeed()["agents"] as kotlinx.serialization.json.JsonArray).size == 3,
            "the subagents seed keeps its three agents",
        )
        assertTrue(
            (botsViewerSeed()["bots"] as kotlinx.serialization.json.JsonArray).size == 2,
            "the bots seed keeps its two bots",
        )
    }

    @Test
    fun `big mode overflows the phone's 512 KiB ring with numbered newline-terminated lines`() {
        val m = mock()
        m.script.terminalSeedOutput("big")
        val lines = ArrayList<String>()
        synchronized(m.script.terminalOutput) {
            while (true) lines.add(m.script.terminalOutput.removeFirstOrNull() ?: break)
        }
        assertEquals(2400, lines.size)
        assertTrue(lines.all { it.endsWith("\n") }, "every chunk newline-terminated")
        val totalBytes = lines.sumOf { it.toByteArray().size }
        assertTrue(totalBytes > 512 * 1024, "seed must overflow the ring: got $totalBytes bytes")
        assertTrue(lines.first().contains("line 0001"), "the head is numbered (eviction provable by number)")
        assertTrue(lines.last().contains("line 2400"), "the tail is numbered")
    }

    @Test
    fun `plain seed unaffected by the big mode addition`() {
        val m = mock()
        m.script.terminalSeedOutput("plain")
        val lines = ArrayList<String>()
        synchronized(m.script.terminalOutput) {
            while (true) lines.add(m.script.terminalOutput.removeFirstOrNull() ?: break)
        }
        assertEquals(4, lines.size)
        assertEquals("rig-p47 workspace /synthetic/ws-one\n", lines.first())
    }

    @Test
    fun `bigone mode is the big payload delivered as ONE newline-terminated chunk (p49 discriminator)`() {
        val m = mock()
        m.script.terminalSeedOutput("bigone")
        val lines = ArrayList<String>()
        synchronized(m.script.terminalOutput) {
            while (true) lines.add(m.script.terminalOutput.removeFirstOrNull() ?: break)
        }
        assertEquals(1, lines.size, "the discriminator: one queue entry = one stream frame")
        val chunk = lines.first()
        assertTrue(chunk.endsWith("\n"), "the single chunk stays newline-terminated (no carry-line join)")
        assertTrue(chunk.toByteArray().size > 512 * 1024, "the single chunk still overflows the ring")
        assertTrue(chunk.contains("rig-p48 line 0001"), "head numbered")
        assertTrue(chunk.contains("rig-p48 line 2400"), "tail numbered")
        // Same payload identity as `big` (byte volume held constant across the pair).
        val big = mock()
        big.script.terminalSeedOutput("big")
        val bigBytes = synchronized(big.script.terminalOutput) {
            big.script.terminalOutput.sumOf { it.toByteArray().size }
        }
        assertEquals(bigBytes, chunk.toByteArray().size, "bigone == big byte volume")
    }
}
