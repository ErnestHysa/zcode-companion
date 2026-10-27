package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.DeliveryKind
import com.zcode.android.core.protocol.v4.SubscribeAck
import com.zcode.android.core.protocol.v4.TopicFrame
import com.zcode.android.core.transport.AppTransport
import com.zcode.android.core.transport.InboundFrame
import com.zcode.android.domain.model.ConversationRow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-CHAT-FOCUS4 (field 2026-10-27, vc50 S23 diagnostics — the "new task stuck
 * loading" report): the desktop DELIVERS the initial snapshot BEFORE the subscribe's
 * RPC response for a fresh session (the frame raced the ack by ~1ms in the field),
 * and the gateway dropped it as "no subscription record" — 0 cached rows, state
 * UNKNOWN, a 90s watchdog escalation, a notOwned resync refusal, and a task that
 * could be created but never viewed (stuck loading on phone AND desktop) and never
 * deleted (desktop matches=0). Pinned: frames racing an in-flight subscribe are
 * BUFFERED and replayed once the ack stamps the epoch.
 */
class GatewayRacingInitialTest {

    private val inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)

    private fun snapshotFrame(sessionId: String) = InboundFrame.TopicFrameArrived(
        topic = "conversation/$sessionId",
        subscriptionId = "sub-1",
        deliveryKind = DeliveryKind.INITIAL,
        fromSeq = 0,
        toSeq = 2,
        frame = TopicFrame(
            topic = "conversation/$sessionId",
            subscriptionId = "sub-1",
            fromSeq = 0,
            toSeq = 2,
            sentAt = 1.0,
            payload = TopicFrame.TopicPayload.Snapshot(
                buildJsonObject {
                    put("rows", buildJsonObject {
                        put("window", kotlinx.serialization.json.JsonArray(listOf(rowJson(1))))
                        put("totalCount", JsonPrimitive(1))
                        put("firstRowId", JsonPrimitive(1))
                    })
                },
            ),
        ),
    )

    private fun rowJson(id: Long) = buildJsonObject {
        put("rowId", JsonPrimitive(id))
        put("turnId", JsonPrimitive("t-$id"))
        put("kind", JsonPrimitive("userInput"))
        put("createdAt", JsonPrimitive(1.0))
        put("createdAtSeq", JsonPrimitive(id))
        put("text", JsonPrimitive("hello"))
        put("origin", JsonPrimitive("user"))
        put("guided", JsonPrimitive(false))
    }

    /**
     * A Proxy transport whose subscribeSession DELIVERS the racing initial frame
     * (emits into [inbound], which the gateway's forwarding collector consumes)
     * BEFORE returning the ack — the exact field ordering.
     */
    private fun racingTransport(): AppTransport {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "inbound", "getInbound" -> inbound
                "ensureConversationListen" -> Unit
                "subscribeSession" -> {
                    // THE FIELD ORDER: snapshot first, ack second.
                    inbound.tryEmit(snapshotFrame("sess-race"))
                    SubscribeAck("sub-1", "snapshot", "epoch-race-1")
                }
                "subscriptionEpoch" -> null
                "rowsRange" -> throw UnsupportedOperationException()
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            AppTransport::class.java.classLoader, arrayOf(AppTransport::class.java), handler,
        ) as AppTransport
    }

    @Test
    fun `initial frame racing the subscribe ack is buffered and replayed - never dropped`() = runTest {
        val gateway = RelayConversationGateway(racingTransport(), clientId = "test", traceLog = null)
        val scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.Job())
        gateway.startForwarding(scope)
        try {
            val events = java.util.concurrent.CopyOnWriteArrayList<GatewayTopicEvent>()
            val collector = launch { gateway.topicEvents.collect { events.add(it) } }
            testScheduler.runCurrent()

            gateway.subscribe("sess-race", workspacePath = "/ws", workspaceIdentity = "ws-key")
            testScheduler.runCurrent()

            val snapshot = events.filterIsInstance<GatewayTopicEvent.Snapshot>().firstOrNull()
            assertTrue(snapshot != null, "the racing INITIAL must be replayed after the ack (field: dropped → stuck loading)")
            assertEquals("sess-race", snapshot!!.sessionId)
            assertEquals("epoch-race-1", snapshot.logEpoch, "the replayed frame carries the ACK-stamped epoch")
            assertEquals(1, snapshot.rows.size)
            assertEquals(ConversationRow.Decoder.decode(rowJson(1)).rowId, snapshot.rows.single().rowId)
            collector.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `frames for a session nobody subscribed stay dropped`() = runTest {
        val gateway = RelayConversationGateway(racingTransport(), clientId = "test", traceLog = null)
        val scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.Job())
        gateway.startForwarding(scope)
        try {
            val events = java.util.concurrent.CopyOnWriteArrayList<GatewayTopicEvent>()
            val collector = launch { gateway.topicEvents.collect { events.add(it) } }
            testScheduler.runCurrent()

            // No subscribe in flight: the frame is unsolicited for THIS gateway — drop.
            inbound.tryEmit(snapshotFrame("sess-unknown"))
            testScheduler.runCurrent()

            assertEquals(
                0,
                events.filterIsInstance<GatewayTopicEvent.Snapshot>().size,
                "unsolicited frames must stay dropped (the §28.4 routing contract)",
            )
            collector.cancel()
        } finally {
            scope.cancel()
        }
    }
}
