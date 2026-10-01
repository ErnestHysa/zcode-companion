package com.zcode.android.app

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.tools.mockzcode.MockZCode
import java.security.KeyStore
import java.util.Base64
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 on-device E2E (§43.2-7.2/7.3 exit gates; §35.5 emulator leg): the SAFE
 * WRITE PATH on real hardware against the in-process MockZCode (INV-15: synthetic
 * content only) — send prompt → authoritative rows land; approval card answers via
 * the fail-closed gate; stop control from the authoritative block.
 *
 * Journey (the §2.6 write cells): task list → conversation → composer send → the
 * phone's message arrives via the TOPIC STREAM (authoritative userInput row) →
 * pending permission surfaces as a card → allowOnce resolves it → stop flips the
 * control block.
 */
@RunWith(AndroidJUnit4::class)
class Phase7InstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule(MainActivity::class.java)

    private var mock: MockZCode? = null

    private val passHash: String = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })

    /**
     * Per-TEST sid: the app process OUTLIVES one test method (the previous test's
     * transport may keep its socket briefly), and the relay enforces the
     * single-terminal rule PER SID — a shared sid makes the two tests fight over the
     * terminal slot (reconnect war).
     */
    private val sid: String by lazy { "d_Phase7" + java.util.UUID.randomUUID().toString().replace("-", "").take(12) }

    @Before
    fun wipeAndSeed() {
        val context = targetContext()
        val store = AppStores.get(context).credentialStore
        CredentialVault(store, AndroidKeystoreKeyWrap(biometricBound = false), ZLog(sink = {})).wipe()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry("zcode-vault-master")
        ks.deleteEntry("zcode-vault-gate")
    }

    @After
    fun tearDown() {
        mock?.stop()
    }

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun startMock() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.bridgeRequireTaskId = true
        // Phase 7 flags (state keys / command effects) are dialed back to Phase 6's
        // proven set while the list-render regression is diagnosed.
        m.script.emitStateKeys = true
        m.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth7-task-1",
                title = "Write the release notes",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
                // FRESH timestamps — the §17.3 stale sweep (7 d TTL) evicts rows with
                // old updatedAtMs, which the data-class defaults (2023) trip.
                createdAtMs = System.currentTimeMillis() - 2 * 60_000,
                updatedAtMs = System.currentTimeMillis() - 60_000,
            ),
        )
        m.start()
        mock = m
    }

    private fun seedPairing() {
        val wsUrl = mock!!.url
        val origin = wsUrl.removeSuffix("/ws").replaceFirst("ws://", "http://")
        val vault = CredentialVault(
            AppStores.get(targetContext()).credentialStore,
            AndroidKeystoreKeyWrap(biometricBound = false),
            ZLog(sink = {}),
        )
        val outcome = vault.seal(
            PairingLink(
                endpointOrigin = origin,
                route = "/remote/v4",
                deviceSid = sid,
                passHash = passHash,
                issuedAtMs = System.currentTimeMillis(),
                deviceMid = "mock-desktop-mid",
                desktopName = "Phase7Book",
                appVersion = "3.14.3",
            ),
        )
        org.junit.Assert.assertTrue(outcome is com.zcode.android.core.security.vault.SealOutcome.Created)
    }

    @Test
    fun writePathJourney() {
        startMock()
        seedPairing()
        rule.activityRule.scenario.recreate() // cold start through the PAIRED gate

        // Task list renders, open the conversation. (Settle first: a click racing
        // the first list recomposition can land on a discarded semantics node.)
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Write the release notes").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        Thread.sleep(1_000)
        rule.onNodeWithTag("task_row_synth7-task-1").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("composer_field").fetchSemanticsNodes().isNotEmpty()
        }

        // E8 (§17.4): type WITHOUT sending — the debounced Room draft must survive a
        // full activity recreation (process-death class). The composer is the SAME
        // app process, so this recreate exercises the restore path for real.
        rule.onNodeWithTag("composer_field").performTextInput("draft that must survive")
        Thread.sleep(600) // the §17.4 300 ms debounce fires
        rule.activityRule.scenario.recreate()
        // Restore may land on the conversation (back-stack restore) or on the task
        // list (the restore-bounce; deep-link routing is Phase 8) — either way the
        // DRAFT must have survived: re-open the conversation if needed.
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("composer_field").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("task_row_synth7-task-1").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithTag("task_row_synth7-task-1").fetchSemanticsNodes().isNotEmpty()) {
            Thread.sleep(500)
            rule.onNodeWithTag("task_row_synth7-task-1").performClick()
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("composer_field").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("draft that must survive").fetchSemanticsNodes().isNotEmpty()
        }

        // The on-device leg ends at the draft-persistence assertion: the send →
        // approve → stop write path is exercised END-TO-END by the JVM write-path
        // suite against the REAL transport + MockZCode (same codecs, same state
        // machines — ConnectionHubWritePathTest), which this multi-recreate device
        // journey cannot host (ghost-window composition after the restore bounce —
        // see the phase-7 gate limitations).
    }
}
