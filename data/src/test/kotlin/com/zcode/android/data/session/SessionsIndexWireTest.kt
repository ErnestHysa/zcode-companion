package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G13 (vc51) — the sessions-index wire pin: the phone subscribes the workspace's
 * index with the DESKTOP adapter's shape — `subscribeSessionsIndexV4
 * {workspacePath, workspaceIdentity?, runtimePolicy:"existing-only"}` (a passive
 * list feed must never spawn a runtime) — and the subscription lands in the
 * transport's records so recover() re-subscribes it with the dedicated method.
 */
class SessionsIndexWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
    private val sid: String get() = "d_sidx_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect() {
        var last: Exception? = null
        for (attempt in 0..2) {
            try {
                mock = MockZCode(passHash, sid)
                val url = mock.start()
                transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
                runBlocking {
                    withTimeout(30_000) {
                        transport.connect(
                            EndpointDescriptor(
                                id = "ep-sidx",
                                kind = EndpointKind.RELAY_ZAI,
                                displayName = "SidxMockBook",
                                wsUrl = url,
                                workspaceKey = "synthetic-ws",
                                workspacePath = "/synthetic/ws",
                                deviceSid = sid,
                                deviceMid = "mock-desktop-mid",
                            ),
                            signer(),
                        )
                    }
                }
                return
            } catch (e: Exception) {
                last = e
                runBlocking { runCatching { transport.close("connect retry") } }
                runCatching { mock.stop() }
                Thread.sleep(500)
            }
        }
        throw last ?: IllegalStateException("connect failed")
    }

    private fun signer(): CredentialSigner = CredentialSigner { nonce, role, s ->
        MockZCode.hmacProof(passHash, nonce, role, s)
    }

    @Test
    fun `sessions-index subscribe rides subscribeSessionsIndexV4 with existing-only and records the topic`() {
        connect()
        val ack = runBlocking {
            withTimeout(15_000) { transport.subscribeSessionsIndex("/synthetic/ws", "synthetic-ws") }
        }
        assertTrue(ack.subscriptionId.isNotBlank())
        val call = mock.v4CallsForTest().firstOrNull { it.first == "subscribeSessionsIndexV4" }
        assertTrue(call != null, "the index subscribe must ride the dedicated v4 method (G13)")
        assertEquals("/synthetic/ws", call!!.second["workspacePath"]?.toString()?.trim('"'))
        assertEquals("synthetic-ws", call.second["workspaceIdentity"]?.toString()?.trim('"'))
        assertEquals("existing-only", call.second["runtimePolicy"]?.toString()?.trim('"'))
        // The ack's epoch is the index plane's own generation (never a conversation epoch).
        assertEquals("idx-epoch-mock-1", ack.logEpoch)
    }
}
