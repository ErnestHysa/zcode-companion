package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.ViewerTools
import com.zcode.android.domain.model.SlashCatalogExtras
import com.zcode.android.tools.mockzcode.MockZCode
import com.zcode.android.tools.mockzcode.botsViewerSeed
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G30 (vc51 Phase 4B) wire leg over the REAL transport ↔ mock: `bots.list` (the INV-9
 * seed's scripted inventory) round-trips and decodes both mode faces. Against the real
 * shipped host (no bots RPC) the same call faults → the viewer renders its honest face.
 */
class BotsListWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
    private val sid: String get() = "d_bots_${System.identityHashCode(this)}"
    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect(seeded: Boolean): ViewerTools {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        if (seeded) mock.script.botsList = botsViewerSeed()
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-bots", kind = EndpointKind.RELAY_ZAI, displayName = "BotsMockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws-one", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
                transport.subscribeSession("synth-task-1")
            }
        }
        return ViewerTools(transport)
    }

    @Test
    fun `seeded inventory decodes across providers and both mode faces`() {
        val tools = connect(seeded = true)
        runBlocking {
            val bots = tools.bots()
            assertEquals(2, bots.size)
            assertTrue(bots.any { it.provider == "telegram" && it.mode == "task" && it.enabled })
            assertTrue(bots.any { it.provider == "discord" && it.mode == "draft" && !it.enabled })
            assertEquals("synth-task-1", bots.first { it.enabled }.activeTaskId)
            assertEquals(2, bots.map { SlashCatalogExtras.BotEntry::class }.size)
        }
    }

    @Test
    fun `unscripted host faults honestly (the real-desktop shape)`() {
        val tools = connect(seeded = false)
        runBlocking {
            val error = runCatching { tools.bots() }.exceptionOrNull()
            assertTrue(error != null, "an unscripted bots.list must fault, not return an empty list")
        }
    }
}
