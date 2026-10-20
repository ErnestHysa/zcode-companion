package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * I2.8 (Phase 6A) pins: generated-artifact preview cards (desktop `chat.previewCards.*`).
 * The kind table is the desktop's own (extension → markdown/html/docx/xlsx/pptx/pdf/
 * audio/video/text, cross-checked against `artifactKind` when present); `artifact` rows
 * render as cards with an open-external action, and assistant text that mentions a
 * generated document surfaces the same card. The desktop derives cards from file:// URLs,
 * markdown links, quoted paths and bare workspace paths — the phone detects the same
 * four families.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class ArtifactPreviewUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun artifactRow(rowId: Long, displayName: String, mime: String, artifactType: String = "file") =
        ConversationRow.Decoder.decode(
            Json.parseToJsonElement(
                """{"rowId":$rowId,"turnId":"t$rowId","kind":"artifact","entityId":"ent-$rowId",
                    "createdAt":1700000000000,"createdAtSeq":$rowId,
                    "artifactVersionId":"v-$rowId","logicalArtifactKey":"key-$rowId",
                    "displayName":"$displayName","artifactType":"$artifactType","mimeType":"$mime",
                    "sizeBytes":2048,"sha256":"ab","ref":"generated/report-$rowId.pdf"}""",
            ).jsonObject,
        )

    private fun assistantRow(rowId: Long, text: String) = ConversationRow.Decoder.decode(
        Json.parseToJsonElement(
            """{"rowId":$rowId,"turnId":"t$rowId","kind":"assistantText","entityId":"ent-$rowId",
                "createdAt":1700000000000,"createdAtSeq":$rowId,"text":${Json.parseToJsonElement("\"$text\"").toString()},"state":"complete"}""",
        ).jsonObject,
    )

    private val opens = mutableListOf<String>()

    private fun setContent(vararg rows: ConversationRow) {
        opens.clear()
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = rows.toList(),
                        loading = false,
                        taskState = TaskStateMachine.State.IDLE,
                        phase = ConnectionPhase.CONNECTED,
                        epoch = "e1",
                    ),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    onOpenArtifact = { ref, mime -> opens.add("$ref|$mime") },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the kind table is the desktop's - extension decides, artifactKind must agree`() {
        assertEquals(GeneratedArtifactPreview.Kind.MARKDOWN, GeneratedArtifactPreview.kindFor("README.md"))
        assertEquals(GeneratedArtifactPreview.Kind.HTML, GeneratedArtifactPreview.kindFor("site.html"))
        assertEquals(GeneratedArtifactPreview.Kind.HTML, GeneratedArtifactPreview.kindFor("site.htm"))
        assertEquals(GeneratedArtifactPreview.Kind.DOCX, GeneratedArtifactPreview.kindFor("Report.DOCX"))
        assertEquals(GeneratedArtifactPreview.Kind.XLSX, GeneratedArtifactPreview.kindFor("data.xlsx"))
        assertEquals(GeneratedArtifactPreview.Kind.PPTX, GeneratedArtifactPreview.kindFor("deck.pptx"))
        assertEquals(GeneratedArtifactPreview.Kind.PDF, GeneratedArtifactPreview.kindFor("paper.pdf"))
        assertEquals(GeneratedArtifactPreview.Kind.AUDIO, GeneratedArtifactPreview.kindFor("take2.mp3"))
        assertEquals(GeneratedArtifactPreview.Kind.VIDEO, GeneratedArtifactPreview.kindFor("demo.webm"))
        assertEquals(GeneratedArtifactPreview.Kind.TEXT, GeneratedArtifactPreview.kindFor("notes.txt"))
        assertNull(GeneratedArtifactPreview.kindFor("archive.tar.gz"), "unknown extensions make no card")
        // artifactKind cross-check (desktop cZe): the card survives only on agreement.
        assertEquals(
            GeneratedArtifactPreview.Kind.DOCX,
            GeneratedArtifactPreview.kindFor("report.docx", artifactKind = "document"),
        )
        assertNull(
            GeneratedArtifactPreview.kindFor("report.pdf", artifactKind = "document"),
            "a pdf path labeled as a document artifact makes no card (desktop mismatch rule)",
        )
    }

    @Test
    fun `the text detector finds the four desktop path families`() {
        val text = """
            See [the spec](docs/spec.md), "quoted deck.pptx", file:///ws/notes.txt and
            the bare path build/figures/plot.pdf. Unrelated: https://example.test/x.
        """.trimIndent()
        val cards = GeneratedArtifactPreview.detectCards(text)
        assertEquals(listOf("docs/spec.md", "quoted deck.pptx", "notes.txt", "build/figures/plot.pdf"), cards.map { it.path })
        assertEquals(GeneratedArtifactPreview.Kind.MARKDOWN, cards[0].kind)
        assertEquals(GeneratedArtifactPreview.Kind.PPTX, cards[1].kind)
        assertEquals(GeneratedArtifactPreview.Kind.TEXT, cards[2].kind)
        assertEquals(GeneratedArtifactPreview.Kind.PDF, cards[3].kind)
    }

    @Test
    fun `artifact rows render as preview cards with the desktop subtitle and open-external`() {
        setContent(artifactRow(41, displayName = "quarterly-report.pdf", mime = "application/pdf"))
        rule.onNodeWithTag("preview_card_41").assertIsDisplayed()
        rule.onNodeWithText("Document · PDF").assertIsDisplayed()
        rule.onNodeWithText("quarterly-report.pdf").assertIsDisplayed()
        rule.onNodeWithTag("preview_open_41").performClick()
        rule.waitForIdle()
        assertEquals(listOf("generated/report-41.pdf|application/pdf"), opens)
    }

    @Test
    fun `assistant text mentioning a generated document surfaces the same card`() {
        setContent(assistantRow(42, "I drafted the brief — see \"final brief.docx\" for details."))
        rule.onNodeWithTag("preview_card_42").assertIsDisplayed()
        rule.onNodeWithText("Document · DOCX").assertIsDisplayed()
        rule.onNodeWithTag("preview_open_42").performClick()
        rule.waitForIdle()
        assertEquals(listOf("final brief.docx|application/vnd.openxmlformats-officedocument.wordprocessingml.document"), opens)
    }

    @Test
    fun `prose without generated documents makes no card`() {
        setContent(assistantRow(43, "The parser now batches deltas per frame and memoizes parsed blocks."))
        rule.onAllNodesWithTag("preview_card_43").let {
            org.junit.Assert.assertEquals(0, it.fetchSemanticsNodes().size)
        }
    }
}
