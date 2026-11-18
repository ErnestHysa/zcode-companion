package com.zcode.android.tools.mockzcode

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
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
 * RIG-P4 (vc52): MOCK_MODE=direct serves the `zcode --web` DIRECT surface instead
 * (docs/protocol/phase10-direct.md §1–§2): `GET /api/server-info?token=` (200 + the
 * HttpOnly `zcode_lite_token` cookie; 401 on a wrong/missing token) and `/ws` (binary
 * SocketProtocol frames, cookie- or query-token gated). MOCK_DIRECT_TOKEN sets the token
 * (default `rig-direct-token`); the default port becomes 18200. Every other MOCK_* arm
 * applies unchanged. `gradle :tools:mockzcode:mockDirectServer` is the run target.
 *
 * `model-selection.getView` is answered with a two-provider default view
 * (phase9-channels.md §4 shape; only providerId/providerName/models[].modelId are
 * consumed by Phase9Tools.modelOptions) so the pickers render their real grouped
 * list on device E2E instead of the refused-fault empty branch.
 */
/**
 * N-BK-1 (pass-5 rig fix): the MOCK_GIT read fixtures in the CONTRACT vocabulary the
 * app decodes (data/Phase9Tools.kt). Extracted from `main` so the self-test pins the
 * app-decodable contract against the LIVE fixture build, not a copy. `section` uses
 * the app's verified set ("unstaged" | "untracked" | "staged" — features/git
 * GitScreen.kt section orders); getChanges filters rows by the requested sourceId.
 */
internal object RigGitFixtures {
    fun changes(): JsonArray = JsonArray(listOf(
        JsonObject(mapOf(
            "path" to JsonPrimitive("/synthetic/repo/src/version.ts"),
            "workspaceRelativePath" to JsonPrimitive("src/version.ts"),
            "kind" to JsonPrimitive("modified"),
            "section" to JsonPrimitive("unstaged"),
            "added" to JsonPrimitive(3L),
            "removed" to JsonPrimitive(1L),
        )),
        JsonObject(mapOf(
            "path" to JsonPrimitive("/synthetic/repo/scratch/probe.txt"),
            "workspaceRelativePath" to JsonPrimitive("scratch/probe.txt"),
            "kind" to JsonPrimitive("added"),
            "section" to JsonPrimitive("untracked"),
            "added" to JsonPrimitive(2L),
            "removed" to JsonPrimitive(0L),
        )),
        JsonObject(mapOf(
            "path" to JsonPrimitive("/synthetic/repo/src/index.ts"),
            "workspaceRelativePath" to JsonPrimitive("src/index.ts"),
            "kind" to JsonPrimitive("modified"),
            "section" to JsonPrimitive("staged"),
            "added" to JsonPrimitive(12L),
            "removed" to JsonPrimitive(4L),
        )),
    ))

    /**
     * p41 (pass-41): the write tier's staged set seeded from the fixture's staged rows —
     * a fixture-staged row absent from [MockScript.stagedPaths] could never be unstaged
     * (servedGitChanges only promoted rows into "staged"; the live receipt: unstagePaths
     * ok → the refetch served the SAME staged row). The arming AND every git self-test
     * mock share this seed so the served/committed views agree on the initial state.
     */
    fun seedWriteTier(script: MockZCode.Script, fixture: JsonArray = changes()) {
        fixture.mapNotNull { it as? JsonObject }
            .filter { (it["section"] as? JsonPrimitive)?.content == "staged" }
            .mapNotNull { (it["path"] as? JsonPrimitive)?.content }
            .forEach { script.stagedPaths.add(it) }
    }

    /**
     * p42 (pass-42): MOCK_GIT_MANY swaps the Changes fixture for a many-rows set —
     * 44 unstaged rows across nested directories + 2 staged — crossing GitScreen's
     * J1.2 gates on the Unstaged section: SEARCH_GATE=10 (the per-section search
     * field renders) and TREE_GATE=40 (the collapsible directory tree replaces the
     * flat list). The desktop `git.tree.*` surface the gate comment names.
     */
    fun manyChanges(): JsonArray {
        val rows = ArrayList<JsonObject>()
        fun row(path: String, rel: String, kind: String, section: String, added: Long, removed: Long) =
            JsonObject(mapOf(
                "path" to JsonPrimitive("/synthetic/repo/$path"),
                "workspaceRelativePath" to JsonPrimitive(rel),
                "kind" to JsonPrimitive(kind),
                "section" to JsonPrimitive(section),
                "added" to JsonPrimitive(added),
                "removed" to JsonPrimitive(removed),
            ))
        // staged tier first (seedWriteTier picks these into stagedPaths)
        rows.add(row("src/core/config.ts", "src/core/config.ts", "modified", "staged", 9, 2))
        rows.add(row("src/app/main.ts", "src/app/main.ts", "modified", "staged", 4, 1))
        // 50 unstaged across nested dirs — ALL modified (the p41 served-section rule
        // re-sections kind=added rows to "untracked", which would split the section
        // below the TREE gate). Tree grouping has real depth + root files.
        val plan: List<Pair<String, Int>> = listOf(
            "src/app/ui" to 6, "src/app/state" to 5, "src/core/net" to 6,
            "src/core/db" to 5, "src/tools" to 6, "docs/notes" to 4,
            "scratch/tmp" to 6,
        )
        var n = 0
        for ((dir, count) in plan) {
            for (i in 1..count) {
                n += 1
                rows.add(row("$dir/file$i.ts", "$dir/file$i.ts", "modified", "unstaged", n.toLong() % 30 + 1, n.toLong() % 7))
            }
        }
        for (i in 1..6) {
            rows.add(row("rootfile$i.md", "rootfile$i.md", "modified", "unstaged", 2, 1))
        }
        return JsonArray(rows)
    }

    /**
     * p42 (pass-42, debt #28): the getDiff fixture re-shaped to the DECODER contract —
     * Phase9Tools.gitDiff reads {path, availability ∈ patch|binary|truncated|unavailable,
     * patch}. The pass-5-era literal served {diff: "<unified>"} (no availability/patch
     * keys), so the FIRST live diff-expand decoded availability=null → "unavailable" and
     * patch=null — the honest unavailable face with the wire ok (git.getDiff begin → ok,
     * never driven before). Desktop-faithful shape: a unified patch with availability
     * "patch".
     */
    fun diff(): JsonObject = JsonObject(mapOf(
        "path" to JsonPrimitive("src/version.ts"),
        "availability" to JsonPrimitive("patch"),
        "patch" to JsonPrimitive(
            "diff --git a/src/version.ts b/src/version.ts\n" +
                "--- a/src/version.ts\n+++ b/src/version.ts\n@@ -1,3 +1,5 @@\n" +
                " const a = 1\n+const b = a + 1\n-const c = 2\n+const c = 3\n export { a, b, c }\n",
        ),
    ))

    fun commits(): JsonArray = JsonArray(listOf(
        JsonObject(mapOf(
            "hash" to JsonPrimitive("abc1234def5678901a2b3c4d5e6f708192a3b4c5d"),
            "parents" to JsonArray(listOf(JsonPrimitive("def5678091a2b3c4d5e6f708192a3b4c5d6e7f80"))),
            "refs" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("origin/main"))))),
            "subject" to JsonPrimitive("fix(rig): keep the summary merged with the tracked branch"),
            "authorName" to JsonPrimitive("Ernest"),
            "authoredAtMs" to JsonPrimitive(System.currentTimeMillis() - 3_600_000),
        )),
        JsonObject(mapOf(
            "hash" to JsonPrimitive("def5678091a2b3c4d5e6f708192a3b4c5d6e7f80"),
            "parents" to JsonArray(emptyList()),
            "refs" to JsonArray(emptyList()),
            "subject" to JsonPrimitive("chore(rig): seed the git read fixtures"),
            "authorName" to JsonPrimitive("Ernest"),
            "authoredAtMs" to JsonPrimitive(System.currentTimeMillis() - 7_200_000),
        )),
    ))
}

fun main() {
    val env: (String) -> String? = System::getenv
    val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    val sid = "d_SyntheticSid000001"
    val mid = "mock-desktop-mid"
    val target = launcherTarget(env, passHash, sid)
    val port = target.port
    val scenario = runCatching {
        MockZCode.AuthScenario.valueOf(env("MOCK_SCENARIO") ?: "HAPPY")
    }.getOrDefault(MockZCode.AuthScenario.HAPPY)
    val mock = target.mock
    mock.script.authScenario = scenario
    mock.script.modelSelection = defaultModelSelection()
    mock.script.emitStateKeys = true
    // RIG-P1 (vc52 Phase 14): the per-relay-GENERATION nonce — every SCRIPTED
    // interaction id (approval, question, hook, gen push) carries it, so two relay
    // generations never collide. The pass-15 "inert cards" saga: constant ids were
    // re-asserted across relay restarts while the app's interaction lifecycle
    // persisted in Room — the INV-4 duplicate guard correctly refused every later
    // generation's same-id cards (and the id collision is also the q-rig-1 push
    // anchor confusion of pass 35: a pushed permission re-claimed the SEEDED
    // question's id). MOCK_GEN_NONCE overrides (repro drives that pin an id).
    val genNonce = relayGenerationNonce(env)
    mock.script.interactionPushId = nonceId(mock.script.interactionPushId, genNonce)
    // PART N rig-fidelity fix: echo sends like production — a sendText command appends
    // the user row + a synthetic reply and pushes rowAppended. Without this the ack
    // arrives with no row and the sent message never renders (blocked the I1 leg).
    mock.script.commandEffects = true
    // F-ROUND26 field-hostility legs (2026-10-26 vc46 S918B real-relay trace) — arm the
    // pathological shapes the field captured, on demand:
    //   MOCK_PAIR_STATUS_PENDING_MS=<ms>  auth_ack/pair_status_ack hold "pending" and
    //                                     flip to "matched" after the delay (the desktop
    //                                     approval latency: ~29 s observed on cold start).
    //   MOCK_ERROR_FIRST_CONTROL=<code>   the first post-auth control request is answered
    //                                     {"type":"error","code":<code>} (relay-4's
    //                                     DEVICE_OFFLINE).
    //   MOCK_DISCONNECT_AFTER_FRAMES=<n>  the FIRST connection's socket closes after n
    //                                     outbound logical frames (the mid-pairing 1006
    //                                     loss that dead-ended pairing pre-F-PAIR2).
    //   MOCK_DISCONNECT_AFTER_DATA_FRAMES=<n>  (debt #12, pass 14) the same storm
    //                                     counted in DATA frames only (topic/delta/
    //                                     observation pushes) — poll/RPC frames never
    //                                     count, so the disconnect lands mid-LIFE
    //                                     instead of mid-handshake (=2) or at ~2.5
    //                                     min of polls (=30).
    System.getenv("MOCK_PAIR_STATUS_PENDING_MS")?.toLongOrNull()?.let { ms ->
        mock.script.initialPairStatus = "pending"
        mock.script.pairStatusMatchAfterMs = ms
    }
    System.getenv("MOCK_ERROR_FIRST_CONTROL")?.let { mock.script.errorFirstControlRequest = it }
    System.getenv("MOCK_DISCONNECT_AFTER_FRAMES")?.toIntOrNull()?.let { mock.script.disconnectAfterFrames = it }
    System.getenv("MOCK_DISCONNECT_AFTER_DATA_FRAMES")?.toIntOrNull()?.let { mock.script.disconnectAfterDataFrames = it }
    // PASS-30 arms (Part AG): the attachment transaction's non-offline failure faces.
    //   MOCK_ATTACHMENT_FAIL=<begin|chunk|commit>  the named phase answers a server
    //                                     fault (RPC error) while CONNECTED — the
    //                                     app's transport throws TransportFailure →
    //                                     the port's GENERIC Failed path (chip failed,
    //                                     payload RETAINED, Retry live) → the
    //                                     ATTACHMENT_FAILED / ATTACHMENT_RETRY_FAILED
    //                                     notices become drivable.
    //   MOCK_ATTACHMENT_CHUNK_DELAY_MS=<ms>      delay before each attachmentChunkV4
    //                                     response — widens the ↑ NN% uploading-chip
    //                                     window so the progress render is catchable.
    //   MOCK_ATTACHMENT_KEEP_AFTER_ABORT=1       attachmentAbortV4 KEEPS the staging
    //                                     entry, so post-abort chunks are accepted
    //                                     and commit succeeds — the AH.D
    //                                     "chunk-accepting server" shape that
    //                                     resurrects the user-removed chip as
    //                                     ✓ READY (F-P31-A's second variant).
    System.getenv("MOCK_ATTACHMENT_FAIL")?.let { mock.script.attachmentFailPhase = it }
    System.getenv("MOCK_ATTACHMENT_CHUNK_DELAY_MS")?.toLongOrNull()?.let { mock.script.attachmentChunkDelayMs = it }
    System.getenv("MOCK_ATTACHMENT_KEEP_AFTER_ABORT")?.let { mock.script.attachmentKeepAfterAbort = it == "1" }
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
    // RIG-P3 (vc52 Phase 10 / F-37-D): MOCK_ROWS_TOTAL=<n> models a conversation
    // whose STORE has n rows while the SNAPSHOT carries only the production
    // rowsWindowSchema TAIL window ({window, totalCount, firstRowId=1}) of
    // MOCK_ROWS_WINDOW rows (default 320 = the phone's cache bound) — the window's
    // first row id is then above the store floor, so the phone's hasMoreHistory
    // derives true and the Load-earlier strip shows; the rowsRange handler pages
    // backward through the virtual store (row ids 1..n). Without the arm the mock
    // keeps the bare-array snapshot and the strip can never appear (the recorded
    // F-37-D receipt shape).
    System.getenv("MOCK_ROWS_TOTAL")?.toLongOrNull()?.let { total ->
        val tail = System.getenv("MOCK_ROWS_WINDOW")?.toIntOrNull() ?: 320
        val base = (total - tail + 1).coerceAtLeast(1L)
        mock.script.snapshotRowIdBase = base
        mock.script.snapshotRowCount = tail
        mock.script.snapshotRowsWindowShape = true
        mock.script.snapshotWindowTruncated = true
        // Pass-35 (Part AL): totalCount is the SESSION total — the window shape used to
        // serve rows.size, so the phone's strip claimed "320 of 320" (the pass-3/4-era
        // "armed 2000 → of 338" observation).
        mock.script.snapshotTotalCount = total
    }
    // Pass-35 fault arm (Part AL): conversationRowsRangeV4 answers fault.rowsRange.rig —
    // the phone's Load-earlier error strip + Retry face (never receipted in the pass0
    // series) becomes drivable.
    System.getenv("MOCK_PAGE_FAULT")?.let { mock.script.pageRangeFault = it == "1" }
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
    // F-37-E (vc52 Phase 10) idle-watchdog leg: MOCK_TURN_RUNNING=1 scripts the
    // snapshot's appended turn header as RUNNING — the phone's task machine reads
    // non-terminal (the pass-37 shape) and the stream watchdog engages on idle.
    if (System.getenv("MOCK_TURN_RUNNING") != null) {
        mock.setSessionTurnOutcome("running")
    }
    // Pass-16 (P.A2 watch-note): MOCK_TURN_STALL=1 arms a REAL frames>0 delivery
    // stall — the non-terminal turn (running) plus a subscribe-anchored schedule
    // that emits live row frames, then journals rows WITHOUT emitting; the stream
    // watchdog's resync replays the frames>0 backlog. Implies the running turn.
    if (System.getenv("MOCK_TURN_STALL") != null) {
        mock.setSessionTurnOutcome("running")
        mock.script.turnStall = true
        System.getenv("MOCK_TURN_STALL_LIVE")?.toIntOrNull()?.let { mock.script.turnStallLiveFrames = it }
        System.getenv("MOCK_TURN_STALL_EVERY_MS")?.toLongOrNull()?.let { mock.script.turnStallEveryMs = it }
    }
        // Pass-37 markdown conformance leg: MOCK_MD_FILE=<path> replaces the demo row's markdown.
    System.getenv("MOCK_MD_FILE")?.let { f -> mock.script.demoAssistantMarkdown = File(f).readText() }
    // MOCK_APPROVAL=1 arms one pending-approval tool row so the Approvals screen
    // renders live (B2.3/B2.4 geometry checks need a real interaction on-device).
    // RIG-P1: the id carries the generation nonce (two relay generations never collide).
    if (System.getenv("MOCK_APPROVAL") != null) {
        mock.setPendingApproval("Bash", nonceId("mock-approval-1", genNonce))
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
        // Phase 5B (A3.11): the rewind preview for the completed turn — one safe restore,
        // one unsafe (externally modified), one ignored (bash_ignored), canApply=true.
        mock.script.rewindPreview = JsonObject(mapOf(
            "canApply" to JsonPrimitive(true),
            "safeFiles" to JsonArray(listOf(
                JsonObject(mapOf("action" to JsonPrimitive("restore"), "path" to JsonPrimitive("src/version.ts"), "operationCount" to JsonPrimitive(1), "toolNames" to JsonArray(listOf(JsonPrimitive("edit"))))),
                JsonObject(mapOf("action" to JsonPrimitive("delete"), "path" to JsonPrimitive("scratch/probe.txt"), "operationCount" to JsonPrimitive(1), "toolNames" to JsonArray(listOf(JsonPrimitive("write"))))),
            )),
            "unsafeFiles" to JsonArray(listOf(
                JsonObject(mapOf("path" to JsonPrimitive("src/config.ts"), "reason" to JsonPrimitive("external_modified"), "message" to JsonPrimitive("Modified after the checkpoint"), "toolNames" to JsonArray(emptyList()))),
            )),
            "ignoredFiles" to JsonArray(listOf(
                JsonObject(mapOf("path" to JsonPrimitive("tmp/build.log"), "reason" to JsonPrimitive("bash_ignored"), "toolNames" to JsonArray(listOf(JsonPrimitive("bash"))))),
            )),
        ))
    }
    // Phase 4C (D2) rig leg: MOCK_GOAL=1 scripts a goal (with a pass/notSatisfied
    // verification timeline) + a todo list into the state block — the goal/plan
    // panels and the turn-kind label render live.
    if (System.getenv("MOCK_GOAL") != null) {        mock.script.goalState = JsonObject(mapOf(
            "targetId" to JsonPrimitive("g-rig"),
            "objective" to JsonPrimitive("Ship the release"),
            "summaryTitle" to JsonPrimitive("Ship it"),
            "timeUsedSeconds" to JsonPrimitive(120),
            "activeRunStartedAtMs" to JsonPrimitive(System.currentTimeMillis()),
            "status" to JsonPrimitive(System.getenv("MOCK_GOAL_STATUS") ?: "verifying"),
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
        // Phase 5B (A3.12 + A3.13): the plans viewer + the trajectory/search corpus.
        // p46: extracted to Script.seedPhase5bDemo() so the MOCK_TRAJECTORY arms below
        // mutate one source and the self-tests seed the exact served shape.
        mock.script.seedPhase5bDemo()
        // Pass-46 rig leg: MOCK_TRAJECTORY=<comma modes> mutates the trajectory seed —
        // usage (a third record carrying response.usage token columns), truncated (the
        // badge), empty (records=[] — the honest empty face; sessionMessages stays
        // seeded), unavailable (modelTrajectory=null → the typed fault the phone
        // renders as its unavailable face). Requires MOCK_GOAL (the Phase-5B seed is
        // goal-gated so the context sheet's goal/todos cards render alongside).
        System.getenv("MOCK_TRAJECTORY")?.takeIf { it.isNotBlank() }?.let { raw ->
            mock.script.applyTrajectoryModes(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }
        // Pass-46 rig leg: MOCK_CHECKPOINT_CONFLICT_FILES=<comma list> widens the
        // checkpoint conflict arm's served paths (default stays the single
        // src/config.ts) — the phone's conflicts.take(3) body slice is drivable.
        System.getenv("MOCK_CHECKPOINT_CONFLICT_FILES")?.takeIf { it.isNotBlank() }?.let { raw ->
            val files = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (files.isNotEmpty()) mock.script.checkpointConflictFiles = files
        }
    }
    // Phase 6A (I2.4) rig leg: MOCK_COMPACT=1 stamps the context-compaction lifecycle
    // markers (started:auto → completed:auto with token counts → failed:manual) into the
    // NEXT snapshot — the conversation renders the compaction notice rows per phase and
    // the failed one offers Retry (vm.retryCompaction → the compact command).
    if (System.getenv("MOCK_COMPACT") != null) {
        mock.script.compactionDemo = true
    }
    // Pass-33 rig leg: MOCK_ROW_KINDS=1 seeds the never-driven row-kind render faces
    // into the NEXT snapshot — the openable artifact card (bytes seeded for the
    // download affordance) + the quiet-fallback artifact (unknown extension, unseeded
    // ref) + the unknown-kind tolerance row + a non-compact timelineMarker + a
    // STREAMING reasoning row. Composes with MOCK_HOOKS (hook groups + the subagent
    // child-session row) and MOCK_COMPACT (the failed marker's Retry affordance).
    if (System.getenv("MOCK_ROW_KINDS") != null) {
        mock.script.rowKindsDemo = true
    }
    // Pass-34 rig leg: MOCK_ROW_TOOLS=1 seeds the toolCall row's PER-TOOL-KIND render
    // matrix — the CHANGES group ("2 files" count + durations), the EXECUTE group
    // (incl. the taxonomy-hole BashOutput), the ERROR card (error{code,message} +
    // actions.canRetry → the G66 recovery card), two >2k-preview Grep rows (one SERVED
    // via getTaskSnapshotToolCallsSlice, one unscripted = the fallback face), and the
    // cancelled/running/pendingApproval/unknown-status single cards.
    if (System.getenv("MOCK_ROW_TOOLS") != null) {
        mock.script.rowToolsDemo = true
    }
    // Pass-27 rig leg: MOCK_CHECKPOINT_CONFLICTS=1 arms restoreBetweenCheckpoints' conflict
    // branch — the first restore answers success=false + conflicts[], and the phone's
    // force path (restore anyway → force=true) flips it to the success face in the same leg.
    if (System.getenv("MOCK_CHECKPOINT_CONFLICTS") != null) {
        mock.script.checkpointConflicts = true
    }
    // Pass-27 rig leg: MOCK_FILECHANGES seeds the conversationFileChanges queue (pass-26
    // rig debt: the view chip's TurnDiffDialog had only ever seen fault.fileChanges.none).
    // Two entries so a second open in the same relay lifetime still serves content.
    if (System.getenv("MOCK_FILECHANGES") != null) {
        fun changeFile(path: String, added: Int, removed: Int, tool: String) = JsonObject(mapOf(
            "path" to JsonPrimitive(path),
            "additions" to JsonPrimitive(added),
            "deletions" to JsonPrimitive(removed),
            "writeCount" to JsonPrimitive(1),
            "toolNames" to JsonArray(listOf(JsonPrimitive(tool))),
            "patches" to JsonArray(listOf(JsonObject(mapOf(
                "oldStart" to JsonPrimitive(1), "oldLines" to JsonPrimitive(3),
                "newStart" to JsonPrimitive(1), "newLines" to JsonPrimitive(if (removed == 0) 3 + added else 3),
                "lines" to JsonArray(listOf(
                    JsonPrimitive(" const a = 1"),
                    JsonPrimitive("+const b = importMetaHot.data.a + 1"),
                    JsonPrimitive(" export { a }"),
                )),
            )))),
        ))
        repeat(2) {
            mock.script.conversationFileChanges.addLast(JsonObject(mapOf(
                "files" to JsonPrimitive(2),
                "additions" to JsonPrimitive(5),
                "deletions" to JsonPrimitive(1),
                "state" to JsonPrimitive("active"),
                "items" to JsonArray(listOf(
                    changeFile("src/version.ts", 3, 1, "edit"),
                    changeFile("scratch/probe.txt", 2, 0, "write"),
                )),
            )))
        }
    }
    // Phase 5A (A3.3 + L6.4) rig leg: MOCK_WORKFLOWS=1 scripts two saved workflows (one with a
    // required arg), one live session run with an event timeline, and one completed saved run —
    // the runs panel, the run sheet, and the hub's last-run states render live; Run… sends
    // startSavedWorkflow and the new run appears on the next refresh.
    if (System.getenv("MOCK_WORKFLOWS") != null) {
        val now = System.currentTimeMillis()
        mock.script.savedWorkflows = JsonObject(mapOf(
            "workflows" to JsonArray(listOf(
                JsonObject(mapOf(
                    "name" to JsonPrimitive("release-notes"), "description" to JsonPrimitive("Draft the release notes"),
                    "whenToUse" to JsonPrimitive("Before tagging a release"), "scope" to JsonPrimitive("project"),
                    "path" to JsonPrimitive("/synthetic/ws/.zcode/workflows/release-notes.ts"),
                    "args" to JsonObject(mapOf("version" to JsonObject(mapOf("type" to JsonPrimitive("string"), "required" to JsonPrimitive(true), "description" to JsonPrimitive("semver"))))),
                )),
                JsonObject(mapOf(
                    "name" to JsonPrimitive("lint-all"), "description" to JsonPrimitive("Lint every module"),
                    "scope" to JsonPrimitive("global"), "path" to JsonPrimitive("/synthetic/global/lint-all.ts"), "args" to JsonObject(emptyMap()),
                )),
            )),
            "invalid" to JsonArray(listOf(JsonObject(mapOf("path" to JsonPrimitive("/synthetic/ws/.zcode/workflows/broken.ts"), "reason" to JsonPrimitive("parse error"))))),
            "dir" to JsonPrimitive("/synthetic/ws/.zcode/workflows"),
        ))
        mock.script.workflowRuns.add(JsonObject(mapOf(
            "runId" to JsonPrimitive("wf-run-0"), "label" to JsonPrimitive("lint-all"), "status" to JsonPrimitive("running"),
            "updatedAt" to JsonPrimitive(now - 30_000), "resumable" to JsonPrimitive(false),
        )))
        mock.script.workflowRunEvents["wf-run-0"] = mutableListOf(
            JsonObject(mapOf("sequence" to JsonPrimitive(1), "type" to JsonPrimitive("run.started"), "payload" to JsonObject(mapOf("message" to JsonPrimitive("started lint-all"))))),
            JsonObject(mapOf("sequence" to JsonPrimitive(2), "type" to JsonPrimitive("node.dispatched"), "payload" to JsonObject(mapOf("siteId" to JsonPrimitive("s1"), "ordinal" to JsonPrimitive(0), "message" to JsonPrimitive("lint :core"))))),
            JsonObject(mapOf("sequence" to JsonPrimitive(3), "type" to JsonPrimitive("node.settled"), "payload" to JsonObject(mapOf("siteId" to JsonPrimitive("s1"), "ordinal" to JsonPrimitive(0), "outcome" to JsonPrimitive("ok"))))),
        )
        mock.script.savedWorkflowRuns.add(JsonObject(mapOf(
            "runId" to JsonPrimitive("wf-run-old"), "name" to JsonPrimitive("release-notes"), "status" to JsonPrimitive("completed"),
            "createdAt" to JsonPrimitive(now - 86_400_000), "updatedAt" to JsonPrimitive(now - 86_000_000), "spentTokens" to JsonPrimitive(4321),
            // G17 (vc51 Phase 4D): the completion's artifact previews on the rig.
            "artifacts" to JsonArray(listOf(JsonObject(mapOf(
                "ref" to JsonPrimitive("wf-art-release-1"),
                "name" to JsonPrimitive("RELEASE-NOTES.md"),
                "mime" to JsonPrimitive("text/markdown"),
            )))),
        )))
        // G3 (vc51) rig leg: a RESUMABLE run — the run sheet's Resume action has a live
        // target (resumeWorkflowRun flips it to running + resumable=false, honestly).
        mock.script.workflowRuns.add(JsonObject(mapOf(
            "runId" to JsonPrimitive("wf-run-s1"), "label" to JsonPrimitive("release-notes"), "status" to JsonPrimitive("stopped"),
            "updatedAt" to JsonPrimitive(now - 60_000), "resumable" to JsonPrimitive(true),
            "stopReason" to JsonPrimitive("interrupted"),
        )))
        mock.script.workflowRunEvents["wf-run-s1"] = mutableListOf(
            JsonObject(mapOf("sequence" to JsonPrimitive(1), "type" to JsonPrimitive("run.started"), "payload" to JsonObject(mapOf("message" to JsonPrimitive("started release-notes"))))),
            JsonObject(mapOf("sequence" to JsonPrimitive(2), "type" to JsonPrimitive("run.stopped"), "payload" to JsonObject(mapOf("message" to JsonPrimitive("interrupted"))))),
        )
        mock.script.savedWorkflowRuns.add(JsonObject(mapOf(
            "runId" to JsonPrimitive("wf-run-s1"), "name" to JsonPrimitive("release-notes"), "status" to JsonPrimitive("stopped"),
            "createdAt" to JsonPrimitive(now - 70_000), "updatedAt" to JsonPrimitive(now - 60_000), "spentTokens" to JsonPrimitive(221),
        )))
    }
    // Phase 5A (A3.8–A3.10) rig leg: MOCK_TASKMGMT=1 pins the first synthetic task, marks the
    // second unread, and seeds one custom group — the pinned section, the unread dot and the
    // group sub-section render live; the ⋯ verbs round-trip against the mock's state.
    if (System.getenv("MOCK_TASKMGMT") != null) {
        armTaskManagementRig(mock)
    }
    // p43 (debt #29) rig leg: MOCK_TASKS_MANY=1 seeds 24 extra synthetic tasks (27 total
    // matching "synthetic") so the phone's listTaskList search — limit:20 — crosses the
    // desktop-faithful pagination face (items=20, total=27, hasMore=true). Additive:
    // arm it ALONGSIDE MOCK_TASKMGMT; on its own it still seeds the search surface.
    if (System.getenv("MOCK_TASKS_MANY") != null) {
        armTasksManyRig(mock)
    }
    // RIG-P1 (vc52 Phase 7, G77): MOCK_AUTO_RESOLUTION=grace|countdown|snoozed|expired
    // serves the byte-verified desktop autoResolution discriminatedUnion on EVERY
    // pending permission/userInput entry (the 5-minute fuse the phone must decode and
    // render). MOCK_AUTO_RESOLUTION_MS sizes the fuse (default 300000 = the desktop
    // policy default; the rig usually arms something short). The "countdown" arm
    // DROPS the entry once the fuse fires (the auto-continue), "expired" serves a
    // deadline in the past (the already-expired, non-actionable face).
    if (System.getenv("MOCK_AUTO_RESOLUTION") != null) {
        val arm = System.getenv("MOCK_AUTO_RESOLUTION")!!
        require(arm in setOf("grace", "countdown", "snoozed", "expired")) {
            "MOCK_AUTO_RESOLUTION must be one of grace|countdown|snoozed|expired, got: $arm"
        }
        mock.script.autoResolutionArm = arm
        System.getenv("MOCK_AUTO_RESOLUTION_MS")?.toLongOrNull()?.let { mock.script.autoResolutionMs = it }
    }
    // Phase 5B (A3.14) rig leg: MOCK_QUESTION=1 scripts a blocking AskUserQuestion
    // elicitation (two questions, one multi-select) into the snapshot's pending
    // interactions — the card renders and the answer/decline ride resolveInteraction.
    // RIG-P1: the interaction id carries the generation nonce (a gen push with the
    // base name can never re-claim it — the pass-35 anchor confusion).
    if (System.getenv("MOCK_QUESTION") != null) {
        val questionId = nonceId("q-rig-1", genNonce)
        mock.script.pendingUserInput = MockZCode.PendingUserInput(
            interactionId = questionId,
            prompt = "How should I ship this?",
            freeText = false,
            options = emptyList(),
        )
        mock.script.pendingQuestion = JsonObject(mapOf(
            "interactionId" to JsonPrimitive(questionId),
            "prompt" to JsonPrimitive("How should I ship this?"),
            "questions" to JsonArray(listOf(
                JsonObject(mapOf(
                    "question" to JsonPrimitive("Which channel?"),
                    "header" to JsonPrimitive("Channel"),
                    "multiSelect" to JsonPrimitive(false),
                    "options" to JsonArray(listOf(
                        JsonObject(mapOf("value" to JsonPrimitive("tag"), "label" to JsonPrimitive("Tag the release"))),
                        JsonObject(mapOf("value" to JsonPrimitive("branch"), "label" to JsonPrimitive("Cut a branch"))),
                    )),
                )),
                JsonObject(mapOf(
                    "question" to JsonPrimitive("Which extras?"),
                    "header" to JsonPrimitive("Extras"),
                    "multiSelect" to JsonPrimitive(true),
                    "options" to JsonArray(listOf(
                        JsonObject(mapOf("value" to JsonPrimitive("notes"), "label" to JsonPrimitive("Release notes"))),
                        JsonObject(mapOf("value" to JsonPrimitive("changelog"), "label" to JsonPrimitive("Changelog"))),
                    )),
                )),
            )),
        ))
    }
    // Phase 5D (ADR-024) rig leg: MOCK_HOOKS=1 scripts a workspaceHookReview (2 pending +
    // 1 trusted item) — the blocking card renders; trust_selected flips the states live.
    // RIG-P1: the interaction id carries the generation nonce; the served summary
    // block is DERIVED from the live items (scriptedHookInteraction), never the
    // static arm-time face.
    if (System.getenv("MOCK_HOOKS") != null) {
        // G71 (vc52 Phase 12): the hooks-monitor snapshot rows ride the same arm.
        mock.script.hooksRowsDemo = true
        mock.script.hookReview = JsonObject(mapOf(
            "kind" to JsonPrimitive("workspaceHookReview"),
            "reviewFlowId" to JsonPrimitive("flow-rig"), "generation" to JsonPrimitive(1),
            "interactionId" to JsonPrimitive(nonceId("hook-rig", genNonce)), "sessionId" to JsonPrimitive("synth-task-1"),
            "taskId" to JsonPrimitive("synth-task-1"), "runId" to JsonPrimitive("run-rig"),
            "workspaceIdentity" to JsonPrimitive("synthetic-ws"), "workspaceLabel" to JsonPrimitive("Synthetic Workspace"),
            "bundleDigest" to JsonPrimitive("a".repeat(64)),
            "createdAt" to JsonPrimitive(System.currentTimeMillis() - 60_000),
            "deadlineAt" to JsonPrimitive(System.currentTimeMillis() + 300_000),
            "summary" to JsonObject(mapOf("eventCount" to JsonPrimitive(2), "hookCount" to JsonPrimitive(3), "pendingCount" to JsonPrimitive(2))),
            "items" to JsonArray(listOf(
                JsonObject(mapOf(
                    "reviewItemId" to JsonPrimitive("h1"), "event" to JsonPrimitive("SessionStart"),
                    "type" to JsonPrimitive("command"), "displayName" to JsonPrimitive("Warm build cache"),
                    "displayCommand" to JsonPrimitive("echo warming"), "sourcePath" to JsonPrimitive(".zcode/hooks.json"),
                    "executionMode" to JsonPrimitive("foreground"), "configuredEnabled" to JsonPrimitive(true),
                    "editable" to JsonPrimitive(true), "trustState" to JsonPrimitive("pending_trust"),
                )),
                JsonObject(mapOf(
                    "reviewItemId" to JsonPrimitive("h2"), "event" to JsonPrimitive("PreToolUse"),
                    "type" to JsonPrimitive("command"), "displayName" to JsonPrimitive("Lint gate"),
                    "displayCommand" to JsonPrimitive("npm run lint"), "sourcePath" to JsonPrimitive(".zcode/hooks.json"),
                    "executionMode" to JsonPrimitive("background"), "configuredEnabled" to JsonPrimitive(true),
                    "editable" to JsonPrimitive(false), "trustState" to JsonPrimitive("pending_trust"),
                )),
                JsonObject(mapOf(
                    "reviewItemId" to JsonPrimitive("h3"), "event" to JsonPrimitive("Stop"),
                    "type" to JsonPrimitive("process"), "displayName" to JsonPrimitive("Watcher"),
                    "displayCommand" to JsonPrimitive("watch build"), "sourcePath" to JsonPrimitive(".zcode/hooks.json"),
                    "executionMode" to JsonPrimitive("background"), "configuredEnabled" to JsonPrimitive(true),
                    "editable" to JsonPrimitive(true), "trustState" to JsonPrimitive("trusted_persistent"),
                )),
            )),
        ))
    }
    // Pass-45 (p45 hooks-second): MOCK_HOOKS_SECOND=1 seeds a SECOND task's review
    // (synth-task-2, distinct flow/interaction ids + h4/h5 items) — the hooks viewer's
    // per-task group separation (hooks_group_<taskId>) needs two ledger entries;
    // pass 44 could only ever render one group against the single seed. Shares
    // hookReviewsResolved with the first review (the resolve leg stays F-P3-A-gated).
    if (System.getenv("MOCK_HOOKS_SECOND") != null) {
        mock.script.hookReviewSecond = JsonObject(mapOf(
            "kind" to JsonPrimitive("workspaceHookReview"),
            "reviewFlowId" to JsonPrimitive("flow-rig-2"), "generation" to JsonPrimitive(1),
            "interactionId" to JsonPrimitive(nonceId("hook-rig-2", genNonce)), "sessionId" to JsonPrimitive("synth-task-2"),
            "taskId" to JsonPrimitive("synth-task-2"), "runId" to JsonPrimitive("run-rig-2"),
            "workspaceIdentity" to JsonPrimitive("synthetic-ws-two"), "workspaceLabel" to JsonPrimitive("Synthetic Workspace Two"),
            "bundleDigest" to JsonPrimitive("b".repeat(64)),
            "createdAt" to JsonPrimitive(System.currentTimeMillis() - 30_000),
            "deadlineAt" to JsonPrimitive(System.currentTimeMillis() + 300_000),
            "summary" to JsonObject(mapOf("eventCount" to JsonPrimitive(2), "hookCount" to JsonPrimitive(2), "pendingCount" to JsonPrimitive(1))),
            "items" to JsonArray(listOf(
                JsonObject(mapOf(
                    "reviewItemId" to JsonPrimitive("h4"), "event" to JsonPrimitive("PreToolUse"),
                    "type" to JsonPrimitive("command"), "displayName" to JsonPrimitive("Deploy check"),
                    "displayCommand" to JsonPrimitive("fly deploy --wait"), "sourcePath" to JsonPrimitive("deploy/hooks.json"),
                    "executionMode" to JsonPrimitive("foreground"), "configuredEnabled" to JsonPrimitive(true),
                    "editable" to JsonPrimitive(true), "trustState" to JsonPrimitive("pending_trust"),
                )),
                JsonObject(mapOf(
                    "reviewItemId" to JsonPrimitive("h5"), "event" to JsonPrimitive("Stop"),
                    "type" to JsonPrimitive("process"), "displayName" to JsonPrimitive("Nightly sync"),
                    "displayCommand" to JsonPrimitive("rsync -a /data /backup"), "sourcePath" to JsonPrimitive("deploy/hooks.json"),
                    "executionMode" to JsonPrimitive("background"), "configuredEnabled" to JsonPrimitive(true),
                    "editable" to JsonPrimitive(false), "trustState" to JsonPrimitive("trusted_persistent"),
                )),
            )),
        ))
    }
    // Pass-11 rig leg (AB4.1 rig debt): MOCK_QUEUE=1 seeds 2 queued follow-ups +
    // autoDrain=false so the QueueStrip renders and the four verbs (send now / edit /
    // move up-down / auto-drain) become FIRST-executable live.
    if (System.getenv("MOCK_QUEUE") != null) {
        mock.script.queueItems = listOf(
            MockZCode.QueueItem("q-rig-1", "queued follow-up one"),
            MockZCode.QueueItem("q-rig-2", "queued follow-up two"),
        )
        mock.script.autoDrain = false
    }
    // Pass-17 rig arm (debt #18): MOCK_INPUT_ROUTING=<mode> pins the state's
    // inputRouting mode (the desktop enum: startNow|enqueue|guide|reject|choice) so
    // the phone's held-queue CHOICE disposition dialog (G50) is drivable on the rig —
    // the mock otherwise only ever serves startNow/enqueue. Sticky: the send/stop
    // handler flips are suppressed under the arm. Fail-fast on a bad value (the
    // H.A0.2 empty-string rule).
    System.getenv("MOCK_INPUT_ROUTING")?.let { mode ->
        require(mode in setOf("startNow", "enqueue", "guide", "reject", "choice")) {
            "MOCK_INPUT_ROUTING must be one of startNow|enqueue|guide|reject|choice (got '$mode')"
        }
        mock.script.inputRoutingMode = mode
        mock.script.inputRoutingArmSticky = true
    }
    // Pass-19 rig arm (debt #20): MOCK_QUEUE_GHOST_APPEND_MS=<ms> arms the desktop's
    // held-queue CAS validation (host applyHeldQueueDisposition: a trio-bearing
    // sendText whose expectedHeldQueueItemIds no longer matches the live queue gets
    // {status:"failed", reasonCode:"guard.heldQueueConfirmationStale"}) AND the
    // one-shot SILENT ghost append <ms> after the FIRST trio-bearing sendText — the
    // queue moves server-side with NO patch, so the next dialog answer races it.
    // Drive with MOCK_QUEUE=1 + MOCK_INPUT_ROUTING=choice (the trio only flows on
    // the G50 dialog path).
    System.getenv("MOCK_QUEUE_GHOST_APPEND_MS")?.let { raw ->
        val ms = raw.toLongOrNull()
        require(ms != null && ms >= 0) {
            "MOCK_QUEUE_GHOST_APPEND_MS must be a non-negative integer (got '$raw')"
        }
        mock.script.heldQueueCasArm = true
        mock.script.heldQueueGhostDelayMs = ms
    }
    // Pass-20 rig arm (F-P20-A + the stale-face sweep): MOCK_CAS_STALE_TYPES=a,b,c
    // forces the listed command types to answer the host's revision-mismatch ack
    // {status:"stale", reasonCode:"proto.staleRevision"} — the phone's typed
    // SESSION_CHANGED stale faces become drivable on the rig (the desktop recovers
    // silently via recoverFromStaleAuthority; the phone warns honestly).
    System.getenv("MOCK_CAS_STALE_TYPES")?.let { raw ->
        val types = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        require(types.isNotEmpty()) {
            "MOCK_CAS_STALE_TYPES must name at least one command type (got '$raw')"
        }
        mock.script.casStaleTypes = types
    }
    // Pass-37 rig arm (Part AN): MOCK_FEEDBACK_FAIL=1 makes setAssistantFeedback answer
    // the host's failed ack {status:"failed", reasonCode:"fault.feedback.fail"} — the
    // phone's keyed FEEDBACK_REFUSED notice becomes drivable. Mutates nothing.
    if (System.getenv("MOCK_FEEDBACK_FAIL") == "1") mock.script.feedbackFail = true
    // Pass-39 rig arm: MOCK_ARCHIVED_FAULT=1 makes listArchivedTasks answer the typed
    // RPC ERROR fault.archived.rig — the archived viewer's UNAVAILABLE face (the
    // null-list branch pass 38 left undriven) becomes drivable. Mutates nothing.
    if (System.getenv("MOCK_ARCHIVED_FAULT") == "1") mock.script.archivedFault = true
    // Pass-39 rig arm: MOCK_UNREAD_CAS=1 makes setTaskUnread(unread=false) validate the
    // client's expectedUnreadAt against the stored stamp (the desktop's host-side CAS);
    // a mismatch answers fault.unread.stale:expectedUnreadAt and mutates nothing — the
    // mark-read refusal face becomes drivable. The arg is logged either way.
    // MOCK_UNREAD_CAS=force drifts the comparison (expected+1) so every mark-read
    // mismatches — the deterministic desktop-diverged-elsewhere simulator.
    when (System.getenv("MOCK_UNREAD_CAS")) {
        "1" -> mock.script.unreadCas = true
        "force" -> { mock.script.unreadCas = true; mock.script.unreadCasForce = true }
    }
    // Pass-22 rig arm (Part X leftover): MOCK_SET_THOUGHT_LEVEL_STALE=1 makes the
    // effort switch (the PROTOCOL method v4Call setThoughtLevel) answer the host's
    // FQ revision-mismatch shape — the JSON-RPC ERROR Dl(-32009, "Session state
    // revision mismatch", {actualRevision, expectedRevision}) — and mutate nothing.
    // The command-typed stale arm cannot reach this surface (it is not a
    // sendConversationCommandV4 type), which is exactly why the phone's effort
    // STALE notice branch needed its own arm to adjudicate.
    if (System.getenv("MOCK_SET_THOUGHT_LEVEL_STALE") != null) {
        mock.script.setThoughtLevelStale = true
    }
    // Pass-22 rig arm (Part X leftover): MOCK_COMPACT_COMMAND=1 scripts the host's
    // command-plane compact handler (vMc): under enqueue/guide/choice the compact
    // SELF-QUEUES (kind:"compact" queue item + queue patch + accepted ack, no rows)
    // and a second compact answers the typed rejected ack compactOperationLock.
    // The phone's /compact-under-choice "structurally QUEUED, not refused" face is
    // finally live-drivable (pass 21 left it static-only, mock-unscripted).
    if (System.getenv("MOCK_COMPACT_COMMAND") != null) {
        mock.script.compactCommandArm = true
    }
    // Pass-11 rig leg (AB4.1 rig debt): MOCK_REFUSE_COMMANDS=1 makes the goal verbs
    // answer respondError — the SendNotice.GOAL_*_REFUSED degrade branches finally run.
    // Pass-44 extension (p44-mock-refuse-list): a comma-list value arms ANY command
    // types (e.g. MOCK_REFUSE_COMMANDS=requestWorkspaceHookReview — the hooks
    // viewer's REFUSED note face); the bare `1`/`true`/blank keeps the goal trio.
    if (System.getenv("MOCK_REFUSE_COMMANDS") != null) {
        val spec = System.getenv("MOCK_REFUSE_COMMANDS")!!
        mock.script.refuseCommandTypes =
            if (spec.isBlank() || spec == "1" || spec.equals("true", ignoreCase = true)) {
                setOf("sendGoal", "pauseGoal", "resumeGoal")
            } else {
                spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
    }
    // Mock-fidelity debt #1 (pass 13): MOCK_UNKNOWN_INTERACTION_IDS=1 arms the typed
    // unknown-id rejection on resolveInteraction — an answer naming no pending slot
    // gets {status:"rejected", reasonCode:"fault.interaction.unknownId"} instead of
    // the old unconditional accept, so the app's answer-with-error face is drivable.
    if (System.getenv("MOCK_UNKNOWN_INTERACTION_IDS") != null) {
        mock.script.unknownInteractionIdsArm = true
    }
    // RIG-3 (vc51 Phase 5): MOCK_INTERACTION_PUSH_MS=<ms> schedules a FRESH approval
    // pushed AFTER the FIRST conversation subscribe — pair, open a task, BACKGROUND the
    // app within the window, and the shade posts the approval WITH its two actions (the
    // fresh path; the A12 re-post path is pinned app-side by NotificationBrokerTest).
    // Optional overrides: MOCK_INTERACTION_PUSH_TOOL=<toolName>, MOCK_INTERACTION_PUSH_ID=<id>.
    System.getenv("MOCK_INTERACTION_PUSH_MS")?.toLongOrNull()?.let { ms ->
        mock.script.interactionPushDelayMs = ms
        System.getenv("MOCK_INTERACTION_PUSH_TOOL")?.let { mock.script.interactionPushTool = it }
        System.getenv("MOCK_INTERACTION_PUSH_ID")?.let { mock.script.interactionPushId = it }
    }
    // vc51 Phase-3 (G13): MOCK_SESSIONS_INDEX=1 scripts the sessions-index snapshot
    // (task-1 running) plus a DELAYED `session.upserted` that flips task-3 to running
    // with a preview — the live cross-session row-activity flip leg (no refresh).
    if (System.getenv("MOCK_SESSIONS_INDEX") != null) {
        fun indexSession(id: String, title: String, phase: String, ended: Boolean, preview: String?): JsonObject =
            JsonObject(mapOf(
                "sessionId" to JsonPrimitive(id),
                "workspaceId" to JsonPrimitive("synthetic-ws-one"),
                "title" to JsonPrimitive(title),
                "phase" to JsonPrimitive(phase),
                "sessionEnded" to JsonPrimitive(ended),
                "hasBackgroundWork" to JsonPrimitive(false),
                "lastActivityAt" to JsonPrimitive(System.currentTimeMillis().toDouble()),
                "createdAt" to JsonPrimitive(System.currentTimeMillis().toDouble()),
            ) + (preview?.let { mapOf("lastAssistantPreview" to JsonPrimitive(it)) } ?: emptyMap()))
        mock.script.sessionsIndexSnapshot = JsonObject(mapOf(
            "protocolVersion" to JsonPrimitive(1),
            "workspaceId" to JsonPrimitive("synthetic-ws-one"),
            "logEpoch" to JsonPrimitive("idx-epoch-mock-1"),
            "sessions" to JsonArray(listOf(
                indexSession("synth-task-1", "Synthetic task", "running", ended = false, preview = "working on the bootstrap"),
                indexSession("synth-task-2", "Synthetic task 2", "idle", ended = false, preview = null),
                indexSession("synth-task-3", "Synthetic task 3", "idle", ended = false, preview = null),
            )),
        ))
        mock.script.sessionsIndexUpsertDelayMs = 5_000L
        mock.script.sessionsIndexUpsertDelta = JsonObject(mapOf(
            "op" to JsonPrimitive("session.upserted"),
            "session" to indexSession("synth-task-3", "Synthetic task 3", "running", ended = false, preview = "agent just started"),
        ))
    }
    // vc51 Phase-3 (G15): MOCK_BACKGROUND_BASH=1 arms a running background bash in
    // the state block + the typed output read (background-bash-output.ts shape).
    if (System.getenv("MOCK_BACKGROUND_BASH") != null) {
        mock.script.backgroundWorks = JsonArray(listOf(JsonObject(mapOf(
            "workId" to JsonPrimitive("work-bash-1"),
            "kind" to JsonPrimitive("bash"),
            "title" to JsonPrimitive("synthetic background build"),
            "status" to JsonPrimitive("running"),
            "cancellable" to JsonPrimitive(true),
        ))))
        mock.script.backgroundBashOutputs["work-bash-1"] = JsonObject(mapOf(
            "kind" to JsonPrimitive("output"),
            "workId" to JsonPrimitive("work-bash-1"),
            "status" to JsonPrimitive("running"),
            "output" to JsonPrimitive("compiling module 1/3\ncompiling module 2/3\n"),
            "truncated" to JsonPrimitive(false),
            "outputPath" to JsonPrimitive("/synthetic/ws-one/.zcode/background/work-bash-1.log"),
        ))
    }
    // RIG-2 (vc51 Phase-3, G9): MOCK_WORKSPACE_FILES=1 seeds file-tree entries so
    // searchWorkspaceFiles returns hits and the composer mention panel renders them.
    if (System.getenv("MOCK_WORKSPACE_FILES") != null) {
        mock.script.seedWorkspaceFiles(listOf(
            "src/main.rs", "src/lib.rs", "docs/plan.md", "README.md", "Cargo.toml",
        ))
        // Pass-21 (Files viewer live leg): paged-read content for the §23.4 viewer —
        // readTextFile serves slices of this, so the paged text viewer has real bytes.
        mock.script.fileText["README.md"] = (1..40).joinToString("\n") { i ->
            "# Rig workspace — line $i\nSome prose for the paged file viewer leg (pass 21)."
        }
    }
    // vc51 Phase-3 (G9): MOCK_SHARED_CONTEXT=1 seeds the share-handover state so the
    // composer surfaces the shared-context chip and the send carries context_refs.
    if (System.getenv("MOCK_SHARED_CONTEXT") != null) {
        mock.script.sharedContextImport = JsonObject(mapOf(
            "contextId" to JsonPrimitive("ctx-share-rig-1"),
            "title" to JsonPrimitive("Shared: release-notes rewrite"),
            "shareUrl" to JsonPrimitive("https://zcode.example/cn/share/rigshare1"),
            "status" to JsonPrimitive("pending"),
        ))
    }
    // G18 (vc51 Phase 4D): MOCK_CUA=1 scripts three consecutive computer-use tool rows
    // (the shipped CUA prefixes) into the snapshot — one carries a real 1×1 PNG as the
    // screenshot payload so the frame renders inside the expanded tool card.
    if (System.getenv("MOCK_CUA") != null) {
        armCuaRigForTest(mock)
    }
    // RIG-P3 (vc52 Phase 14, the Phase-13 fixture debt): MOCK_TOOLS=1 seeds KERNEL
    // tool rows (the G80 name-keyed taxonomy: TaskUpdate/Bash/Edit/Glob/WebSearch)
    // into the snapshot conversation — the tool-card kind subtitles and the row
    // grouping render live (the seeded conversation used to carry text/thinking/plan
    // rows only, so the leg was compose-pin-covered).
    if (System.getenv("MOCK_TOOLS") != null) {
        fun toolRow(tool: String, input: String, output: String) {
            mock.appendRow(
                MockZCode.RowKind.TOOL_CALL,
                "success",
                extra = buildJsonObject {
                    put("toolName", JsonPrimitive(tool))
                    put("toolCallId", JsonPrimitive("tool-rig-${tool.lowercase()}"))
                    put("inputText", JsonPrimitive(input))
                    put("outputPreview", JsonPrimitive(output))
                },
            )
        }
        toolRow("TaskUpdate", "{\"taskId\":\"synth-task-1\",\"status\":\"in_progress\"}", "{\"ok\":true}")
        toolRow("Bash", "{\"command\":\"git status --short\"}", "M src/version.ts\n?? scratch/probe.txt")
        toolRow("Edit", "{\"path\":\"src/version.ts\"}", "{\"ok\":true,\"replacements\":1}")
        toolRow("Glob", "{\"pattern\":\"src/**/*.ts\"}", "src/version.ts\nsrc/main.ts")
        toolRow("WebSearch", "{\"query\":\"kotlin compose desktop\"}", "3 results")
    }
    // G48+G51 (vc52 Phase 11): MOCK_ENFORCE_RUN_DELTAS=1 arms the capability-enforcing
    // workflowRun delta stream — a scripted workflowRun.updated (run patch + actor/node
    // step graph) at MOCK_RUN_DELTA_MS (default 4000) and a workflowRun.removed at
    // MOCK_RUN_REMOVE_MS (default 14000), streamed ONLY when the client's clientHello
    // declared capabilities.workflowRunDeltas === true (the shipped desktop's gate).
    if (System.getenv("MOCK_ENFORCE_RUN_DELTAS") != null) {
        mock.script.enforceWorkflowRunDeltas = true
        System.getenv("MOCK_RUN_DELTA_MS")?.toLongOrNull()?.let { mock.script.runDeltaUpdateMs = it }
        System.getenv("MOCK_RUN_REMOVE_MS")?.toLongOrNull()?.let { mock.script.runDeltaRemoveMs = it }
        // Debt #13 (pass 14): MOCK_RUN_DELTA_ANCHOR=subscribe anchors the schedule at
        // the FIRST conversation subscribe (the relay-start anchor raced every
        // reconnect — the deltas fired before the app ever subscribed). Default:
        // the legacy relay-start anchor.
        if (System.getenv("MOCK_RUN_DELTA_ANCHOR")?.trim() == "subscribe") {
            mock.script.runDeltasAnchorSubscribe = true
        } else {
            armWorkflowRunDeltasForTest(mock)
        }
    }
    // G32 (vc51 Phase 4): MOCK_GIT=1 scripts the git-channel read legs so the Git pane
    // reaches the branch sheet — the summary (main, clean, dirty) feeds the header row
    // and the branch list (main + feature/rig) feeds the switch/create sheet.
    if (System.getenv("MOCK_GIT") != null) {
        mock.script.gitSummary = JsonObject(mapOf(
            "repoRoot" to JsonPrimitive("/synthetic/repo"),
            "branchName" to JsonPrimitive("main"),
            "trackingBranchName" to JsonPrimitive("origin/main"),
            "headRefType" to JsonPrimitive("branch"),
            "ahead" to JsonPrimitive(0),
            "behind" to JsonPrimitive(0),
            "isDirty" to JsonPrimitive(false),
            "isGitAvailable" to JsonPrimitive(true),
            "isRepository" to JsonPrimitive(true),
        ))
        // getLocalBranches is served hardcoded (main + feature/a) — no script field.
        // G53 (vc52 Phase 11): the depth trio rides the same arm — the comparison
        // (main to HEAD with two per-file changes), the ignored paths, the repo info.
        mock.script.gitBranchComparison = JsonObject(mapOf(
            "baseRef" to JsonPrimitive("origin/main"),
            "headRef" to JsonPrimitive("main"),
            "comparisonLabel" to JsonPrimitive("main -> origin/main"),
            "changes" to JsonArray(listOf(
                JsonObject(mapOf(
                    "path" to JsonPrimitive("src/feature.ts"),
                    "originalPath" to JsonNull,
                    "kind" to JsonPrimitive("added"),
                    "added" to JsonPrimitive(42),
                    "removed" to JsonPrimitive(0),
                )),
                JsonObject(mapOf(
                    "path" to JsonPrimitive("src/readme.md"),
                    "originalPath" to JsonNull,
                    "kind" to JsonPrimitive("modified"),
                    "added" to JsonPrimitive(3),
                    "removed" to JsonPrimitive(7),
                )),
            )),
        ))
        mock.script.gitIgnoredPaths = JsonArray(listOf(JsonPrimitive("build/"), JsonPrimitive(".env")))
        mock.script.gitWorkspaceRepositoryInfo = JsonObject(mapOf(
            "workspacePath" to JsonPrimitive("/synthetic/ws-one"),
            "kind" to JsonPrimitive("main-tree"),
            "isGitAvailable" to JsonPrimitive(true),
        ))
        // RIG-P2 (vc52 Phase 14): the READ fixtures the N-BG-2 legs never had — the
        // Commit dialog's Generate button, the Changes tab and the History tab serve
        // real data (the recorded face was silent refusals on all three). The
        // deliberate-fault arm (MOCK_GIT_REFUSALS) still answers FIRST per method.
        mock.script.generatedCommitMessage = JsonObject(mapOf(
            "message" to JsonPrimitive("chore(rig): bump the synthetic parser version\n\nReviewed the staged diff and drafted a conventional message."),
        ))
        // N-BK-1 (pass-5 rig fix): the fixtures serve the CONTRACT vocabulary the app
        // decodes (data/Phase9Tools.kt) — workspaceRelativePath (the row label), kind,
        // section ("unstaged"/"untracked"/"staged"), added/removed — and getChanges
        // filters rows by the requested sourceId. The old status/staged/additions/
        // deletions shape decoded to blank labels, +0 −0, and every row duplicated
        // into BOTH tabs. Literals live in [RigGitFixtures] so the self-test pins the
        // contract against the LIVE fixture build (not a copy).
        mock.script.gitChanges = RigGitFixtures.changes()
        // p41 (pass-41): seed the WRITE TIER's staged set from the fixture's staged rows.
        // servedGitChanges only PROMOTED rows into "staged"; a fixture-staged row absent
        // from stagedPaths could never leave the tab — the live receipt: unstagePaths ok
        // → the refetch served the SAME staged row (index.ts stuck under Staged (1)).
        RigGitFixtures.seedWriteTier(mock.script)
        // p42 (pass-42): MOCK_GIT_MANY=1 swaps the Changes fixture for the many-rows set
        // (44 unstaged: 38 nested + 6 root, + 2 staged) crossing GitScreen's J1.2 gates — SEARCH_GATE=10
        // (the per-section search field renders) and TREE_GATE=40 (the collapsible
        // directory tree replaces the flat list) — and re-seeds the write tier from THAT
        // fixture so the served-section derivation (p41) agrees.
        if (System.getenv("MOCK_GIT_MANY") == "1") {
            mock.script.gitChanges = RigGitFixtures.manyChanges()
            RigGitFixtures.seedWriteTier(mock.script, RigGitFixtures.manyChanges())
        }
        // p42 (debt #28): the DECODER contract shape — {path, availability:"patch", patch}.
        // The pass-5-era {diff: ...} literal decoded to availability="unavailable" +
        // patch=null (the honest unavailable face on the first live diff-expand).
        mock.script.gitDiff = RigGitFixtures.diff()
        // N-BK-1: the commit graph decodes hash/parents/refs/subject/authorName/
        // authoredAtMs (data/Phase9Tools.kt) — the old `oid`/`message`/`timestamp`
        // keys dropped EVERY row (hash was required) so History rendered silent-empty.
        mock.script.gitCommits = RigGitFixtures.commits()
    }
    // pass-0 Phase 4 rig leg (N-BK-1): MOCK_GIT_EMPTY_COMMITS=1 serves a HEALTHY but
    // EMPTY commit graph (getCommitGraph ok with commits=[] hasMore=false) — the app
    // half's "No commits yet" empty state is drivable live (the unscripted fixture
    // refuses instead, which is the F-P5-A face, not the empty face). Runs AFTER the
    // MOCK_GIT fixture arm so the override wins ordering.
    if (System.getenv("MOCK_GIT_EMPTY_COMMITS") == "1") {
        mock.script.gitCommits = kotlinx.serialization.json.JsonArray(emptyList())
        mock.script.gitHasMore = false
    }
    // G67 (vc52 Phase 11): MOCK_REMOTE_DOWN_MS=N arms the remote-down fixture N ms
    // after startup — the NEXT bridge-open fails with the typed disconnected-remote
    // error (the Reconnect card's live face); workspace-reconnect-request flips it.
    System.getenv("MOCK_REMOTE_DOWN_MS")?.toLongOrNull()?.let { delayMs ->
        val t = Thread {
            try {
                Thread.sleep(delayMs)
                mock.armRemoteDown()
            } catch (_: InterruptedException) { }
        }
        t.isDaemon = true
        t.name = "mock-remote-down"
        t.start()
    }
    // G64 (vc52 Phase 11): MOCK_CUA_PERMISSION=denied|stale|granted pushes the CUA
    // permission observation through the scoped listen at MOCK_CUA_PERMISSION_MS
    // (default 3000) — the typed card renders unless the payload is granted.
    if (System.getenv("MOCK_CUA_PERMISSION") != null) {
        val status = System.getenv("MOCK_CUA_PERMISSION")!!
        require(status in setOf("denied", "stale", "granted")) { "MOCK_CUA_PERMISSION must be denied|stale|granted, got: $status" }
        val delayMs = System.getenv("MOCK_CUA_PERMISSION_MS")?.toLongOrNull() ?: 3_000L
        val t = Thread {
            try {
                Thread.sleep(delayMs)
                mock.pushCuaPermissionObservation(
                    mock.blockedCuaPayload(mock.workspace.sessionId, accessibility = status, screenRecording = "granted"),
                )
            } catch (_: InterruptedException) { }
        }
        t.isDaemon = true
        t.name = "mock-cua-permission"
        t.start()
    }
    // N-BG-2 (vc52 Phase 11) / RIG-P2 (vc52 Phase 14): MOCK_GIT_REFUSALS arms the
    // deliberate git-refusal map so the phone's SURFACING of refused git calls is
    // drivable on the rig. Spec = comma-separated methods, optionally `method=code`
    // to serve a SPECIFIC fault code (the G79 vocabulary leg needs a KNOWN code —
    // e.g. `getChanges=fault.git.notAvailable`); a bare method serves
    // fault.git.notAvailable (the recorded live face).
    System.getenv("MOCK_GIT_REFUSALS")?.takeIf { it.isNotBlank() }?.let { spec ->
        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
            val parts = entry.split('=', limit = 2).map { it.trim() }
            val method = parts[0]
            val code = parts.getOrElse(1) { "" }.ifBlank { "fault.git.notAvailable" }
            mock.script.gitRefusals[method] = code
        }
    }
    // p40 (pass-40): MOCK_GIT_BRANCH_ISSUE=<json object> scripts the TYPED branch-issue
    // ack shape ({code,message,detail?,paths[]} — the GitBranchResult.issues decode; ok
    // = issues.isEmpty()) so the BranchSheet's typed-issue face is drivable per the
    // desktop-faithful SUCCESS-with-issues ack (distinct from a MOCK_GIT_REFUSALS
    // transport fault). MOCK_GIT_PUSH_FAIL=1 arms the push refusal (gitPushFails).
    System.getenv("MOCK_GIT_BRANCH_ISSUE")?.takeIf { it.isNotBlank() }?.let { spec ->
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(spec).let { it as? JsonObject }
        }.getOrNull()?.let { mock.script.gitBranchIssue = it }
    }
    if (System.getenv("MOCK_GIT_PUSH_FAIL") == "1") mock.script.gitPushFails = true
    // p41 (pass-41): MOCK_WS_TWO=1 serves a SECOND workspace row on the relay's
    // bootstrap/workspace-list responses — unblocks the Manage sheet's reorder
    // arrows (both arrows need index>0 AND index<last) and the chips row's second
    // chip (relayWorkspacesView; no task seeded on ws-two — no F-P38-A ghost).
    if (System.getenv("MOCK_WS_TWO") == "1") mock.script.relaySecondWorkspace = true
    // G52 (vc52 Phase 11): MOCK_G52_KEYS=transition|admission|both pushes a state patch
    // seeding the two silently-dropped session-state keys (a conversation-level arm; the
    // patch rides 1.5 s after the first conversation subscribe so the screens are up).
    if (System.getenv("MOCK_G52_KEYS") != null) {
        val what = System.getenv("MOCK_G52_KEYS")!!
        val patch = kotlinx.serialization.json.buildJsonObject {
            if (what == "transition" || what == "both") put("modelTransition", kotlinx.serialization.json.buildJsonObject {
                put("eventId", JsonPrimitive("g52-rig-1"))
                put("origin", JsonPrimitive("registryFallback"))
                put("from", kotlinx.serialization.json.buildJsonObject {
                    put("provider", JsonPrimitive("zai")); put("model", JsonPrimitive("glm-5.1"))
                })
                put("to", kotlinx.serialization.json.buildJsonObject {
                    put("provider", JsonPrimitive("zai")); put("model", JsonPrimitive("glm-4.7-air"))
                })
            })
            if (what == "admission" || what == "both") put("workspaceHookAdmission", kotlinx.serialization.json.buildJsonObject {
                put("pendingCount", JsonPrimitive(2))
                put("bundleDigest", JsonPrimitive("rig-digest-1"))
                put("workspaceIdentity", JsonPrimitive("/synthetic/ws-one"))
            })
        }
        val delayMs = System.getenv("MOCK_G52_KEYS_MS")?.toLongOrNull() ?: 1_500L
        val t = Thread {
            try {
                Thread.sleep(delayMs)
                mock.pushStatePatch(patch)
            } catch (_: InterruptedException) { }
        }
        t.isDaemon = true
        t.name = "mock-g52-keys"
        t.start()
    }
    // RIG-1 (vc51 Phase 4C / G29): MOCK_AUTOMATIONS=1 seeds the automations list; the
    // handlers were always scripted (passes 5/9 debt) — only the env flags were missing.
    // RIG-P3 (vc52 Phase 14, N-BE-6): the seed carries `nextRunAt` (epoch ms, one
    // hour out) — the phone's timezone-safe "Next …" label never showed on the rig.
    if (System.getenv("MOCK_AUTOMATIONS") != null) {
        mock.script.listAutomations = JsonArray(listOf(automationRigSeed()))
        // Debt #8 (pass 18): the automations plane IMPLIES the off-peak plane — the
        // desktop's automations ARE scheduled/off-peak tasks, so a session serving
        // listAutomations always serves off-peak-task.list. Seeding it by default
        // removes the co-requirement (the pass-10 receipt: without MOCK_OFFPEAK the
        // home screen rendered the "This feature isn't available" face).
        // MOCK_OFFPEAK=0 opts out explicitly.
        if (System.getenv("MOCK_OFFPEAK") == null && System.getenv("MOCK_OFFPEAK") != "0") {
            seedOffPeakRigList(mock)
        }
    }
    // RIG-1 (vc51 Phase 4C / G29): MOCK_OFFPEAK=1 seeds the off-peak list — one queued,
    // one paused (the pause arm exercises the monitor verbs), one COMPLETED (the history
    // face the G29 leg verifies) — plus the workspace key the phone filters by.
    if (System.getenv("MOCK_OFFPEAK") != null && System.getenv("MOCK_OFFPEAK") != "0") {
        seedOffPeakRigList(mock)
    }
    // RIG-P3 (vc52 Phase 14, the Phase-13 fixture debt): MOCK_OFFPEAK_COMPLETE_MS=<n>
    // flips the QUEUED rig row (offpeak-rig-1) to `completed` n ms after startup —
    // the G63 notifier diffs the list on every CONNECTED transition, so the rig
    // restarts the relay after the flip and the fresh CONNECTED delivers the
    // queued→completed transition (first emission seeds, the SECOND notifies).
    System.getenv("MOCK_OFFPEAK_COMPLETE_MS")?.toLongOrNull()?.let { delayMs ->
        // Debt #11 (pass 14): MOCK_OFFPEAK_COMPLETE_ANCHOR=first-list arms the flip at
        // the FIRST `off-peak-task.list` (the G63 notifier's baseline seed) so the
        // queued→completed transition materializes INSIDE one process life (the
        // relay-start anchor fired the flip before the app's first CONNECTED).
        // Default: the legacy relay-start anchor.
        if (System.getenv("MOCK_OFFPEAK_COMPLETE_ANCHOR")?.trim() == "first-list") {
            mock.script.offPeakCompleteOnFirstListMs = delayMs
        } else {
            val t = Thread {
                try {
                    Thread.sleep(delayMs)
                    completeOffPeakRow(mock, "offpeak-rig-1")
                } catch (_: InterruptedException) { }
            }
            t.isDaemon = true
            t.name = "mock-offpeak-complete"
            t.start()
        }
    }
    // Pass-29 rig arm: MOCK_USAGE=1 renders the Usage screen with REAL data — the
    // app-usage stat cards, the quota panel (entitlement), the G20 coding-plan
    // charts and the reset-status block — instead of the honest-unavailable fault
    // face. Snapshot shapes come from the pinned wire fixtures
    // (fixtures/protocol/phase{3,4}-wire-fixtures.json, resolved under
    // MOCK_FIXTURES_DIR) so the rig serves exactly the decode-tested payloads.
    if (System.getenv("MOCK_USAGE") != null) {
        val dir = System.getenv("MOCK_FIXTURES_DIR") ?: "fixtures/protocol"
        fun fixture(name: String, file: String): JsonObject {
            val cases = Json.parseToJsonElement(File("$dir/$file").readText())
                .jsonObject["cases"]!!.jsonArray
            return cases.map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == name }["data"]!!.jsonObject
        }
        mock.script.appUsageStats = fixture("app-usage-full", "phase3-wire-fixtures.json")
        mock.script.usageEntitlement = fixture("entitlement-full", "phase4-wire-fixtures.json")
        // RIG-P3 (vc52 Phase 14, N-BE-6): the reset window is FRESH every relay
        // generation — the pinned fixture's dates are static past epochs, so the
        // reset panel's countdowns were always expired on the rig.
        mock.script.codingPlanResetStatus = redateResetStatus(
            fixture("reset-status-full", "phase4-wire-fixtures.json"),
            System.currentTimeMillis(),
        )
        mock.script.codingPlanUsageSnapshot = codingPlanUsageRigSeed()
    }
    // Mock-fidelity debt #10 (pass 14): MOCK_RESET_SUCCESS=1 scripts the G90 reset
    // happy path — {used:true} on useCodingPlanReset, and every later
    // getCodingPlanResetStatus serves the CONFIRMED progression (the consumed
    // opportunity + the usedAt history + the unread cursor), so the phone's post-use
    // reconciliation lands COMPLETED and the success face renders (pass 13 could
    // only receipt the typed failure — fault.mock.unscripted:useCodingPlanReset).
    if (System.getenv("MOCK_RESET_SUCCESS") != null) {
        mock.script.resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
    }
    // Pass-15 (debt #10's queue variant): MOCK_RESET_QUEUE_PRE=1 arms the
    // reconciliation RETRY-LADDER leg — one pre-consume status is queued at use
    // time, so the first post-use getCodingPlanResetStatus is NOT yet confirmed
    // (the phone's useRetryDelaysMs 250/750/1500 ladder fires) and the completion
    // lands on a later poll via the fallback's confirmed fold. Combine with
    // MOCK_RESET_SUCCESS (both arms together).
    if (System.getenv("MOCK_RESET_QUEUE_PRE") != null) {
        mock.script.resetQueuePreOnUse = true
    }
    // Pass-29 rig arm: MOCK_AUTOMATION_RUNS=1 seeds one COMPLETED historical run for
    // auto-rig-1 so runAutomationNow scripts `queued` (instead of fault.mock.unscripted)
    // and the Automations History sheet lists the run rows.
    if (System.getenv("MOCK_AUTOMATION_RUNS") != null) {
        mock.script.automationRuns = JsonArray(listOf(automationRunRigSeed()))
    }
    // G75 (vc52 Phase 12): MOCK_MEMORY seeds the desktop's workspace-memory tree as
    // absolute file-channel paths under the SAME home the synthetic workspace lives
    // in (/synthetic/ws-one -> home /synthetic). The phone's derivation reads
    // <home>/.zcode/cli/memories/projects/<key>/memory/*.
    if (System.getenv("MOCK_MEMORY") != null) {
        // G75 (vc52 Phase 12): the desktop's workspace-memory tree as absolute
        // file-channel paths under the SAME home the synthetic workspace lives in
        // (/synthetic/ws-one -> home /synthetic). The phone's derivation reads
        // <home>/.zcode/cli/memories/projects/<key>/memory/*.
        fun memDir(path: String, name: String) = JsonObject(mapOf(
            "name" to JsonPrimitive(name),
            "path" to JsonPrimitive(path),
            "type" to JsonPrimitive("directory"),
            "isSymbolicLink" to JsonPrimitive(false),
        ))
        fun memFile(path: String, name: String) = JsonObject(mapOf(
            "name" to JsonPrimitive(name),
            "path" to JsonPrimitive(path),
            "type" to JsonPrimitive("file"),
            "isSymbolicLink" to JsonPrimitive(false),
        ))
        mock.script.fileTree["/synthetic/.zcode/cli/memories/projects"] = JsonArray(listOf(
            memDir("/synthetic/.zcode/cli/memories/projects/synthetic-ws", "synthetic-ws"),
        ))
        mock.script.fileTree["/synthetic/.zcode/cli/memories/projects/synthetic-ws/memory"] = JsonArray(listOf(
            memFile("/synthetic/.zcode/cli/memories/projects/synthetic-ws/memory/MEMORY.md", "MEMORY.md"),
            memFile("/synthetic/.zcode/cli/memories/projects/synthetic-ws/memory/release-prefs.md", "release-prefs.md"),
        ))
        mock.script.fileText["/synthetic/.zcode/cli/memories/projects/synthetic-ws/memory/MEMORY.md"] =
            "# Workspace memory\n\n- prefers conventional commits\n- releases every Thursday"
        mock.script.fileText["/synthetic/.zcode/cli/memories/projects/synthetic-ws/memory/release-prefs.md"] =
            "Release preferences: changelog first, then tag; never push without Ernest."
    }
    // G60 (vc52 Phase 12): the per-task token usage (MOCK_USAGE rides the same arm).
    if (System.getenv("MOCK_USAGE") != null) {
        mock.script.taskTokenUsage = JsonObject(mapOf(
            "sessionId" to JsonPrimitive("synth-task-1"),
            "totalTokens" to JsonPrimitive(184_320),
            "inputTokens" to JsonPrimitive(151_000),
            "outputTokens" to JsonPrimitive(21_000),
            "reasoningTokens" to JsonPrimitive(6_400),
            "cacheCreationTokens" to JsonPrimitive(2_800),
            "cacheReadTokens" to JsonPrimitive(48_600),
            "modelRequestCount" to JsonPrimitive(37),
            "modelErrorCount" to JsonPrimitive(1),
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
        // G74 (vc52 Phase 12): a plugin command so the viewer's BOTH-source faces
        // render on the rig (user cmd-deploy + plugin acme:review).
        "pluginCommands" to JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("cmd-acme-review"),
            "name" to JsonPrimitive("acme:review"),
            "description" to JsonPrimitive("Acme code review pass"),
            "argumentHint" to JsonPrimitive("[path]"),
            "enabled" to JsonPrimitive(true),
            "source" to JsonPrimitive("plugin"),
        )))),
    ))
    // G25 (vc51 Phase 4B): three agents across scopes — one DISABLED so the
    // subagents viewer's state badges render both faces on the rig.
    mock.script.subagentsList = subagentsViewerSeed()
    // G30 (vc51 Phase 4B): the bots-viewer seed — two bots across providers/modes so the
    // read-only viewer renders both mode faces on the rig.
    mock.script.botsList = botsViewerSeed()
    // p48 rig leg (MOCK_CATALOG_EMPTY=1): serve EMPTY catalogs so the three viewer
    // screens' EMPTY faces are drivable (the pass-40 census receipted only the
    // seeded-list renders; the empty faces were never live-driven).
    if (System.getenv("MOCK_CATALOG_EMPTY") == "1") {
        mock.script.commandsList = emptyCommandsCatalog()
        mock.script.subagentsList = emptySubagentsCatalog()
        mock.script.botsList = emptyBotsCatalog()
    }
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
                            // G70 rig-fidelity: a REAL failureKind from the desktop's closed
                            // 19-code vocabulary (connectionRefused was invented pre-G70 and
                            // renders raw; connection_failed drives the mapped copy live).
                            "failureKind" to JsonPrimitive("connection_failed"),
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
    // F-CHAT-FOCUS vc50 (field 2026-10-27, Ernest S23 on vc49): the REAL desktop echo
    // carries the long coding-plan provider id and the real model/thought — the rig
    // must reproduce the WORST strings (the short "zai/glm-5.1 · high" echo hid the
    // session-bar width explosion entirely).
    mock.script.sessionConfig = JsonObject(
        mapOf(
            "provider" to JsonPrimitive("account:zai-individual-coding-plan"),
            "model" to JsonPrimitive("GLM-5.3-Flash"),
            "thought" to JsonPrimitive("max"),
            "thoughtLevels" to JsonArray(
                listOf(JsonPrimitive("medium"), JsonPrimitive("high"), JsonPrimitive("xhigh"), JsonPrimitive("max")),
            ),
            "mode" to JsonPrimitive("yolo"),
            "modelSelection" to JsonObject(
                mapOf(
                    "providerId" to JsonPrimitive("account:zai-individual-coding-plan"),
                    "modelId" to JsonPrimitive("GLM-5.3-Flash"),
                ),
            ),
        ),
    )
    // RawWsServer (not MockWebServer): mockwebserver3 masks server→client frames
    // (RFC 6455 §5.1 violation, probe-confirmed — report PART E9), which OkHttp
    // clients refuse with close 1006; the raw server keeps live device E2E possible.
    val url = mock.startRaw(port)
    if (target.direct) {
        // RIG-P4: the phone's Add-direct-endpoint dialog takes the ws URL + the token.
        println("MOCKZCODE_DIRECT_URL=$url")
        println("MOCKZCODE_DIRECT_URL_EMULATOR=" + url.replace("ws://127.0.0.1", "ws://10.0.2.2"))
        println("MOCKZCODE_DIRECT_TOKEN=${mock.serverToken}")
        println("MOCKZCODE_SERVER_INFO=http://127.0.0.1:$port/api/server-info?token=<MOCKZCODE_DIRECT_TOKEN>")
        println("MOCKZCODE_READY mode=direct")
        Runtime.getRuntime().addShutdownHook(Thread { mock.stop() })
        Thread.currentThread().join()
        exitProcess(0)
    }
    val link = "http://127.0.0.1:$port/remote/v4" +
        "?sid=$sid&hash=${java.net.URLEncoder.encode(passHash, "UTF-8")}" +
        "&t=${System.currentTimeMillis()}&mid=$mid&name=MockBook&app_version=3.14.3"
    println("MOCKZCODE_WS_URL=$url")
    println("MOCKZCODE_LINK=$link")
    // Emulator leg: the same link against the host loopback (the parser's dev-origin
    // allowlist admits 10.0.2.2) — paste THIS one into the phone's manual entry.
    println(
        "MOCKZCODE_LINK_EMULATOR=" + link.replace("http://127.0.0.1", "http://10.0.2.2"),
    )
    println("MOCKZCODE_READY scenario=$scenario")
    Runtime.getRuntime().addShutdownHook(Thread { mock.stop() })
    Thread.currentThread().join()
    exitProcess(0)
}

/** RIG-P4: the launcher's mode, port and mock (relay unless MOCK_MODE=direct). */
internal data class LauncherTarget(val mock: MockZCode, val port: Int, val direct: Boolean)

internal fun launcherTarget(env: (String) -> String?, passHash: String, sid: String): LauncherTarget {
    val direct = env("MOCK_MODE")?.trim()?.equals("direct", ignoreCase = true) == true
    val port = env("MOCK_PORT")?.toIntOrNull() ?: if (direct) 18_200 else 18_100
    val mock = if (direct) {
        MockZCode(
            passHash = passHash,
            expectedSid = sid,
            mode = MockZCode.Mode.DIRECT,
            serverToken = env("MOCK_DIRECT_TOKEN")?.takeIf { it.isNotBlank() } ?: "rig-direct-token",
            workspace = MockZCode.MockWorkspace(workspacePath = "/synthetic/direct-ws", workspaceKey = "direct-ws"),
        )
    } else {
        MockZCode(passHash = passHash, expectedSid = sid)
    }
    return LauncherTarget(mock, port, direct)
}

/**
 * T1 (pass-4 rig fix): the task-management rig leg — the pins/unread/group STATE the
 * management words serve is decorated from `directTasks` (`listPinnedTasks`,
 * `listGroupedTaskViewStructure` all filter it), so arming ids alone renders nothing:
 * before this arming populated rows, the A3.8–A3.10 surfaces never appeared on the rig
 * in any pass. Task-1 mirrors the sessions bootstrap row's identity keys so the
 * listTasks upsert refreshes the projection row in place instead of duplicating it.
 */
/** G30 (vc51 Phase 4B): the bots-viewer seed — shared by the relay arming and the self-test. */
/**
 * G48+G51 (vc52 Phase 11): the enforcing-mock schedule — one daemon thread pushes the
 * scripted workflowRun.updated then workflowRun.removed at the armed delays. The GATE
 * itself is evaluated at PUSH time (workflowRunDeltasDeclared) so a client that never
 * declared the capability is counted suppressed, never streamed.
 */
fun armWorkflowRunDeltasForTest(mock: MockZCode) {
    val updateMs = mock.script.runDeltaUpdateMs
    val removeMs = mock.script.runDeltaRemoveMs
    val thread = Thread {
        try {
            Thread.sleep(updateMs)
            mock.pushWorkflowRunUpdatedForTest()
            val gap = (removeMs - updateMs).coerceAtLeast(500L)
            Thread.sleep(gap)
            mock.pushWorkflowRunRemovedForTest()
        } catch (_: InterruptedException) {
            // relay shutdown — the schedule dies with the process
        }
    }
    thread.isDaemon = true
    thread.name = "mock-workflow-run-deltas"
    thread.start()
}

fun armCuaRigForTest(mock: MockZCode) {
    val png = java.util.Base64.getEncoder().encodeToString(
        java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
        ),
    )

    fun cuaRow(call: String, output: String) {
        mock.appendRow(
            MockZCode.RowKind.TOOL_CALL,
            "success",
            extra = buildJsonObject {
                put("toolName", JsonPrimitive("mcp__computer-use__$call"))
                put("toolCallId", JsonPrimitive("cua-call-$call"))
                put("inputText", JsonPrimitive("{}"))
                put("outputPreview", JsonPrimitive(output))
            },
        )
    }
    cuaRow("screenshot", "{\"screenshot\":\"$png\"}")
    cuaRow("left_click", "{\"ok\":true,\"x\":420,\"y\":230}")
    cuaRow("type", "{\"ok\":true,\"chars\":16}")
}

fun botsViewerSeed(): JsonObject = JsonObject(mapOf(
    "bots" to JsonArray(listOf(
        JsonObject(mapOf(
            "botId" to JsonPrimitive("bot-telegram-1"),
            "provider" to JsonPrimitive("telegram"),
            "mode" to JsonPrimitive("task"),
            "workspacePath" to JsonPrimitive("/synthetic/ws-one"),
            "activeTaskId" to JsonPrimitive("synth-task-1"),
            "enabled" to JsonPrimitive(true),
        )),
        JsonObject(mapOf(
            "botId" to JsonPrimitive("bot-discord-1"),
            "provider" to JsonPrimitive("discord"),
            "mode" to JsonPrimitive("draft"),
            "workspacePath" to JsonPrimitive("/synthetic/ws-two"),
            "enabled" to JsonPrimitive(false),
        )),
    )),
))

/** p48 rig leg (MOCK_CATALOG_EMPTY=1): the EMPTY catalog shapes — the three viewer
 *  screens' empty faces were never live-driven (the pass-40 census receipted only
 *  the seeded-list renders). Keys mirror the decoders (SlashCatalogExtras +
 *  MockRelayMain's own seeds). */
fun emptyCommandsCatalog(): JsonObject = JsonObject(mapOf(
    "commands" to JsonArray(emptyList()),
    "userCommands" to JsonArray(emptyList()),
    "pluginCommands" to JsonArray(emptyList()),
))

fun emptySubagentsCatalog(): JsonObject = JsonObject(mapOf("agents" to JsonArray(emptyList())))

fun emptyBotsCatalog(): JsonObject = JsonObject(mapOf("bots" to JsonArray(emptyList())))

/** G25 (vc51 Phase 4B): the subagents-viewer seed — shared by the relay arming and the self-test. */
fun subagentsViewerSeed(): JsonObject = JsonObject(mapOf(
    "agents" to JsonArray(listOf(
        JsonObject(mapOf(
            "id" to JsonPrimitive("a-explore"),
            "name" to JsonPrimitive("Explore"),
            "description" to JsonPrimitive("Read-only search agent"),
            "scope" to JsonPrimitive("built-in"),
            "enabled" to JsonPrimitive(true),
        )),
        JsonObject(mapOf(
            "id" to JsonPrimitive("a-plan"),
            "name" to JsonPrimitive("Plan"),
            "description" to JsonPrimitive("Implementation planning agent"),
            "scope" to JsonPrimitive("built-in"),
            "enabled" to JsonPrimitive(true),
        )),
        JsonObject(mapOf(
            "id" to JsonPrimitive("a-docs"),
            "name" to JsonPrimitive("docs-writer"),
            "description" to JsonPrimitive("Project documentation agent"),
            "scope" to JsonPrimitive("project"),
            "enabled" to JsonPrimitive(false),
        )),
    )),
))

internal fun armTaskManagementRig(mock: MockZCode) {
    val now = System.currentTimeMillis()
    mock.script.bootstrapTasks = listOf(
        MockZCode.MockTask(
            taskId = "synth-task-1", title = "Synthetic task", displayStatus = "idle",
            workspacePath = "/synthetic/ws-one", workspaceLabel = "Synthetic One",
            pinned = true,
            // RIG-P3 (N-BD-5): the per-task provider chip's fixture — the badge renders.
            provider = "GLM-5.3-Flash",
        ),
        MockZCode.MockTask(
            taskId = "synth-task-2", title = "Synthetic task 2", displayStatus = "idle",
            workspacePath = "/synthetic/ws-one", workspaceLabel = "Synthetic One",
            unreadAtMs = now,
        ),
        MockZCode.MockTask(
            taskId = "synth-task-3", title = "Synthetic task 3", displayStatus = "idle",
            workspacePath = "/synthetic/ws-one", workspaceLabel = "Synthetic One",
        ),
    )
    mock.script.directTasks = mock.script.bootstrapTasks!!.map { it.toJson() }
    mock.script.pinnedTaskIds.add("synth-task-1")
    mock.script.unreadTaskIds["synth-task-2"] = now
    mock.script.taskGroups.add(JsonObject(mapOf(
        "id" to JsonPrimitive("task-group-rig"), "title" to JsonPrimitive("Backend"), "color" to JsonPrimitive("green"),
        "createdAt" to JsonPrimitive(now), "updatedAt" to JsonPrimitive(now),
    )))
    mock.script.taskGroupMembers["task-group-rig"] = mutableListOf("synth-task-3")
}

/**
 * p43 (debt #29) rig leg: MOCK_TASKS_MANY=1 seeds synth-task-4..27 so the tasks-search
 * surface ("synthetic" → 27 hits) exceeds the phone's listTaskList limit:20 — the
 * desktop-faithful pagination answer {items: 20, total: 27, hasMore: true} becomes
 * drivable, and with it the phone's truncation face. Additive over any existing seed.
 */
internal fun armTasksManyRig(mock: MockZCode) {
    val extra = (4..27).map { n ->
        MockZCode.MockTask(
            taskId = "synth-task-$n", title = "Synthetic task $n", displayStatus = "idle",
            workspacePath = "/synthetic/ws-one", workspaceLabel = "Synthetic One",
        )
    }
    mock.script.bootstrapTasks = (mock.script.bootstrapTasks ?: emptyList()) + extra
    mock.script.directTasks = mock.script.directTasks + extra.map { it.toJson() }
}

/**
 * D4 (Phase 3A): the shipped view's `{modelId, config}` rows may carry the desktop
 * picker's badge fields — `config.badgeLabel` per model, `labelBadge` per provider
 * group. The default selection scripts one of each so the rig renders the badge slot.
 */private fun provider(
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

/** Pass-29 rig seed (MOCK_USAGE): the G20 coding-plan usage charts' payload —
 *  a two-model 7-day stacked-tokens series in the modelUsage.ts shape. */
internal fun codingPlanUsageRigSeed(): JsonObject = JsonObject(mapOf(
    "range" to JsonPrimitive("7d"),
    "rangeStartDate" to JsonPrimitive("2026-10-29"),
    "rangeEndDate" to JsonPrimitive("2026-11-04"),
    "modelUsage" to JsonObject(mapOf(
        "xTime" to JsonArray((29..31).map { JsonPrimitive("10-$it") } + (1..4).map { JsonPrimitive("11-0$it") }),
        "modelDataList" to JsonArray(listOf(
            JsonObject(mapOf(
                "modelName" to JsonPrimitive("GLM-5.3"),
                "tokensUsage" to JsonArray(listOf(
                    JsonPrimitive(42000), JsonPrimitive(18000), JsonPrimitive(0),
                    JsonPrimitive(56000), JsonPrimitive(31000), JsonPrimitive(9200),
                    JsonPrimitive(22400),
                )),
                "totalTokens" to JsonPrimitive(178600L),
            )),
            JsonObject(mapOf(
                "modelName" to JsonPrimitive("GLM-5.1"),
                "tokensUsage" to JsonArray(listOf(
                    JsonPrimitive(6000), JsonPrimitive(2400), JsonPrimitive(1200),
                    JsonPrimitive(9000), JsonPrimitive(4800), JsonPrimitive(1600),
                    JsonPrimitive(5200),
                )),
                "totalTokens" to JsonPrimitive(30000L),
            )),
        )),
    )),
))

/** Pass-29 rig seed (MOCK_AUTOMATION_RUNS): one COMPLETED historical run for
 *  auto-rig-1 in the recordAutomationRun row shape (outcome included). */
internal fun automationRunRigSeed(): JsonObject {
    val now = System.currentTimeMillis()
    return JsonObject(mapOf(
        "runId" to JsonPrimitive("auto-rig-1:manual:seed-1"),
        "automationId" to JsonPrimitive("auto-rig-1"),
        "workspaceKey" to JsonPrimitive("/synthetic/ws-one"),
        "trigger" to JsonPrimitive("schedule"),
        "dispatchStatus" to JsonPrimitive("dispatched"),
        "sessionId" to JsonPrimitive("synth-op-session-1"),
        "outcome" to JsonPrimitive("succeeded"),
        "attempts" to JsonPrimitive(1),
        "createdAt" to JsonPrimitive(now - 3600_000),
        "updatedAt" to JsonPrimitive(now - 3500_000),
    ))
}

/**
 * RIG-P1 (vc52 Phase 14): the per-relay-GENERATION nonce. Every relay process
 * lifetime derives a distinct nonce (env override MOCK_GEN_NONCE for repro drives
 * that pin an id); two relay generations can never collide on a scripted
 * interaction id — the recorded root of the pass-15 "inert cards" saga (INV-4
 * duplicate guards correctly refusing a re-asserted RESOLVED id) and the pass-35
 * q-rig-1 push-anchor confusion (a pushed permission re-claiming the seeded
 * question's id).
 */
internal fun relayGenerationNonce(env: (String) -> String?): String {
    env("MOCK_GEN_NONCE")?.takeIf { it.isNotBlank() }?.let { return it }
    val millis = java.lang.Long.toString(System.currentTimeMillis(), 36)
    val rand = (0 until 4096).random().toString(36)
    return "g$millis-$rand"
}

/** RIG-P1: a scripted id with the generation nonce appended (empty nonce = bare base, the self-test shape). */
internal fun nonceId(base: String, nonce: String): String =
    if (nonce.isBlank()) base else "$base-$nonce"

/**
 * RIG-P3 (vc52 Phase 14, N-BE-6): the automation rig seed — the INV-8 strict row
 * (workspace + location required) now carrying `nextRunAt` (epoch ms one hour out)
 * so the phone's timezone-safe "Next …" label renders on the rig.
 */
internal fun automationRigSeed(): JsonObject = JsonObject(mapOf(
    "automationId" to JsonPrimitive("auto-rig-1"),
    "title" to JsonPrimitive("Nightly digest"),
    "cronExpr" to JsonPrimitive("0 2 * * *"),
    "prompt" to JsonPrimitive("Summarize yesterday's commits"),
    "enabled" to JsonPrimitive(true),
    "lifecycleStatus" to JsonPrimitive("active"),
    "runCount" to JsonPrimitive(3),
    "recurring" to JsonPrimitive(true),
    "mode" to JsonPrimitive("task"),
    "nextRunAt" to JsonPrimitive(System.currentTimeMillis() + 3_600_000),
    "workspaceKey" to JsonPrimitive("/synthetic/ws-one"),
    "locationKind" to JsonPrimitive("local"),
))

/**
 * RIG-P3 (vc52 Phase 14, N-BE-6): re-date the pinned reset-status fixture to THIS
 * relay generation — `expireAt` moves to now +5h / +7d, the used stamp to now -1h
 * (the fixture's static past epochs made every rig countdown already-expired).
 */
internal fun redateResetStatus(fixture: JsonObject, now: Long): JsonObject {
    fun redate(o: JsonObject, key: String, at: Long): JsonObject {
        val arr = (o[key] as? JsonArray)?.map { el ->
            (el as? JsonObject)?.let { JsonObject(it + ("expireAt" to JsonPrimitive(at))) } ?: el
        }
        return if (arr != null) JsonObject(o + (key to JsonArray(arr))) else o
    }
    val fiveH = redate(fixture, "availableFiveHourResets", now + 5 * 3_600_000)
    val week = redate(fiveH, "availableWeekResets", now + 7 * 24 * 3_600_000)
    val hist = week["latestFiveHourResetHistory"] as? JsonObject
    return JsonObject(week + ("latestFiveHourResetHistory" to
        (hist?.let { JsonObject(it + ("usedAt" to JsonPrimitive(now - 3_600_000))) } ?: JsonNull)))
}

/**
 * RIG-P3 (vc52 Phase 14, the Phase-13 fixture debt): flip one off-peak row to
 * `completed` IN the scripted fixture — the G63 notifier's queued→completed
 * transition material (delivered on the next CONNECTED re-list after a relay
 * restart). Unknown id = a logged no-op (the arm is a rig convenience, INV-15).
 */
/** Debt #8 (pass 18): the off-peak rig list — one queued, one paused (the pause arm
 * exercises the monitor verbs), one COMPLETED (the history face the G29 leg verifies)
 * — plus the workspace key the phone filters by. Served by MOCK_OFFPEAK=1 and, since
 * the automations plane implies the off-peak plane, by MOCK_AUTOMATIONS=1. */
internal fun seedOffPeakRigList(mock: MockZCode) {
    val now = System.currentTimeMillis()
    mock.script.offPeakTaskList = JsonArray(listOf(
        JsonObject(mapOf(
            "id" to JsonPrimitive("offpeak-rig-1"),
            "offPeakTaskId" to JsonPrimitive("offpeak-rig-1"),
            "title" to JsonPrimitive("Nightly refactor sweep"),
            "status" to JsonPrimitive("queued"),
            "queuePosition" to JsonPrimitive(1),
            "createdAt" to JsonPrimitive(now - 40_000),
            "workspaceKey" to JsonPrimitive("/synthetic/ws-one"),
        )),
        JsonObject(mapOf(
            "id" to JsonPrimitive("offpeak-rig-2"),
            "offPeakTaskId" to JsonPrimitive("offpeak-rig-2"),
            "title" to JsonPrimitive("Weekend doc migration"),
            "status" to JsonPrimitive("paused"),
            "createdAt" to JsonPrimitive(now - 80_000),
            "workspaceKey" to JsonPrimitive("/synthetic/ws-one"),
        )),
        JsonObject(mapOf(
            "id" to JsonPrimitive("offpeak-rig-3"),
            "offPeakTaskId" to JsonPrimitive("offpeak-rig-3"),
            "title" to JsonPrimitive("Old dependency bump"),
            "status" to JsonPrimitive("completed"),
            "createdAt" to JsonPrimitive(now - 3 * 24 * 3600_000),
            "workspaceKey" to JsonPrimitive("/synthetic/ws-one"),
            "sessionId" to JsonPrimitive("synth-op-session-1"),
        )),
    ))
}

internal fun completeOffPeakRow(mock: MockZCode, offPeakTaskId: String) {
    val list = mock.script.offPeakTaskList ?: return
    val idx = list.indexOfFirst { (it.jsonObject["offPeakTaskId"] as? JsonPrimitive)?.content == offPeakTaskId }
    if (idx < 0) {
        println("MOCK: completeOffPeakRow $offPeakTaskId not in the seeded list (no-op)")
        return
    }
    val row = list[idx].jsonObject
    val now = System.currentTimeMillis()
    val completed = JsonObject(row + mapOf(
        "status" to JsonPrimitive("completed"),
        "sessionId" to JsonPrimitive("synth-op-session-1"),
        "updatedAt" to JsonPrimitive(now),
    ))
    mock.script.offPeakTaskList = JsonArray(list.toMutableList().also { it[idx] = completed })
    println("MOCK: offpeak $offPeakTaskId → completed (the G63 transition material)")
}
