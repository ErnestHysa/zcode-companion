package com.zcode.android.app

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.tools.mockzcode.MockZCode
import java.security.KeyStore
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 9 on-device leg (§43.2-9 exit gates; §35.5 emulator leg):
 *
 *  1. Conversation header renders the Phase 9 affordances: model/mode chips fed by
 *     the VERIFIED session `config` echo (9.5) and the Files/Git/Terminal entries.
 *  2. A turnHeader row carrying a fileChanges summary is TAPPABLE and opens the
 *     §22 diff viewer fed by `conversationFileChangesV4` (9.1, U10).
 *  3. Files (9.2, U11) and Git (9.4, U13) routes render their honest states against
 *     scripted channel fixtures.
 *  4. Terminal (9.3, U12): opt-in gate (default OFF — §21 never enabled silently),
 *     attach against the mock's scripted ANSI stream, styled lines render.
 *
 * INV-15: everything runs against the in-process MockZCode with synthetic content.
 */
@RunWith(AndroidJUnit4::class)
class Phase9InstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule(MainActivity::class.java)

    private var mock: MockZCode? = null

    private val passHash: String = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
    private val sid: String by lazy { "d_Phase9" + java.util.UUID.randomUUID().toString().replace("-", "").take(12) }

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
        m.script.emitStateKeys = true
        m.script.commandEffects = true
        m.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth9-task-1",
                title = "Phase 9 surfaces journey",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
                createdAtMs = System.currentTimeMillis() - 2 * 60_000,
                updatedAtMs = System.currentTimeMillis() - 60_000,
            ),
        )
        m.script.sessionConfig = buildJsonObject {
            put("provider", JsonPrimitive("zai"))
            put("model", JsonPrimitive("glm-5.3"))
            put("thought", JsonPrimitive("standard"))
            put("thoughtLevels", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("standard"), JsonPrimitive("deep"))))
            put("followupMode", JsonPrimitive("queue"))
            put("mode", JsonPrimitive("build"))
        }
        // §9.1: a completed turn whose summary the phone can open (turnHeader + fileChanges).
        m.appendRow(
            MockZCode.RowKind.TURN_HEADER,
            "completedSuccess",
            extra = buildJsonObject {
                put("fileChanges", buildJsonObject {
                    put("additions", JsonPrimitive(12))
                    put("deletions", JsonPrimitive(3))
                    put("files", JsonPrimitive(1))
                    put("state", JsonPrimitive("active"))
                })
            },
        )
        synchronized(m.script.conversationFileChanges) {
            m.script.conversationFileChanges.add(buildJsonObject {
                put("files", JsonPrimitive(1)); put("additions", JsonPrimitive(12)); put("deletions", JsonPrimitive(3))
                put("state", JsonPrimitive("active"))
                put("items", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                    put("path", JsonPrimitive("src/Main.kt"))
                    put("additions", JsonPrimitive(12)); put("deletions", JsonPrimitive(3)); put("writeCount", JsonPrimitive(1))
                    put("toolNames", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Edit"))))
                    put("patches", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                        put("oldStart", JsonPrimitive(1)); put("oldLines", JsonPrimitive(2))
                        put("newStart", JsonPrimitive(1)); put("newLines", JsonPrimitive(3))
                        put("lines", kotlinx.serialization.json.JsonArray(listOf(
                            JsonPrimitive(" fun main() {"),
                            JsonPrimitive("-    val a = 1"),
                            JsonPrimitive("+    val a = 2"),
                            JsonPrimitive("+    val b = 3"),
                            JsonPrimitive(" }"),
                        )))
                    })))
                })))
            })
        }
        // 9.3: scripted terminal output (SGR red + plain).
        m.script.terminalOutput.add("\u001B[31msynthetic-error-line\u001B[0m plain tail\n")
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
                desktopName = "Phase9Book",
                appVersion = "3.14.3",
            ),
        )
        org.junit.Assert.assertTrue(outcome is com.zcode.android.core.security.vault.SealOutcome.Created)
    }

    private fun openConversation() {
        startMock()
        seedPairing()
        // Cold recreate boots a fresh hub on the NEW seal (the fleet-proven pattern).
        rule.activityRule.scenario.recreate()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("task_row_synth9-task-1").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("task_row_synth9-task-1").performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("conversation_list").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("composer_field").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun phase9_chips_turnDiff_files_git_and_terminal_gate() {
        openConversation()

        // ---- 1. header: Phase 9 affordances + the config echo (9.5)
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("mode: build", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(rule.onAllNodesWithTag("conv_open_files").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("conv_open_git").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("conv_open_terminal").fetchSemanticsNodes().isNotEmpty())

        // ---- 2. turn summary → §22 diff viewer (9.1 / U10)
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("row_turn_changes").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithTag("row_turn_changes")[0].performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("src/Main.kt", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("+    val a = 2").fetchSemanticsNodes().isNotEmpty()
        }
        // Close the dialog.
        if (rule.onAllNodesWithText("Close").fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText("Close")[0].performClick()
        }

        // ---- 3. Files route renders (9.2 / U11): empty tree → honest empty/error state.
        rule.onNodeWithTag("conv_open_files").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("files_back").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("files_back").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("conv_open_git").fetchSemanticsNodes().isNotEmpty()
        }

        // ---- 4. Git route renders (9.4 / U13): no fixtures → the honest unavailable state.
        rule.onNodeWithTag("conv_open_git").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("git_back").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("git_back").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("conv_open_terminal").fetchSemanticsNodes().isNotEmpty()
        }

        // ---- 5. Terminal (9.3 / U12): opt-in gate FIRST (default OFF), then attach.
        rule.onNodeWithTag("conv_open_terminal").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Enable terminal (view-only)", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Opt in through the REAL preference store (Settings toggle writes the same key).
        kotlinx.coroutines.runBlocking {
            AppStores.get(targetContext()).preferencesRepository.update {
                it.copy(terminalOptIn = true)
            }
        }
        // The port mirrors the preference; the gate flips to the attach affordance.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Attach").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("Attach")[0].performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("synthetic-error-line", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(rule.onAllNodesWithText("VIEW-ONLY").fetchSemanticsNodes().isNotEmpty())
    }
}
