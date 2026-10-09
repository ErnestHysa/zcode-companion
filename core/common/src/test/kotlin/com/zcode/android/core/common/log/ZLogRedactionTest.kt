package com.zcode.android.core.common.log

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * INV-3 proof (§34): no registered secret substring ever appears in any output, and
 * structural key-value scrubbers hold on adversarial inputs. Property-style with a
 * fixed seed for reproducibility.
 */
class ZLogRedactionTest {

    private fun capturingLog(minLevel: ZLog.LogLevel = ZLog.LogLevel.DEBUG): Pair<ZLog, MutableList<ZLog.LogEntry>> {
        val out = mutableListOf<ZLog.LogEntry>()
        val log = ZLog(minLevel = minLevel, clock = { 1_000L }, sink = { out.add(it) })
        return log to out
    }

    private fun render(e: ZLog.LogEntry): String =
        e.event + "|" + (e.details ?: "") + "|" + e.context.values.joinToString(",")

    // ------------------------------------------------------ property: registered secrets

    @Test
    fun `no registered secret substring survives in any output`() {
        val rnd = Random(seed = 42)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/_-="
        repeat(300) {
            val (log, out) = capturingLog()
            // 3 distinct non-blank secrets of random shape/length
            val secrets = (1..3).map {
                (1..rnd.nextInt(6, 48)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            }
            secrets.forEach(log::registerSecret)
            // messages that embed the secrets in hostile positions
            val contexts = (0..rnd.nextInt(3)).associate { "k$it" to secrets[rnd.nextInt(secrets.size)] }
            log.warn("authChallengeProofComputed proof=${secrets[0]}", "passHash echo: ${secrets[1]} raw=${secrets.joinToString("::")}")
            log.info("state", null, ZLog.LogContext(endpointId = secrets[rnd.nextInt(secrets.size)]))
            log.error("json-ish {\"hash\":\"${secrets[2]}\"}")
            val rendered = out.joinToString("\n") { render(it) }
            for (s in secrets) {
                assertTrue(!rendered.contains(s), "secret leaked (${s.take(6)}…) in: ${rendered.take(200)}")
            }
            assertTrue(rendered.count { it == '[' } >= 1, "expected some REDACTED markers")
        }
    }

    @Test
    fun `overlapping and substring secrets all scrub`() {
        val (log, out) = capturingLog()
        val long = "SECRETLONGVALUE123456"
        val short = "SECRET"
        log.registerSecret(long)
        log.registerSecret(short)
        log.warn("values: $short then $long")
        val rendered = render(out.single())
        assertTrue(!rendered.contains("SECRET"), rendered)
        assertEquals(2, rendered.split(ZLog.REDACTED).size - 1)
    }

    @Test
    fun `blank secret registration is ignored without destroying output`() {
        val (log, out) = capturingLog()
        log.registerSecret("")
        log.registerSecret("   ")
        log.info("hello world")
        assertEquals("hello world", out.single().event)
    }

    // ------------------------------------------------------ structural scrubbers

    @Test
    fun `structural patterns redact unregistered key-value shapes`() {
        val (log, out) = capturingLog()
        val cases = listOf(
            "auth_init pass_hash=c3VwZXJzZWNyZXR2YWx1ZQ next",
            "{\"type\":\"auth_response\",\"proof\":\"4Kc2z0base64urlValue\"}",
            "relay error token=abcdef123456 retrying",
            "header Authorization: Bearer eyJhbGciOi.payload.sig",
            "nonce=deadbeefcafebabe1234 issued",
        )
        for (c in cases) log.warn(c)
        val rendered = out.joinToString("\n") { render(it) }
        for (leak in listOf("c3VwZXJzZWNyZXR2YWx1ZQ", "4Kc2z0base64urlValue", "abcdef123456", "eyJhbGciOi", "deadbeefcafebabe1234")) {
            assertTrue(!rendered.contains(leak), "leak: $leak in $rendered")
        }
    }

    @Test
    fun `normal identifiers are not eaten by structural scrubbers`() {
        val (log, out) = capturingLog()
        log.info("sessionId=sess_abc endpointId=endpoint-1 event=subscribe ok")
        log.info("tokenRing=off proofreading=enabled") // word-boundary keys only
        assertTrue(out[0].event.contains("sess_abc"))
        val second = out[1].event
        assertTrue(second.contains("tokenRing=off") && second.contains("proofreading=enabled"), second)
    }

    // ------------------------------------------------------ ring + levels

    @Test
    fun `diagnostics ring is bounded at 500 (INV-5)`() {
        val (log, _) = capturingLog()
        repeat(1_200) { i -> log.info("event-$i") }
        assertEquals(ZLog.RING_CAPACITY, log.ringSize())
        val dump = log.exportDiagnostics()
        assertTrue(dump.contains("event-1199"))
        assertTrue(!dump.contains("event-699\n"))
    }

    @Test
    fun `level gate suppresses below minLevel and debug gate works`() {
        val (infoLog, out1) = capturingLog(minLevel = ZLog.LogLevel.INFO)
        infoLog.debug("raw frame") // suppressed — release posture
        infoLog.info("lifecycle")
        assertEquals(1, out1.size)
    }

    @Test
    fun `context values are scrubbed too`() {
        val (log, out) = capturingLog()
        val secret = "Sup3rS3cretHashValue"
        log.registerSecret(secret)
        log.info("pairing", null, ZLog.LogContext(endpointId = "ep-1", connectionId = secret))
        val rendered = render(out.single())
        assertTrue(!rendered.contains(secret))
        assertTrue(rendered.contains("ep-1"))
    }
}
