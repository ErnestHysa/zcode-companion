package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.features.conversation.ui.ApprovalCard
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G76 (vc52 Phase 12) compose leg: the permission card renders the RUNTIME-NAMED
 * scoped options (the label IS the scope — the wire has no scope field) with the
 * scope/per-kind description line; the picked optionId rides the answer (the
 * wire carries the scope by WHICH option was sent); legacy ids keep the
 * consequence copy; inert (OTHER) options stay inert.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class PermissionScopeCardUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val answered = mutableListOf<String>()

    private fun payload(toolName: String, options: List<Triple<String, String?, String>>) =
        PendingInteraction.PermissionPayload(
            toolCallId = "tc-1",
            toolName = toolName,
            summary = "Run the build?",
            detail = null,
            freeText = false,
            options = options.map { (id, label, kind) ->
                com.zcode.android.domain.model.PermissionOption(id, label, kind)
            },
            raw = JsonObject(emptyMap()),
        )

    private fun interaction(payload: PendingInteraction.PermissionPayload) = PendingInteraction(
        interactionId = "perm-1",
        kind = PendingInteraction.Kind.PERMISSION,
        anchorRowId = null,
        createdAtMs = null,
        permission = payload,
        userInput = null,
        raw = JsonObject(emptyMap()),
    )

    private fun setContent(payload: PendingInteraction.PermissionPayload) {
        answered.clear()
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                ApprovalCard(
                    interaction = interaction(payload),
                    busy = false,
                    onAnswer = { optionId, _ -> answered.add(optionId) },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `scoped runtime options render their labels and the scope descriptions`() {
        setContent(
            payload(
                "Bash",
                options = listOf(
                    Triple("allowOnce", null, "allowOnce"),
                    Triple("opt-proj", "Always allow in this project", "allowAlways"),
                    Triple("opt-sess", "Always allow in this session", "allowAlways"),
                ),
            ),
        )
        rule.onNodeWithText("Always allow in this project").assertExists()
        rule.onNodeWithText("Always allow in this session").assertExists()
        rule.onNodeWithTag("approval_desc_opt-proj").assertExists()
        rule.onNodeWithText("Do not ask again for matching requests in this project.").assertExists()
        rule.onNodeWithTag("approval_desc_opt-sess").assertExists()
        rule.onNodeWithText("Do not ask again for the rest of this session.").assertExists()
        // The wire carries the scope by WHICH option is sent. The always-class
        // CONFIRMS first (§20.2) — the confirm body names the scope choice.
        rule.onNodeWithTag("approval_opt-proj").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("approval_confirm_yes").performClick()
        rule.waitForIdle()
        assertEquals(listOf("opt-proj"), answered)
    }

    @Test
    fun `the unscoped always carries the per-kind description (command family)`() {
        setContent(
            payload(
                "Bash",
                options = listOf(Triple("allowAlways", "Always allow", "allowAlways")),
            ),
        )
        rule.onNodeWithTag("approval_desc_allowAlways").assertExists()
        rule.onNodeWithText("Do not ask again for the same command.").assertExists()
    }

    @Test
    fun `the fullAccess option renders its description and fires its literal id`() {
        setContent(
            payload(
                "Bash",
                options = listOf(Triple("fullAccess", "Full access", "custom")),
            ),
        )
        rule.onNodeWithText("Full access").assertExists()
        rule.onNodeWithText("Grant the agent full access without further confirmation.").assertExists()
        rule.onNodeWithTag("approval_fullAccess").performClick()
        rule.waitForIdle()
        // §20.2: the allowAlways-class confirms before sending.
        rule.onNodeWithTag("approval_confirm_yes").performClick()
        rule.waitForIdle()
        assertEquals(listOf("fullAccess"), answered)
    }

    @Test
    fun `a custom option with no scope stays inert (the unsupported face)`() {
        setContent(
            payload(
                "Bash",
                options = listOf(Triple("opt-weird", "Something else", "custom")),
            ),
        )
        rule.onAllNodesWithTag("approval_unsupported_opt-weird").assertCountEquals(1)
        rule.onNodeWithTag("approval_unsupported_opt-weird").performClick()
        rule.waitForIdle()
        assertEquals(0, answered.size)
    }
}
