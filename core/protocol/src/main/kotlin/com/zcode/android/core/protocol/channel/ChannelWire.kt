package com.zcode.android.core.protocol.channel

import com.zcode.android.core.protocol.bytes.ProtocolException
import com.zcode.android.core.protocol.bytes.RpcSerialization
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue

/**
 * Channel RPC wire layer — faithful port of the wire shapes in
 * packages/rpc/src/channelClient.ts / channelServer.ts / channels.shared.ts.
 *
 * Client→server request : serialize([reqType, id, channelName, name]) ++ serialize(arg)
 * Client cancel/dispose : serialize([reqType, id])                 ++ serialize(undefined)
 * Server Initialize     : serialize([200])                          ++ serialize(undefined)
 * Server response/event : serialize([respType, id])                 ++ serialize(data)
 */
object ChannelWire {

    enum class RequestType(val id: Int) {
        Promise(100), PromiseCancel(101), EventListen(102), EventDispose(103);
    }

    enum class ResponseType(val id: Int) {
        Initialize(200), PromiseSuccess(201), PromiseError(202), PromiseErrorObj(203), EventFire(204);

        companion object {
            fun from(id: Int): ResponseType? = entries.firstOrNull { it.id == id }
        }
    }

    fun encodeRequest(
        type: RequestType,
        id: Int,
        channelName: String,
        name: String,
        arg: RpcValue,
    ): ByteArray = RpcSerialization.encodeAll(
        listOf(
            RpcValue.Arr(
                listOf(
                    RpcValue.Int(type.id),
                    RpcValue.Int(id),
                    RpcValue.Str(channelName),
                    RpcValue.Str(name),
                )
            ),
            arg,
        )
    )

    fun encodeCancelOrDispose(type: RequestType, id: Int): ByteArray {
        require(type == RequestType.PromiseCancel || type == RequestType.EventDispose)
        return RpcSerialization.encodeAll(
            listOf(
                RpcValue.Arr(listOf(RpcValue.Int(type.id), RpcValue.Int(id))),
                RpcValue.Undefined,
            )
        )
    }

    sealed class Incoming {
        data object Initialize : Incoming()
        data class PromiseSuccess(val id: Int, val data: RpcValue) : Incoming()
        data class PromiseError(val id: Int, val data: RpcValue) : Incoming()
        data class PromiseErrorObj(val id: Int, val data: RpcValue) : Incoming()
        data class EventFire(val id: Int, val data: RpcValue) : Incoming()
        data class Unparsed(val raw: RpcValue) : Incoming()
    }

    fun decodeIncoming(bytes: ByteArray): Incoming {
        val values = RpcSerialization.decodeAll(bytes)
        val header = values.getOrNull(0) as? RpcValue.Arr
            ?: throw ProtocolException("channel frame header is not an array")
        val typeV = header.items.getOrNull(0) as? RpcValue.Int
            ?: throw ProtocolException("channel frame header lacks numeric type")
        val type = ResponseType.from(typeV.value)
            ?: throw ProtocolException("unknown channel ResponseType ${typeV.value}")
        return when (type) {
            ResponseType.Initialize -> Incoming.Initialize
            ResponseType.PromiseSuccess ->
                Incoming.PromiseSuccess(intAt(header, 1), values.getOrNull(1) ?: RpcValue.Undefined)
            ResponseType.PromiseError ->
                Incoming.PromiseError(intAt(header, 1), values.getOrNull(1) ?: RpcValue.Undefined)
            ResponseType.PromiseErrorObj ->
                Incoming.PromiseErrorObj(intAt(header, 1), values.getOrNull(1) ?: RpcValue.Undefined)
            ResponseType.EventFire ->
                Incoming.EventFire(intAt(header, 1), values.getOrNull(1) ?: RpcValue.Undefined)
        }
    }

    private fun intAt(header: RpcValue.Arr, index: Int): Int =
        (header.items.getOrNull(index) as? RpcValue.Int)?.value
            ?: throw ProtocolException("channel frame header lacks numeric id")
}

/**
 * Minimal ChannelClient state machine for the spike: request-id allocation, pending
 * promise map, event-listen registry. Transport-agnostic — the caller feeds it Channel
 * frames already extracted from the relay rpc-frame envelope.
 */
class ChannelClient {

    data class ChannelError(
        val detail: String,
        val name: String?,
        val data: RpcValue?,
    ) : Exception(detail)

    private var lastRequestId = 0
    private val pending = HashMap<Int, PendingRequest>()
    private val listeners = HashMap<Int, (RpcValue) -> Unit>()
    private var initialized = false
    private val queuedActions = ArrayDeque<() -> Unit>()

    /** True once the peer's Initialize arrived; channel actions go out immediately after. */
    @kotlin.jvm.Volatile
    var isInitialized: Boolean = false
        private set

    var onOutbound: ((ByteArray) -> Unit)? = null

    private class PendingRequest {
        var completed = false
        var success: RpcValue? = null
        var error: ChannelError? = null
    }

    /** Pending events fire before Initialize; buffer until then (reference semantics). */
    private val pendingEvents = ArrayDeque<Pair<Int, RpcValue>>()

    fun call(channelName: String, name: String, arg: RpcValue = RpcValue.Undefined): Promise {
        val id = ++lastRequestId
        pending[id] = PendingRequest()
        val encoded = ChannelWire.encodeRequest(ChannelWire.RequestType.Promise, id, channelName, name, arg)
        val action: () -> Unit = { onOutbound?.invoke(encoded) }
        if (initialized) action() else queuedActions.addLast(action)
        return Promise.of(id, this)
    }

    fun listen(channelName: String, event: String, arg: RpcValue = RpcValue.Undefined, handler: (RpcValue) -> Unit): Int {
        val id = ++lastRequestId
        listeners[id] = handler
        val encoded = ChannelWire.encodeRequest(ChannelWire.RequestType.EventListen, id, channelName, event, arg)
        val action: () -> Unit = { onOutbound?.invoke(encoded) }
        if (initialized) action() else queuedActions.addLast(action)
        return id
    }

    fun disposeListener(id: Int) {
        listeners.remove(id)
        onOutbound?.invoke(ChannelWire.encodeCancelOrDispose(ChannelWire.RequestType.EventDispose, id))
    }

    fun cancelCall(id: Int) {
        pending.remove(id)
        onOutbound?.invoke(ChannelWire.encodeCancelOrDispose(ChannelWire.RequestType.PromiseCancel, id))
    }

    fun onFrame(bytes: ByteArray) {
        when (val incoming = ChannelWire.decodeIncoming(bytes)) {
            is ChannelWire.Incoming.Initialize -> {
                initialized = true
                isInitialized = true
                while (queuedActions.isNotEmpty()) queuedActions.removeFirst().invoke()
            }
            is ChannelWire.Incoming.PromiseSuccess -> pending[incoming.id]?.let {
                it.success = incoming.data
                it.completed = true
            }
            is ChannelWire.Incoming.PromiseError,
            is ChannelWire.Incoming.PromiseErrorObj -> {
                val id = when (incoming) {
                    is ChannelWire.Incoming.PromiseError -> incoming.id
                    else -> (incoming as ChannelWire.Incoming.PromiseErrorObj).id
                }
                pending[id]?.let {
                    val body = when (incoming) {
                        is ChannelWire.Incoming.PromiseError -> incoming.data
                        else -> (incoming as ChannelWire.Incoming.PromiseErrorObj).data
                    }
                    // PromiseError body: {message, name, stack[], code?, ...} per the
                    // reference IRawResponse contract — surface message + code.
                    val obj = (body as? RpcValue.Obj)?.json as? kotlinx.serialization.json.JsonObject
                    val message = (obj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: "channel request failed"
                    val code = (obj?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val full = if (code != null) "${message.take(260)} (code=$code)" else message.take(300)
                    it.error = ChannelError(full, null, body)
                    it.completed = true
                }
            }
            is ChannelWire.Incoming.EventFire -> listeners[incoming.id]?.invoke(incoming.data)
            is ChannelWire.Incoming.Unparsed -> Unit
        }
    }

    class Promise private constructor(
        private val id: Int,
        private val client: ChannelClient,
    ) {
        val isDone: Boolean get() = client.pending[id]?.completed == true
        val isSuccess: Boolean get() = isDone && client.pending[id]?.error == null
        val result: RpcValue? get() = client.pending[id]?.success
        val error: ChannelError? get() = client.pending[id]?.error

        fun cancel() = client.cancelCall(id)

        internal companion object {
            fun of(id: Int, client: ChannelClient) = Promise(id, client)
        }
    }
}
