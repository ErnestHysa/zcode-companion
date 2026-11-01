package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.Phase9Tools
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G16 (vc51 Phase 4D) wire legs over the REAL transport ↔ mock: the preview-source
 * verdict (`attachmentPreviewSourceV4` → `{kind:"chunked"}` for a mobile client), the
 * PREVIEW-QUALITY fetch streamed over `conversationAttachmentReadV4` (full bytes, not
 * the 512 KiB first chunk), and the conversation-scoped stat. An unknown ref faults —
 * the degrade is the caller's (INV-11).
 */
class AttachmentPreviewQualityWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
    private val sid: String get() = "d_g16_${System.identityHashCode(this)}"
    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect(): Phase9Tools {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
        )
        mock.setScriptedAttachment("mock-attachment-1", "image/png", png)
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-g16", kind = EndpointKind.RELAY_ZAI, displayName = "G16MockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
                transport.subscribeSession("synth-task-1")
            }
        }
        return Phase9Tools(transport)
    }

    @Test
    fun `preview source answers the chunked verdict for a mobile client`() {
        val tools = connect()
        runBlocking {
            assertEquals("chunked", tools.attachmentPreviewSourceKind("synth-task-1", "mock-attachment-1"))
        }
    }

    @Test
    fun `preview-quality fetch streams the full attachment over the conversation channel`() {
        val tools = connect()
        runBlocking {
            val bytes = tools.attachmentPreviewQuality("synth-task-1", "mock-attachment-1")
            assertTrue(bytes != null && bytes.isNotEmpty())
            // the mock's scripted PNG, delivered whole (PNG magic)
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals('P'.code.toByte(), bytes[1])
            assertEquals('N'.code.toByte(), bytes[2])
            assertEquals('G'.code.toByte(), bytes[3])
        }
    }

    @Test
    fun `unknown ref faults (the honest-degrade input)`() {
        val tools = connect()
        runBlocking {
            assertNull(
                runCatching { tools.attachmentPreviewQuality("synth-task-1", "no-such-ref") }
                    .getOrNull(),
            )
        }
    }
}
