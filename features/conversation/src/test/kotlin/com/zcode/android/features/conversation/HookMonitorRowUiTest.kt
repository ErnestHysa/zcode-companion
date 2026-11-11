package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.HookInvocationRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/**
 * G71 (vc52 Phase 12) compose leg: the per-turn hooks monitor renders the
 * `hookInvocation` row's executions grouped under the event name — per-execution
 * displayName + source + state (running/completed/blocked/failed/timed-out/
 * cancelled) + duration + blockReason; `didExecute=false` stays hidden (the
 * desktop's sTn filter); the group starts COLLAPSED and expands in place.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class HookMonitorRowUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun exec(
        runId: String,
        displayName: String,
        state: String,
        outcome: String? = null,
        source: String = "project",
        didExecute: Boolean = true,
        durationMs: Long? = null,
        blockReason: String? = null,
        pluginName: String? = null,
    ): JsonObject {
        val fields = mutableListOf<Pair<String, kotlinx.serialization.json.JsonElement>>(
            "hookRunId" to JsonPrimitive(runId),
            "hookIndex" to JsonPrimitive(0),
            "didExecute" to JsonPrimitive(didExecute),
            "state" to JsonPrimitive(state),
            "displayName" to JsonPrimitive(displayName),
            "sourceKind" to JsonPrimitive(source),
        )
        outcome?.let { fields.add("outcome" to JsonPrimitive(it)) }
        durationMs?.let { fields.add("durationMs" to JsonPrimitive(it)) }
        blockReason?.let { fields.add("blockReason" to JsonPrimitive(it)) }
        pluginName?.let { fields.add("pluginName" to JsonPrimitive(it)) }
        return JsonObject(fields.toMap())
    }

    private fun row(vararg execs: JsonObject) = ConversationRow.Decoder.decode(
        JsonObject(
            mapOf(
                "rowId" to JsonPrimitive(9L),
                "turnId" to JsonPrimitive("t1"),
                "kind" to JsonPrimitive("hookInvocation"),
                "entityId" to JsonPrimitive("e1"),
                "createdAt" to JsonPrimitive(1.0),
                "createdAtSeq" to JsonPrimitive(9L),
                "hookInvocationId" to JsonPrimitive("hi-9"),
                "hookEventName" to JsonPrimitive("PreToolUse"),
                "hookCount" to JsonPrimitive(execs.size.toLong()),
                "state" to JsonPrimitive("completed"),
                "lane" to JsonPrimitive("toolAfter"),
                "executions" to JsonArray(execs.toList()),
            ),
        ),
    ) as ConversationRow.HookInvocation

    @Test
    fun `the group renders collapsed and expands to the execution rows`() {
        val r = row(
            exec("hr-1", "Lint gate", "completed", outcome = "success", durationMs = 210),
            exec("hr-2", "Secrets guard", "completed", outcome = "blocked", source = "user", blockReason = "deny rule"),
        )
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) { HookInvocationRow(r) }
        }
        rule.onNodeWithTag("row_hooks").assertExists()
        rule.onNodeWithText("Hooks · PreToolUse · 2").assertExists()
        rule.onAllNodesWithTag("hook_exec_hr-1").assertCountEquals(0)
        rule.onNodeWithTag("row_hooks_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_exec_hr-1").assertExists()
        rule.onNodeWithTag("hook_exec_hr-2").assertExists()
        rule.onNodeWithText("Lint gate").assertExists()
        rule.onNodeWithText("Secrets guard").assertExists()
        rule.onNodeWithText("blocked", substring = true).assertExists()
        rule.onNodeWithTag("hook_block_hr-2").assertExists()
    }

    @Test
    fun `running failed and cancelled faces render their state labels`() {
        val r = row(
            exec("hr-5", "Warm build cache", "running"),
            exec("hr-6", "Telemetry", "failed", outcome = "failed", source = "plugin", pluginName = "acme"),
            exec("hr-7", "Watcher", "completed", outcome = "cancelled"),
            exec("hr-8", "Slow scan", "completed", outcome = "timed_out"),
        )
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) { HookInvocationRow(r) }
        }
        rule.onNodeWithTag("row_hooks_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("running", substring = true).assertExists()
        rule.onNodeWithText("failed", substring = true).assertExists()
        rule.onNodeWithText("cancelled", substring = true).assertExists()
        rule.onNodeWithText("timed out", substring = true).assertExists()
    }

    @Test
    fun `a didExecute-false execution stays hidden`() {
        val r = row(
            exec("hr-9", "Skipped watcher", "completed", didExecute = false),
            exec("hr-10", "Lint gate", "completed"),
        )
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) { HookInvocationRow(r) }
        }
        rule.onNodeWithText("Hooks · PreToolUse · 1").assertExists()
        rule.onNodeWithTag("row_hooks_toggle").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("hook_exec_hr-9").assertCountEquals(0)
        rule.onNodeWithTag("hook_exec_hr-10").assertExists()
    }

    @Test
    fun `no didExecute rows keeps the quiet fallback`() {
        val r = row(exec("hr-11", "Skipped", "completed", didExecute = false))
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) { HookInvocationRow(r) }
        }
        rule.onAllNodesWithTag("row_hooks").assertCountEquals(0)
        rule.onAllNodesWithTag("row_other").assertCountEquals(1)
    }
}
