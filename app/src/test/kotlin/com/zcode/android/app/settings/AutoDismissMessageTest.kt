package com.zcode.android.app.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B2.6 (gaps-and-polish Phase 1E, P0): the endpoint-switch message auto-dismiss
 * was launched IN COMPOSITION — a `scope.launch` per recomposition, so the timer
 * restarted on every recomposition (the message could persist far beyond 2.5 s),
 * dismissals piled up, and the message vanished abruptly. Fix: one effect keyed on
 * the message — dismisses exactly once per message.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AutoDismissMessageTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `recompositions do not re-arm the dismiss timer - exactly one dismissal per message`() {
        val launches = mutableListOf<CompletableDeferred<Unit>>()
        val visible = mutableStateOf<String?>("endpoint switched")
        var dismissed = 0

        rule.setContent {
            AutoDismissMessage(
                message = visible.value,
                onDismiss = { dismissed += 1 },
                delay = { millis ->
                    val d = CompletableDeferred<Unit>()
                    launches += d
                    d.await()
                },
            )
        }
        rule.waitForIdle()

        // Unrelated state churn recomposes the banner 3 extra times.
        repeat(3) {
            rule.runOnIdle { visible.value = visible.value }
        }
        rule.waitForIdle()

        assertEquals(
            "the timer must arm ONCE per message (found ${launches.size} armed dismissals)",
            1,
            launches.size,
        )

        // The armed timer dismisses exactly once.
        rule.runOnIdle { launches.first().complete(Unit) }
        rule.waitForIdle()
        assertEquals(1, dismissed)
    }
}
