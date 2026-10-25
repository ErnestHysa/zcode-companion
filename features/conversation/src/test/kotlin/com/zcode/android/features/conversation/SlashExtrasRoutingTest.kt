package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C2 (gaps-and-polish Phase 4C): the skills / subagents / custom-command legs load
 * LAZILY when the slash menu opens (desktop useSkills `enabled: activeTrigger?.trigger
 * === "/"`), once per session, and land on the UI state with per-section errors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlashExtrasRoutingTest {

    private class ExtrasPort : Phase7FakePort() {
        var fetches = 0
        var result = ConversationPort.SlashExtras(
            userCommands = listOf(ConversationPort.SlashCommandView("deploy", "d", null, "user")),
            skills = listOf(ConversationPort.SlashSkillView("pdf", "pdfs", "plugin")),
            skillsError = null,
            agents = emptyList(),
            agentsError = "fault.subagents.unavailable",
            loading = false,
        )
        override suspend fun fetchSlashExtras(taskId: String): ConversationPort.SlashExtras { fetches++; return result }
    }

    @Test
    fun `opening the slash menu fetches the extras once and lands them`() = runTest {
        val port = ExtrasPort()
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.onSlashMenuOpen()
        vm.onSlashMenuOpen()
        runCurrent()
        assertEquals(1, port.fetches, "lazy, once per session")
        val extras = assertNotNull(vm.state.value.slashExtras)
        assertEquals(listOf("deploy"), extras.userCommands.map { it.name })
        assertEquals(listOf("pdf"), extras.skills?.map { it.name })
        assertEquals("fault.subagents.unavailable", extras.agentsError)
        assertTrue(!extras.loading)
    }

    @Test
    fun `before the first open no extras are fetched`() = runTest {
        val port = ExtrasPort()
        val vm = ConversationViewModel(port, backgroundScope, "s")
        runCurrent()
        assertEquals(0, port.fetches)
        assertEquals(null, vm.state.value.slashExtras)
    }
}
