package com.zcode.android.features.conversation.port

/**
 * FIELD (2026-10-12): the desktop's BUILTIN slash command catalog, embedded as a
 * generated list. The relay adapter does not forward workspace-config subscribes
 * for device connections (live-verified: fault.subscribe.sessionNotFound), so the
 * live catalog NEVER loads on the phone — this fallback serves the builtin
 * commands (which the desktop serves from its local CLI server connection).
 * GENERATED from zcode-slash-command-help.ts
 * BUILTIN_ZCODE_SLASH_COMMAND_HELP_ENTRIES — regenerate, never hand-edit.
 */
object BuiltinSlashCatalog {
    data class Entry(val name: String, val description: String, val inputHint: String?)
    val COMMANDS: List<Entry> = listOf(
        Entry("help", "Show this slash command help.", "[command]"),
        Entry("login", "Set up a Coding Plan provider.", "[zai-coding-plan|bigmodel-coding-plan|zai-coding-plan-api-key <api-key>|bigmodel-coding-plan-api-key <api-key>]"),
        Entry("logout", "Remove the shared Z.ai login credentials.", null),
        Entry("compact", "Compact the current conversation with optional instructions.", "[instructions]"),
        Entry("init", "Create or update workspace AGENTS.md instructions.", "[notes]"),
        Entry("expert", "Run or manage the expert workflow.", "[status|resume|stop|<task>]"),
        Entry("dwf", "List, cancel, or resume dynamic workflow runs.", "[list|cancel [runId]|resume <runId>]"),
        Entry("fork", "Fork a new session from a workspace checkpoint.", "[latest|checkpointId]"),
        Entry("mcp", "Show or manage configured MCP servers.", "[list|status|connect <server>|disconnect <server>]"),
        Entry("mode", "Show or switch the current permission mode.", "[plan|build|edit|yolo]"),
        Entry("model", "Show or switch the current session model.", "[list|provider/model]"),
        Entry("rewind", "Inspect or restore workspace checkpoints.", "[latest|checkpointId]"),
        Entry("skill", "List skills, or force the next prompt to load one.", "[<skill-name> [task]]"),
        Entry("workflow", "Design and launch a dynamic workflow for a task.", "[what the workflow should accomplish]"),
    )
}
