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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

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

    // ------------------------------------------------------------------ F-37-A cleartext gate

    /**
     * F-37-A (vc52 Phase 6): the platform's network-security policy is the ONE authority on
     * cleartext. A release build permits it nowhere; the debug config allows only the
     * loopback/emulator hosts. This shadow mirrors the debug allowlist, so a LAN `ws://`
     * is exactly the release-shaped refusal OkHttp would raise mid-probe.
     */
    @Implements(android.security.NetworkSecurityPolicy::class)
    class DebugCleartextPolicyShadow {
        @Implementation
        fun isCleartextTrafficPermitted(hostname: String): Boolean =
            hostname in setOf("127.0.0.1", "localhost", "10.0.2.2")

        companion object {
            /**
             * The platform singleton may predate this shadow in a shared sandbox (it then
             * carries no shadow instance); hand out a policy object constructed while the
             * shadow is active so the instance method above is the one consulted.
             */
            @JvmStatic
            @Implementation
            fun getInstance(): android.security.NetworkSecurityPolicy =
                org.robolectric.util.ReflectionHelpers.callConstructor(android.security.NetworkSecurityPolicy::class.java)
        }
    }

    private fun contentCountingProbes(probes: java.util.concurrent.atomic.AtomicInteger) {
        rule.setContent {
            ZCodeTheme {
                AddDirectEndpointDialog(
                    adder = { _, _, _, _ ->
                        probes.incrementAndGet()
                        rejectedOutcome()
                    },
                    onAdded = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    @Config(shadows = [DebugCleartextPolicyShadow::class])
    fun cleartext_lan_url_shows_honest_copy_before_any_probe() {
        val probes = java.util.concurrent.atomic.AtomicInteger()
        contentCountingProbes(probes)
        rule.onNodeWithTag("add_direct_name").performTextInput("NAS")
        rule.onNodeWithTag("add_direct_url").performTextInput("ws://192.0.2.1:3030/ws")
        rule.onNodeWithTag("add_direct_token").performTextInput("tok")
        rule.onNodeWithTag("add_direct_submit").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("no probe may run for a URL the platform refuses", 0, probes.get())
        rule.onNodeWithTag("add_direct_error").assertExists()
        rule.onNodeWithText("Plain ws:// isn't allowed — use wss:// or a tunnel URL").assertExists()
        // GAP-A10's contract holds on this rejection too: the dialog keeps the typed URL.
        rule.onNodeWithText("ws://192.0.2.1:3030/ws").assertExists()
    }

    @Test
    @Config(shadows = [DebugCleartextPolicyShadow::class])
    fun cleartext_candidate_the_platform_refuses_is_rejected_before_any_probe() {
        val probes = java.util.concurrent.atomic.AtomicInteger()
        contentCountingProbes(probes)
        rule.onNodeWithTag("add_direct_name").performTextInput("NAS")
        rule.onNodeWithTag("add_direct_url").performTextInput("wss://nas.example.test/ws")
        rule.onNodeWithTag("add_direct_token").performTextInput("tok")
        rule.onNodeWithTag("add_direct_candidates").performTextInput("ws://192.168.1.5:3030/ws")
        rule.onNodeWithTag("add_direct_submit").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(0, probes.get())
        rule.onNodeWithText("Plain ws:// isn't allowed — use wss:// or a tunnel URL").assertExists()
    }

    @Test
    @Config(shadows = [DebugCleartextPolicyShadow::class])
    fun cleartext_loopback_the_platform_permits_still_probes() {
        val probes = java.util.concurrent.atomic.AtomicInteger()
        contentCountingProbes(probes)
        rule.onNodeWithTag("add_direct_name").performTextInput("Rig")
        rule.onNodeWithTag("add_direct_url").performTextInput("ws://10.0.2.2:18200/ws")
        rule.onNodeWithTag("add_direct_token").performTextInput("tok")
        rule.onNodeWithTag("add_direct_submit").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { probes.get() == 1 }
    }

    @Test
    fun cleartext_gate_refuses_exactly_the_hosts_the_policy_refuses() {
        val debugPolicy: (String) -> Boolean = { it in setOf("127.0.0.1", "localhost", "10.0.2.2", "::1") }
        org.junit.Assert.assertEquals(
            listOf("ws://192.168.1.5:3030/ws", "http://nas.lan:3030"),
            cleartextRefusals(
                listOf(
                    "ws://192.168.1.5:3030/ws", // LAN — the release-shaped refusal
                    " ws://10.0.2.2:18200/ws ", // emulator host — debug-permitted (trimmed)
                    "http://nas.lan:3030", // plain http to a LAN name
                    "wss://nas.example.test/ws", // TLS — never a cleartext question
                    "https://tunnel.example.test",
                    "ws://[::1]:3030/ws", // IPv6 loopback — brackets stripped before the lookup
                    "ws://bad host/ws", // unparsable — left to the probe's honest classifier
                ),
                debugPolicy,
            ),
        )
        // A release build permits cleartext nowhere: even loopback is refused up front.
        org.junit.Assert.assertEquals(
            listOf("ws://127.0.0.1:3030/ws"),
            cleartextRefusals(listOf("ws://127.0.0.1:3030/ws", "wss://ok.example.test/ws")) { false },
        )
    }
}
