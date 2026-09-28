package com.zcode.android.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/** Socket-less Ws for ingest/replay unit scenarios (no network, no OkHttp client). */
class FakeWs : Ws {
    override suspend fun connect(url: String, timeoutMs: Long) = Unit
    override fun sendText(text: String): Boolean = true
    override fun close(code: Int, reason: String) = Unit
    override val incoming: Flow<String> = emptyFlow()
    override val closed: Flow<WsClosed> = MutableSharedFlow()

    companion object {
        fun Factory(): WsFactory = WsFactory { FakeWs() }
    }
}
