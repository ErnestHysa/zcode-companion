package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G3 rig-fidelity pins (gaps-and-polish Phase 7): the switch-command effects are
 * echo-faithful — the rebuilt `config` never drops keys the command does not manage
 * (`modelSelection` survived a mode switch on the shipped desktop; the mock's fixed-key
 * rebuild was the rig defect that hid the chip re-render), a model switch REFRESHES
 * `modelSelection` to the picked pair (the §10.3 echo carries the CURRENT selection),
 * and every effect's `state.updated` frame is JOURNALED so a resumable
 * `resyncConversationV4` replays it (G3: "its state patch appears lost after the resync
 * the model switch triggered"). Grounded in docs/protocol/v4-wire.md §10.3/§15.1;
 * synthetic content only (INV-15/16).
 */
class MockZCodeSwitchEchoSelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_switchecho")
        script(m.script)
        return m
    }

    private fun envelope(payload: JsonObject) = kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("envelope"))
        put("commandId", kotlinx.serialization.json.JsonPrimitive("cmd-switch"))
        put("payload", payload)
    }

    private fun faithfulConfig(): JsonObject = JsonObject(
        mapOf(
            "provider" to JsonPrimitive("zai"),
            "model" to JsonPrimitive("glm-5.1"),
            "thought" to JsonPrimitive("high"),
            "thoughtLevels" to JsonArray(listOf(JsonPrimitive("low"), JsonPrimitive("medium"), JsonPrimitive("high"))),
            "mode" to JsonPrimitive("build"),
            "modelSelection" to JsonObject(
                mapOf("providerId" to JsonPrimitive("zai"), "modelId" to JsonPrimitive("glm-5.1")),
            ),
        ),
    )

    private fun configOf(m: MockZCode): JsonObject =
        assertNotNull(m.stateBlockForTest(), "state block carries the config echo")["config"]!!.jsonObject

    @Test
    fun `mode switch preserves modelSelection in the config echo (G3)`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.commandEffects = true
            s.sessionConfig = faithfulConfig()
        }
        val before = m.revisionForTest()
        m.applyCommandEffectForTest("switchCollaborationMode", envelope(JsonObject(mapOf("mode" to JsonPrimitive("plan")))))
        assertEquals("plan", m.script.sessionConfig?.jsonObject?.get("mode")?.let { (it as JsonPrimitive).content })
        val selection = assertNotNull(
            m.script.sessionConfig?.jsonObject?.get("modelSelection")?.jsonObject,
            "the mode switch must not drop modelSelection (G3 rig defect)",
        )
        assertEquals("zai", (selection["providerId"] as JsonPrimitive).content)
        assertEquals("glm-5.1", (selection["modelId"] as JsonPrimitive).content)
        // The snapshot/patch plane (what the phone's chips reconcile against) agrees.
        val echoed = configOf(m)
        assertEquals("plan", (echoed["mode"] as JsonPrimitive).content)
        assertEquals("glm-5.1", (echoed["modelSelection"]!!.jsonObject["modelId"] as JsonPrimitive).content)
        assertTrue(m.revisionForTest() > before, "every effect bumps the CAS revision")
    }

    @Test
    fun `model switch refreshes modelSelection to the picked pair`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.commandEffects = true
            s.sessionConfig = faithfulConfig()
        }
        m.applyCommandEffectForTest(
            "switchModelConfig",
            envelope(
                JsonObject(
                    mapOf(
                        "provider" to JsonPrimitive("zai"),
                        "model" to JsonPrimitive("glm-5.1-air"),
                        "thought" to JsonPrimitive("medium"),
                    ),
                ),
            ),
        )
        val config = m.script.sessionConfig?.jsonObject
        assertEquals("glm-5.1-air", (config?.get("model") as? JsonPrimitive)?.content)
        assertEquals("medium", (config?.get("thought") as? JsonPrimitive)?.content)
        val selection = assertNotNull(config?.get("modelSelection")?.jsonObject, "the echo carries the CURRENT selection")
        assertEquals("zai", (selection["providerId"] as JsonPrimitive).content)
        assertEquals("glm-5.1-air", (selection["modelId"] as JsonPrimitive).content)
    }

    @Test
    fun `followup-mode switch preserves modelSelection`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.commandEffects = true
            s.sessionConfig = faithfulConfig()
        }
        m.applyCommandEffectForTest("setFollowupMode", envelope(JsonObject(mapOf("mode" to JsonPrimitive("followup")))))
        assertEquals("followup", (m.script.sessionConfig?.jsonObject?.get("followupMode") as? JsonPrimitive)?.content)
        assertNotNull(
            m.script.sessionConfig?.jsonObject?.get("modelSelection")?.jsonObject,
            "the followup switch must not drop modelSelection either (same rebuild defect class)",
        )
    }

    @Test
    fun `the switch's state patch is journaled for resync replay (G3 patch-lost)`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.commandEffects = true
            s.sessionConfig = faithfulConfig()
        }
        m.applyCommandEffectForTest("switchCollaborationMode", envelope(JsonObject(mapOf("mode" to JsonPrimitive("plan")))))
        val journal = m.opLogForTest("synth-task-1")
        val patch = journal.lastOrNull { frame ->
            frame.ops.any { op ->
                (op as? JsonObject)?.get("op").let { it is JsonPrimitive && it.content == "state.updated" } &&
                    (op.jsonObject["patch"]?.jsonObject?.get("config")?.jsonObject?.get("mode").let { it is JsonPrimitive && it.content == "plan" })
            }
        }
        assertNotNull(patch, "the config patch is journaled — a resumable resync (fromSeq > base) replays it")
        assertTrue(patch.fromSeq > 0, "journaled frames carry the monotonic seq the replay filter uses")
        // The G3 flow end-to-end: model switch THEN mode switch — both patches replay.
        m.applyCommandEffectForTest(
            "switchModelConfig",
            envelope(
                JsonObject(
                    mapOf(
                        "provider" to JsonPrimitive("zai"),
                        "model" to JsonPrimitive("glm-5.1-air"),
                        "thought" to JsonPrimitive("high"),
                    ),
                ),
            ),
        )
        assertTrue(m.opLogForTest("synth-task-1").size >= 2, "each switch effect journals its own replayable frame")
    }

    @Test
    fun `sendText row effects are journaled too (same frame class)`() {
        val m = mock { s -> s.commandEffects = true }
        m.applyCommandEffectForTest("sendText", envelope(JsonObject(mapOf("text" to JsonPrimitive("hello rig")))))
        val ops = m.opLogForTest("synth-task-1").flatMap { it.ops }
        assertTrue(
            ops.any { (it as? JsonObject)?.get("op").let { o -> o is JsonPrimitive && o.content == "row.appended" } },
            "row.appended effect frames survive the journal (authoritative rows replay like snapshots)",
        )
    }

    @Test
    fun `effort switch (setThoughtLevel) preserves modelSelection too — fourth rebuild site`() {
        val m = mock { s -> s.sessionConfig = faithfulConfig() }
        val next = m.thoughtLevelConfig("medium")
        assertEquals("medium", (next["thought"] as JsonPrimitive).content)
        assertNotNull(next["modelSelection"]?.jsonObject, "the effort switch must not drop modelSelection (same defect class)")
        assertEquals("glm-5.1", (next["modelSelection"]!!.jsonObject["modelId"] as JsonPrimitive).content)
        assertEquals("build", (next["mode"] as JsonPrimitive).content, "unmanaged keys ride through")
    }
}

/**
 * RIG-ledger pin (gaps-and-polish Phase 7): the rig assets are PERMANENT — the
 * inventory every fix round regression-pins against must stay present in the mock
 * sources (assets extended, never forked; K8/L9/N5/N9). This is a source-contract
 * pin: removing or forking any listed asset knob fails here before a rig round can
 * silently lose its fixture.
 */
class RigAssetsLedgerSelfTest {

    private fun source(name: String): String =
        java.io.File("src/main/kotlin/com/zcode/android/tools/mockzcode/$name").readText()

    private fun assertKnob(where: String, text: String, needle: String) =
        assertTrue(text.contains(needle), "rig asset missing from $where: `$needle`")

    @Test
    fun `relay-main wires the canonical rig asset knobs`() {
        val main = source("MockRelayMain.kt")
        assertKnob("MockRelayMain", main, "MOCK_APPROVAL")
        assertKnob("MockRelayMain", main, "script.demoAssistantMarkdown")
        assertKnob("MockRelayMain", main, "MOCK_ROWS")
        assertKnob("MockRelayMain", main, "commandEffects = true")
        assertKnob("MockRelayMain", main, "MOCKZCODE_WS_URL")
        assertKnob("MockRelayMain", main, "MOCKZCODE_LINK")
        assertKnob("MockRelayMain", main, "MOCKZCODE_READY")
    }

    @Test
    fun `the v4call wire-proof log is unconditional`() {
        val mockzcode = source("MockZCode.kt")
        val line = mockzcode.lineSequence().firstOrNull { it.contains("v4call") }
        assertNotNull(line, "the v4call log line is missing — the rig's wire proof is gone")
        assertTrue(line.contains("println(\"MOCK: v4call"), "the v4call log must be a plain unconditional println")
        assertTrue(!line.trimStart().startsWith("//"), "the v4call log must not be commented out")
    }

    @Test
    fun `RawWsServer stays the transport and keeps its behavior pin`() {
        val raw = source("RawWsServer.kt")
        assertTrue(raw.contains("class RawWsServer"), "RawWsServer is the RFC 6455-correct transport — do not fork to MockWebServer")
        val pin = java.io.File("src/test/kotlin/com/zcode/android/tools/mockzcode/RawWsServerTest.kt")
        assertTrue(pin.exists() && pin.readText().contains("echoRoundTripWithRealOkHttpClient"), "RawWsServerTest behavior pin missing")
    }
}
