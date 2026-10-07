package com.zcode.android.features.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GAP-12 (docs/protocol/v4-wire.md §11.3) — the desktop send-path grammar ported
 * VERBATIM from parseV4VisibleSlashCommand (packages/ui/src/v4/slashCommands.ts,
 * 29628c9): /compact (alias /compress) → compact; /goal <objective> and
 * /goal replace <objective> → sendGoalCommand; /goal resume → resumeGoal;
 * /goal pause|clear|show → unsupported (NOT silently sent as a prompt);
 * empty /goal → EmptyGoal; /plan <task> → plan shortcut (mode + planEnabled, then
 * the task as sendText); ANY other /name → null (plain sendText — the CLI consumes
 * custom commands). Attachments block all of it (desktop parse gate).
 */
class SlashCommandParserTest {

    private fun parse(text: String, attachments: Int = 0) = parseVisibleSlashCommand(text, attachments)

    @Test
    fun nonSlashText_isNull() {
        assertNull(parse("fix the login bug"))
        assertNull(parse(""))
        assertNull(parse("   "))
    }

    @Test
    fun compactAndCompress_toCompact() {
        assertEquals(SlashDispatch.Compact("/compact"), parse("/compact"))
        assertEquals(SlashDispatch.Compact("/compress"), parse("/compress"))
        assertEquals(SlashDispatch.Compact("/compact keep the goal"), parse("/compact keep the goal"))
        assertEquals(SlashDispatch.Compact("/COMPACT"), parse("/COMPACT"), "commandName is case-insensitive (desktop toLowerCase)")
    }

    @Test
    fun goalVariants_followTheDesktopTable() {
        assertEquals(
            SlashDispatch.SendGoal(objective = "ship the release", displayText = "/goal ship the release"),
            parse("/goal ship the release"),
        )
        assertEquals(
            SlashDispatch.SendGoal(objective = "new target", displayText = "/goal replace new target"),
            parse("/goal replace new target"),
        )
        assertEquals(SlashDispatch.ResumeGoal("/goal resume"), parse("/goal resume"))
        assertEquals(SlashDispatch.EmptyGoal("/goal"), parse("/goal"))
        assertEquals(SlashDispatch.EmptyGoal("/goal   "), parse("/goal   "))
        assertEquals(SlashDispatch.UnsupportedGoal("pause", "/goal pause"), parse("/goal pause"))
        assertEquals(SlashDispatch.UnsupportedGoal("clear", "/goal clear"), parse("/goal clear"))
        assertEquals(SlashDispatch.UnsupportedGoal("show", "/goal show"), parse("/goal show"))
    }

    @Test
    fun planShortcut_carriesTask() {
        assertEquals(
            SlashDispatch.PlanShortcut(task = "migrate the db", displayText = "/plan migrate the db"),
            parse("/plan migrate the db"),
        )
        assertEquals(SlashDispatch.PlanShortcut(task = "", displayText = "/plan"), parse("/plan"))
    }

    @Test
    fun customAndUnknownCommands_areNull_plainSendText() {
        assertNull(parse("/deploy prod"), "custom commands go to the CLI as plain sendText")
        assertNull(parse("/definitely-not-a-command"))
    }

    @Test
    fun attachmentsBlockEverySlashDispatch() {
        // Desktop gate: attachments.length > 0 → null (except nothing — even compact).
        assertNull(parse("/compact", attachments = 1))
        assertNull(parse("/goal x", attachments = 1))
        assertEquals(
            SlashDispatch.UnsupportedPlanShortcut(displayText = "/plan x"),
            parse("/plan x", attachments = 1),
            "/plan with attachments is the UNSUPPORTED variant (must be refused, never a silent prompt)",
        )
    }

    @Test
    fun goalIsNotConfusedInsideLongerWords() {
        assertNull(parse("/goals x"), "the desktop regex anchors /goal|/target on a word boundary")
        assertEquals(
            SlashDispatch.SendGoal("x", "/target x"),
            parse("/target x"),
            "target is a goal alias on the desktop",
        )
    }

    @Test
    fun replaceWithoutObjective_isEmptyGoal() {
        assertEquals(SlashDispatch.EmptyGoal("/goal replace"), parse("/goal replace"))
        assertTrue(true)
    }
}
