package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.ui.ToolCategory
import com.zcode.android.features.conversation.ui.ToolKindTaxonomy
import com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * G80 (vc52 Phase 13): the kernel's own tool-kind taxonomy. The desktop's
 * compact-tool-call layer keys tools BY NAME into a 14-kind map over 31 names
 * (+ KillShell/KillBash as TaskStop aliases, ApplyPatch, the js/js_reset/
 * js_add_node_module_dir node-repl family, web_search as WebSearch's provider
 * alias); the phone bucketed with 5 substring checks and SCATTERED the kernel's
 * scheduling family — CronCreate/CronDelete matched create/delete as file
 * CHANGES, CronList matched list as EXPLORE, CronUpdate fell to OTHER, so a
 * CronCreate next to an Edit folded into one "Changes" group.
 *
 * Pins: the FULL name list maps to the exact kind (the roadmap's named minimum);
 * the name-keyed buckets fix the scheduling scatter; unknown names fall back
 * gracefully (null kind; the substring heuristic keeps ownership of them).
 */
class ToolKindTaxonomyTest {

    @Test
    fun `the full 31-name kernel list maps to the exact kind`() {
        val expected = mapOf(
            "Agent" to ToolKind.AGENT,
            "AskUserQuestion" to ToolKind.ASK_USER_QUESTION,
            "Bash" to ToolKind.SHELL,
            "CronCreate" to ToolKind.TASK_CONTROL,
            "CronDelete" to ToolKind.TASK_CONTROL,
            "CronList" to ToolKind.TASK_CONTROL,
            "CronUpdate" to ToolKind.TASK_CONTROL,
            "Edit" to ToolKind.FILE_WRITE,
            "EnterPlanMode" to ToolKind.GOAL,
            "EnterWorktree" to ToolKind.SESSION_CONTEXT,
            "ExitPlanMode" to ToolKind.GOAL,
            "ExitWorktree" to ToolKind.SESSION_CONTEXT,
            "Glob" to ToolKind.SEARCH,
            "Grep" to ToolKind.SEARCH,
            "LSP" to ToolKind.SEARCH,
            "NotebookEdit" to ToolKind.FILE_WRITE,
            "Read" to ToolKind.FILE_READ,
            "ScheduleWakeup" to ToolKind.TASK_CONTROL,
            "Skill" to ToolKind.SKILL,
            "TaskCreate" to ToolKind.TASK_CONTROL,
            "TaskGet" to ToolKind.TASK_CONTROL,
            "TaskList" to ToolKind.TASK_CONTROL,
            "TaskOutput" to ToolKind.TASK_CONTROL,
            "TaskStop" to ToolKind.TASK_CONTROL,
            "TaskUpdate" to ToolKind.TASK_CONTROL,
            "TodoRead" to ToolKind.TODO,
            "TodoWrite" to ToolKind.TODO,
            "WebFetch" to ToolKind.SEARCH,
            "WebSearch" to ToolKind.SEARCH,
            "Workflow" to ToolKind.WORKFLOW,
            "Write" to ToolKind.FILE_WRITE,
        )
        assertEquals(31, expected.size)
        for ((name, kind) in expected) {
            assertEquals(name, kind, ToolKindTaxonomy.kindOf(name))
        }
    }

    @Test
    fun `the kernel's aliases map to their canonical kinds`() {
        assertEquals(ToolKind.TASK_CONTROL, ToolKindTaxonomy.kindOf("KillShell"))
        assertEquals(ToolKind.TASK_CONTROL, ToolKindTaxonomy.kindOf("KillBash"))
        assertEquals(ToolKind.FILE_WRITE, ToolKindTaxonomy.kindOf("ApplyPatch"))
        assertEquals(ToolKind.NODE_REPL, ToolKindTaxonomy.kindOf("js"))
        assertEquals(ToolKind.NODE_REPL, ToolKindTaxonomy.kindOf("js_reset"))
        assertEquals(ToolKind.NODE_REPL, ToolKindTaxonomy.kindOf("js_add_node_module_dir"))
        assertEquals(ToolKind.SEARCH, ToolKindTaxonomy.kindOf("web_search"))
    }

    @Test
    fun `unknown names have no kind (graceful fallback)`() {
        assertNull(ToolKindTaxonomy.kindOf("mcp__computer-use__screenshot"))
        assertNull(ToolKindTaxonomy.kindOf("SomeCustomTool"))
        assertNull(ToolKindTaxonomy.kindOf(""))
    }

    @Test
    fun `the scheduling family scatters NO MORE - the name-keyed buckets`() {
        // The audit's simulated scatter, now fixed: all four Cron words are
        // TASK_CONTROL -> OTHER (one group beside TaskUpdate, never beside Edit).
        assertEquals(ToolCategory.OTHER, ToolKindTaxonomy.categoryOf("CronCreate"))
        assertEquals(ToolCategory.OTHER, ToolKindTaxonomy.categoryOf("CronDelete"))
        assertEquals(ToolCategory.OTHER, ToolKindTaxonomy.categoryOf("CronList"))
        assertEquals(ToolCategory.OTHER, ToolKindTaxonomy.categoryOf("CronUpdate"))
        // The known families keep their desktop buckets.
        assertEquals(ToolCategory.EXPLORE, ToolKindTaxonomy.categoryOf("Read"))
        assertEquals(ToolCategory.EXPLORE, ToolKindTaxonomy.categoryOf("Grep"))
        assertEquals(ToolCategory.EXECUTE, ToolKindTaxonomy.categoryOf("Bash"))
        assertEquals(ToolCategory.CHANGES, ToolKindTaxonomy.categoryOf("Edit"))
        assertEquals(ToolCategory.CHANGES, ToolKindTaxonomy.categoryOf("Write"))
        assertEquals(ToolCategory.OTHER, ToolKindTaxonomy.categoryOf("TodoWrite"))
    }

    @Test
    fun `unknown names fall back to the substring heuristic (ToolCategory ownership unchanged)`() {
        // The G18 CUA contract holds (plugin-spelled names keep their group).
        assertEquals(ToolCategory.COMPUTER_USE, ToolCategory.of("mcp__computer-use__screenshot"))
        assertEquals(ToolCategory.COMPUTER_USE, ToolCategory.of("mcp__computer-use__click"))
        // A custom plugin name with a change word still buckets by substring.
        assertEquals(ToolCategory.CHANGES, ToolCategory.of("myCustomReplace"))
    }
}
