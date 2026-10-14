package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.features.tasks.port.AutomationsPort
import com.zcode.android.features.tasks.ui.AutomationsSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B2.1+J2 (gaps-and-polish Phase 1B, P0): the automation editor silently corrupted
 * every non-minute schedule. kind inited as `if (unit == "minute") 0 else 1`, so a
 * WEEKLY (or any cron-carried) automation opened as DAILY with a hardcoded 09:00;
 * the configured model was dropped (pickedModel always null); a blank cron field
 * silently saved the default "0 9 * * *"; and cron syntax was never validated.
 * J10 pins: weekly round-trip unchanged, blank cron refused, model round-trips.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AutomationsEditorIntegrityTest {

    @get:Rule
    val rule = createComposeRule()

    /** Recording fake: every create/update write is captured for the round-trip pins. */
    private class RecordingPort(autos: List<AutomationRow>) : AutomationsPort {
        val created = mutableListOf<AutomationsPort.AutomationWrite>()
        val updated = mutableListOf<Pair<String, AutomationsPort.AutomationWrite>>()
        private val lists = AutomationsPort.Lists(autos, null, null)

        override suspend fun lists() = lists
        override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite): Boolean {
            created += write
            return true
        }

        override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite): Boolean {
            updated += automationId to write
            return true
        }

        override suspend fun delete(automationId: String): Boolean = true
        override suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean = true
        override suspend fun runNow(automationId: String): Boolean = true
    }

    private fun weeklyRow() = AutomationRow.decodeList(
        buildJsonObject {
            put(
                "automations",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("automationId", "auto-weekly-1")
                            put("title", "Weekly report")
                            put("cronExpr", "30 5 * * 1")
                            put("prompt", "Write the weekly report")
                            put("mode", "build")
                            put("targetTaskId", kotlinx.serialization.json.JsonNull)
                            put("enabled", true)
                            put("lifecycleStatus", "active")
                            put("nextRunAt", kotlinx.serialization.json.JsonPrimitive(1_791_000_000_000L))
                            put("lastRunAt", kotlinx.serialization.json.JsonNull)
                            put("runCount", 3)
                            put("recurring", true)
                            put("maxRuns", kotlinx.serialization.json.JsonNull)
                            put(
                                "scheduleRule",
                                buildJsonObject {
                                    put("unit", "week")
                                    put("interval", 1)
                                    put("hour", 5)
                                    put("minute", 30)
                                    put("anchorAt", 1_700_000_000_000L)
                                    put(
                                        "weekdays",
                                        buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(1)) },
                                    )
                                    put("monthDays", buildJsonArray {})
                                },
                            )
                            put(
                                "modelSelection",
                                buildJsonObject {
                                    put("providerId", "zai")
                                    put("modelId", "glm-5.1")
                                },
                            )
                            put("workspaceKey", "/synthetic/ws")
                            put("locationKind", "remote")
                        },
                    )
                },
            )
        }.toString().let { Json.parseToJsonElement(it).jsonObject },
    ).first()

    private fun setContent(port: AutomationsPort) {
        rule.setContent {
            ZCodeTheme {
                AutomationsSection(port = port)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `weekly automation - open editor, bare Save keeps the schedule unchanged`() {
        val row = weeklyRow()
        val port = RecordingPort(listOf(row))
        setContent(port)

        rule.onNodeWithTag("automation_row_auto-weekly-1").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithTag("automation_form_save").performClick()
        rule.waitForIdle()

        assertEquals("bare Save must reach update exactly once", 1, port.updated.size)
        val (_, write) = port.updated.first()
        assertEquals(
            "a WEEKLY automation edited on the phone must not silently become daily 09:00",
            "30 5 * * 1",
            write.schedule.cronExprOrPlaceholder(),
        )
    }

    @Test
    fun `weekly automation - configured model round-trips through bare Save`() {
        val row = weeklyRow()
        val port = RecordingPort(listOf(row))
        setContent(port)

        rule.onNodeWithTag("automation_row_auto-weekly-1").performTouchInput { click() }
        rule.waitForIdle()
        // The editor must SHOW the carried model, not the workspace default.
        rule.onNodeWithTag("automation_form_model").assertTextContains("zai/glm-5.1")
        rule.onNodeWithTag("automation_form_save").performClick()
        rule.waitForIdle()

        val (_, write) = port.updated.first()
        assertEquals("the configured model must round-trip", "zai", write.modelProvider)
        assertEquals("the configured model must round-trip", "glm-5.1", write.modelId)
    }

    @Test
    fun `blank cron field - save refused (never the silent 0-9 default)`() {
        val port = RecordingPort(emptyList())
        setContent(port)

        rule.onNodeWithTag("automations_new").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("Nightly")
        rule.onNodeWithTag("automation_form_prompt").performTextInput("Do the thing")
        rule.onNodeWithText("Cron").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("automation_form_save").assertIsNotEnabled()
        rule.onNodeWithTag("automation_form_save").performClick()
        rule.waitForIdle()
        assertEquals(
            "a blank cron must never silently save the 09:00 default",
            0,
            port.created.size,
        )
    }

    @Test
    fun `malformed cron - save refused (cron syntax gate)`() {
        val port = RecordingPort(emptyList())
        setContent(port)

        rule.onNodeWithTag("automations_new").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("Nightly")
        rule.onNodeWithTag("automation_form_prompt").performTextInput("Do the thing")
        rule.onNodeWithText("Cron").performClick()
        rule.onNodeWithTag("automation_form_cron").performTextInput("not a cron")
        rule.waitForIdle()

        rule.onNodeWithTag("automation_form_save").assertIsNotEnabled()
    }

    @Test
    fun `well-formed five-field cron - save enabled`() {
        val port = RecordingPort(emptyList())
        setContent(port)

        rule.onNodeWithTag("automations_new").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("Nightly")
        rule.onNodeWithTag("automation_form_prompt").performTextInput("Do the thing")
        rule.onNodeWithText("Cron").performClick()
        rule.onNodeWithTag("automation_form_cron").performTextInput("30 5 * * 1")
        rule.waitForIdle()

        rule.onNodeWithTag("automation_form_save").performClick()
        rule.waitForIdle()
        assertEquals("a valid 5-field cron must be savable", 1, port.created.size)
    }
}
