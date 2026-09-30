package com.zcode.android.features.conversation

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.Correlation
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.RecoveryAction
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.markdown.MarkdownParser
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.stream.coalesceForUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 6.4 pipeline pieces: coalescer (§19.2 ≤30 Hz), bounded markdown parser
 * (ADR-015; total on untrusted input — T15), and ViewModel streaming merge +
 * §15-S9 pagination interplay.
 */
/** The stateIn(Eagerly) share-start hops real dispatchers; settle with real yields. */
suspend fun kotlinx.coroutines.test.TestScope.eventually(timeoutMs: Long = 2_000, cond: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        advanceUntilIdle()
        if (cond()) return true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(10) }
    }
    advanceUntilIdle()
    return cond()
}

class StreamCoalescerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `first value emits immediately, then at most one per period`() = runTest {
        val src = MutableStateFlow(0)
        val seen = mutableListOf<Int>()
        val job = launch { src.coalesceForUi(33).collect { seen.add(it) } }
        runCurrent()
        assertEquals(listOf(0), seen.toList())
        // Burst of 40 emissions inside one period → exactly ONE further emission.
        repeat(40) { src.value = it + 1 }
        advanceTimeBy(34)
        runCurrent()
        assertEquals(2, seen.size) // first + coalesced latest
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sustained stream does not exceed ~30Hz`() = runTest {
        val src = MutableStateFlow(0)
        val seen = mutableListOf<Int>()
        val job = launch { src.coalesceForUi(33).collect { seen.add(it) } }
        repeat(300) {
            src.value = it
            advanceTimeBy(5) // 5 ms cadence = 200 Hz raw
        }
        advanceTimeBy(100)
        // 300 × 5 ms = 1.5 s at ≤ 33 ms → far under the raw 300 emissions.
        assertTrue(seen.size < 60, "coalesced=${seen.size}")
        job.cancel()
    }
}

class MarkdownParserTest {

    @Test
    fun `paragraphs headings lists quotes fences`() {
        val src = """
            # Title
            Plain paragraph with text.
            - bullet one
            2. ordered two
            > quoted line
            ```kotlin
            val a = 1
            ```
            after
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        assertTrue(blocks[0] is MarkdownParser.Block.Heading)
        assertTrue(blocks[1] is MarkdownParser.Block.Paragraph)
        val bullet = blocks[2] as MarkdownParser.Block.ListItem
        assertTrue(!bullet.ordered)
        val ordered = blocks[3] as MarkdownParser.Block.ListItem
        assertTrue(ordered.ordered && ordered.number == 2)
        assertTrue(blocks[4] is MarkdownParser.Block.Quote)
        val fence = blocks[5] as MarkdownParser.Block.CodeFence
        assertEquals("kotlin", fence.lang)
        assertTrue(fence.complete)
        assertTrue(fence.code.contains("val a = 1"))
    }

    @Test
    fun `inline code bold italic links`() {
        val spans = MarkdownParser.parseInline("run `make check` now, **bold**, *it*, and [docs](https://example.com)")
        assertTrue(spans.any { it.code && it.text == "make check" })
        assertTrue(spans.any { it.bold && it.text == "bold" })
        assertTrue(spans.any { it.italic && it.text == "it" })
        assertTrue(spans.any { it.linkUrl == "https://example.com" && it.text == "docs" })
    }

    @Test
    fun `unclosed fence renders as pending literal (stabilized prefix, 19-2)`() {
        val blocks = MarkdownParser.parse("before\n```python\nprint('partial'")
        val fence = blocks.last() as MarkdownParser.Block.CodeFence
        assertEquals(false, fence.complete)
        assertEquals("python", fence.lang)
        assertTrue(fence.code.contains("partial"))
    }

    @Test
    fun `unterminated inline markers render literally (no layout jumps)`() {
        val spans = MarkdownParser.parseInline("streaming **partial and `tick")
        val joined = spans.joinToString("") { it.text }
        assertTrue(joined.contains("**partial"))
        assertTrue(joined.contains("`tick"))
    }

    @Test
    fun `parser is TOTAL on hostile input (T15, INV-8)`() {
        val hostile = listOf("", "```", "```\n", "****", "[][](", "`", "#", "\n\n\n", "a".repeat(100_000), "```js\n```js\n```", "[a](".repeat(500))
        for (h in hostile) {
            MarkdownParser.parse(h) // must never throw
            MarkdownParser.parseInline(h)
        }
    }
}

/** Fakes + ViewModel behavior for the conversation surface. */
class ConversationViewModelTest {

    private class FakePort : ConversationPort {
        override val phase = MutableStateFlow(ConnectionPhase.CONNECTED)
        val rowsFlow = MutableStateFlow<List<ConversationRow>>(emptyList())
        val epochFlow = MutableStateFlow<String?>(null)
        val staleFlow = MutableStateFlow(false)
        val taskStateFlow = MutableStateFlow(TaskStateMachine.State.UNKNOWN)
        val streamingFlow = MutableStateFlow<List<StreamingBuffer>>(emptyList())
        var openCalled = 0
        val loadOlderCalls = mutableListOf<Long>()
        var loadOlderResult = ConversationPort.LoadOlderResult(0, hasMore = false, error = null, epochRejected = false)

        override fun observeRows(taskId: String): Flow<List<ConversationRow>> = rowsFlow
        override fun observeEpoch(taskId: String): Flow<String?> = epochFlow
        override fun observeStale(taskId: String): Flow<Boolean> = staleFlow
        override fun observeTaskState(taskId: String): Flow<TaskStateMachine.State> = taskStateFlow
        override fun observeStreaming(taskId: String): Flow<List<StreamingBuffer>> = streamingFlow

    override fun observeHasMoreHistory(taskId: String): Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun observeTotalRows(taskId: String): Flow<Long?> = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

        override suspend fun task(taskId: String): Task? = Task(
            taskId = taskId,
            title = "T",
            status = TaskStatus.RUNNING,
            workspace = WorkspaceIdentityKey("/w"),
            createdAtMs = 1,
            updatedAtMs = 2,
        )

        override suspend fun open(task: Task): Boolean {
            openCalled++
            return true
        }

        override suspend fun loadOlder(taskId: String, beforeRowId: Long): ConversationPort.LoadOlderResult {
            loadOlderCalls.add(beforeRowId)
            return loadOlderResult
        }
    }

    private fun row(id: Long, text: String): ConversationRow.AssistantText =
        ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(id))
                put("turnId", JsonPrimitive("t"))
                put("kind", JsonPrimitive("assistantText"))
                put("createdAt", JsonPrimitive(1.0))
                put("createdAtSeq", JsonPrimitive(id))
                put("assistantResponseId", JsonPrimitive("ar"))
                put("text", JsonPrimitive(text))
                put("state", JsonPrimitive("complete"))
            },
        ) as ConversationRow.AssistantText

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loading until the authoritative anchor, then ready`() = runTest {
        val port = FakePort()
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        runCurrent()
        assertTrue(vm.state.value.loading)
        port.epochFlow.value = "e1"
        port.taskStateFlow.value = TaskStateMachine.State.IDLE
        assertTrue(eventually { vm.state.value.empty })
        port.rowsFlow.value = listOf(row(1, "hello"))
        assertTrue(eventually { vm.state.value.rows.isNotEmpty() })
        assertEquals("hello", (vm.state.value.rows.single() as ConversationRow.AssistantText).text)
        assertTrue(!vm.state.value.loading)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `streaming buffers merge onto their row`() = runTest {
        val port = FakePort()
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        port.epochFlow.value = "e1"
        port.taskStateFlow.value = TaskStateMachine.State.RUNNING
        port.rowsFlow.value = listOf(row(1, "par"))
        port.streamingFlow.value = listOf(StreamingBuffer(1, "partial te", 5))
        assertTrue(eventually { vm.state.value.streaming[1L] == "partial te" })
        assertEquals("partial te", vm.state.value.streaming[1L])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `epoch-rejected page surfaces history-changed and re-anchors (15-S9)`() = runTest {
        val port = FakePort()
        port.loadOlderResult = ConversationPort.LoadOlderResult(0, hasMore = false, error = null, epochRejected = true)
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        runCurrent()
        var callbackRows = -1
        vm.loadOlder(beforeRowId = 10) { callbackRows = it }
        assertTrue(eventually { vm.state.value.historyChanged })
        assertEquals(0, callbackRows)
        assertEquals(10L, port.loadOlderCalls.single())
        vm.dismissHistoryNotice()
        assertTrue(eventually { !vm.state.value.historyChanged })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadOlder error surfaces with retry affordance`() = runTest {
        val port = FakePort()
        port.loadOlderResult = ConversationPort.LoadOlderResult(
            0,
            hasMore = true,
            error = AppError(
                ErrorCategory.TIMEOUT, "Taking too long", "diag",
                AppError.Retryable.AUTO, recoverable = true,
                autoRecovery = RecoveryAction.NONE, correlation = Correlation(),
            ),
            epochRejected = false,
        )
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        runCurrent()
        vm.loadOlder(5) { }
        assertTrue(eventually { vm.state.value.loadOlderError?.category == ErrorCategory.TIMEOUT })
        assertEquals(ErrorCategory.TIMEOUT, vm.state.value.loadOlderError?.category)
    }
}
