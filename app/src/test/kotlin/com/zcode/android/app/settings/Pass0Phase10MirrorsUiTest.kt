package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ClientScenesView
import com.zcode.android.domain.model.CuaPermissionStatus
import com.zcode.android.domain.model.OutputStylesView
import com.zcode.android.domain.model.SourceServersView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * pass-0 Phase 10 — the four parity-mirror screens (G83/G84/G101/G98): each
 * renders its decoded model with the honest empty/error faces and NO write
 * affordance anywhere (INV-9 — the mirrors are read-only by construction).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase10MirrorsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fakeScenesPort(view: ClientScenesView?, error: String? = null) =
        object : ClientScenesPort {
            override suspend fun scenes(): ClientScenesPort.Result = ClientScenesPort.Result(view, error)
        }

    private fun fakeStylePort(view: OutputStylesView?, error: String? = null) =
        object : OutputStylePort {
            override suspend fun styles(): OutputStylePort.Result = OutputStylePort.Result(view, error)
        }

    private fun fakeCuaPort(status: CuaPermissionStatus?, error: String? = null) =
        object : CuaStatusPort {
            override suspend fun status(): CuaStatusPort.Result = CuaStatusPort.Result(status, error)
        }

    private fun fakeSrcPort(view: SourceServersView?, error: String? = null) =
        object : SourceServersPort {
            override suspend fun servers(): SourceServersPort.Result = SourceServersPort.Result(view, error)
        }

    @Test
    fun `G83 the scenes mirror renders the decoded prompts read-only`() {
        val payload = buildJsonObject {
            put("code", 0)
            put("msg", "ok")
            put("data", buildJsonArray {
                add(buildJsonObject {
                    put("scene", "draft-suggestion")
                    putJsonObject("options") {
                        putJsonObject("prompts") {
                            put("items", buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "p1")
                                    putJsonObject("defaults") { put("a", buildJsonArray { add("opt-1") }) }
                                })
                            })
                        }
                        putJsonObject("a") {
                            put("items", buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "opt-1")
                                    putJsonObject("contents") { put("en", "Summarize recent commits") }
                                })
                            })
                        }
                    }
                })
            })
        }
        rule.setContent { ZCodeTheme { ClientScenesViewerScreen(port = fakeScenesPort(ClientScenesView.decode(payload))) } }
        rule.onNodeWithTag("scenes_list").assertIsDisplayed()
        rule.onNodeWithTag("scenes_row_p1").assertExists()
        rule.onNodeWithText("Summarize recent commits").assertIsDisplayed()
        // Read-only: no send/tap affordance exists anywhere in the tree.
        rule.onAllNodesWithTag("scenes_send").assertCountEquals(0)
    }

    @Test
    fun `G83 a business error renders the honest empty face`() {
        val bad = Json.parseToJsonElement("""{"code":12001,"msg":"quota","data":[]}""").jsonObject
        rule.setContent { ZCodeTheme { ClientScenesViewerScreen(port = fakeScenesPort(ClientScenesView.decode(bad))) } }
        rule.onNodeWithTag("scenes_empty").assertExists()
    }

    @Test
    fun `G84 the style mirror renders the list with the ACTIVE badge and no switch`() {
        val list = Json.parseToJsonElement(
            """
            {"styles":[
              {"id":"default","name":"Default","description":"","enabled":true,"isBuiltIn":true},
              {"id":"custom-explain","name":"Explain","description":"Explain mode","enabled":false,"isBuiltIn":false}
            ]}
            """.trimIndent(),
        ).jsonObject
        val view = OutputStylesView.decode(list, activeStyleId = "custom-explain")
        rule.setContent { ZCodeTheme { OutputStyleViewerScreen(port = fakeStylePort(view)) } }
        rule.onNodeWithTag("style_list").assertIsDisplayed()
        rule.onNodeWithTag("style_row_custom-explain").assertExists()
        rule.onNodeWithTag("style_active_custom-explain").assertExists()
        rule.onAllNodesWithTag("style_switch").assertCountEquals(0)
        rule.onAllNodesWithText("built-in").assertCountEquals(1)
        rule.onAllNodesWithText("custom").assertCountEquals(1)
    }

    @Test
    fun `G101 the CUA status card renders the typed health`() {
        val granted = Json.parseToJsonElement(
            """
            {"available":true,"grantOwner":"ernest","grantOwnerDisplayName":"Ernest",
             "accessibility":true,"accessibilityProbeOk":true,"screenRecording":true,
             "screenCaptureProbeOk":false,"idle":false}
            """.trimIndent(),
        ).jsonObject
        rule.setContent { ZCodeTheme { CuaStatusViewerScreen(port = fakeCuaPort(CuaPermissionStatus.decode(granted))) } }
        rule.onNodeWithTag("cua_status_card").assertIsDisplayed()
        rule.onNodeWithText("Available").assertExists()
        rule.onNodeWithTag("cua_status_owner").assertExists()
        // restartHelper has NO phone affordance (the dated no-action decision).
        rule.onAllNodesWithTag("cua_restart").assertCountEquals(0)
    }

    @Test
    fun `G101 the unavailable CUA shape renders its typed reason line`() {
        val starting = Json.parseToJsonElement(
            """
            {"available":false,"reason":"ZCode Computer Use is starting up; retry in a moment.","idle":true}
            """.trimIndent(),
        ).jsonObject
        rule.setContent { ZCodeTheme { CuaStatusViewerScreen(port = fakeCuaPort(CuaPermissionStatus.decode(starting))) } }
        rule.onNodeWithTag("cua_status_reason").assertExists()
        rule.onNodeWithText("Unavailable").assertExists()
    }

    @Test
    fun `G98 the source-server mirror renders hosts with state chips and workspace counts`() {
        val snapshot = Json.parseToJsonElement(
            """
            {"generation":7,
             "servers":[
               {"sourceServerId":"srv-1","displayName":"gpu-box","hostname":"gpu-box.local",
                "installState":"installed","declaredWorkspaces":[
                  {"workspacePath":"/srv/proj","workspaceIdentity":"/srv/proj","label":"Proj"}]},
               {"sourceServerId":"srv-2","displayName":"nas","installState":"uninstalled","declaredWorkspaces":[]}
             ],
             "states":[
               {"profileId":"prof-1","sourceServerId":"srv-1","state":"online"},
               {"profileId":"prof-2","sourceServerId":"srv-2","state":"failed","error":"SSH refused"}
             ],
             "failedPathCount":0,"abandonedPathCount":0}
            """.trimIndent(),
        ).jsonObject
        rule.setContent { ZCodeTheme { SourceServersViewerScreen(port = fakeSrcPort(SourceServersView.decode(snapshot))) } }
        rule.onNodeWithTag("srcsrv_list").assertIsDisplayed()
        rule.onNodeWithTag("srcsrv_row_srv-1").assertExists()
        rule.onNodeWithTag("srcsrv_state_srv-1").assertExists()
        rule.onNodeWithTag("srcsrv_error_srv-2").assertExists()
        rule.onNodeWithText("SSH refused").assertExists()
        rule.onAllNodesWithText("1 declared workspace(s)", substring = true).assertCountEquals(1)
        // Read-only: no connect/rename/remove affordance exists anywhere.
        listOf("srcsrv_connect", "srcsrv_rename", "srcsrv_remove").forEach { tag ->
            rule.onAllNodesWithTag(tag).assertCountEquals(0)
        }
    }

    @Test
    fun `G98 an empty directory renders the honest empty face`() {
        val empty = Json.parseToJsonElement(
            """{"generation":1,"servers":[],"states":[],"failedPathCount":0,"abandonedPathCount":0}""",
        ).jsonObject
        rule.setContent { ZCodeTheme { SourceServersViewerScreen(port = fakeSrcPort(SourceServersView.decode(empty))) } }
        rule.onNodeWithTag("srcsrv_empty").assertExists()
    }

    @Test
    fun `a port error renders the unavailable face on every mirror`() {
        rule.setContent { ZCodeTheme { SourceServersViewerScreen(port = fakeSrcPort(null, error = "refused")) } }
        rule.onNodeWithTag("srcsrv_error").assertExists()
        rule.setContent { ZCodeTheme { OutputStyleViewerScreen(port = fakeStylePort(null, error = "refused")) } }
        rule.onNodeWithTag("style_error").assertExists()
        rule.setContent { ZCodeTheme { ClientScenesViewerScreen(port = fakeScenesPort(null, error = "refused")) } }
        rule.onNodeWithTag("scenes_error").assertExists()
        rule.setContent { ZCodeTheme { CuaStatusViewerScreen(port = fakeCuaPort(null, error = "refused")) } }
        rule.onNodeWithTag("cua_status_error").assertExists()
    }
}
