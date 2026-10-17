package com.zcode.android.domain.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3 (gaps-and-polish Phase 3B, P1): the approvals inbox was approve-blind — the full
 * payload was retained (`StoredInteraction.payload`) but never reached the card. The
 * preview is ONE bounded line derived from the verified §20.1 `input` object, redacted
 * with the diagnostics rules (ZLog's structural secret scrub), never the raw JSON.
 */
class InteractionPreviewTest {

    private fun permission(input: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("interactionId", "int-1")
        put("toolName", "Bash")
        put("input", input)
    }

    @Test
    fun `Bash - the command is the preview`() {
        val p = InteractionPreview.of("Bash", permission(buildJsonObject { put("command", "rm -rf build && ./gradlew assemble") }))
        assertEquals("rm -rf build && ./gradlew assemble", p)
    }

    @Test
    fun `verified wire shape - detail dot command is the preview (snapshot pendingInteraction + permission event)`() {
        // InteractionCoordinator maps payload.detail → PermissionRequest.input; the stored raw
        // payload therefore carries `detail`, not `input`.
        val payload = buildJsonObject {
            put("kind", "permission")
            put("toolName", "Bash")
            put("summary", "Run a synthetic command?")
            put("detail", buildJsonObject { put("command", "echo synthetic") })
        }
        assertEquals("echo synthetic", InteractionPreview.of("Bash", payload))
    }

    @Test
    fun `verified wire shape - no detail keys fall back to the summary line`() {
        val payload = buildJsonObject {
            put("kind", "permission")
            put("toolName", "Bash")
            put("summary", "Run a synthetic command?")
            put("detail", buildJsonObject { put("count", 1) })
        }
        assertEquals("Run a synthetic command?", InteractionPreview.of("Bash", payload))
    }

    @Test
    fun `file tools - the path is the preview`() {
        val p = InteractionPreview.of("Write", permission(buildJsonObject { put("file_path", "/repo/src/Main.kt"); put("content", "a".repeat(5000)) }))
        assertEquals("/repo/src/Main.kt", p)
    }

    @Test
    fun `unknown input shape - the first string field renders as key and value`() {
        val p = InteractionPreview.of("Custom", permission(buildJsonObject { put("count", 3); put("target", "prod-db") }))
        assertEquals("target: prod-db", p)
    }

    @Test
    fun `secrets inside the command are redacted per the diagnostics rules`() {
        val p = InteractionPreview.of("Bash", permission(buildJsonObject { put("command", "curl -H 'Authorization: Bearer abcdef123456' https://x --data token=zzzz9999") }))
        requireNotNull(p)
        assertTrue("bearer value must be redacted: $p", "abcdef123456" !in p)
        assertTrue("token value must be redacted: $p", "zzzz9999" !in p)
        assertTrue("[REDACTED]" in p)
    }

    @Test
    fun `multi-line input collapses to one bounded line`() {
        val long = "echo one\n   echo two\t\techo " + "x".repeat(300)
        val p = InteractionPreview.of("Bash", permission(buildJsonObject { put("command", long) }))
        requireNotNull(p)
        assertTrue("no newlines: $p", '\n' !in p && '\t' !in p)
        assertTrue("bounded: ${p.length}", p.length <= InteractionPreview.MAX_CHARS)
        assertTrue(p.startsWith("echo one echo two echo "))
        assertTrue(p.endsWith("…"))
    }

    @Test
    fun `no input - no preview (the card falls back to the tool name only)`() {
        assertNull(InteractionPreview.of("Bash", buildJsonObject { put("toolName", "Bash") }))
        assertNull(InteractionPreview.of("Bash", null))
        assertNull(InteractionPreview.of("Bash", permission(buildJsonObject { })))
    }

    @Test
    fun `user-input questions preview the first question text`() {
        val payload = buildJsonObject {
            put("interactionId", "int-2")
            putJsonArray("questions") { addJsonObject { put("question", "Which database should we migrate first?") } }
        }
        assertEquals("Which database should we migrate first?", InteractionPreview.of(null, payload))
    }

    @Test
    fun `malformed payload never throws`() {
        val payload = buildJsonObject { put("input", "not-an-object") }
        assertNull(InteractionPreview.of("Bash", payload))
    }
}
