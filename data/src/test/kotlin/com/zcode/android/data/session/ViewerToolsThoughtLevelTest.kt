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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(
                        id = "ep-effort",
                        kind = EndpointKind.RELAY_ZAI,
                        displayName = "EffortMockBook",
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
        tools = ViewerTools(transport)
    }

    private fun signer(): CredentialSigner = CredentialSigner { nonce, role, sid ->
        MockZCode.hmacProof(passHash, nonce, role, sid)
    }

    @Test
    fun `setThoughtLevel sends sessionId level expectedRevision and persistAsWorkspaceLastUsed`() {
        connect()
        mock.script.sessionConfig = kotlinx.serialization.json.buildJsonObject {
            put("provider", JsonPrimitive("zai"))
            put("model", JsonPrimitive("glm-5.1"))
            put("thought", JsonPrimitive("low"))
            put("thoughtLevels", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("low"), JsonPrimitive("medium"), JsonPrimitive("high"))))
            put("mode", JsonPrimitive("build"))
        }
        runBlocking {
            tools.setThoughtLevel("/synthetic/ws", null, "sess-effort", "high", expectedRevision = 7)
        }
        val params = assertNotNull(mock.lastV4CallParamsForTest("setThoughtLevel"), "setThoughtLevel never reached the mock")
        assertEquals("sess-effort", (params["sessionId"] as JsonPrimitive).content)
        assertEquals("high", (params["thoughtLevel"] as JsonPrimitive).content)
        assertEquals("7", (params["expectedRevision"] as JsonPrimitive).content)
        assertEquals("true", (params["persistAsWorkspaceLastUsed"] as JsonPrimitive).content)
        assertEquals("high", (mock.script.sessionConfig?.get("thought") as? JsonPrimitive)?.content, "the echo plane reflects the new level")
    }
}
