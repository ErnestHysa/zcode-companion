package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * FIELD-KILLER #5 regression (2026-09-30, vc10 real device): the conversation-topic
 * EventListen is scoped to a WORKSPACE, and the bridge scope covers only the pinned
 * workspace. A session in another workspace subscribed fine (mode=snapshot acked) but
 * its INITIAL snapshot was routed to nobody — task 1 (pinned workspace) loaded with
 * rows=60 while tasks in /Users/ernest/ZCodeProject and /Users/ernest/Desktop/pragma
 * never delivered a frame. The gateway now attaches a scoped listener per workspace
 * BEFORE subscribing into it (RelayTransport.ensureConversationListen).
 *
 * The mock enforces the production routing: the snapshot is delivered only when a
 * scoped listener for the subscribe's workspace exists first.
 */
class RelayTransportScopedListenTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 31 })
    private val sid = "d_ScopeRouteSynthetic1"

    @Test
    fun `subscribing a session in another workspace attaches its scoped listen before the snapshot`() = runBlocking {
        val mock = MockZCode(passHash = passHash, expectedSid = sid).apply {
            script.enforceScopeRouting = true
        }
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
        val otherTopicSnapshots = ArrayList<Long>()
        val collector = launch {
            t.inbound.collect {
                if (it is InboundFrame.TopicFrameArrived &&
                    it.frame.topic == "conversation/sess-other-ws" &&
                    it.frame.payload is com.zcode.android.core.protocol.v4.TopicFrame.TopicPayload.Snapshot
                ) otherTopicSnapshots.add(it.frame.toSeq)
            }
        }
        try {
            withTimeout(30_000) {
                t.connect(
                    EndpointDescriptor(
                        id = "ep-scoperoute", kind = EndpointKind.RELAY_ZAI, displayName = "ScopeRoute",
                        wsUrl = url, workspaceKey = mock.workspace.sessionId, workspacePath = "/synthetic/ws-one",
                        deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }

                // A session in ANOTHER workspace: pre-fix, no scoped listener existed for
                // /other/ws and the snapshot was routed to nobody (delivered nothing).
                // The gateway orchestrates ensure-before-subscribe; mirror it here.
                t.ensureConversationListen("/other/ws", null)
                t.subscribeSession("sess-other-ws", visibility = null, workspacePath = "/other/ws", workspaceIdentity = null)
                withTimeout(5_000) {
                    while (otherTopicSnapshots.isEmpty()) delay(25)
                }
                // The scoped listener for the other workspace must have been attached —
                // BEFORE the subscribe (that ordering is what routes the snapshot).
                assertTrue(
                    mock.listenScopesSeen.contains("/other/ws"),
                    "scoped listener for the other workspace missing; scopes seen: ${mock.listenScopesSeen}",
                )
                assertEquals("/synthetic/ws-one", mock.listenScopesSeen.first(), "bridge scope must register first")
                // Idempotence: same scope again must NOT re-listen.
                val before = mock.listenScopesSeen.size
                t.ensureConversationListen("/other/ws", null)
                delay(150)
                assertEquals(before, mock.listenScopesSeen.size, "duplicate scope must not re-register")
            }
        } finally {
            collector.cancel()
            kotlinx.coroutines.runBlocking { t.close("done") }
            mock.stop()
        }
        Unit
    }
}
