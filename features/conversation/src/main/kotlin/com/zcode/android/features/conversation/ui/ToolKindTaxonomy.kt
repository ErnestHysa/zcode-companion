package com.zcode.android.features.conversation.ui

/**
 * G80 (vc52 Phase 13): the kernel's own tool-kind taxonomy, phone-ported. The
 * desktop's compact-tool-call layer keys tools BY NAME into a 14-kind map over
 * 31 names (+ aliases); the phone bucketed with 5 substring checks and
 * scattered the kernel's scheduling family (CronCreate/CronDelete as CHANGES,
 * CronList as EXPLORE, CronUpdate to OTHER). The name-keyed map fixes the
 * scatter; UNKNOWN names keep null (the substring heuristic keeps ownership —
 * the fallback is graceful, INV-8 direction).
 */
object ToolKindTaxonomy {

    /** The kernel's 14 kinds (the desktop's compact-tool-call map, verbatim keys). */
    enum class ToolKind(val key: String) {
        FILE_READ("file-read"),
        FILE_WRITE("file-write"),
        SHELL("shell"),
        SEARCH("search"),
        TODO("todo"),
        GOAL("goal"),
        SESSION_CONTEXT("session-context"),
        ASK_USER_QUESTION("ask-user-question"),
        MESSAGE("message"),
        TASK_CONTROL("task-control"),
        NODE_REPL("node-repl"),
        AGENT("agent"),
        SKILL("skill"),
        WORKFLOW("workflow"),
    }

    private val BY_NAME: Map<String, ToolKind> = buildMap {
        // file-read
        put("Read", ToolKind.FILE_READ)
        // file-write
        put("Edit", ToolKind.FILE_WRITE)
        put("Write", ToolKind.FILE_WRITE)
        put("NotebookEdit", ToolKind.FILE_WRITE)
        put("ApplyPatch", ToolKind.FILE_WRITE)
        // shell
        put("Bash", ToolKind.SHELL)
        // search
        put("Glob", ToolKind.SEARCH)
        put("Grep", ToolKind.SEARCH)
        put("LSP", ToolKind.SEARCH)
        put("WebFetch", ToolKind.SEARCH)
        put("WebSearch", ToolKind.SEARCH)
        put("web_search", ToolKind.SEARCH)
        // todo
        put("TodoRead", ToolKind.TODO)
        put("TodoWrite", ToolKind.TODO)
        // goal
        put("EnterPlanMode", ToolKind.GOAL)
        put("ExitPlanMode", ToolKind.GOAL)
        // session-context
        put("EnterWorktree", ToolKind.SESSION_CONTEXT)
        put("ExitWorktree", ToolKind.SESSION_CONTEXT)
        // ask-user-question
        put("AskUserQuestion", ToolKind.ASK_USER_QUESTION)
        // task-control (the kernel's scheduling family lives HERE)
        for (n in listOf(
            "TaskCreate", "TaskGet", "TaskList", "TaskOutput", "TaskStop", "TaskUpdate",
            "CronCreate", "CronDelete", "CronList", "CronUpdate", "ScheduleWakeup",
            "KillShell", "KillBash",
        )) {
            put(n, ToolKind.TASK_CONTROL)
        }
        // node-repl
        put("js", ToolKind.NODE_REPL)
        put("js_reset", ToolKind.NODE_REPL)
        put("js_add_node_module_dir", ToolKind.NODE_REPL)
        // agent / skill / workflow
        put("Agent", ToolKind.AGENT)
        put("Skill", ToolKind.SKILL)
        put("Workflow", ToolKind.WORKFLOW)
    }

    /** Exact-name kind; null = unknown (graceful fallback — never guessed). */
    fun kindOf(toolName: String): ToolKind? = BY_NAME[toolName]

    /** The kernel kind's GROUPING bucket (ToolCategory) — the name-keyed fix. */
    fun categoryOf(toolName: String): ToolCategory? = when (kindOf(toolName)) {
        ToolKind.FILE_READ -> ToolCategory.EXPLORE
        ToolKind.FILE_WRITE -> ToolCategory.CHANGES
        ToolKind.SHELL -> ToolCategory.EXECUTE
        ToolKind.SEARCH -> ToolCategory.EXPLORE
        ToolKind.NODE_REPL -> ToolCategory.EXECUTE
        ToolKind.TODO,
        ToolKind.GOAL,
        ToolKind.SESSION_CONTEXT,
        ToolKind.ASK_USER_QUESTION,
        ToolKind.MESSAGE,
        ToolKind.TASK_CONTROL,
        ToolKind.AGENT,
        ToolKind.SKILL,
        ToolKind.WORKFLOW,
        -> ToolCategory.OTHER
        null -> null
    }

    /** The 14 kinds in map-declaration order (the label table's canonical order). */
    val ALL_KINDS: List<ToolKind> = ToolKind.entries.toList()
}
