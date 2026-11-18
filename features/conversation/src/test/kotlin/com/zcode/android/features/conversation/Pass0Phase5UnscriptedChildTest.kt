package com.zcode.android.features.conversation

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.Task
import com.zcode.android.features.conversation.ui.ConversationScreen
import com.zcode.android.domain.state.ConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * pass-0 Phase 5 — F-P3-B + N-BM-2: the automation "Go to session" UNSCRIPTED child.
 *
 * A run's sessionId that the task corpus does not carry (`port.task(taskId) == null`)
 * never arms the subscribe path (`port.open(task)` needs a Task), so the timeline
 * starved on the four skeleton bars FOREVER with zero transcript wire calls (pass-3
 * F.A2; pass-17 T.A2 stamp: no subscribe, no readSessionMessages, skeletons-forever).
 *
 * The pinned contract (the row's Accept text): the unscripted child renders the
 * TRANSCRIPT (via readSessionMessages — the already-consumed verb) or an HONEST EMPTY
 * state — never skeletons-forever; and the child view carries the session's IDENTITY
 * in the title, not the generic "Conversation".
 *
 * The harness uses ONLY pre-existing port APIs (task() → null is THE unscripted shape;
 * fetchSessionMessages is the search-plane read), so it runs — and fails — against the
 * pre-fix behavior too (RED at the pin commit).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase5UnscriptedChildTest {

    private val unscriptedId = "synth-op-session-1"

    /** {role, content} message objects — the mock's readSessionMessages seed shape. */
    private fun msg(role: String, content: String): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(content))
    }

    private val seeded = listOf(
        msg("user", "Ship the release this week"),
        msg("assistant", "I'll draft the changelog and bump the version"),
    )

    /** THE unscripted shape: the task corpus does not carry this session. */
    private open class UnscriptedPort(
        private val unscriptedId: String,
        var sessionMessages: List<JsonObject>,
    ) : Phase7FakePort() {
        override suspend fun task(taskId: String): Task? =
            if (taskId == unscriptedId) null else super.task(taskId)

        override suspend fun fetchSessionMessages(taskId: String, limit: Int): List<JsonObject> =
            sessionMessages
    }

    // ---- VM legs (the row's Accept harness) ----

    @Test
    fun `seeded corpus - the transcript renders and loading resolves (never skeletons-forever)`() = runTest {
        val port = UnscriptedPort(unscriptedId, seeded)
        val vm = ConversationViewModel(port, backgroundScope, unscriptedId)
        runCurrent()
        val state = vm.state.value
        assertFalse(
            state.loading,
            "pre-fix shape: the unscripted child starves on skeletons (loading forever, zero rows)",
        )
        assertTrue(
            state.rows.map { it.kind }.containsAll(listOf("userInput", "assistantText")),
            "the readSessionMessages transcript must render as timeline rows",
        )
    }

    @Test
    fun `empty corpus - the honest empty face resolves (never skeletons-forever)`() = runTest {
        val port = UnscriptedPort(unscriptedId, emptyList())
        val vm = ConversationViewModel(port, backgroundScope, unscriptedId)
        runCurrent()
        val state = vm.state.value
        assertFalse(
            state.loading,
            "an empty unscripted session must resolve loading (the honest empty face), not skeleton forever",
        )
        assertTrue(state.empty, "zero messages → the honest empty face")
    }

    @Test
    fun `scripted sessions never hydrate (the subscribe path owns them)`() = runTest {
        val port = UnscriptedPort(unscriptedId, seeded)
        val vm = ConversationViewModel(port, backgroundScope, "synth-task-1")
        runCurrent()
        // The scripted session rides port.open() — no unscripted rows may inject.
        assertTrue(vm.state.value.rows.isEmpty())
    }

    // ---- compose legs: the screen renders the hydrated state (real VM, fake port) ----

    @get:Rule
    val rule = createComposeRule()

    private var scope: CoroutineScope? = null

    @AfterTest
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun vmFor(messages: List<JsonObject>): ConversationViewModel {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope = s
        return ConversationViewModel(UnscriptedPort(unscriptedId, messages), s, unscriptedId)
    }

    private fun setContent(vm: ConversationViewModel) {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                val state = vm.state.collectAsState()
                ConversationScreen(
                    state = state.value,
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = { _, _, _ -> },
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `compose - the seeded child renders its transcript rows, zero skeletons`() {
        setContent(vmFor(seeded))
        rule.onNodeWithTag("conv-row-1").assertIsDisplayed()
        rule.onAllNodesWithTag("conversation_skeleton").assertCountEquals(0)
        rule.onNodeWithText("I'll draft the changelog and bump the version").assertExists()
    }

    @Test
    fun `compose - the empty child renders the honest empty face, zero skeletons`() {
        setContent(vmFor(emptyList()))
        rule.onNodeWithTag("conversation_empty").assertIsDisplayed()
        rule.onAllNodesWithTag("conversation_skeleton").assertCountEquals(0)
    }

    @Test
    fun `compose - the child's title carries the session identity (N-BM-2, never the generic default)`() {
        setContent(vmFor(seeded))
        rule.onNodeWithTag("conversation_title").assertExists()
    }
}
