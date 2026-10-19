package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C7 (gaps-and-polish Phase 4C): @-mentions in the composer — the desktop's mention
 * panel suggests workspace files (the same search service; the phone's port has owned
 * `searchWorkspaceFiles` since Phase 3C with ZERO callers). Typing "@token" suggests
 * files; picking inserts the path (the desktop's mention form). The composer's add
 * menu carries a discoverable "Mention a file" entry.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MentionFilesUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val queries = mutableListOf<String>()
    private val inserted = mutableListOf<String>()

    private val files = listOf(
        "/synthetic/ws/src/main.rs",
        "/synthetic/ws/docs/readme.md",
        "/synthetic/ws/src/net/client.rs",
    )

    private fun setContent(draft: String = "") {
        queries.clear(); inserted.clear()
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
                    onDraftChange = { inserted += it },
                    onSend = {},
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    fileSearchProvider = { query ->
                        queries += query
                        files.filter { it.lowercase().contains(query.lowercase()) }
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `typing an at-token suggests workspace files`() {
        setContent(draft = "look at @read")
        // The suggestion query is debounce-delayed (150ms real time) — wait for it.
        rule.waitUntil(3_000) { queries.isNotEmpty() }
        assertEquals(listOf("read"), queries)
        rule.onNodeWithText("/synthetic/ws/docs/readme.md").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("/synthetic/ws/src/main.rs").fetchSemanticsNodes().size)
    }

    @Test
    fun `picking a suggestion inserts the path in place of the at-token`() {
        setContent(draft = "look at @read")
        rule.waitUntil(3_000) { queries.isNotEmpty() }
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("mention_file_/synthetic/ws/docs/readme.md", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("mention_file_/synthetic/ws/docs/readme.md", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("look at /synthetic/ws/docs/readme.md "), inserted)
    }

    @Test
    fun `no matches render no menu`() {
        setContent(draft = "@zzz")
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("mention_menu").fetchSemanticsNodes().size)
    }

    @Test
    fun `the add menu carries a discoverable Mention-a-file entry`() {
        setContent()
        rule.onNodeWithTag("composer_attach").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Mention a file").assertIsDisplayed()
    }
}
