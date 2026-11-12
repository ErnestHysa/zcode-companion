package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-21-A (vc52 Phase 10): the FIRST-TOKEN @-mention is dead — a draft STARTING with
 * the mention (`@re`) computed `atToken = ""` (substringAfterLast(' ', "") returns the
 * EMPTY MISSING-DEFAULT when the draft has no space), so the search never fired and no
 * panel rendered (11 wire-proofed live repros). The desktop renders this face by
 * design: its trigger regexes anchor at `^`, and an EMPTY query is allowed (a bare `@`
 * lists up to 1000 candidates). Pins assert PANEL PRESENCE, not call count (the
 * pass-26 adjudication: the cache-warm leg can legitimately return zero fresh calls).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MentionFirstTokenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val queries = mutableListOf<Pair<String, Boolean>>()

    private val files = listOf(
        "/synthetic/ws/README.md",
        "/synthetic/ws/src/main.rs",
    )

    private fun setContent(draft: String) {
        queries.clear()
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = emptyList(),
                        loading = false,
                        taskState = TaskStateMachine.State.RUNNING,
                        phase = ConnectionPhase.CONNECTED,
                        epoch = "e1",
                        draft = draft,
                    ),
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
                    fileSearchProvider = { query, refresh ->
                        queries += query to refresh
                        files.filter { it.lowercase().contains(query.lowercase()) }
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `first-token mention fires and renders the panel - the recorded dead face`() {
        setContent(draft = "@re")
        rule.waitUntil(5_000) { queries.isNotEmpty() }
        rule.onNodeWithText("/synthetic/ws/README.md").assertIsDisplayed()
    }

    @Test
    fun `mid-text mention still works (the control leg)`() {
        setContent(draft = "x @re")
        rule.waitUntil(5_000) { queries.isNotEmpty() }
        rule.onNodeWithText("/synthetic/ws/README.md").assertIsDisplayed()
    }

    @Test
    fun `a bare at-sign arms the empty-query listing (desktop parity)`() {
        setContent(draft = "@")
        rule.waitUntil(5_000) { queries.isNotEmpty() }
        // The desktop allows an EMPTY query (BA10.5.2) — the listing arms on a bare @.
        assertEquals("", queries.first().first)
        rule.onNodeWithText("/synthetic/ws/README.md").assertIsDisplayed()
    }

    @Test
    fun `a han-prefixed mention triggers (the desktop V7e widening)`() {
        setContent(draft = "设置@re")
        rule.waitUntil(5_000) { queries.isNotEmpty() }
        rule.onNodeWithText("/synthetic/ws/README.md").assertIsDisplayed()
    }

    @Test
    fun `an empty result retries once before the miss is accepted (refresh-on-miss)`() {
        var calls = 0
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = emptyList(),
                        loading = false,
                        taskState = TaskStateMachine.State.RUNNING,
                        phase = ConnectionPhase.CONNECTED,
                        epoch = "e1",
                        draft = "@miss",
                    ),
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
                    fileSearchProvider = { _, _ ->
                        calls += 1
                        if (calls >= 2) listOf("/synthetic/ws/README.md") else emptyList()
                    },
                )
            }
        }
        // The miss retries ONCE (refresh:true shape) — the panel appears on the retry.
        rule.waitUntil(5_000) { calls >= 2 }
        rule.onNodeWithText("/synthetic/ws/README.md").assertIsDisplayed()
    }

    @Test
    fun `a genuine miss renders no menu after the single retry`() {
        var calls = 0
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = emptyList(),
                        loading = false,
                        taskState = TaskStateMachine.State.RUNNING,
                        phase = ConnectionPhase.CONNECTED,
                        epoch = "e1",
                        draft = "@zzz",
                    ),
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
                    fileSearchProvider = { _, _ ->
                        calls += 1
                        emptyList()
                    },
                )
            }
        }
        rule.waitUntil(5_000) { calls >= 2 }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("mention_menu").fetchSemanticsNodes().size)
        // The miss retry is bounded at ONE re-query (a third call = a re-trigger loop).
        assertEquals(2, calls)
    }
}
