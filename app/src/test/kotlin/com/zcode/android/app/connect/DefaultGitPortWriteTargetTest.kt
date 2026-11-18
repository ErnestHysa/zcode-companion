package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.features.git.port.GitPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-P10-A (pass-0 Phase 4): the write tier's null-target path is TYPED, not silent.
 * `writeOp`'s first line (`workspacePath() ?: return null`) was the silent exit —
 * dialog closed, zero wire verbs, zero logs, zero user face. The typed outcomes
 * carry GitWriteRefusal(noTarget=true) for commit AND push; a connected transport
 * (endpoint null until connect) is the reproduction shape.
 */
class DefaultGitPortWriteTargetTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A transport that never connected: endpoint null → activeWorkspaceTarget null. */
    private fun hubWithDisconnectedTransport(): ConnectionHub {
        val vault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}))
        return ConnectionHub(
            transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {})),
            directTransport = testDirectTransport(),
            preferences = FakePreferencesRepository(),
            vault = vault,
            taskRepo = FakeTaskRepository(),
            conversationRepo = FakeConversationRepository(),
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = FakeEndpointRepository(),
            outbox = InMemoryOutboxRepository(),
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = ZLog(sink = {}),
        )
    }

    @Test
    fun `a null write target is a TYPED refusal on commit and push - F-P10-A`() = runBlocking {
        val port: GitPort = DefaultGitPort(hubWithDisconnectedTransport())
        assertNull(port.workspacePath(), "the reproduction shape: no workspace target resolves")

        val commit = port.commitOutcome("rig message", stagedOnly = true)
        assertNull(commit.result)
        assertTrue(
            commit.refusal?.noTarget == true,
            "the null-target commit must carry the typed noTarget refusal",
        )

        val push = port.pushOutcome()
        assertNull(push.result)
        assertTrue(
            push.refusal?.noTarget == true,
            "the null-target push must carry the typed noTarget refusal (the F-P41-A plane)",
        )
    }
}
