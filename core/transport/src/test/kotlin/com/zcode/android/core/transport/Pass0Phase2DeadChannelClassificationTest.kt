package com.zcode.android.core.transport

import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.serverSpoke
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.bytes.RpcSerialization
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.protocol.channel.ChannelSession
import com.zcode.android.core.protocol.v4.CommandEnvelope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-P7-B (pass-0 Phase 2; the pass-7 shade receipt + pass-13 P.A5): a CLOSED
 * channel is a transport-class loss, never a server verdict. The old
 * generic-failure mapping in [ChannelV4Plane.v4Call]
 * (protocolFault → PROTOCOL — a serverSpoke category) made a dead channel look
 * like an AUTHORITATIVE rejection: the shade approve answered onto a dead
 * socket, logged `interaction.rejected :: channel closed: abnormal close 1006`,
 * and the interaction went FAILED — no retry, no queue, no user face.
 *
 * Post-fix: `Failure.Closed` maps to `transportLoss` (TRANSPORT, serverSpoke
 * FALSE) — the coordinator's outcome-UNKNOWN path (SendLost → RetryableKept,
 * the outbox holds the answer for the retry). A genuine server fault
 * (ChannelError, fault.*) keeps its task-class rejection classification.
 */
class Pass0Phase2DeadChannelClassificationTest {

    private fun envelope(): CommandEnvelope = CommandEnvelope.build(
        clientId = "test-endpoint",
        sessionId = "synth-task-1",
        type = "resolveInteraction",
        payload = buildJsonObject {
            put("interactionId", "i-1")
            put("answer", buildJsonObject { put("optionId", "allowOnce") })
        },
        issuedAt = 1L,
        baseRevision = null,
        baseLogEpoch = null,
        commandId = "cmd-1",
    )

    @Test
    fun `F-P7-B - a closed channel throws TRANSPORT-class (outcome unknown, never a server verdict)`() = runTest {
        val channel = ChannelSession()
        val p = newPlane(channel)
        // The relay died mid-flight (the pass-7 flap: abnormal close 1006).
        channel.close("abnormal close 1006: Broken pipe")

        val failure = runCatching {
            p.sendConversationCommand(envelope(), workspacePath = "/repo", workspaceIdentity = "ws-one")
        }.exceptionOrNull()

        assertTrue("the call must fail with TransportFailure; got $failure", failure is TransportFailure)
        val error = (failure as TransportFailure).error
        assertEquals(ErrorCategory.TRANSPORT, error.category)
        assertFalse(
            "a dead channel is NOT a server-spoke verdict (serverSpoke drove the FAILED store row)",
            error.serverSpoke,
        )
    }

    @Test
    fun `F-P7-B - a genuine server ChannelError keeps its task-class rejection (control)`() = runTest {
        val channel = ChannelSession()
        val p = newPlane(channel)
        // Initialize the channel (the 200 frame) + attach a drop sink so the
        // request bytes go nowhere but the pending call stays live for the fault.
        channel.onFrame(RpcSerialization.encodeAll(listOf(RpcValue.Arr(listOf(RpcValue.Int(200))))))
        channel.onOutbound = { /* drop */ }

        var failure: Throwable? = null
        // UNDISPATCHED: the call registers its pending request before we feed the fault.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching {
                p.sendConversationCommand(envelope(), workspacePath = "/repo", workspaceIdentity = "ws-one")
            }.exceptionOrNull()
        }
        // The server's verdict: PromiseError(202, requestId) with a fault.* payload.
        // The first call's request id is 1 (the session mints 1..n).
        channel.onFrame(
            RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(202), RpcValue.Int(1))),
                    RpcValue.Str("fault.command.rigRefused: rig refused the command"),
                ),
            ),
        )
        job.join()

        assertTrue("the call must fail with TransportFailure; got $failure", failure is TransportFailure)
        val error = (failure as TransportFailure).error
        assertTrue(
            "a genuine fault.* ChannelError stays server-spoke (authoritative rejection); category=${error.category}",
            error.serverSpoke,
        )
    }
}

/** Minimal host so the test can build the internal plane. */
private fun newPlane(channel: ChannelSession): ChannelV4Plane = ChannelV4Plane(
    channel = channel,
    host = object : ChannelV4Plane.Host {
        override fun endpointId(): String? = "test-endpoint"
        override fun workspaceTarget(): Pair<String?, String?>? = "/repo" to "ws-one"
        override fun emitInbound(frame: InboundFrame) = Unit
        override fun onWireVersionMismatch(message: String?) = Unit
        override fun onCountedFault(reason: String) = Unit
        override fun onSerializationFault(reason: String) = Unit
        override fun onCuaPermissionObservation(o: com.zcode.android.core.protocol.v4.CuaPermissionObservation) = Unit
        override fun onV4Call() = Unit
        override fun connectionId(): String? = "conn-1"
        override fun boundLog(): ZLog.Bound = ZLog().with(ZLog.LogContext(endpointId = "test-endpoint"))
    },
    log = ZLog(),
    clock = { 1L },
    timeouts = ChannelV4Plane.Timeouts(
        channelInitTimeoutMs = 1_000,
        v4CallTimeoutMs = 1_000,
        bridgeHandshakeTimeoutMs = 1_000,
        controlRequestTimeoutMs = 1_000,
        authTimeoutMs = 1_000,
    ),
)
