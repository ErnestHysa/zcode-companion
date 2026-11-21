package com.zcode.android.data.session

import com.zcode.android.domain.model.SourceServersView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * pass-0 Phase 10 — G98's decoder pin. `source-server-management` channel
 * `getSnapshot` (asar 3.18.1 `ec475e6e…`): `{generation, servers[], profiles[],
 * workspaceHistory[], states[], failedPathCount, abandonedPathCount,
 * migrationCounts}` — the Dh server item (`sourceServerId/displayName/osUser/
 * hostname/lastSeenVersion/lastOnlineAt/installState/declaredWorkspaces`) and
 * the J0 state item (`state: absent|bootstrapping|needs-install|needs-upgrade|
 * updating|online|retrying|offline|identity-change-pending|failed`), the state
 * vocabulary kept RAW (unknown values still display truthfully). Authoring
 * (rename/remove/connect) stays desktop-only — no write verb phone-side.
 */
class Pass0Phase10SourceServersDecodeTest {

    @Test
    fun `decodes the directory snapshot with states and counts`() {
        val snapshot = """
            {"generation":7,
             "servers":[
               {"sourceServerId":"11111111-1111-1111-1111-111111111111","displayName":"gpu-box",
                "osUser":"ernie","hostname":"gpu-box.local","lastSeenVersion":"3.18.1",
                "lastOnlineAt":1730000000000,"installState":"installed",
                "declaredWorkspaces":[{"workspacePath":"/srv/proj","workspaceIdentity":"/srv/proj","label":"Proj"}]},
               {"sourceServerId":"22222222-2222-2222-2222-222222222222","displayName":"nas",
                "installState":"uninstalled","declaredWorkspaces":[]}
             ],
             "profiles":[],
             "workspaceHistory":[],
             "states":[
               {"profileId":"33333333-3333-3333-3333-333333333333",
                "sourceServerId":"11111111-1111-1111-1111-111111111111",
                "state":"online","updateAvailability":"none"},
               {"profileId":"44444444-4444-4444-4444-444444444444",
                "sourceServerId":"22222222-2222-2222-2222-222222222222",
                "state":"failed","error":"SSH refused"}
             ],
             "failedPathCount":1,"abandonedPathCount":0,"migrationCounts":[]}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        val view = SourceServersView.decode(snapshot)
        assertEquals(7, view.generation)
        assertEquals(2, view.servers.size)
        assertEquals("gpu-box", view.servers[0].displayName)
        assertEquals("ernie", view.servers[0].osUser)
        assertEquals(1, view.servers[0].declaredWorkspaces.size)
        assertEquals("uninstalled", view.servers[1].installState)
        assertNull(view.servers[1].hostname)
        assertEquals(2, view.states.size)
        assertEquals("online", view.states[0].state)
        assertEquals("SSH refused", view.states[1].error)
        assertEquals(1, view.failedPathCount)
    }

    @Test
    fun `the state vocabulary stays raw for unknown values and absent optionals stay null`() {
        val snapshot = """
            {"generation":1,"servers":[{"sourceServerId":"id-1","displayName":"box"}],
             "profiles":[],"workspaceHistory":[],
             "states":[{"profileId":"p","state":"identity-change-pending"}],
             "failedPathCount":0,"abandonedPathCount":0,"migrationCounts":[]}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        val view = SourceServersView.decode(snapshot)
        assertEquals("identity-change-pending", view.states.single().state)
        assertTrue(view.servers.single().declaredWorkspaces.isEmpty())
        assertNull(view.servers.single().installState)
        assertNull(view.servers.single().lastOnlineAtMs)
    }
}
