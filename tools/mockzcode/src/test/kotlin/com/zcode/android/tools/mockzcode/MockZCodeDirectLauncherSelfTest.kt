package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RIG-P4 (vc52, pulled forward into Phase 6 for F-37-A's exit-gate leg): the launcher's
 * MOCK_MODE=direct switch serves the `zcode --web` DIRECT surface — the
 * docs/protocol/phase10-direct.md §1–§2 contract the phone's add-endpoint probe and
 * DirectTransport speak: `GET /api/server-info?token=` → 200 + the HttpOnly
 * `zcode_lite_token` cookie, a wrong or missing token → 401. The relay default is
 * untouched. Synthetic content only (INV-15/16).
 */
class MockZCodeDirectLauncherSelfTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun get(url: String): Pair<Int, HttpURLConnection> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        return conn.responseCode to conn
    }

    @Test
    fun `MOCK_MODE=direct selects the DIRECT surface on 18200 with the configured token`() {
        val target = launcherTarget(env("MOCK_MODE" to "direct", "MOCK_DIRECT_TOKEN" to "rig-tok-1"), passHash, "d_rig")
        assertTrue(target.direct)
        assertEquals(18_200, target.port)
        assertEquals(MockZCode.Mode.DIRECT, target.mock.mode)
        assertEquals("rig-tok-1", target.mock.serverToken)
        assertEquals(
            "rig-direct-token",
            launcherTarget(env("MOCK_MODE" to "DIRECT"), passHash, "d_rig").mock.serverToken,
            "the default token when MOCK_DIRECT_TOKEN is absent (mode matched case-insensitively)",
        )
    }

    @Test
    fun `without MOCK_MODE the launcher is the unchanged relay rig on 18100`() {
        val target = launcherTarget(env(), passHash, "d_rig")
        assertFalse(target.direct)
        assertEquals(18_100, target.port)
        assertEquals(MockZCode.Mode.RELAY, target.mock.mode)
        assertEquals(19_001, launcherTarget(env("MOCK_PORT" to "19001"), passHash, "d_rig").port)
    }

    @Test
    fun `the direct surface honors the zcode_lite_token contract over the raw server`() {
        val port = freePort()
        val target = launcherTarget(
            env("MOCK_MODE" to "direct", "MOCK_DIRECT_TOKEN" to "rig-tok-2", "MOCK_PORT" to port.toString()),
            passHash,
            "d_rig",
        )
        val url = target.mock.startRaw(target.port)
        try {
            assertEquals("ws://127.0.0.1:$port/ws", url)
            val (ok, conn) = get("http://127.0.0.1:$port/api/server-info?token=rig-tok-2")
            assertEquals(200, ok)
            val cookie = conn.getHeaderField("Set-Cookie").orEmpty()
            assertTrue(cookie.startsWith("zcode_lite_token=rig-tok-2"), "server-info sets the HttpOnly cookie: $cookie")
            val info = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            val path = ((info["workspaces"] as JsonArray).first() as JsonObject)["path"] as JsonPrimitive
            assertEquals("/synthetic/direct-ws", path.content)
            assertEquals(401, get("http://127.0.0.1:$port/api/server-info?token=wrong").first)
            assertEquals(401, get("http://127.0.0.1:$port/api/server-info").first)
        } finally {
            target.mock.stop()
        }
    }
}
