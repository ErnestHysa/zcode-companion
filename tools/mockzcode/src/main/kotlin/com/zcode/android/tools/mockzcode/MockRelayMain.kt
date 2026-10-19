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
    // PART N rig-fidelity fix: echo sends like production — a sendText command appends
    // the user row + a synthetic reply and pushes rowAppended. Without this the ack
    // arrives with no row and the sent message never renders (blocked the I1 leg).
    mock.script.commandEffects = true
    // PART N bulk leg (MOCK_ROWS): a long conversation for the gfxinfo scroll-jank leg.
    System.getenv("MOCK_ROWS")?.toIntOrNull()?.let { n ->
        mock.script.snapshotRowCount = n
        mock.script.bulkAssistantText = listOf(
            "The coordinator batches deltas per frame and memoizes parsed blocks per row id,",
            "so a long conversation scrolls at the display cadence instead of re-parsing per token.",
            "",
            "```kotlin",
            "val cadence = frameClock.frames().throttle(16.milliseconds)",
            "```",
            "",
            "- derived state stays derived — no mutableStateOf in the render path",
        ).joinToString("\n")
    }
    // PART K live leg: one rich-markdown assistant row so the phone's parser renders
    // against real wire content (nested list, table, fenced code, inline code, link).
    mock.script.demoAssistantMarkdown = listOf(
        "## Rewrite plan",
        "",
        "Applied `Parser.kt` fixes in [the workspace](https://example.test/ws):",
        "",
        "- Top-level item one",
        "  - nested sub-item A",
        "  - nested sub-item B",
        "- Top-level item two",
        "",
        "| File | Status |",
        "| ---- | ------ |",
        "| Parser.kt | rewritten |",
        "| ParserTest.kt | +12 −3 |",
        "",
        "```kotlin",
        "fun parse(input: String): List<Node> =",
        "    input.lines().mapNotNull(::parseLine)",
        "```",
        "",
        "Done ~~temporarily~~ permanently.",
    ).joinToString("\n")
    // MOCK_APPROVAL=1 arms one pending-approval tool row so the Approvals screen
    // renders live (B2.3/B2.4 geometry checks need a real interaction on-device).
    if (System.getenv("MOCK_APPROVAL") != null) {
        mock.setPendingApproval("Bash", "mock-approval-1")
    }
    // A3.1 (Phase 3D): one pre-committed 1x1 PNG as a sent user attachment — the
    // conversation renders the image it "sent" (read channel serves the bytes).
    if (System.getenv("MOCK_ATTACHMENT") != null) {
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
        )
        mock.setScriptedAttachment("mock-attachment-1", "image/png", png)
    }
    // Phase 4A (E3.2+I1+N5) rig leg: MOCK_ROW_ACTIONS=1 stamps canFork/canRetry on the
    // latest assistant row + a completed turn with fileChanges/canRewindFiles, and the
    // four row commands land real effects (retried reply row, forked child task,
    // reverted turn, feedback echo) — the `v4call sendConversationCommandV4` log line
    // is the wire proof.
    if (System.getenv("MOCK_ROW_ACTIONS") != null) {
        mock.script.rowActionsDemo = true
    }
    // Phase 4C (D2) rig leg: MOCK_GOAL=1 scripts a goal (with a pass/notSatisfied
    // verification timeline) + a todo list into the state block — the goal/plan
    // panels and the turn-kind label render live.
    if (System.getenv("MOCK_GOAL") != null) {
        mock.script.goalState = JsonObject(mapOf(
            "targetId" to JsonPrimitive("g-rig"),
            "objective" to JsonPrimitive("Ship the release"),
            "summaryTitle" to JsonPrimitive("Ship it"),
            "timeUsedSeconds" to JsonPrimitive(120),
            "activeRunStartedAtMs" to JsonPrimitive(System.currentTimeMillis()),
            "status" to JsonPrimitive("verifying"),
            "iteration" to JsonPrimitive(3),
            "verifications" to JsonArray(listOf(
                JsonObject(mapOf("iteration" to JsonPrimitive(1), "outcome" to JsonPrimitive("pass"), "at" to JsonPrimitive(System.currentTimeMillis() - 200_000), "anchorRowId" to JsonPrimitive(12))),
                JsonObject(mapOf("iteration" to JsonPrimitive(2), "outcome" to JsonPrimitive("notSatisfied"), "at" to JsonPrimitive(System.currentTimeMillis() - 100_000), "reason" to JsonPrimitive("2 criteria unmet"), "nextAction" to JsonPrimitive("continue"))),
            )),
        ))
        mock.script.planState = JsonObject(mapOf(
            "items" to JsonArray(listOf(
                JsonObject(mapOf("id" to JsonPrimitive("t1"), "content" to JsonPrimitive("Write the changelog"), "status" to JsonPrimitive("completed"))),
                JsonObject(mapOf("id" to JsonPrimitive("t2"), "content" to JsonPrimitive("Bump the version"), "status" to JsonPrimitive("inProgress"))),
            )),
            "updatedAt" to JsonPrimitive(System.currentTimeMillis()),
        ))
    }
    // Phase 4C (C2) rig leg: the slash panel's extra sections — one custom command,
    // one skill, one agent (synthetic, INV-15/16).
    mock.script.commandsList = JsonObject(mapOf(
        "commands" to JsonArray(emptyList()),
        "userCommands" to JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("cmd-deploy"),
            "name" to JsonPrimitive("deploy"),
            "description" to JsonPrimitive("Deploy the project"),
            "argumentHint" to JsonPrimitive("[env]"),
            "enabled" to JsonPrimitive(true),
            "source" to JsonPrimitive("user"),
        )))),
        "pluginCommands" to JsonArray(emptyList()),
    ))
    mock.script.subagentsList = JsonObject(mapOf(
        "agents" to JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("a-explore"),
            "name" to JsonPrimitive("Explore"),
            "description" to JsonPrimitive("Read-only search agent"),
            "scope" to JsonPrimitive("built-in"),
            "enabled" to JsonPrimitive(true),
        )))),
    ))
    // PART M live leg: populated MCP / plugins / skills viewers (shapes mirror the
    // domain decoders: ViewerModels.McpStatuses / PluginsView / SkillCatalog) so the
    // three Settings viewers render their real lists on device instead of the
    // fault.mock.unscripted error states.
    mock.script.mcpServerStatuses = JsonObject(
        mapOf(
            "statuses" to JsonObject(
                mapOf(
                    "github" to JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("connected"),
                            "transport" to JsonPrimitive("stdio"),
                            "toolCount" to JsonPrimitive(12),
                            "updatedAt" to JsonPrimitive("2026-10-14T09:30:00Z"),
                            "error" to JsonPrimitive(null as String?),
                            "failureKind" to JsonPrimitive(null as String?),
                            "protocolEra" to JsonPrimitive("modern"),
                        ),
                    ),
                    "playwright" to JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("failed"),
                            "transport" to JsonPrimitive("sse"),
                            "toolCount" to JsonPrimitive(0),
                            "updatedAt" to JsonPrimitive("2026-10-14T09:31:00Z"),
                            "error" to JsonPrimitive("ECONNREFUSED 127.0.0.1:9222"),
                            "failureKind" to JsonPrimitive("connectionRefused"),
                            "protocolEra" to JsonPrimitive("modern"),
                        ),
                    ),
                    "docs-search" to JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("disabled"),
                            "transport" to JsonPrimitive("http"),
                            "toolCount" to JsonPrimitive(0),
                            "updatedAt" to JsonPrimitive("2026-10-14T09:00:00Z"),
                            "error" to JsonPrimitive(null as String?),
                            "failureKind" to JsonPrimitive(null as String?),
                            "protocolEra" to JsonPrimitive("legacy"),
                        ),
                    ),
                ),
            ),
        ),
    )
    mock.script.listPlugins = JsonObject(
        mapOf(
            "plugins" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("plugin-prisma"),
                            "name" to JsonPrimitive("prisma-toolkit"),
                            "description" to JsonPrimitive("Schema-first database workflows and migrations."),
                            "version" to JsonPrimitive("2.4.0"),
                            "enabled" to JsonPrimitive(true),
                            "source" to JsonPrimitive("marketplace"),
                            "marketplace" to JsonPrimitive("official"),
                            "skillRootCount" to JsonPrimitive(3),
                            "commandRootCount" to JsonPrimitive(2),
                            "mcpServerNames" to JsonArray(listOf(JsonPrimitive("prisma-mcp"))),
                            "rootPath" to JsonPrimitive("/Users/mock/.zcode/plugins/cache/prisma-toolkit/2.4.0"),
                        ),
                    ),
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("plugin-review"),
                            "name" to JsonPrimitive("code-review"),
                            "description" to JsonPrimitive("Structured PR review commands and review subagents."),
                            "version" to JsonPrimitive(null as String?),
                            "enabled" to JsonPrimitive(false),
                            "source" to JsonPrimitive("local"),
                            "marketplace" to JsonPrimitive("none"),
                            "skillRootCount" to JsonPrimitive(1),
                            "commandRootCount" to JsonPrimitive(4),
                            "mcpServerNames" to JsonArray(emptyList()),
                            "rootPath" to JsonPrimitive("/Users/mock/proj/.zcode/plugins/code-review"),
                        ),
                    ),
                ),
            ),
            "diagnostics" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "code" to JsonPrimitive("plugin.missingDependency"),
                            "message" to JsonPrimitive("code-review requires a workspace plugin root"),
                            "severity" to JsonPrimitive("warning"),
                            "pluginId" to JsonPrimitive("plugin-review"),
                        ),
                    ),
                ),
            ),
        ),
    )
    mock.script.skillReferenceCatalog = JsonObject(
        mapOf(
            "authority" to JsonPrimitive("workspace"),
            "skills" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("skill-release"),
                            "name" to JsonPrimitive("release"),
                            "description" to JsonPrimitive("Cut a release: bump version, changelog, tag, build artifact."),
                            "path" to JsonPrimitive("/Users/mock/proj/.zcode/skills/release/SKILL.md"),
                            "scope" to JsonPrimitive("workspace"),
                            "pluginName" to JsonPrimitive(null as String?),
                        ),
                    ),
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("skill-sql-migrate"),
                            "name" to JsonPrimitive("sql-migrate"),
                            "description" to JsonPrimitive("Generate and apply safe SQL migrations from schema diffs."),
                            "path" to JsonPrimitive("/Users/mock/.zcode/plugins/cache/prisma-toolkit/2.4.0/skills/sql-migrate/SKILL.md"),
                            "scope" to JsonPrimitive("plugin"),
                            "pluginName" to JsonPrimitive("prisma-toolkit"),
                        ),
                    ),
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("skill-commit"),
                            "name" to JsonPrimitive("commit"),
                            "description" to JsonPrimitive("Stage and commit the current change set with a good message."),
                            "path" to JsonPrimitive("/Users/mock/.zcode/skills/commit/SKILL.md"),
                            "scope" to JsonPrimitive("user"),
                            "pluginName" to JsonPrimitive(null as String?),
                        ),
                    ),
                ),
            ),
        ),
    )
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

/**
 * D4 (Phase 3A): the shipped view's `{modelId, config}` rows may carry the desktop
 * picker's badge fields — `config.badgeLabel` per model, `labelBadge` per provider
 * group. The default selection scripts one of each so the rig renders the badge slot.
 */
private fun provider(
    id: String,
    name: String,
    models: List<String>,
    badge: String? = null,
    modelBadges: Map<String, String> = emptyMap(),
): JsonObject = JsonObject(
    buildMap {
        put("providerId", JsonPrimitive(id))
        put("providerName", JsonPrimitive(name))
        badge?.let { put("labelBadge", JsonPrimitive(it)) }
        put(
            "models",
            JsonArray(
                models.map { m ->
                    JsonObject(
                        buildMap {
                            put("modelId", JsonPrimitive(m))
                            modelBadges[m]?.let { put("config", JsonObject(mapOf("badgeLabel" to JsonPrimitive(it)))) }
                        },
                    )
                },
            ),
        )
    },
)

private fun defaultModelSelection(): JsonObject = JsonObject(
    mapOf(
        "providers" to JsonArray(
            listOf(
                provider("zai", "Z.ai", listOf("glm-5.1", "glm-5.1-air", "glm-5.1-flash"), badge = "Default", modelBadges = mapOf("glm-5.1-flash" to "Preview")),
                provider("openai-compat", "OpenAI-compatible", listOf("glm-4.6")),
            ),
        ),
    ),
)
