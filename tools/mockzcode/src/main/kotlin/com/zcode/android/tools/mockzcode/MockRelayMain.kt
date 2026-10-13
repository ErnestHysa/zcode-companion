package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.system.exitProcess

/**
 * Standalone MockZCode relay server for on-device E2E (§35.4 — test tooling, INV-15:
 * synthetic credentials only, never a production-system dependency).
 *
 * Prints the synthetic pairing LINK and the ws URL; blocks until killed.
 * Environment overrides: MOCK_PORT (default 18100), MOCK_SCENARIO
 * (HAPPY | WRONG_PROOF | UNKNOWN_SID).
 *
 * `model-selection.getView` is answered with a two-provider default view
 * (phase9-channels.md §4 shape; only providerId/providerName/models[].modelId are
 * consumed by Phase9Tools.modelOptions) so the pickers render their real grouped
 * list on device E2E instead of the refused-fault empty branch.
 */
fun main() {
    val port = System.getenv("MOCK_PORT")?.toIntOrNull() ?: 18_100
    val scenario = runCatching {
        MockZCode.AuthScenario.valueOf(System.getenv("MOCK_SCENARIO") ?: "HAPPY")
    }.getOrDefault(MockZCode.AuthScenario.HAPPY)
    val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    val sid = "d_SyntheticSid000001"
    val mid = "mock-desktop-mid"
    val mock = MockZCode(passHash = passHash, expectedSid = sid)
    mock.script.authScenario = scenario
    mock.script.modelSelection = defaultModelSelection()
    mock.script.emitStateKeys = true
    // Session config echo (§10.3): provider/model/thought are REQUIRED keys — without
    // them the config state drops and the conversation chips never render.
    mock.script.sessionConfig = JsonObject(
        mapOf(
            "provider" to JsonPrimitive("zai"),
            "model" to JsonPrimitive("glm-5.1"),
            "thought" to JsonPrimitive("high"),
            "thoughtLevels" to JsonArray(
                listOf(JsonPrimitive("low"), JsonPrimitive("medium"), JsonPrimitive("high")),
            ),
            "mode" to JsonPrimitive("build"),
            "modelSelection" to JsonObject(
                mapOf(
                    "providerId" to JsonPrimitive("zai"),
                    "modelId" to JsonPrimitive("glm-5.1"),
                ),
            ),
        ),
    )
    // RawWsServer (not MockWebServer): mockwebserver3 masks server→client frames
    // (RFC 6455 §5.1 violation, probe-confirmed — report PART E9), which OkHttp
    // clients refuse with close 1006; the raw server keeps live device E2E possible.
    val url = mock.startRaw(port)
    val link = "http://127.0.0.1:$port/remote/v4" +
        "?sid=$sid&hash=${java.net.URLEncoder.encode(passHash, "UTF-8")}" +
        "&t=${System.currentTimeMillis()}&mid=$mid&name=MockBook&app_version=3.14.3"
    println("MOCKZCODE_WS_URL=$url")
    println("MOCKZCODE_LINK=$link")
    println("MOCKZCODE_READY scenario=$scenario")
    Runtime.getRuntime().addShutdownHook(Thread { mock.stop() })
    Thread.currentThread().join()
    exitProcess(0)
}

private fun provider(id: String, name: String, models: List<String>): JsonObject = JsonObject(
    mapOf(
        "providerId" to JsonPrimitive(id),
        "providerName" to JsonPrimitive(name),
        "models" to JsonArray(models.map { JsonObject(mapOf("modelId" to JsonPrimitive(it))) }),
    ),
)

private fun defaultModelSelection(): JsonObject = JsonObject(
    mapOf(
        "providers" to JsonArray(
            listOf(
                provider("zai", "Z.ai", listOf("glm-5.1", "glm-5.1-air", "glm-5.1-flash")),
                provider("openai-compat", "OpenAI-compatible", listOf("glm-4.6")),
            ),
        ),
    ),
)
