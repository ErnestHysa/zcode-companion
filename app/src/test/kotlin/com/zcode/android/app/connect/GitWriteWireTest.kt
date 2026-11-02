package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.model.GitBranchList
import com.zcode.android.domain.model.GitBranchResult
import com.zcode.android.domain.model.GitCommitResult
import com.zcode.android.domain.model.GitIdentity
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-021 (Phase 5D) wire legs over the REAL transport ↔ mock: stage/unstage/discard
 * mutate the mock's state (staged set + the discarded ledger); commit echoes the
 * bundle-extracted `{commitHash, summary}`; the branch result decodes the TYPED issue
 * map (the kebab wire codes); the identity-missing state decodes nulls; the
 * create-and-switch SUCCESS round-trip (the G32 vc51 receipt's cited leg) moves the
 * served branch-list head and switches back clean.
 */
class GitWriteWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid: String get() = "d_gw_${System.identityHashCode(this)}"
    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect(setup: (MockZCode.Script) -> Unit = {}) {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        setup(mock.script)
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-gw", kind = EndpointKind.RELAY_ZAI, displayName = "GwMockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
            }
        }
    }

    @Test
    fun `stage unstage and discard mutate the mock state in the workspace-target form`() {
        connect()
        runBlocking {
            transport.gitStagePaths("/synthetic/ws", listOf("src/a.ts", "src/b.ts"))
            transport.gitStagePaths("/synthetic/ws", listOf("src/c.ts"))
            transport.gitUnstagePaths("/synthetic/ws", listOf("src/b.ts"))
            transport.gitDiscardPaths("/synthetic/ws", listOf("src/c.ts"), staged = true)
        }
        assertEquals(listOf("src/a.ts"), mock.script.stagedPaths.toList(), "unstage removed b; discard removed c")
        assertEquals(listOf("src/c.ts"), mock.script.discardedPaths.toList())
        val calls = mock.taskChannelLedgerForTest("git")
        assertEquals(listOf("stagePaths", "stagePaths", "unstagePaths", "discardPaths"), calls.map { it.first })
        assertEquals("/synthetic/ws", (calls[0].second["workspacePath"] as JsonPrimitive).content)
    }

    @Test
    fun `commit push and branches decode the bundle shapes and the typed issue`() {
        connect {
            it.gitBranchIssue = buildJsonObject {
                put("code", JsonPrimitive("untracked-changes-would-be-overwritten"))
                put("message", JsonPrimitive("Untracked files would be overwritten by switching branches."))
                put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("scratch/x.txt"))))
            }
        }
        runBlocking {
            val commit = GitCommitResult.decode(transport.gitCommit("/synthetic/ws", "Fix the thing", null, stagedOnly = true))!!
            assertTrue(commit.commitHash.isNotEmpty())
            assertEquals("main", GitPushResult_decodeBranch(transport.gitPush("/synthetic/ws")))
            val branches = GitBranchList.decode(transport.gitLocalBranches("/synthetic/ws"))
            assertEquals("main", branches.currentBranchName)
            assertEquals(2, branches.branches.size)
            assertTrue(branches.branches.first().isCurrent)
            val refused = GitBranchResult.decode(transport.gitSwitchBranch("/synthetic/ws", "feature/a"))
            assertFalse(refused.ok)
            assertEquals("untracked-changes-would-be-overwritten", refused.issues.single().code)
            assertEquals(listOf("scratch/x.txt"), refused.issues.single().paths)
            assertEquals("untrackedChangesWouldBeOverwritten", refused.issues.single().camelKey, "J1.3's camelCase maps 1:1")
            val identity = GitIdentity.decode(transport.gitIdentity("/synthetic/ws"))
            assertTrue(identity.present)
        }
    }

    @Test
    fun `create and switch round-trip over the real transport - the served head follows the ack`() {
        connect()
        runBlocking {
            val created = GitBranchResult.decode(transport.gitCreateBranchAndSwitch("/synthetic/ws", "feature/wire", null))
            assertTrue(created.ok)
            assertTrue(created.created, "createBranchAndSwitch reports created=true")
            assertEquals("feature/wire", created.branchName)
            assertEquals(
                "feature/wire",
                GitBranchList.decode(transport.gitLocalBranches("/synthetic/ws")).currentBranchName,
                "the branch-list head follows the create-and-switch ack (the RIG-5 store mutation, seen over the wire)",
            )
            val switched = GitBranchResult.decode(transport.gitSwitchBranch("/synthetic/ws", "main"))
            assertTrue(switched.ok)
            assertFalse(switched.created, "a plain switch reports created=false")
            assertEquals("main", switched.branchName)
            assertEquals(
                "main",
                GitBranchList.decode(transport.gitLocalBranches("/synthetic/ws")).currentBranchName,
                "switching back moves the served head again",
            )
        }
    }

    private suspend fun GitPushResult_decodeBranch(o: JsonObject): String? =
        com.zcode.android.domain.model.GitPushResult.decode(o).branchName
}
