package com.zcode.android.app

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.security.vault.SealOutcome
import com.zcode.android.tools.mockzcode.MockZCode
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3.4/3.5 on-device E2E (§43.2-3.4 "E2E"; §35.5 emulator leg): REAL app UI +
 * REAL core:security (AndroidKeyStore + Tink) + REAL RelayTransport against the
 * in-process MockZCode (§35.4, INV-15 — synthetic credentials only).
 *
 * Exercises: pairing happy path (manual-entry variant of the QR path, §12.2 step 1),
 * first-pair timing (≤ 60 s, §48), credential persistence across relaunch (§15-S2
 * seed), rotation classification (§12.2 step 4), E5 (auth-failure → re-scan), unpair
 * wipe with forensic zero-remnant verification (§3.5).
 */
@RunWith(AndroidJUnit4::class)
class PairingE2eInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule(MainActivity::class.java)

    private var mock: MockZCode? = null

    @Before
    fun ensureUnpaired() {
        // Device state persists across test methods — start every test from a wiped state.
        wipeAll(targetContext())
        // Phase 6 shell: the storage wipe carries no change signal to the in-memory
        // pairing port (by design — §12.4 custody has no observers); recreate the
        // activity so the nav gate re-reads the vault and starts at the pairing flow.
        rule.activityRule.scenario.recreate()
        val dbgStore = AppStores.get(targetContext()).credentialStore
        val dbgVault = CredentialVault(dbgStore, AndroidKeystoreKeyWrap(biometricBound = false), ZLog(sink = {}))
        println("PAIRDBG post-wipe names=${dbgStore.listNames()} meta=${dbgVault.loadMetadata() != null}")
    }

    @After
    fun tearDown() {
        mock?.stop()
    }

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun wipeAll(context: Context) {
        // Phase 4.6 binding: production credential storage is the DataStore-proto
        // envelope store (ADR-008) — wipe through the SAME store the app seals into.
        val store = AppStores.get(context).credentialStore
        val vault = CredentialVault(store, AndroidKeystoreKeyWrap(biometricBound = false), ZLog(sink = {}))
        vault.wipe()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry("zcode-vault-master")
        ks.deleteEntry("zcode-vault-gate")
    }

    private fun startMock(): MockZCode {
        val m = MockZCode(passHash = happyHash(), expectedSid = "d_SyntheticSid000001")
        m.script.authScenario = MockZCode.AuthScenario.HAPPY
        m.start()
        mock = m
        return m
    }

    private fun happyHash(): String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    private fun mockPort(): Int {
        val f = MockZCode::class.java.getDeclaredField("server")
        f.isAccessible = true
        val server = f.get(mock) as mockwebserver3.MockWebServer
        return server.url("/ws").port
    }

    private fun link(port: Int, hash: String, sid: String = "d_SyntheticSid000001"): String =
        "http://127.0.0.1:$port/remote/v4?sid=$sid" +
            "&hash=" + java.net.URLEncoder.encode(hash, "UTF-8") +
            "&t=${System.currentTimeMillis()}&mid=mock-desktop-mid&name=MockBook&app_version=3.14.3"

    private fun waitForTag(tag: String) {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun submitLink(raw: String) {
        waitForTag("open-manual")
        rule.onNodeWithTag("open-manual").performClick()
        waitForTag("manual-field")
        rule.onNodeWithTag("manual-field").performTextInput(raw)
        rule.onNodeWithTag("manual-submit").performClick()
    }

    @Test
    fun pairConnectRelaunchResumeUnpairWipe_forensicsClean_firstPairUnder60s() {
        startMock()
        // ---- 1. happy pairing: submit → E7 consent (emulator has no lock screen) →
        //         connected. PHASE 6 SHELL: pairing success hands control to the app
        //         nav — the landing surface is the TASK LIST (ui-ux navigation.md:
        //         "Pairing → Connecting → Task list"); the pairing flow's own Connected
        //         state remains the seam the nav reacts to (§43.2-6.1).
        val t0 = System.currentTimeMillis()
        submitLink(link(mockPort(), happyHash()))
        waitForTag("consent-reduced")
        rule.onNodeWithTag("consent-reduced").performClick()
        waitForTag("tasks-title")
        val elapsedMs = System.currentTimeMillis() - t0
        assertTrue("first-pair took $elapsedMs ms — budget is 60 s (§48)", elapsedMs < 60_000)
        println("E2E-PAIR-ELAPSED_MS=$elapsedMs")

        // ---- 2. persistence: recreate activity → the paired gate skips pairing and
        //         reconnects with the SEALED credential (AndroidKeyStore unwrap + Tink
        //         open) straight into the task list (cold-start path §15-S2).
        rule.activityRule.scenario.recreate()
        waitForTag("tasks-title")

        // ---- 3. unpair wipe is forensically complete (§3.5). PHASE 6 SHELL: unpair
        //         lives in Settings (danger action, confirm dialog).
        rule.onNodeWithTag("tasks_settings").performClick()
        waitForTag("settings_unpair")
        // PHASE 8: the Notifications section grew the screen — the danger row now
        // sits below the fold. Scroll it into view before clicking (an off-screen
        // performClick injects at out-of-window coordinates and lands as BACK).
        rule.onNodeWithTag("settings_scroll")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("settings_unpair"))
        rule.onNodeWithTag("settings_unpair").performClick()
        waitForTag("settings_unpair_confirm")
        rule.onNodeWithTag("settings_unpair_confirm").performClick()
        waitForTag("open-manual")
        val store = AppStores.get(targetContext()).credentialStore
        assertTrue("credential blobs must be wiped", store.listNames().isEmpty())
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("master key must be destroyed", ks.containsAlias("zcode-vault-master"))
        assertFalse("gate key must be destroyed", ks.containsAlias("zcode-vault-gate"))

        // ---- 4. relaunch after wipe → unpaired Welcome (no resume card)
        rule.activityRule.scenario.recreate()
        waitForTag("open-manual")
        assertEquals("no resume card after wipe", 0, rule.onAllNodesWithTag("resume-connect").fetchSemanticsNodes().size)
    }

    @Test
    fun rotation_sameSidReseal_classifiesRotated_andAtomicallyReplaces() {
        startMock()
        // Pair once through the real UI (seals credential generation 1).
        submitLink(link(mockPort(), happyHash()))
        waitForTag("consent-reduced")
        rule.onNodeWithTag("consent-reduced").performClick()
        waitForTag("tasks-title") // Phase 6 shell: success lands on the task list

        // §12.2 step-4 classification on-device: sealing the same sid again = ROTATION
        // (rotation continuity/vectors are JVM-proven in CredentialVaultTest).
        // Inspect through the PRODUCTION binding (Phase 4.6 swap): the UI sealed into
        // the DataStore-proto store; the assertions below are unchanged.
        val store = AppStores.get(targetContext()).credentialStore
        val vault = CredentialVault(store, AndroidKeystoreKeyWrap(biometricBound = false), ZLog(sink = {}))
        val rotatedHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 5).toByte() })
        val linkA = PairingLink(
            endpointOrigin = "https://zcode.z.ai", route = "/remote/v4",
            deviceSid = "d_SyntheticSid000001", passHash = happyHash(),
            issuedAtMs = System.currentTimeMillis(), deviceMid = "m", desktopName = "A", appVersion = "3.14.3",
        )
        val s2 = vault.seal(linkA.copy(passHash = rotatedHash, desktopName = "A2"))
        assertTrue("same-sid reseal must classify as rotation (§12.2 step 4)", s2 is SealOutcome.Rotated)
        assertEquals(2, (s2 as SealOutcome.Rotated).metadata.generation)
        assertEquals("rotation must atomically replace metadata", "A2", vault.loadMetadata()?.desktopName)
    }

    @Test
    fun wrongHash_sealedButRejectedByRelay_E5RePair() {
        startMock()
        // Foreign hash: parser-valid, but the relay rejects proofs (AUTH_FAILED) → E5.
        val foreignHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        submitLink(link(mockPort(), foreignHash))
        waitForTag("consent-reduced")
        rule.onNodeWithTag("consent-reduced").performClick()
        waitForTag("fail-rescan")
        rule.onNodeWithTag("fail-message").assertExists()
    }
}
