package com.zcode.android.smoke

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.zcode.android.core.protocol.channel.ChannelWire
import com.zcode.android.core.protocol.relay.AuthInit
import com.zcode.android.core.protocol.relay.AuthMeta
import com.zcode.android.core.protocol.relay.AuthResponse
import com.zcode.android.core.protocol.relay.BootstrapRequest
import com.zcode.android.core.protocol.relay.RelayFrameCodec
import com.zcode.android.core.protocol.relay.WorkspaceBridgeOpen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Phase 1.7 Android device smoke (throwaway, S43.0 no-UI rule): proves on-device
 * (1) AndroidKeyStore HMAC proof identity vs the desktop construction,
 * (2) OkHttp WebSocket + the relay handshake,
 * (3) control-plane envelope flow + rpc-frame/Channel decode.
 * Synthetic credentials only (INV-15). Not product UI.
 */
class SmokeActivity : Activity() {
    private lateinit var out: TextView
    private val passHashB64 = "c3ludGhldGljLXBhc3NoYXNoLWtleQ==" // base64 of a synthetic string
    private val sid = "d_syntheticdevice000001"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this)
        out.setPadding(32, 32, 32, 32)
        out.textSize = 12f
        setContentView(out)
        Thread { run() }.start()
    }

    private fun log(line: String) {
        android.util.Log.i("ZCODE-SMOKE", line)
        runOnUiThread { out.append("$line\n") }
    }

    private fun run() {
        try {
            val wsUrl = "ws://10.0.2.2:18100"
            val keystoreProof = keystoreVectorTest()
            log("STEP1 keystore-hmac-vector: " + if (keystoreProof) "PASS" else "FAIL")
            if (!keystoreProof) {
                log("SMOKE RESULT: FAILED")
                return
            }
            val ok = mockHandshakeCycle(wsUrl)
            log("STEP2 handshake-bootstrap-frame: " + if (ok) "PASS" else "FAIL")
            log("SMOKE RESULT: " + if (ok) "ALL PASS" else "FAILED")
        } catch (t: Throwable) {
            log("SMOKE EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
            android.util.Log.e("ZCODE-SMOKE", "fatal", t)
        }
    }

    /** Import the passHash string as an AndroidKeyStore HmacSHA256 key; proof must equal host vector. */
    private fun keystoreVectorTest(): Boolean {
        val alias = "spike-hmac"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(alias)
        // Phase 1 verification of S12.4 import semantics: the HMAC key is the UTF-8 bytes
        // of the base64 hash STRING (Node createHmac("sha256", <string>)), NOT the decoded
        // digest bytes. Import at pairing = import the string bytes.
        val raw = passHashB64.toByteArray(Charsets.UTF_8)
        val secret = SecretKeySpec(raw, "HmacSHA256")
        // Platform finding (2026-09-28, API 30 ATD): AndroidKeyStore REQUIRES protection
        // parameters when importing symmetric keys — a null ProtectionParameter throws.
        val protection = android.security.keystore.KeyProtection.Builder(
            android.security.keystore.KeyProperties.PURPOSE_SIGN or
                android.security.keystore.KeyProperties.PURPOSE_VERIFY)
            .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
            .build()
        ks.setEntry(alias, java.security.KeyStore.SecretKeyEntry(secret), protection)
        val loaded = ks.getKey(alias, null) as SecretKey
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(loaded)
        val nonce = "nonce-12345"
        val msg = "$nonce|terminal|$sid"
        val proof = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
        val expected = javaxProof(passHashB64, nonce, sid)
        log("  proof=${proof.take(8)}… expected=${expected.take(8)}…")
        return proof == expected
    }

    private fun javaxProof(passHash: String, nonce: String, sid: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("$nonce|terminal|$sid".toByteArray(Charsets.UTF_8)))
    }

    /** Full cycle against the host mock relay. Returns true when a Channel frame decodes. */
    private fun mockHandshakeCycle(wsUrl: String): Boolean {
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val jsonTolerant = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val assembler = RelayFrameCodec.Assembler()
        val bridgeSessionId = RelayFrameCodec.newBridgeSessionId()
        var frameDecoded = false
        var matched = false
        var bootstrapSent = false
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder().url(wsUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(json.encodeToString(
                    AuthInit(role = "terminal", deviceSid = sid,
                        meta = AuthMeta(platform = "android", version = "1.0", name = "smoke"),
                        clientTs = System.currentTimeMillis())))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = jsonTolerant.parseToJsonElement(text).jsonObject
                    when ((obj["type"] as? JsonPrimitive)?.content) {
                        "auth_challenge" -> {
                            val nonce = (obj["nonce"] as? JsonPrimitive)?.content ?: return
                            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                            val key = ks.getKey("spike-hmac", null) as SecretKey
                            val mac = Mac.getInstance("HmacSHA256")
                            mac.init(key)
                            val proof = java.util.Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(mac.doFinal("$nonce|terminal|$sid".toByteArray(Charsets.UTF_8)))
                            webSocket.send(json.encodeToString(
                                AuthResponse(deviceSid = sid, proof = proof, clientTs = System.currentTimeMillis())))
                        }
                        "auth_ack", "pair_status_ack" -> {
                            if ((obj["pair_status"] as? JsonPrimitive)?.content == "matched") matched = true
                        }
                        "data" -> {
                            val results = assembler.handleEnvelope(text)
                            for (r in results) {
                                if (r is RelayFrameCodec.Reassembled.Message) {
                                    val parsed = ChannelWire.decodeIncoming(r.payload)
                                    if (parsed is ChannelWire.Incoming.EventFire) frameDecoded = true
                                }
                            }
                        }
                        else -> Unit
                    }
                } catch (t: Throwable) {
                    log("  onMessage error: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("  ws failure: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        val ws = client.newWebSocket(request, listener)

        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            if (matched && !bootstrapSent) {
                bootstrapSent = true
                ws.send(RelayFrameCodec.encodeEnvelope(json.encodeToString(BootstrapRequest(requestId = "smoke-1"))))
                Thread.sleep(300)
                ws.send(RelayFrameCodec.encodeEnvelope(
                    json.encodeToString(WorkspaceBridgeOpen(
                        requestId = "smoke-bridge", bridgeSessionId = bridgeSessionId,
                        workspaceKey = "synthetic-ws-one"))))
            }
            if (frameDecoded) break
            Thread.sleep(100)
        }
        ws.close(1000, "done")
        return frameDecoded
    }
}
