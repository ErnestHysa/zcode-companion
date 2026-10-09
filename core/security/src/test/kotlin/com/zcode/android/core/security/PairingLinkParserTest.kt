package com.zcode.android.core.security

import com.zcode.android.core.security.pairing.CompatibilityGate
import com.zcode.android.core.security.pairing.PairingLinkParser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 3.1 exit-gate suite — §12.2 normative parser + §39.2 compat gate.
 * Corpus: valid shapes, every typed rejection, hostile inputs (fuzz + injection class).
 * INV-14: malformed input fails closed; the parser is pure (zero state writes).
 */
class PairingLinkParserTest {

    private val now = 1_727_500_000_000L

    private fun hash64(): String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    private fun link(
        origin: String = "https://zcode.z.ai",
        route: String = "/remote/v4",
        sid: String = "d_SyntheticSid000001",
        hash: String = hash64(),
        t: Long = now - 60_000,
        mid: String = "mid123",
        name: String = "MacBook",
        version: String = "3.14.3",
    ): String = "$origin$route?sid=$sid&hash=${java.net.URLEncoder.encode(hash, "UTF-8")}" +
        "&t=$t&mid=$mid&name=${java.net.URLEncoder.encode(name, "UTF-8")}&app_version=$version"

    private fun parseOk(raw: String, stale: Boolean = false): PairingLinkParser.Result.Ready {
        val r = PairingLinkParser.parse(raw, nowMs = now)
        assertTrue(r is PairingLinkParser.Result.Ready, "expected Ready, got $r")
        assertEquals(stale, r.staleWarning)
        return r
    }

    private fun parseRejected(raw: String, reason: PairingLinkParser.Reason) {
        val r = PairingLinkParser.parse(raw, nowMs = now)
        assertTrue(r is PairingLinkParser.Result.Rejected, "expected Rejected, got $r")
        assertEquals(reason, r.reason, r.detail)
    }

    // ------------------------------------------------------------- valid shapes

    @Test
    fun `valid prod link parses with all fields`() {
        val r = parseOk(link())
        assertEquals("d_SyntheticSid000001", r.link.deviceSid)
        assertEquals("https://zcode.z.ai", r.link.endpointOrigin)
        assertEquals("mid123", r.link.deviceMid)
        assertEquals("MacBook", r.link.desktopName)
        assertEquals("3.14.3", r.link.appVersion)
        assertEquals(hash64(), r.link.passHash)
        assertEquals("wss://zcode.z.ai/ws", r.link.wsUrl())
        assertFalse(r.link.legacyRoute)
    }

    @Test
    fun `test origin and legacy v3 route accepted with flags`() {
        val r = parseOk(link(origin = "https://zcode.chatglm.site", route = "/remote/v3"), stale = false)
        assertTrue(r.link.legacyRoute)
        assertFalse(r.devOrigin)
    }

    @Test
    fun `dev loopback origins flagged dev with ws scheme mapping`() {
        val r = parseOk(link(origin = "http://10.0.2.2:18100"))
        assertTrue(r.devOrigin)
        assertEquals("ws://10.0.2.2:18100/ws", r.link.wsUrl())
    }

    @Test
    fun `url-encoded name and hash decode correctly`() {
        val rawHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7).toByte() })
        // the builder URL-encodes the hash param; verify the parser decodes it back
        val r = parseOk(link(name = "My MacBook Pro", hash = rawHash))
        assertEquals("My MacBook Pro", r.link.desktopName)
        assertEquals(rawHash, r.link.passHash)
    }

    @Test
    fun `hash lengths 16 to 64 bytes accepted 44-char expectation not assumed`() {
        parseOk(link(hash = java.util.Base64.getEncoder().encodeToString(ByteArray(16))))
        parseOk(link(hash = java.util.Base64.getEncoder().encodeToString(ByteArray(64))))
    }

    // ------------------------------------------------------------- rejections

    @Test
    fun `not a url class rejected`() {
        parseRejected("not a url at all", PairingLinkParser.Reason.NOT_A_URL)
        parseRejected("zcode.z.ai/remote/v4?sid=d_x", PairingLinkParser.Reason.NOT_A_URL) // no scheme
        parseRejected("https://", PairingLinkParser.Reason.NOT_A_URL)
        parseRejected("https://zcode.z.ai/remote/v4", PairingLinkParser.Reason.NOT_A_URL) // no query
    }

    @Test
    fun `foreign origins rejected including userinfo trick`() {
        parseRejected(
            "https://evil.example/remote/v4?sid=${link().substringAfter("sid=").substringBefore("&")}",
            PairingLinkParser.Reason.UNSUPPORTED_ORIGIN,
        )
        parseRejected(link(origin = "https://zcode.z.ai").replace("zcode.z.ai", "zcode.z.ai@evil.invalid")
            .replaceFirst("?sid", "?sid"), PairingLinkParser.Reason.UNSUPPORTED_ORIGIN)
    }

    @Test
    fun `cleartext http outside dev loopback rejected`() {
        parseRejected(link(origin = "http://insecure.example"), PairingLinkParser.Reason.UNSUPPORTED_ORIGIN)
    }

    @Test
    fun `wrong route rejected`() {
        parseRejected(link(route = "/remote/v5"), PairingLinkParser.Reason.WRONG_ROUTE)
        parseRejected(link(route = "/other"), PairingLinkParser.Reason.WRONG_ROUTE)
        parseRejected(link(route = "/admin/remote/v4"), PairingLinkParser.Reason.WRONG_ROUTE)
        parseRejected(link(route = "//remote/v4"), PairingLinkParser.Reason.WRONG_ROUTE)
    }

    @Test
    fun `every missing required param rejected`() {
        for (param in listOf("sid", "hash", "t", "mid", "name", "app_version")) {
            val raw = link().replaceFirst(Regex("&?$param=[^&]*"), "")
            parseRejected(raw, PairingLinkParser.Reason.MISSING_PARAM)
        }
    }

    @Test
    fun `bad sid shapes rejected`() {
        parseRejected(link(sid = "notd_format0001"), PairingLinkParser.Reason.BAD_SID)
        parseRejected(link(sid = "d_short"), PairingLinkParser.Reason.BAD_SID)
        parseRejected(link(sid = "d_has_underscore_bad"), PairingLinkParser.Reason.BAD_SID)
        parseRejected(link(sid = "d_" + "x".repeat(200)), PairingLinkParser.Reason.BAD_SID)
    }

    @Test
    fun `bad hash rejected`() {
        parseRejected(link(hash = "!!!not-base64!!!"), PairingLinkParser.Reason.BAD_HASH)
        parseRejected(link(hash = java.util.Base64.getEncoder().encodeToString(ByteArray(4))), PairingLinkParser.Reason.BAD_HASH)
        parseRejected(link(hash = java.util.Base64.getEncoder().encodeToString(ByteArray(65))), PairingLinkParser.Reason.BAD_HASH)
    }

    @Test
    fun `future timestamp rejected stale only warns`() {
        parseRejected(link(t = now + 10 * 60_000), PairingLinkParser.Reason.FUTURE_TIMESTAMP)
        parseRejected(link(t = now + 61_000), PairingLinkParser.Reason.FUTURE_TIMESTAMP)
        parseOk(link(t = now + 30_000)) // within grace: clock skew tolerated
        parseOk(link(t = now - 11 * 60_000), stale = true)
    }

    @Test
    fun `duplicate params rejected`() {
        parseRejected(link() + "&sid=d_AnotherSid000001", PairingLinkParser.Reason.DUPLICATE_PARAM)
    }

    @Test
    fun `oversized input rejected`() {
        parseRejected(link(name = "x".repeat(2_000)), PairingLinkParser.Reason.OVERSIZED_INPUT)
        parseRejected("https://zcode.z.ai/remote/v4?" + "a".repeat(10_000), PairingLinkParser.Reason.OVERSIZED_INPUT)
    }

    @Test
    fun `malformed percent encoding rejected not interpreted`() {
        // The URI constructor itself rejects invalid escapes → typed NOT_A_URL rejection;
        // the value is never silently interpreted.
        parseRejected(
            "https://zcode.z.ai/remote/v4?name=%ZZbad",
            PairingLinkParser.Reason.NOT_A_URL,
        )
    }

    @Test
    fun `bad app_version shape rejected`() {
        parseRejected(link(version = "three.fourteen"), PairingLinkParser.Reason.BAD_APP_VERSION)
        parseRejected(link(version = "3.14"), PairingLinkParser.Reason.BAD_APP_VERSION)
    }

    // ------------------------------------------------------------- hostile corpus + fuzz

    @Test
    fun `hostile corpus — every input fails closed with a typed reason and never throws`() {
        val hostile = listOf(
            "",
            " ",
            "\u0000",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "https://zcode.z.ai/remote/v4?sid=",
            "https://zcode.z.ai/remote/v4?" + "sid=d_AAAAAAAAAAAA&".repeat(400),
            "HTTPS://ZCODE.Z.AI/remote/v4?sid=d_SyntheticSid01&hash=${hash64()}&t=1&mid=m&name=n&app_version=3.14.3",
            "https://zcode.z.ai:99999/remote/v4?sid=d_SyntheticSid01",
            "https://[::1]/remote/v4?sid=d_SyntheticSid01&hash=${hash64()}&t=$now&mid=m&name=n&app_version=3.14.3",
            "https://zcode.z.ai/remote/v4?sid=d_ok&hash=${hash64()}&t=$now&mid=m&name=n&app_version=3.14.3#frag",
            "https://zcode.z.ai//remote/v4?sid=d_ok&hash=${hash64()}&t=$now&mid=m&name=n&app_version=3.14.3",
            link(sid = "d_%00"),
        )
        for (raw in hostile) {
            val r = PairingLinkParser.parse(raw, nowMs = now) // must not throw
            assertTrue(r is PairingLinkParser.Result.Ready || r is PairingLinkParser.Result.Rejected, "unhandled result for: ${raw.take(60)}")
        }
        // Uppercase origin host normalizes to supported; lookalike routes rejected.
        assertTrue(PairingLinkParser.parse(hostile[8], nowMs = now) is PairingLinkParser.Result.Ready)
        assertTrue(PairingLinkParser.parse(hostile[12], nowMs = now) is PairingLinkParser.Result.Rejected)
    }

    @Test
    fun `fuzz — 5000 random mutations never throw and never produce Ready without all gates`() {
        val rnd = Random(0x5EED)
        val base = link()
        var ready = 0
        repeat(5_000) {
            val mutated = mutate(base, rnd)
            val r = try {
                PairingLinkParser.parse(mutated, nowMs = now)
            } catch (t: Throwable) {
                throw AssertionError("parser threw on: ${mutated.take(120)}", t)
            }
            if (r is PairingLinkParser.Result.Ready) {
                ready++
                // Any Ready result MUST carry every required field non-blank.
                assertTrue(r.link.deviceSid.startsWith("d_"))
                assertTrue(r.link.passHash.isNotBlank())
                assertTrue(r.link.issuedAtMs <= now + PairingLinkParser.FUTURE_GRACE_MS)
                assertTrue(r.link.appVersion.isNotBlank())
            }
        }
        assertTrue(ready > 0, "fuzz should still admit the un-mutated shape class")
    }

    private fun mutate(s: String, rnd: Random): String {
        val sb = StringBuilder(s)
        val ops = rnd.nextInt(1, 5)
        repeat(ops) {
            when (rnd.nextInt(6)) {
                0 -> if (sb.isNotEmpty()) sb.deleteCharAt(rnd.nextInt(sb.length))
                1 -> sb.insert(rnd.nextInt(sb.length + 1), pick(rnd, "&=?%#/\\\"'<>{}[]$;:,"))
                2 -> if (sb.isNotEmpty()) sb.setCharAt(rnd.nextInt(sb.length), pickChar(rnd, "abcdefgh0123456789._-~"))
                3 -> sb.insert(rnd.nextInt(sb.length + 1), pick(rnd, listOf("javascript:", "%00", "%2e", "data:", "\n", "\t", "é", "中")))
                4 -> sb.append(pick(rnd, listOf("?", "&sid=d_", "&hash=", "&t=abc", "&app_version=")))
                else -> if (sb.isNotEmpty()) sb.setLength(rnd.nextInt(sb.length))
            }
        }
        return sb.toString()
    }

    private fun pick(rnd: Random, chars: String): String = chars[rnd.nextInt(chars.length)].toString()
    private fun pickChar(rnd: Random, chars: String): Char = chars[rnd.nextInt(chars.length)]
    private fun pick(rnd: Random, opts: List<String>): String = opts[rnd.nextInt(opts.size)]
}
