package com.zcode.android.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.tools.mockzcode.MockZCode
import java.io.File
import java.security.KeyStore
import java.util.Base64
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 on-device E2E (§43.2-6.x; §35.5 emulator leg): REAL app shell + REAL
 * transport + REAL persistence against the in-process MockZCode (INV-15: synthetic
 * data only). Path: seeded pairing → cold start lands on the TASK LIST (production-
 * shape bootstrap tasks) → open conversation (snapshot renders) → live delta
 * streams → light/dark capture for the visual evidence record.
 *
 * Screenshots are written to the app's external files dir and pulled by the gate
 * runbook (they are EVIDENCE, not committed fixtures — INV-15/16).
 */
@RunWith(AndroidJUnit4::class)
class Phase6InstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule(MainActivity::class.java)

    private var mock: MockZCode? = null

    private val passHash: String = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
    private val sid = "d_Phase6DeviceSid0001"

    /** Set by the runner: `am instrument -e darkMode true …` + `cmd uimode night yes`. */
    private val dark: Boolean =
        InstrumentationRegistry.getArguments().getString("darkMode") == "true"

    @Before
    fun wipeAndSeed() {
        val context = targetContext()
        // Start from a wiped device state (Phase 3 pattern).
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

    private fun startMockWithProductionTasks() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth-dev-task-1",
                title = "Fix authentication token refresh",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
                createdAtMs = System.currentTimeMillis() - 2 * 60_000,
                updatedAtMs = System.currentTimeMillis() - 60_000,
            ),
            MockZCode.MockTask(
                taskId = "synth-dev-task-2",
                title = "Add rate limit middleware",
                displayStatus = "waiting",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
                updatedAtMs = System.currentTimeMillis() - 8 * 60_000,
            ),
            MockZCode.MockTask(
                taskId = "synth-dev-task-3",
                title = "Refactor session store queries",
                displayStatus = "completed",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
                updatedAtMs = System.currentTimeMillis() - 60 * 60_000,
            ),
        )
        m.start()
        mock = m
    }

    /** Seed the credential directly (deterministic; the QR UI path is Phase 3's E2E). */
    private fun seedPairing() {
        val wsUrl = mock!!.url
        val origin = wsUrl.removeSuffix("/ws").replaceFirst("ws://", "http://")
        val context = targetContext()
        val vault = CredentialVault(
            AppStores.get(context).credentialStore,
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
                desktopName = "Phase6Book",
                appVersion = "3.14.3",
            ),
        )
        org.junit.Assert.assertTrue(outcome is com.zcode.android.core.security.vault.SealOutcome.Created)
    }

    private fun saveCapture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File(targetContext().cacheDir, "phase6-evidence").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PHASE6_CAPTURE $name -> ${out.absolutePath}")
    }

    @Test
    fun captureFlow() {
        startMockWithProductionTasks()
        seedPairing()
        rule.activityRule.scenario.recreate() // cold-start through the PAIRED gate
        val mode = if (dark) "dark" else "light"

        // Task list (U2): production-shape rows render with status + workspace meta.
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("task_row_synth-dev-task-1").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Tasks").assertExists()
        rule.onNodeWithText("Fix authentication token refresh").assertExists()
        rule.onNodeWithText("Running").assertExists()
        rule.onNodeWithText("Needs attention").assertExists()
        rule.onNodeWithText("demo-api").assertExists()
        rule.onNodeWithText("Phase6Book").assertExists()
        rule.onAllNodesWithTag("task_row_synth-dev-task-3").onFirst().assertExists()

        // Task-list capture (ui-ux mockup 03/14).
        rule.runOnIdle {
            saveCapture(rule.activity.window.decorView, "06-$mode-task-list.png")
        }

        // Open the conversation (U3): snapshot renders in the timeline grammar.
        // Stamp an authoritative running turnHeader so the §14.4.2 machine drives the
        // "Connected · Running" status line from real protocol data.
        mock!!.setSessionTurnOutcome("running")
        rule.onNodeWithTag("task_row_synth-dev-task-1").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("row_agent").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodes(hasText("Connected", substring = true)).onFirst().assertExists()
        rule.onAllNodesWithTag("row_agent").onFirst().assertExists()

        // Live stream (U5): a delta on the assistant row merges into the timeline.
        mock!!.pushDeltas(
            "synth-dev-task-1",
            listOf(
                MockZCode.DeltaFrame(
                    2, 2,
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("op", kotlinx.serialization.json.JsonPrimitive("row.delta"))
                                put("rowId", kotlinx.serialization.json.JsonPrimitive(2))
                                put("path", kotlinx.serialization.json.JsonPrimitive("text"))
                                put("append", kotlinx.serialization.json.JsonPrimitive(" with streaming output."))
                            },
                        )
                    },
                ),
            ),
        )
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasText("synthetic 2 with streaming output.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // Conversation capture (ui-ux mockup 06/15).
        rule.runOnIdle {
            saveCapture(rule.activity.window.decorView, "07-$mode-conversation.png")
        }
    }


}

