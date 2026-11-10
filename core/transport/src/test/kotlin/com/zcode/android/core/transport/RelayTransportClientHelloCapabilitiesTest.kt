package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * G48 (vc52 Phase 11): the phone DECLARES its capabilities in the clientHello.
 * The shipped desktop's strict clientHelloSchema accepts exactly
 * {workspaceHookReviewUi, workflowRunDeltas, historyFirst} and
 * `clientSupportsWorkflowRunDeltas = capabilities?.workflowRunDeltas === true`
 * gates the workflowRun delta stream — the phone omitted the object entirely
 * (V4Plane initializeV4 pre-fix), so the delta stream never enabled and the ~8s
 * `conversationWorkflowRunsV4` poll (pass-23: 291 calls/40 min) was the symptom.
 *
 * The mock now RECORDS the declared capabilities (the enforcing seam); this pin
 * asserts the wire carries the declaration on the live handshake path.
 */
class RelayTransportClientHelloCapabilitiesTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 17 })
    private val sid = "d_HelloCapsSynthetic1"

    @Test
    fun `the phone hello declares workflowRunDeltas and workspaceHookReviewUi`() = runBlocking {
        val mock = MockZCode(passHash = passHash, expectedSid = sid)
        val url = mock.start()
        val t = RelayTransport(
            log = ZLog(sink = {}),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
                channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
                v4CallTimeoutMs = 5_000,
            ),
        )
        try {
            withTimeout(30_000) {
                t.connect(
                    EndpointDescriptor(
                        id = "ep-hellocaps", kind = EndpointKind.RELAY_ZAI, displayName = "HelloCaps",
                        wsUrl = url, workspaceKey = mock.workspace.sessionId, workspacePath = "/synthetic/ws-one",
                        deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                // The connect ladder drives helloConversationV4 + initializeConversationV4
                // (helloAndInitialize); the mock has recorded the clientHello by now.
                val caps = mock.lastClientHelloCapabilities
                assertEquals(
                    true,
                    caps?.get("workflowRunDeltas")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrictOrNull() },
                    "the hello must declare workflowRunDeltas=true (the delta-stream gate); saw $caps",
                )
                assertEquals(
                    true,
                    caps?.get("workspaceHookReviewUi")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrictOrNull() },
                    "the sibling capability bit rides the same declaration; saw $caps",
                )
            }
        } finally {
            kotlinx.coroutines.runBlocking { t.close("test done") }
            mock.stop()
        }
    }
}
