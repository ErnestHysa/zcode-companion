package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import com.zcode.android.features.conversation.ui.MentionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G65 (vc52 Phase 12) compose leg: the desktop's category-FIRST mention menu — the
 * Files category keeps the GAP-21-A provider verbatim (default category, unchanged
 * pins); sessions / skills / plugins / agents ride the category provider and insert
 * the DESKTOP's serialization shapes (`#id`, `$name`, `[@n](plugin://id)`, `@name`).
 * Without a provider the phone is files-only (the bar hides — the old face).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MentionCategoriesUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val inserted = mutableListOf<String>()
    private val fileQueries = mutableListOf<String>()
    private val categoryQueries = mutableListOf<Pair<String, String>>()

    private fun setContent(draft: String, withProvider: Boolean = true) {
        inserted.clear(); fileQueries.clear(); categoryQueries.clear()
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
                    fileSearchProvider = { query, _ ->
                        fileQueries += query
                        emptyList()
                    },
                    mentionCategoriesProvider = if (withProvider) {
                        { category, query ->
                            categoryQueries += category to query
                            when (category) {
                                "sessions" -> listOf(
                                    MentionOption("Ship the release", "task-1", "#task-1"),
                                    MentionOption("Fix login flow", "task-2", "#task-2"),
                                ).filter { query.isBlank() || it.label.contains(query, true) }
                                "skills" -> listOf(MentionOption("review", "Deep review pass", "\$review"))
                                "plugins" -> listOf(MentionOption("acme", "Acme tools", "[@acme](plugin://acme-tools)"))
                                "agents" -> listOf(MentionOption("explorer", "Read-only scout", "@explorer"))
                                else -> emptyList()
                            }
                        }
                    } else null,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the category bar renders and Files stays the default (the file provider still fires)`() {
        setContent(draft = "@x")
        rule.waitUntil(3_000) { fileQueries.isNotEmpty() }
        rule.onNodeWithTag("mention_categories").assertIsDisplayed()
        rule.onNodeWithTag("mention_category_files").assertExists()
        rule.onNodeWithTag("mention_category_sessions").assertExists()
        rule.onNodeWithTag("mention_category_skills").assertExists()
        rule.onNodeWithTag("mention_category_plugins").assertExists()
        rule.onNodeWithTag("mention_category_agents").assertExists()
    }

    @Test
    fun `the sessions category lists tasks and inserts the #id reference`() {
        setContent(draft = "see @")
        rule.onNodeWithTag("mention_category_sessions").performClick()
        rule.waitForIdle()
        rule.waitUntil(3_000) { categoryQueries.any { it.first == "sessions" } }
        rule.onNodeWithTag("mention_item_sessions_Ship the release").assertIsDisplayed()
        rule.onNodeWithTag("mention_item_sessions_Ship the release").performClick()
        rule.waitForIdle()
        assertEquals(listOf("see #task-1 "), inserted)
    }

    @Test
    fun `the skills category inserts the dollar reference`() {
        setContent(draft = "use @")
        rule.onNodeWithTag("mention_category_skills").performClick()
        rule.waitForIdle()
        rule.waitUntil(3_000) { categoryQueries.any { it.first == "skills" } }
        rule.onNodeWithTag("mention_item_skills_review").performClick()
        rule.waitForIdle()
        assertEquals(listOf("use \$review "), inserted)
    }

    @Test
    fun `the plugins category inserts the desktop markdown link`() {
        setContent(draft = "try @")
        rule.onNodeWithTag("mention_category_plugins").performClick()
        rule.waitForIdle()
        rule.waitUntil(3_000) { categoryQueries.any { it.first == "plugins" } }
        rule.onNodeWithTag("mention_item_plugins_acme").performClick()
        rule.waitForIdle()
        assertEquals(listOf("try [@acme](plugin://acme-tools) "), inserted)
    }

    @Test
    fun `the agents category inserts the at reference`() {
        setContent(draft = "ask @")
        rule.onNodeWithTag("mention_category_agents").performClick()
        rule.waitForIdle()
        rule.waitUntil(3_000) { categoryQueries.any { it.first == "agents" } }
        rule.onNodeWithTag("mention_item_agents_explorer").performClick()
        rule.waitForIdle()
        assertEquals(listOf("ask @explorer "), inserted)
    }

    @Test
    fun `without a provider the bar hides and the files face is unchanged`() {
        setContent(draft = "@x", withProvider = false)
        rule.waitUntil(3_000) { fileQueries.isNotEmpty() }
        assertEquals(0, rule.onAllNodesWithTag("mention_categories").fetchSemanticsNodes().size)
        assertTrue(categoryQueries.isEmpty())
    }
}
