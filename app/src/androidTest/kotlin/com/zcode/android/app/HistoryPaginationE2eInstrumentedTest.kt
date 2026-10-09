package com.zcode.android.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import androidx.compose.ui.test.assertCountEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FIELD-KILLER #10 END-TO-END (2026-09-30, vc14 field report — reproduced on the
 * emulator): the REAL app, paired against an in-process mock desktop, opening a task
 * whose snapshot is a TRUNCATED TAIL WINDOW (the production shape), then paging back
 * through history by scrolling up + the "Load earlier" strip.
 *
 * Verifies Ernest's exact flow:
 *  1. open task → the view anchors at the TAIL (newest window row displayed; the
 *     window's first row NOT composed) — and NO spurious "loading earlier" on entry;
 *  2. scroll up → "Load earlier" strip → tap → a page lands (rowId 1 appears);
 *  3. hasMore retires when the page reports no more (strip disappears).
 */
@RunWith(AndroidJUnit4::class)
class HistoryPaginationE2eInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule(MainActivity::class.java)

    private var mock: MockZCode? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Start from a wiped pairing state (same wipe the pairing E2E uses).
        val store = AppStores.get(context).credentialStore
        CredentialVault(store, AndroidKeystoreKeyWrap(biometricBound = false), ZLog(sink = {})).wipe()
        rule.activityRule.scenario.recreate()
    }

    @After
    fun tearDown() {
        mock?.stop()
    }

    private fun hash(): String = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    private fun dumpTree(): String {
        val sb = StringBuilder()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode, d: Int) {
            sb.append("  ".repeat(d)).append("tag=")
                .append(n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "—" })
                .append(" text=")
                .append(n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .joinToString("|") { it.text }).append('\n')
            n.children.forEach { walk(it, d + 1) }
        }
        walk(rule.onRoot().fetchSemanticsNode(), 0)
        return sb.toString()
    }

    private fun linkFor(port: Int): String =
        "http://127.0.0.1:$port/remote/v4?sid=d_SyntheticSid000001" +
            "&hash=" + java.net.URLEncoder.encode(hash(), "UTF-8") +
            "&t=${System.currentTimeMillis()}&mid=mock-desktop-mid&name=MockBook&app_version=3.14.4"

    @Test
    fun openTask_anchorsAtTail_scrollUpPagesHistoryUntilExhausted() {
        val m = MockZCode(
            passHash = hash(),
            expectedSid = "d_SyntheticSid000001",
            workspace = MockZCode.MockWorkspace(sessionId = "sess_hist_e2e", rowCount = 40),
        ).apply {
            script.authScenario = MockZCode.AuthScenario.HAPPY
            // Truncated tail window: rows 5000..5007, firstRowId=1 → more history above.
            script.snapshotRowsWindowShape = true
            script.snapshotWindowTruncated = true
            script.snapshotRowIdBase = 5_000L
            // Production-shape bootstrap task (blank workspaceKey ⇒ dropped by the hub).
            script.bootstrapTasks = listOf(
                MockZCode.MockTask(
                    taskId = "sess_hist_e2e",
                    title = "History E2E Task",
                    displayStatus = "running",
                    workspacePath = "/synthetic/ws",
                    workspaceLabel = "Synthetic Workspace",
                    // The §17.3 sweep drops rows older than 7 d — a stale fixed timestamp
                    // would be swept the moment it lands (mock-only artifact).
                    createdAtMs = System.currentTimeMillis() - 60_000,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            start()
            mock = this
        }
        val portField = MockZCode::class.java.getDeclaredField("server")
        portField.isAccessible = true
        val server = portField.get(m) as mockwebserver3.MockWebServer
        val port = server.url("/ws").port

        // ---- pair via the manual-entry path (the E2E variant of the QR flow)
        android.util.Log.d("HISTDBG", "stage=waiting-open-manual")
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("open-manual").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("open-manual").performClick()
        android.util.Log.d("HISTDBG", "stage=waiting-manual-field")
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("manual-field").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("manual-field").performClick()
        rule.onNodeWithTag("manual-field").performTextInput(linkFor(port))
        rule.onNodeWithTag("manual-submit").performClick()
        android.util.Log.d("HISTDBG", "stage=link-submitted")
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("consent-reduced").fetchSemanticsNodes().isNotEmpty() }
        android.util.Log.d("HISTDBG", "stage=consent")
        rule.onNodeWithTag("consent-reduced").performClick()

        // ---- task list: open the session
        android.util.Log.d("HISTDBG", "stage=waiting-tasks-list")
        try {
            rule.waitUntil(20_000) { rule.onAllNodesWithTag("tasks_list").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            val root = rule.onRoot().fetchSemanticsNode()
            val sb = StringBuilder()
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode, d: Int) {
                sb.append("  ".repeat(d)).append("tag=").append(n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "—" })
                    .append(" text=").append(n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString("|") { it.text }).append('\n')
                n.children.forEach { walk(it, d + 1) }
            }
            walk(root, 0)
            android.util.Log.d("HISTDBG", "TASKSTREE:\n$sb")
            throw e
        }
        android.util.Log.d("HISTDBG", "stage=tasks-list-ready")
        // Wait on the TITLE TEXT (layout-independent) — diagnoses whether the projection
        // received the task at all vs. a tag-render problem.
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("History E2E Task").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("History E2E Task").performClick()


        // ---- 1. anchored at the TAIL: newest window row (5007) composed + displayed;
        //         the window's first row (5000) is far above and NOT composed, and the
        //         "Load earlier" strip is present WITHOUT any page having been fetched.
        android.util.Log.d("HISTDBG", "stage=waiting-tail-row")
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("conv-row-5039").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("conv-row-5039").assertIsDisplayed()
        // The window (40 rows) is taller than the viewport: anchored at the tail, the
        // FIRST window row must not even be composed.
        rule.onAllNodesWithTag("conv-row-5000").assertCountEquals(0)
        android.util.Log.d("HISTDBG", "stage=waiting-strip")
        try {
            rule.waitUntil(6_000) {
                rule.onAllNodesWithTag("conversation_load_older_strip").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            android.util.Log.d("HISTDBG", "STRIPTREE:\n" + dumpTree())
            throw e
        }

        // ---- 2. scroll to the top of the list: EITHER the scroll-up prefetch or the
        //         strip tap delivers the next page (the prefetch hides the strip while
        //         loading — that's the designed behavior, so accept both paths).
        rule.onNodeWithTag("conversation_list").performScrollToIndex(0)

        // ---- 3. the page lands in the STORE (rowId 1 = history above the window); the
        //         prepended rows stay OFF-screen above the anchored reader position, so
        //         verify the store, then scroll the list to the new top.
        android.util.Log.d("HISTDBG", "stage=waiting-page-row")
        val store = AppStores.get(InstrumentationRegistry.getInstrumentation().targetContext).conversationRepository
        kotlinx.coroutines.runBlocking {
            withTimeout(30_000) {
                while (store.cachedRowCount(com.zcode.android.domain.ids.SessionId("sess_hist_e2e")) < 80) delay(100)
            }
        }
        rule.onNodeWithTag("conversation_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("conv-row-1"),
        )
        rule.onNodeWithTag("conv-row-1").assertIsDisplayed()
        android.util.Log.d("HISTDBG", "stage=waiting-strip-retire")
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("conversation_load_older_strip").fetchSemanticsNodes().isEmpty()
        }
        check(true)
    }
}
