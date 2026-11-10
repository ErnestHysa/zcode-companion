package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.WorkspaceHookAdmissionState
import com.zcode.android.features.conversation.ui.SessionContextSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G52 (vc52 Phase 11) compose leg: the workspace hook-admission plane renders on
 * the context sheet — the pending count row shows ONLY when pending > 0 (a zero
 * admission is the cleared face; an absent key renders nothing). The model
 * transition's user face is the typed notice (ModelTransitionNoticeVmTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class HookAdmissionSheetUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a pending admission renders the count row`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(
                        workspaceHookAdmission = WorkspaceHookAdmissionState(3, "digest-1", "/synthetic/ws-one"),
                        workspaceHookAdmissionKeyPresent = true,
                    ),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithTag("hooks_admission_row")
        rule.onNodeWithText("Hooks pending trust: 3 — review them on the desktop (Settings, Hooks)").assertExists()
    }

    @Test
    fun `a zero admission is the cleared face and renders nothing`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(
                        workspaceHookAdmission = WorkspaceHookAdmissionState(0, "digest-1", null),
                        workspaceHookAdmissionKeyPresent = true,
                    ),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                )
            }
        }
        rule.onAllNodesWithTag("hooks_admission_row").assertCountEquals(0)
    }

    @Test
    fun `an absent admission key renders nothing`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                )
            }
        }
        rule.onAllNodesWithTag("hooks_admission_row").assertCountEquals(0)
    }
}
