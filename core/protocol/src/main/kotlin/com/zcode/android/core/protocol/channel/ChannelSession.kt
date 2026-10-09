package com.zcode.android.core.protocol.channel

import com.zcode.android.core.protocol.bytes.ProtocolException
import com.zcode.android.core.protocol.bytes.RpcSerialization
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Coroutine-native Channel client — production-grade completion of §13.2 layer 3:
 * Promise calls suspend until the peer answers, timeouts map to PromiseCancel
 * (reference semantics), EventListen registers handlers that fire on EventFire.
 *
 * Wire encoders are shared with [ChannelWire]; this class only owns the request-id
 * space, pending map, and event registry. Thread model: [onFrame] is fed from the
 * transport's single reader (ordered, per connection — §10.2); calls arrive from
 * arbitrary coroutines.
 */
class ChannelSession(
    private val callTimeoutMsDefault: Long = 10_000L,
) {
    sealed class Failure(message: String) : Exception(message) {
        class ChannelError(val detail: String, val body: RpcValue?) : Failure(detail)
        class TimedOut(val channelName: String, val name: String, val timeoutMs: Long) :
            Failure("channel call $channelName.$name timed out after ${timeoutMs}ms")
        class NotInitialized : Failure("channel not initialized")
        class Closed(val reason: String) : Failure("channel closed: $reason")
    }

    private val lastRequestId = AtomicInteger(0)
    private val pending = HashMap<Int, CompletableDeferred<RpcValue>>()
    private val listeners = HashMap<Int, (RpcValue) -> Unit>()

    private val outboundRef = AtomicReference<((ByteArray) -> Unit)?>(null)
    @Volatile private var initialized: Boolean = false
    @Volatile private var closedReason: String? = null
    private val initializeSignal = CompletableDeferred<Unit>()
    private val lock = Any()

    var onOutbound: ((ByteArray) -> Unit)?
        get() = outboundRef.get()
        set(value) { outboundRef.set(value) }

    /** True once the peer's Initialize arrived; channel actions go out immediately after. */
    val isInitialized: Boolean get() = initialized

    /** Feed one decoded Channel frame (transport extracts these from rpc-frames). */
    fun onFrame(bytes: ByteArray) {
        when (val incoming = ChannelWire.decodeIncoming(bytes)) {
            is ChannelWire.Incoming.Initialize -> {
                synchronized(lock) {
                    initialized = true
                }
                initializeSignal.complete(Unit)
            }
            is ChannelWire.Incoming.PromiseSuccess -> complete(incoming.id, incoming.data)
            is ChannelWire.Incoming.PromiseError -> failWith(incoming.id, incoming.data)
            is ChannelWire.Incoming.PromiseErrorObj -> failWith(incoming.id, incoming.data)
            is ChannelWire.Incoming.EventFire -> listeners[incoming.id]?.invoke(incoming.data)
            is ChannelWire.Incoming.Unparsed -> Unit // INV-8: counted upstream, never crash
        }
    }

    private fun complete(id: Int, data: RpcValue) {
        val deferred = synchronized(lock) { pending.remove(id) } ?: return
        deferred.complete(data)
    }

    private fun failWith(id: Int, body: RpcValue) {
        val deferred = synchronized(lock) { pending.remove(id) } ?: return
        val obj = (body as? RpcValue.Obj)?.json as? kotlinx.serialization.json.JsonObject
        val message = (obj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: "channel request failed"
        deferred.completeExceptionally(Failure.ChannelError(message.take(300), body))
    }

    private fun send(bytes: ByteArray) {
        outboundRef.get()?.invoke(bytes) ?: throw Failure.Closed("no outbound sink attached")
    }

    /** Suspend until the peer's Initialize arrives (or timeout / closed). */
    suspend fun awaitInitialize(timeoutMs: Long) {
        if (initialized) return
        closedReason?.let { throw Failure.Closed(it) }
        try {
            withTimeout(timeoutMs) { initializeSignal.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw Failure.TimedOut("(initialize)", "(initialize)", timeoutMs)
        }
    }

    suspend fun call(
        channelName: String,
        name: String,
        arg: RpcValue = RpcValue.Undefined,
        timeoutMs: Long = callTimeoutMsDefault,
    ): RpcValue {
        closedReason?.let { throw Failure.Closed(it) }
        val id = lastRequestId.incrementAndGet()
        val deferred = CompletableDeferred<RpcValue>()
        synchronized(lock) { pending[id] = deferred }
        try {
            send(ChannelWire.encodeRequest(ChannelWire.RequestType.Promise, id, channelName, name, arg))
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            cancel(id)
            throw Failure.TimedOut(channelName, name, timeoutMs)
        } catch (e: Throwable) {
            if (e is Failure.ChannelError) {
                // Server answered with an error — no cancel needed (request completed).
                throw e
            }
            cancel(id)
            throw e
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    /** Register an event listener; returns the listener id for [disposeListener]. */
    fun listen(
        channelName: String,
        event: String,
        arg: RpcValue = RpcValue.Undefined,
        handler: (RpcValue) -> Unit,
    ): Int {
        val id = lastRequestId.incrementAndGet()
        synchronized(lock) { listeners[id] = handler }
        send(ChannelWire.encodeRequest(ChannelWire.RequestType.EventListen, id, channelName, event, arg))
        return id
    }

    fun disposeListener(id: Int) {
        synchronized(lock) { listeners.remove(id) }
        runCatching { send(ChannelWire.encodeCancelOrDispose(ChannelWire.RequestType.EventDispose, id)) }
    }

    fun cancel(id: Int) {
        synchronized(lock) { pending.remove(id) }
        runCatching { send(ChannelWire.encodeCancelOrDispose(ChannelWire.RequestType.PromiseCancel, id)) }
    }

    /** Terminal: fail all pending; the transport reconnects and rebuilds the session. */
    fun close(reason: String) {
        synchronized(lock) {
            if (closedReason == null) closedReason = reason
            val pendingCopy = pending.toMap()
            pending.clear()
            listeners.clear()
            for ((_, deferred) in pendingCopy) deferred.completeExceptionally(Failure.Closed(reason))
        }
        runCatching { initializeSignal.completeExceptionally(Failure.Closed(reason)) }
    }
}
