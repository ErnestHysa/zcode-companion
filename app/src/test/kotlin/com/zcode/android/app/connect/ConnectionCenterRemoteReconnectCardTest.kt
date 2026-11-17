package com.zcode.android.app.connect

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.RecoveryAction
import com.zcode.android.core.common.error.UserMessageKey
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P9-B (pass-0 Phase 1): the G67 Reconnect card's live-shape pin. When
 * `hub.lastError` carries the TYPED disconnected-REMOTE key (the F-P9-B hub-side
 * assignment), the Connection Center renders the `connection_remote_reconnect` card
 * — and the card's Reconnect action reaches the `workspace-reconnect-request` wire
 * verb through the hub (`reconnectKey()` → `reconnectWorkspace(key)`). The pass-9
 * live receipt (MOCK_REMOTE_DOWN_MS armed) showed the card ABSENT while the hub
 * logged the typed error — the render condition itself was correct; the surface
 * feeding it was not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConnectionCenterRemoteReconnectCardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun typedError() = AppError(
        category = ErrorCategory.SESSION,
        userMessage = "This remote workspace is disconnected — reconnect it, then retry",
        messageKey = UserMessageKey.REMOTE_WORKSPACE_DISCONNECTED,
        developerDiagnostic = "workspace-bridge-error: remote-disconnected (errorText=The remote workspace is not connected)",
        retryable = AppError.Retryable.USER,
        recoverable = true,
        autoRecovery = RecoveryAction.RECONCILE,
    )

    @Test
    fun `F-P9-B - the typed error renders the Reconnect card and the verb is reachable`() {
        var reconnectRequested = false
        rule.setContent {
            ZCodeTheme {
                ConnectionCenterScreen(
                    phase = ConnectionPhase.DEGRADED,
                    endpointName = "Pass0MockBook",
                    desktopVersion = null,
                    lastError = typedError(),
                    lastRefreshAtMs = null,
                    onRetry = {},
                    onDismissError = {},
                    onReconnectWorkspace = { reconnectRequested = true },
                    onBack = {},
                )
            }
        }
        rule.onNodeWithTag("connection_remote_reconnect").assertExists("the typed card renders (F-P9-B)")
        rule.onNodeWithTag("connection_reconnect_workspace").assertExists("the Reconnect verb is reachable from the card")
        rule.onNodeWithText("Reconnect").performClick()
        assertTrue(reconnectRequested, "the card's action fires the workspace-reconnect verb")
    }

    @Test
    fun `a non-typed error never renders the card`() {
        rule.setContent {
            ZCodeTheme {
                ConnectionCenterScreen(
                    phase = ConnectionPhase.DEGRADED,
                    endpointName = "Pass0MockBook",
                    desktopVersion = null,
                    lastError = AppError(
                        category = ErrorCategory.NETWORK,
                        userMessage = "Connection failed",
                        developerDiagnostic = "connect timed out",
                        retryable = AppError.Retryable.AUTO,
                        recoverable = true,
                    ),
                    lastRefreshAtMs = null,
                    onRetry = {},
                    onDismissError = {},
                    onReconnectWorkspace = {},
                    onBack = {},
                )
            }
        }
        assertTrue(
            rule.onAllNodesWithTag("connection_remote_reconnect", true).fetchSemanticsNodes().isEmpty(),
            "the typed card only renders for the typed key",
        )
    }
}
