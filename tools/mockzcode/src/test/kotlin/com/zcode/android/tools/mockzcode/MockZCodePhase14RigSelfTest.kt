package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RIG-P1/P2/P3 (vc52 Phase 14) mock self-tests — the rig-fidelity batch:
 *
 * - RIG-P1: the per-relay-GENERATION nonce (two relay generations never collide on
 *   a scripted interaction id — the pass-15 "inert cards" saga and the pass-35
 *   q-rig-1 anchor confusion); the REQUIRED `createdAt` served STABLE (the slot's
 *   captured value, never a per-serve re-stamp); the hook review's "N pending · M
 *   hooks" summary DERIVED from the live items (a trust flip moves the header);
 *   `MOCK_INTERACTION_PUSH_MS=0` keeps the gen push id OUT of served snapshots
 *   until its own patch delivered it (N-BE-6).
 * - RIG-P2: the `discardSharedContext` effect (the Remove chip's status flips to
 *   `discarded` — N-BD-2a); the archive verbs MUTATE the served lists
 *   (`listTasks` drops the archived row, `listArchivedTasks` gains it — N-BD-2b);
 *   the git refusal arm answers FIRST on generateCommitMessage/getChanges/
 *   getCommitGraph (N-BG-2's known-code face) while the scripted read fixtures
 *   serve the positive path; the create-branch ack moves the served summary's
 *   branch (the RIG-5 receipt, re-pinned here so the fidelity class stays owned).
 * - RIG-P3: `nextRunAt` on the automation seed (N-BE-6); the reset-status fixture
 *   re-dated to THIS generation (non-static reset dates); the off-peak
 *   queued→completed transition helper (the Phase-13 G63 fixture debt); the
 *   bootstrap task row's optional `provider` (N-BD-5 — present when set, absent on
 *   the default row so the live-corpus key set is preserved, INV-8).
 *
 * Synthetic content only (INV-15/16).
 */
class MockZCodePhase14RigSelfTest {

    private fun mock(arm: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d-p14")
        m.script.emitStateKeys = true
        arm(m.script)
        return m
    }

    /** The SNAPSHOT path (what a subscribe serves) — the N-BE-6 exclusion lives here. */
    private fun snapshotPending(m: MockZCode): JsonArray =
        m.stateBlockForTest()!!["pendingInteractions"] as JsonArray

    private fun idsOf(entries: JsonArray): List<String> =
        entries.mapNotNull { (it as? JsonObject)?.get("interactionId")?.toString()?.trim('"') }

    // ------------------------------------------------------------------ RIG-P1

    @Test
    fun `two relay generations derive distinct nonces and never collide on a scripted id`() {
        val n1 = relayGenerationNonce { null }
        val n2 = relayGenerationNonce { null }
        assertNotEquals(n1, n2, "two relay process lifetimes must never share a nonce (the pass-15 id-collision root)")
        assertEquals("mock-approval-1-$n1", nonceId("mock-approval-1", n1))
        assertNotEquals(nonceId("q-rig-1", n1), nonceId("q-rig-1", n2), "the same base id differs across generations")
    }

    @Test
    fun `MOCK_GEN_NONCE override is honored verbatim (repro drives pin an id)`() {
        assertEquals("gen-fixed", relayGenerationNonce { name -> if (name == "MOCK_GEN_NONCE") "gen-fixed" else null })
    }

    @Test
    fun `empty nonce serves the bare base id (the self-test shape)`() {
        assertEquals("q-rig-1", nonceId("q-rig-1", ""))
    }

    @Test
    fun `permission createdAt is REQUIRED and STABLE across snapshots`() {
        val m = mock()
        val slot = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        m.script.pendingPermission = slot
        val first = snapshotPending(m)[0].jsonObject
        val second = snapshotPending(m)[0].jsonObject
        val createdAt = first["createdAt"]
        assertNotNull(createdAt, "createdAt is REQUIRED on the desktop schema — the wire must carry it")
        assertEquals(
            (first["createdAt"] as JsonPrimitive).content,
            (second["createdAt"] as JsonPrimitive).content,
            "the age must not re-stamp per serve (the phone's ?: clock() fallback hid this)",
        )
        assertEquals(slot.createdAtMs.toDouble(), (createdAt as JsonPrimitive).content.toDouble())
    }

    @Test
    fun `userInput createdAt is REQUIRED and STABLE across snapshots`() {
        val m = mock()
        val slot = MockZCode.PendingUserInput(interactionId = "q-rig-1", prompt = "How?")
        m.script.pendingUserInput = slot
        val first = snapshotPending(m)[0].jsonObject
        val second = snapshotPending(m)[0].jsonObject
        assertEquals(
            (first["createdAt"] as JsonPrimitive).content.toDouble(),
            slot.createdAtMs.toDouble(),
            "the userInput slot serves its captured timestamp",
        )
        assertEquals(
            (first["createdAt"] as JsonPrimitive).content,
            (second["createdAt"] as JsonPrimitive).content,
        )
    }

    private fun hookArm(): MockZCode = mock { s ->
        s.hookReview = JsonObject(mapOf(
            "kind" to JsonPrimitive("workspaceHookReview"),
            "reviewFlowId" to JsonPrimitive("flow-rig"),
            "generation" to JsonPrimitive(1),
            "interactionId" to JsonPrimitive("hook-rig"),
            "createdAt" to JsonPrimitive(1_700_000_000_000.0),
            "items" to JsonArray(listOf(
                JsonObject(mapOf(
                    "reviewItemId" to JsonPrimitive("h1"),
                    "event" to JsonPrimitive("SessionStart"),
                    "displayName" to JsonPrimitive("Warm build cache"),
                    "trustState" to JsonPrimitive("pending_trust"),
                )),
                JsonObject(mapOf(
                    "reviewItemId" to JsonPrimitive("h2"),
                    "event" to JsonPrimitive("SessionStart"),
                    "displayName" to JsonPrimitive("Lint gate"),
                    "trustState" to JsonPrimitive("pending_trust"),
                )),
                JsonObject(mapOf(
                    "reviewItemId" to JsonPrimitive("h3"),
                    "event" to JsonPrimitive("Stop"),
                    "displayName" to JsonPrimitive("Watcher"),
                    "trustState" to JsonPrimitive("trusted_persistent"),
                )),
            )),
        ))
    }

    private fun hookEntry(m: MockZCode): JsonObject =
        snapshotPending(m).map { it.jsonObject }
            .first { (it["kind"] as JsonPrimitive).content == "workspaceHookReview" }

    @Test
    fun `hook summary is DERIVED from the items (the N pending M hooks header follows the trust state)`() {
        val m = hookArm()
        val payload = hookEntry(m)["payload"]!!.jsonObject
        val summary = payload["summary"]!!.jsonObject
        assertEquals(3, (summary["hookCount"] as JsonPrimitive).content.toInt(), "hookCount = the item count")
        assertEquals(2, (summary["pendingCount"] as JsonPrimitive).content.toInt(), "pendingCount = the pending_trust items")
        assertEquals(2, (summary["eventCount"] as JsonPrimitive).content.toInt(), "eventCount = distinct events")
    }

    @Test
    fun `a live trust flip moves the served hook header`() {
        val m = hookArm()
        m.applyCommandEffectForTest(
            "toggleWorkspaceHookReviewItem",
            JsonObject(mapOf("payload" to JsonObject(mapOf(
                "reviewItemId" to JsonPrimitive("h1"),
                "enabled" to JsonPrimitive("false"),
            )))),
        )
        val summary = hookEntry(m)["payload"]!!.jsonObject["summary"]!!.jsonObject
        assertEquals(1, (summary["pendingCount"] as JsonPrimitive).content.toInt(), "h1 revoked → the header follows")
        // The item itself carries the revoked state (the card's per-item face).
        val items = hookEntry(m)["payload"]!!.jsonObject["items"] as JsonArray
        assertEquals("revoked", ((items[0] as JsonObject)["trustState"] as JsonPrimitive).content)
    }

    @Test
    fun `hook createdAt is the ARMED value (never re-stamped per serve)`() {
        val m = hookArm()
        val first = (hookEntry(m)["createdAt"] as JsonPrimitive).content.toDouble()
        val second = (hookEntry(m)["createdAt"] as JsonPrimitive).content.toDouble()
        assertEquals(1_700_000_000_000.0, first, "the armed payload's createdAt is served verbatim")
        assertEquals(first, second, "stable across serves (the age badge must not reset)")
    }

    @Test
    fun `MOCK_INTERACTION_PUSH_MS keeps the push id OUT of snapshots until the patch delivered it (N-BE-6)`() {
        val m = mock()
        m.script.interactionPushDelayMs = 0L
        m.script.interactionPushId = "push-rig-1"
        // The RACE shape: the scheduled push has set the slot but not yet delivered.
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "push-rig-1")
        assertFalse(m.interactionPushDeliveredForTest())
        assertFalse(
            idsOf(snapshotPending(m)).contains("push-rig-1"),
            "the initial snapshot must not carry the gen push id (the fresh-push leg degraded to 'was in the snapshot')",
        )
        // A NON-push interaction is never excluded (the arm-scoped rule).
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "other-1")
        assertTrue(idsOf(snapshotPending(m)).contains("other-1"), "the exclusion is push-id-scoped, not a blanket filter")
        // After delivery, snapshots serve it again (a reconnecting client must see it).
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "push-rig-1")
        m.script.interactionPushDelivered = true
        assertTrue(idsOf(snapshotPending(m)).contains("push-rig-1"), "post-delivery snapshots serve the id (state truth)")
    }

    @Test
    fun `no push arm - the snapshot serves the slot untouched (the pre-RIG-P1 shape)`() {
        val m = mock()
        m.script.interactionPushId = "push-rig-1"
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "push-rig-1")
        assertTrue(
            idsOf(snapshotPending(m)).contains("push-rig-1"),
            "without the arm the exclusion never applies (the arm-scoped rule)",
        )
    }

    // ------------------------------------------------------------------ RIG-P2

    @Test
    fun `discardSharedContext flips the served import status to discarded (N-BD-2a)`() {
        val m = mock { s ->
            s.sharedContextImport = JsonObject(mapOf(
                "contextId" to JsonPrimitive("ctx-share-rig-1"),
                "title" to JsonPrimitive("Shared: release-notes rewrite"),
                "status" to JsonPrimitive("pending"),
            ))
        }
        m.applyCommandEffectForTest(
            "discardSharedContext",
            JsonObject(mapOf("payload" to JsonObject(mapOf("contextId" to JsonPrimitive("ctx-share-rig-1"))))),
        )
        val block = m.stateBlockForTest()!!["sharedContextImport"]!!.jsonObject
        assertEquals(
            "discarded", (block["status"] as JsonPrimitive).content,
            "the wire vocabulary flips the status — the phone's sendable goes false and the chip leaves the composer",
        )
        assertEquals("ctx-share-rig-1", (block["contextId"] as JsonPrimitive).content, "the id survives (support truth)")
    }

    @Test
    fun `discardSharedContext with a foreign contextId leaves the import untouched`() {
        val m = mock { s ->
            s.sharedContextImport = JsonObject(mapOf(
                "contextId" to JsonPrimitive("ctx-share-rig-1"),
                "title" to JsonPrimitive("Shared"),
                "status" to JsonPrimitive("pending"),
            ))
        }
        m.applyCommandEffectForTest(
            "discardSharedContext",
            JsonObject(mapOf("payload" to JsonObject(mapOf("contextId" to JsonPrimitive("ctx-other"))))),
        )
        val block = m.stateBlockForTest()!!["sharedContextImport"]!!.jsonObject
        assertEquals("pending", (block["status"] as JsonPrimitive).content)
    }

    private fun directTasksArm(): MockZCode = mock { s ->
        s.directTasks = listOf(
            JsonObject(mapOf("taskId" to JsonPrimitive("t1"), "title" to JsonPrimitive("Task one"))),
            JsonObject(mapOf("taskId" to JsonPrimitive("t2"), "title" to JsonPrimitive("Task two"))),
        )
        s.bootstrapTasks = listOf(
            MockZCode.MockTask(taskId = "t1", title = "Task one"),
            MockZCode.MockTask(taskId = "t2", title = "Task two"),
        )
    }

    @Test
    fun `archiveTask moves the row out of listTasks into listArchivedTasks (N-BD-2b)`() {
        val m = directTasksArm()
        m.taskPromiseForTest(1, "archiveTask", JsonObject(mapOf("taskId" to JsonPrimitive("t1"))))
        val main = m.listTasksServedForTest().mapNotNull { it["taskId"]?.toString()?.trim('"') }
        assertFalse(main.contains("t1"), "the archived row leaves the SERVED main list")
        assertTrue(main.contains("t2"))
        val archived = m.listArchivedServedForTest().mapNotNull { it["taskId"]?.toString()?.trim('"') }
        assertTrue(archived.contains("t1"), "the row is reachable through listArchivedTasks")
    }

    @Test
    fun `unarchiveTask restores the row to the main list`() {
        val m = directTasksArm()
        m.taskPromiseForTest(1, "archiveTask", JsonObject(mapOf("taskId" to JsonPrimitive("t1"))))
        m.taskPromiseForTest(2, "unarchiveTask", JsonObject(mapOf("taskId" to JsonPrimitive("t1"))))
        val main = m.listTasksServedForTest().mapNotNull { it["taskId"]?.toString()?.trim('"') }
        assertTrue(main.contains("t1"), "the unarchive ack restores the served row")
        assertFalse(m.listArchivedServedForTest().mapNotNull { it["taskId"]?.toString()?.trim('"') }.contains("t1"))
    }

    /** The fault a git promise reply carried, or null when it succeeded (the response seam). */
    private fun gitFault(m: MockZCode, name: String): String? {
        val repliesBefore = m.promiseResponsesForTest().size
        m.gitPromiseForTest(9, name, JsonObject(emptyMap()))
        val replies = m.promiseResponsesForTest().drop(repliesBefore)
        val last = replies.lastOrNull() ?: return null
        val obj = (last.third as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json as? JsonObject
            ?: return null
        val msg = (obj["message"] as? JsonPrimitive)?.content
        return if (last.second == com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseError.id) msg else null
    }

    @Test
    fun `the git refusal arm answers FIRST on the N-BG-2 trio (the known-code face)`() {
        val m = mock { s ->
            s.gitRefusals["getChanges"] = "fault.git.notAvailable"
            s.gitRefusals["generateCommitMessage"] = "fault.git.notAvailable"
            s.gitRefusals["getCommitGraph"] = "fault.git.notAvailable"
            // The refusal BEATS the fixture.
            s.gitChanges = JsonArray(emptyList())
            s.generatedCommitMessage = JsonObject(mapOf("message" to JsonPrimitive("should not serve")))
        }
        assertEquals("fault.git.notAvailable", gitFault(m, "getChanges"), "the deliberate fault beats the scripted fixture")
        assertEquals("fault.git.notAvailable", gitFault(m, "generateCommitMessage"))
        assertEquals("fault.git.notAvailable", gitFault(m, "getCommitGraph"))
    }

    @Test
    fun `the scripted git read fixtures serve the positive path (the N-BG-2 legs had data)`() {
        val m = mock { s ->
            s.generatedCommitMessage = JsonObject(mapOf("message" to JsonPrimitive("chore(rig): bump")))
            s.gitChanges = JsonArray(listOf(JsonObject(mapOf("path" to JsonPrimitive("src/version.ts")))))
            s.gitCommits = JsonArray(listOf(JsonObject(mapOf("oid" to JsonPrimitive("abc1234")))))
        }
        assertNull(gitFault(m, "generateCommitMessage"), "scripted → success")
        assertNull(gitFault(m, "getChanges"))
        assertNull(gitFault(m, "getCommitGraph"))
    }

    // -------------------------------------------------------------- N-BK-1 (pass 5)

    /** The SUCCESS rows getChanges served for a sourceId (the response seam, success side). */
    private fun gitChangesPayload(m: MockZCode, sourceId: String?): List<JsonObject> {
        val repliesBefore = m.promiseResponsesForTest().size
        val arg = if (sourceId == null) JsonObject(emptyMap())
        else JsonObject(mapOf("sourceId" to JsonPrimitive(sourceId)))
        m.gitPromiseForTest(9, "getChanges", arg)
        val last = m.promiseResponsesForTest().drop(repliesBefore).lastOrNull() ?: return emptyList()
        val arr = (last.third as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Arr)?.items
            ?: return emptyList()
        return arr.mapNotNull { (it as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json as? JsonObject }
    }

    private fun str(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.content ?: ""

    @Test
    fun `getChanges serves only the requested section — a row lands in ONE tab (N-BK-1)`() {
        val m = mock { s -> s.gitChanges = RigGitFixtures.changes() }
        val unstaged = gitChangesPayload(m, "unstaged").map { str(it, "workspaceRelativePath") }
        val staged = gitChangesPayload(m, "staged").map { str(it, "workspaceRelativePath") }
        assertEquals(listOf("src/version.ts", "scratch/probe.txt"), unstaged, "unstaged call serves unstaged+untracked rows")
        assertEquals(listOf("src/index.ts"), staged, "staged call serves ONLY the staged row")
        // No-section rows keep the legacy serve-everything behavior (older fixtures).
        val legacy = mock { s ->
            s.gitChanges = JsonArray(listOf(JsonObject(mapOf("path" to JsonPrimitive("a.ts")))))
        }
        assertEquals(1, gitChangesPayload(legacy, "unstaged").size, "section-less rows serve unchanged")
    }

    @Test
    fun `the rig git fixtures satisfy the app decode contract (N-BK-1)`() {
        // data/Phase9Tools.kt decode requirements — every change needs
        // path/workspaceRelativePath/kind/section/added/removed (the old status/staged/
        // additions/deletions shape decoded to blank labels, +0 −0, dual-tab rows) and
        // every commit needs hash (REQUIRED — oid dropped every row)/parents/subject.
        val sections = setOf("unstaged", "untracked", "staged", "conflicted")
        val changes = RigGitFixtures.changes()
        assertTrue(changes.isNotEmpty())
        for (el in changes) {
            val o = el.jsonObject
            assertTrue(str(o, "path").isNotBlank(), "change row needs path")
            assertTrue(str(o, "workspaceRelativePath").isNotBlank(), "change row needs workspaceRelativePath (the row label)")
            assertTrue(str(o, "kind").isNotBlank(), "change row needs kind")
            assertTrue(str(o, "section") in sections, "change row section must be in the app's verified set")
            assertTrue((o["added"] as? JsonPrimitive) != null && (o["removed"] as? JsonPrimitive) != null, "change row needs added/removed")
        }
        val commits = RigGitFixtures.commits()
        assertTrue(commits.isNotEmpty())
        for (el in commits) {
            val o = el.jsonObject
            assertTrue(str(o, "hash").length >= 40, "commit row needs hash (the required key)")
            assertNotNull(o["parents"], "commit row needs parents")
            assertTrue(str(o, "subject").isNotBlank(), "commit row needs subject")
            assertNotNull(o["authoredAtMs"], "commit row needs authoredAtMs")
        }
    }

    @Test
    fun `the create-branch ack moves the served summary branch (the RIG-5 fidelity class)`() {
        val m = mock { s ->
            s.gitSummary = JsonObject(mapOf(
                "branchName" to JsonPrimitive("main"),
                "isGitAvailable" to JsonPrimitive(true),
            ))
        }
        assertEquals("main", m.localBranchesHeadForTest())
        m.gitPromiseForTest(1, "createBranchAndSwitch", JsonObject(mapOf("branchName" to JsonPrimitive("feat/rig"))))
        assertEquals("feat/rig", m.localBranchesHeadForTest())
        assertEquals("feat/rig", (m.repositorySummaryForTest()!!["branchName"] as JsonPrimitive).content)
    }

    // ------------------------------------------------------------------ RIG-P3

    @Test
    fun `the automation seed carries nextRunAt (the timezone-safe Next label had no data)`() {
        val seed = automationRigSeed()
        val next = seed["nextRunAt"]
        assertNotNull(next, "N-BE-6: the phone's 'Next …' label needs nextRunAt on the rig")
        val at = (next as JsonPrimitive).content.toLong()
        assertTrue(at > System.currentTimeMillis(), "nextRunAt is a FUTURE timestamp (one hour out)")
    }

    @Test
    fun `the reset-status fixture is re-dated to THIS generation (non-static reset dates)`() {
        val pinned = JsonObject(mapOf(
            "availableFiveHourResets" to JsonArray(listOf(
                JsonObject(mapOf("expireAt" to JsonPrimitive(1791937200000))),
            )),
            "availableWeekResets" to JsonArray(listOf(
                JsonObject(mapOf("expireAt" to JsonPrimitive(1792538400000))),
            )),
            "latestFiveHourResetHistory" to JsonObject(mapOf("usedAt" to JsonPrimitive(1791922800000))),
            "hasUnreadHistory" to JsonPrimitive(true),
        ))
        val now = 1_800_000_000_000
        val redated = redateResetStatus(pinned, now)
        val fiveH = ((redated["availableFiveHourResets"] as JsonArray)[0] as JsonObject)["expireAt"] as JsonPrimitive
        assertEquals((now + 5 * 3_600_000).toString(), fiveH.content, "the 5h window is now +5h")
        val week = ((redated["availableWeekResets"] as JsonArray)[0] as JsonObject)["expireAt"] as JsonPrimitive
        assertEquals((now + 7 * 24 * 3_600_000).toString(), week.content, "the week window is now +7d")
        val used = (redated["latestFiveHourResetHistory"]!!.jsonObject["usedAt"] as JsonPrimitive)
        assertEquals((now - 3_600_000).toString(), used.content, "the used stamp is now -1h")
        assertEquals("true", (redated["hasUnreadHistory"] as JsonPrimitive).content, "untouched keys survive")
    }

    @Test
    fun `completeOffPeakRow flips the QUEUED rig row to completed (the G63 transition material)`() {
        val m = mock()
        m.script.offPeakTaskList = JsonArray(listOf(
            JsonObject(mapOf(
                "offPeakTaskId" to JsonPrimitive("offpeak-rig-1"),
                "title" to JsonPrimitive("Nightly refactor sweep"),
                "status" to JsonPrimitive("queued"),
            )),
        ))
        completeOffPeakRow(m, "offpeak-rig-1")
        val row = (m.script.offPeakTaskList!![0] as JsonObject)
        assertEquals("completed", (row["status"] as JsonPrimitive).content)
        assertEquals("synth-op-session-1", (row["sessionId"] as JsonPrimitive).content)
    }

    @Test
    fun `completeOffPeakRow on an unknown id is a no-op`() {
        val m = mock()
        m.script.offPeakTaskList = JsonArray(listOf(
            JsonObject(mapOf(
                "offPeakTaskId" to JsonPrimitive("offpeak-rig-2"),
                "status" to JsonPrimitive("paused"),
            )),
        ))
        val before = m.script.offPeakTaskList
        completeOffPeakRow(m, "offpeak-missing")
        assertEquals(before, m.script.offPeakTaskList, "unknown id → the fixture untouched")
    }

    @Test
    fun `the bootstrap task row serves the optional provider badge (N-BD-5)`() {
        val withProvider = MockZCode.MockTask(taskId = "t1", title = "Task", provider = "GLM-5.3-Flash").toJson()
        assertEquals("GLM-5.3-Flash", (withProvider["provider"] as JsonPrimitive).content)
        // The DEFAULT row keeps the live-corpus key set (INV-8): no provider key.
        val plain = MockZCode.MockTask(taskId = "t2", title = "Task").toJson()
        assertNull(plain["provider"], "provider-less rows keep their historical shape")
    }
}
