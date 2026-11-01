package com.zcode.android.app.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.connect.ConnectionHub.AddDirectOutcome
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.EndpointKind
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-A10 (vc51 Phase 4A) pins: a REJECTED probe keeps the dialog open with the typed
 * fields intact and an inline error (the recorded defect shape — the dialog used to close
 * and discard every field on Rejected, leaving only a transient toast); fallback-URL
 * candidates pass the same scheme gate as the primary; the add attempt disables the
 * submit for the ~10 s probe; the Added path still closes via the parent callback.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AddDirectEndpointDialogUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fakeEndpoint() = Endpoint(
        endpointId = EndpointId("direct-test"),
        kind = EndpointKind.DIRECT_SERVER,
        displayName = "Direct rig",
        wsUrl = "ws://10.0.2.2:9100",
        pairedAtMs = 0L,
    )

    private fun rejectedOutcome() = AddDirectOutcome.Rejected(
        AppError(
            category = ErrorCategory.AUTH,
            userMessage = "Token rejected (401)",
            developerDiagnostic = "pin",
            retryable = AppError.Retryable.USER,
            recoverable = true,
        ),
    )

    private fun contentWithOutcome(outcome: AddDirectOutcome) {
        rule.setContent {
            ZCodeTheme {
                val open = remember { mutableStateOf(true) }
                if (open.value) {
                    AddDirectEndpointDialog(
                        adder = { _, _, _, _ -> outcome },
                        onAdded = { open.value = false },
                        onDismiss = { open.value = false },
                    )
                }
            }
        }
    }

    private fun typeMinimumValidEntry() {
        rule.onNodeWithTag("add_direct_name").performTextInput("Rig")
        rule.onNodeWithTag("add_direct_url").performTextInput("ws://10.0.2.2:9100")
        rule.onNodeWithTag("add_direct_token").performTextInput("tok")
    }

    @Test
    fun rejected_probe_keeps_dialog_open_with_inline_error() {
        contentWithOutcome(rejectedOutcome())
        typeMinimumValidEntry()
        rule.onNodeWithTag("add_direct_submit").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("add_direct_error").fetchSemanticsNodes().isNotEmpty()
        }
        // the dialog is STILL open — every typed field survives the rejection
        rule.onNodeWithTag("add_direct_name").assertExists()
        rule.onNodeWithTag("add_direct_url").assertExists()
        rule.onNodeWithText("Rig").assertExists()
        rule.onNodeWithText("ws://10.0.2.2:9100").assertExists()
    }

    @Test
    fun added_outcome_closes_dialog_via_parent_callback() {
        contentWithOutcome(AddDirectOutcome.Added(fakeEndpoint()))
        typeMinimumValidEntry()
        rule.onNodeWithTag("add_direct_submit").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("add_direct_name").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun bad_scheme_candidates_disable_add() {
        rule.setContent {
            ZCodeTheme {
                AddDirectEndpointDialog(
                    adder = { _, _, _, _ -> AddDirectOutcome.Added(fakeEndpoint()) },
                    onAdded = {},
                    onDismiss = {},
                )
            }
        }
        typeMinimumValidEntry()
        rule.onNodeWithTag("add_direct_submit").assertIsEnabled()
        rule.onNodeWithTag("add_direct_candidates").performTextInput("ftp://fallback.example")
        rule.onNodeWithTag("add_direct_submit").assertIsNotEnabled()
        rule.onNodeWithTag("add_direct_candidates").performTextInput(",wss://ok.example")
        rule.onNodeWithTag("add_direct_submit").assertIsNotEnabled() // one bad candidate still blocks
    }

    @Test
    fun good_scheme_candidates_keep_add_enabled() {
        rule.setContent {
            ZCodeTheme {
                AddDirectEndpointDialog(
                    adder = { _, _, _, _ -> AddDirectOutcome.Added(fakeEndpoint()) },
                    onAdded = {},
                    onDismiss = {},
                )
            }
        }
        typeMinimumValidEntry()
        rule.onNodeWithTag("add_direct_candidates").performTextInput("wss://a.example, http://b.example")
        rule.onNodeWithTag("add_direct_submit").assertIsEnabled()
    }

    @Test
    fun probe_in_flight_disables_submit_until_outcome() {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        rule.setContent {
            ZCodeTheme {
                val open = remember { mutableStateOf(true) }
                if (open.value) {
                    AddDirectEndpointDialog(
                        adder = { _, _, _, _ ->
                            started.complete(Unit)
                            gate.await()
                            AddDirectOutcome.Added(fakeEndpoint())
                        },
                        onAdded = { open.value = false },
                        onDismiss = { open.value = false },
                    )
                }
            }
        }
        typeMinimumValidEntry()
        rule.onNodeWithTag("add_direct_submit").performClick()
        org.junit.Assert.assertTrue(started.isCompleted)
        rule.onNodeWithTag("add_direct_submit").assertIsNotEnabled()
        gate.complete(Unit)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("add_direct_name").fetchSemanticsNodes().isEmpty()
        }
    }
}
