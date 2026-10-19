package com.zcode.android.data.session

import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A3.4 (Phase 4C): `setThoughtLevel` over the real transport ↔ mock — the facade
 * params shape (zcodeAgent.ts:705): `{sessionId, thoughtLevel, expectedRevision,
 * persistAsWorkspaceLastUsed}`; the mock's sessionConfig echo updates its `thought`
 * (the §10.3 echo plane the picker reconciles against).
 */
class ViewerToolsThoughtLevelTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
    private val sid: String get() = "d_effort_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var tools: ViewerTools

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect() {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.start()
        val port = mock.portForTest()
        transport = RelayTransport(
            endpoint = EndpointDescriptor(EndpointKind.RELAY, "ws://127.0.0.1:$port"),
            signer = CredentialSigner { _, _, _ -> passHash },
            wsFactory = OkHttpWsFactory(),
        )
        runBlocking {
            withTimeout(15_000) {
                transport.connect(
                    deviceSid = sid,
                    bootstrap = com.zcode.android.core.transport.BootstrapPayload(
                        endpointId = "ep-effort",
                        workspaces = listOf(com.zcode.android.domain.model.WorkspaceListEntry("/synthetic/ws")),
                    ),
                )
            }
        }
        tools = ViewerTools(transport)
    }

    @Test
    fun `setThoughtLevel sends sessionId level expectedRevision and persistAsWorkspaceLastUsed`() {
        connect()
        runBlocking {
            tools.setThoughtLevel("/synthetic/ws", null, "sess-effort", "high", expectedRevision = 7)
        }
        val call = mock.lastV4CallForTest("setThoughtLevel") ?: error("setThoughtLevel never reached the mock")
        assertEquals("sess-effort", (call.params["sessionId"] as JsonPrimitive).content)
        assertEquals("high", (call.params["thoughtLevel"] as JsonPrimitive).content)
        assertEquals("7", (call.params["expectedRevision"] as JsonPrimitive).content)
        assertEquals("true", (call.params["persistAsWorkspaceLastUsed"] as JsonPrimitive).content)
        assertEquals("high", (mock.script.sessionConfig?.get("thought") as? JsonPrimitive)?.content, "the echo plane reflects the new level")
    }
}
