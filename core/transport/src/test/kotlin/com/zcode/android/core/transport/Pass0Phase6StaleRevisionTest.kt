package com.zcode.android.core.transport

import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.serverSpoke
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.bytes.RpcSerialization
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.protocol.channel.ChannelSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-BQ-1 (pass-0 Phase 6; live receipts Part Y.D leg B, Z.E, AA.D): the effort
 * switch rides the PROTOCOL method `setThoughtLevel`, whose host layer CAS-checks
 * `expectedRevision` and answers a JSON-RPC ERROR on mismatch — `Dl(-32009,
 * "Session state revision mismatch", {actualRevision, expectedRevision})` — never
 * a `{status:"stale"}` ack. The phone chain collapsed that error into the generic
 * FAILED face: `v4Call`'s ChannelError arm mapped EVERY error body to
 * `taskRejected(detail)`, so `Ports.setThoughtLevel` could only answer FAILED and
 * the enumerated STALE branch (`SESSION_CHANGED` warning) was structurally dead —
 * three live levels (high/medium/xhigh) all rendered the generic refusal where the
 * switch's whole family renders the honest "try again" warning.
 *
 * Post-fix: the `-32009` identity (the pinned revision-mismatch error code, and
 * its verbatim message as the envelope fallback) propagates as a TYPED
 * [StaleRevisionFailure] — still serverSpoke (the server DID speak: an
 * authoritative stale verdict, never the UNKNOWN/retry class) so every existing
 * TransportFailure consumer keeps its behavior; only the typed catch maps it to
 * the stale face.
 */
class Pass0Phase6StaleRevisionTest {

    private fun staleErrorBody(expected: Long = 100) = buildJsonObject {
        put("message", "Session state revision mismatch")
        put("name", "Error")
        put("code", -32009)
        put("data", buildJsonObject {
            put("actualRevision", 101)
            put("expectedRevision", expected)
        })
    }

    /**
     * The F-P7-B fixture: initialize the channel (the 200 frame), attach a drop
     * sink, launch the call UNDISPATCHED (the pending request registers first),
     * then feed the PromiseError frame with an OBJECT body (the mock's 202+Obj
     * shape — MockZCode.setThoughtLevelStaleErrorBody).
     */
    private suspend fun kotlinx.coroutines.test.TestScope.callAndFeed(
        body: kotlinx.serialization.json.JsonObject,
    ): Throwable {
        val channel = ChannelSession()
        val p = newPlane(channel)
        channel.onFrame(RpcSerialization.encodeAll(listOf(RpcValue.Arr(listOf(RpcValue.Int(200))))))
        channel.onOutbound = { /* drop */ }
        var failure: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching {
                p.setThoughtLevel("/repo", "ws-one", "synth-task-1", "high", expectedRevision = 100)
            }.exceptionOrNull()
        }
        channel.onFrame(
            RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(202), RpcValue.Int(1))),
                    RpcValue.Obj(body),
                ),
            ),
        )
        job.join()
        return assertNotNull(
            failure,
            "the call must fail with the server's error verdict",
        )
    }

    @Test
    fun `N-BQ-1 - the -32009 revision-mismatch error propagates as the typed StaleRevisionFailure`() = runTest {
        val failure = callAndFeed(staleErrorBody())
        assertTrue(
            "the -32009 identity must surface as StaleRevisionFailure; got $failure",
            failure is StaleRevisionFailure,
        )
        val error = (failure as StaleRevisionFailure).error
        assertTrue(
            "an authoritative stale verdict stays server-spoke (never the UNKNOWN/retry class)",
            error.serverSpoke,
        )
        assertEquals(ErrorCategory.TASK, error.category, "the CAS-stale class is a task verdict")
    }

    @Test
    fun `N-BQ-1 - the message fallback catches a body without the code field (envelope-shape tolerance)`() = runTest {
        val failure = callAndFeed(buildJsonObject {
            put("message", "Session state revision mismatch")
        })
        assertTrue(
            "the verbatim host message is the fallback identity; got $failure",
            failure is StaleRevisionFailure,
        )
    }

    @Test
    fun `N-BQ-1 - a DIFFERENT protocol error code keeps the generic task rejection (control)`() = runTest {
        val failure = callAndFeed(buildJsonObject {
            put("message", "session unavailable")
            put("code", -32004)
        })
        assertTrue(failure is TransportFailure)
        assertTrue(
            "non-mismatch codes stay the plain TransportFailure; got $failure",
            failure !is StaleRevisionFailure,
        )
    }
}

/** Minimal host so the test can build the internal plane (the F-P7-B pin's fixture). */
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
