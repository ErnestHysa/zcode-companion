package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.model.BackgroundBashOutput
import com.zcode.android.domain.model.BackgroundBashOutputUnavailable
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G15 (vc51) — the background-bash output pins: the typed union decode
 * (background-bash-output.ts) and the wire shape over the REAL RelayTransport ↔
 * MockZCode loopback (`backgroundBashOutputV4 {sessionId, workId}`; a scripted
 * work round-trips, an unscripted work degrades to the typed unavailable member,
 * a channel failure lands null — never a guessed output).
 */
class BackgroundBashOutputTest {

    // ---- decode pins ----

    @Test
    fun `the output member decodes with the bounded snapshot facts`() {
        val decoded = BackgroundBashOutput.decodeResult(JsonObject(mapOf(
            "kind" to JsonPrimitive("output"),
            "workId" to JsonPrimitive("work-1"),
            "status" to JsonPrimitive("running"),
            "output" to JsonPrimitive("line one"),
            "truncated" to JsonPrimitive(true),
            "outputPath" to JsonPrimitive("/tmp/out.log"),
        )))
        val out = decoded as BackgroundBashOutput
        assertEquals("work-1", out.workId)
        assertEquals("running", out.status)
        assertEquals("line one", out.output)
        assertEquals(true, out.truncated)
        assertEquals("/tmp/out.log", out.outputPath)
    }

    @Test
    fun `the unavailable members decode with the workId + code`() {
        val decoded = BackgroundBashOutput.decodeResult(JsonObject(mapOf(
            "kind" to JsonPrimitive("unavailable"),
            "workId" to JsonPrimitive("work-2"),
        ))) as BackgroundBashOutputUnavailable
        assertEquals("unavailable", decoded.kind)
        assertEquals("work-2", decoded.workId)
        assertNull(decoded.code)
    }

    @Test
    fun `an unknown kind decodes to null - never a guessed output`() {
        assertNull(BackgroundBashOutput.decodeResult(JsonObject(mapOf(
            "kind" to JsonPrimitive("mystery"),
            "workId" to JsonPrimitive("work-3"),
        ))))
    }

    // ---- wire pins ----

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val sid: String get() = "d_bbash_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect(scripted: JsonObject) {
        var last: Exception? = null
        for (attempt in 0..2) {
            try {
                mock = MockZCode(passHash, sid)
                mock.script.backgroundBashOutputs["work-scripted"] = scripted
                val url = mock.start()
                transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
                runBlocking {
                    withTimeout(30_000) {
                        transport.connect(
                            EndpointDescriptor(
                                id = "ep-bbash", kind = EndpointKind.RELAY_ZAI, displayName = "BbashMockBook",
                                wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws",
                                deviceSid = sid, deviceMid = "mock-desktop-mid",
                            ),
                            signer(),
                        )
                    }
                }
                return
            } catch (e: Exception) {
                last = e
                runBlocking { runCatching { transport.close("connect retry") } }
                runCatching { mock.stop() }
                Thread.sleep(500)
            }
        }
        throw last ?: IllegalStateException("connect failed")
    }

    private fun signer(): CredentialSigner = CredentialSigner { nonce, role, s ->
        MockZCode.hmacProof(passHash, nonce, role, s)
    }

    @Test
    fun `backgroundBashOutput rides the v4 method and the scripted work round-trips`() {
        connect(JsonObject(mapOf(
            "kind" to JsonPrimitive("output"),
            "workId" to JsonPrimitive("work-scripted"),
            "status" to JsonPrimitive("running"),
            "output" to JsonPrimitive("compiling"),
            "truncated" to JsonPrimitive(false),
            "outputPath" to JsonPrimitive("/synthetic/out.log"),
        )))
        val tools = Phase9Tools(transport)
        val result = runBlocking { withTimeout(15_000) { tools.backgroundBashOutput("sess-1", "work-scripted") } }
        val out = result as BackgroundBashOutput
        assertEquals("compiling", out.output)
        assertEquals("/synthetic/out.log", out.outputPath)
        val call = mock.v4CallsForTest().firstOrNull { it.first == "backgroundBashOutputV4" }
        assertTrue(call != null, "the read must ride backgroundBashOutputV4")
        assertEquals("sess-1", call!!.second["sessionId"]?.toString()?.trim('"'))
        assertEquals("work-scripted", call.second["workId"]?.toString()?.trim('"'))
    }

    @Test
    fun `an unscripted work degrades to the typed unavailable member`() {
        connect(JsonObject(mapOf()))
        val tools = Phase9Tools(transport)
        val result = runBlocking { withTimeout(15_000) { tools.backgroundBashOutput("sess-1", "work-unknown") } }
        val unavailable = result as BackgroundBashOutputUnavailable
        assertEquals("unavailable", unavailable.kind)
        assertEquals("work-unknown", unavailable.workId)
    }
}
