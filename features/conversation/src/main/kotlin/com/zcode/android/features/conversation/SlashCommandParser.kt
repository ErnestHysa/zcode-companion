package com.zcode.android.features.conversation

/**
 * GAP-12 — the desktop's send-path slash grammar, ported VERBATIM from
 * parseV4VisibleSlashCommand (packages/ui/src/v4/slashCommands.ts, 29628c9;
 * docs/protocol/v4-wire.md §11.3). Dispatch semantics (SessionPane.tsx:2440-2520):
 *  - /compact (alias /compress) → the v4 `compact` command (not CAS-guarded);
 *  - /goal <objective>, /goal replace <objective> → `sendGoalCommand`;
 *  - /goal resume → `resumeGoal` (CAS-guarded);
 *  - /goal pause → `pauseGoal` (CAS-guarded) — I1-goal (Phase 6A) UNLOCK: the word is
 *    wire-supported (COMMANDS_REQUIRING_BASE_REVISION) and the goal state renders
 *    (D2, Phase 4C), so the old blanket refusal no longer applies; refusals surface
 *    as a typed notice, never a silent prompt;
 *  - /goal clear|show → UNSUPPORTED — refused client-side, never a silent prompt
 *    (clear would drop the goal state; show renders nothing the desktop doesn't
 *    already show in its header);
 *  - empty /goal → refused with a notice;
 *  - /plan <task> → mode shortcut: planEnabled + switch to plan, then the TASK text
 *    as ordinary sendText (the desktop: "命令只负责配置 shortcut；后续必须走普通
 *    sendText");
 *  - ANY other /name (custom commands included) → null: plain sendText with the
 *    RAW slash text ("同名 CLI 命令优先，不由 App 消费").
 * Attachments gate the whole plane (desktop: attachments.length > 0 → null), with
 * /plan degrading to the UNSUPPORTED variant instead of a silent prompt.
 */
sealed class SlashDispatch {
    abstract val displayText: String

    data class Compact(override val displayText: String) : SlashDispatch()
    data class SendGoal(val objective: String, override val displayText: String) : SlashDispatch()
    data class ResumeGoal(override val displayText: String) : SlashDispatch()
    data class PauseGoal(override val displayText: String) : SlashDispatch()
    data class EmptyGoal(override val displayText: String) : SlashDispatch()
    data class UnsupportedGoal(val action: String, override val displayText: String) : SlashDispatch()
    data class PlanShortcut(val task: String, override val displayText: String) : SlashDispatch()
    data class UnsupportedPlanShortcut(override val displayText: String) : SlashDispatch()
}

fun parseVisibleSlashCommand(content: String, attachmentCount: Int = 0): SlashDispatch? {
    val displayText = content.trim()
    if (!displayText.startsWith("/")) return null
    val match = Regex("^/([^\\s]+)(?:\\s+([\\s\\S]*))?$").let { r -> r.find(displayText) }
        ?: return null
    val commandName = match.groupValues[1].lowercase()
    val args = match.groupValues[2].trim()

    if (commandName == "plan") {
        return if (attachmentCount > 0) {
            SlashDispatch.UnsupportedPlanShortcut(displayText)
        } else {
            SlashDispatch.PlanShortcut(task = args, displayText = displayText)
        }
    }

    if (attachmentCount > 0) return null

    if (commandName == "compact" || commandName == "compress") {
        return SlashDispatch.Compact(displayText)
    }
    if (commandName != "goal" && commandName != "target") return null
    if (args.isEmpty()) return SlashDispatch.EmptyGoal(displayText)

    val action = args.split(Regex("\\s+"), limit = 2).first().lowercase()
    if (action == "resume") return SlashDispatch.ResumeGoal(displayText)
    if (action == "pause") return SlashDispatch.PauseGoal(displayText)
    if (action == "clear" || action == "show") {
        return SlashDispatch.UnsupportedGoal(action, displayText)
    }
    val objective = if (action == "replace") args.replace(Regex("^replace\\s*", RegexOption.IGNORE_CASE), "").trim() else args
    if (objective.isEmpty()) return SlashDispatch.EmptyGoal(displayText)
    return SlashDispatch.SendGoal(objective, displayText)
}
