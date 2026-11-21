package com.zcode.android.data.session

import com.zcode.android.domain.model.OutputStylesView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * pass-0 Phase 10 — G84's decoder pin. `output-style` channel `listStyles` →
 * `{styles:[{id,name,description,enabled,isBuiltIn,filePath?}]}` (built-ins +
 * `~/.claude/output-styles/*.md` customs, `enabled: id===activeId`) and
 * `getActiveStyle` → `{styleId: string|null}` — the READ-ONLY mirror of the
 * desktop's 7-verb outputStyleService (write verbs exist nowhere phone-side).
 */
class Pass0Phase10OutputStyleDecodeTest {

    @Test
    fun `decodes the list and the active style id`() {
        val list = """
            {"styles":[
              {"id":"default","name":"Default","description":"","enabled":true,"isBuiltIn":true},
              {"id":"custom-explain","name":"Explain","description":"Explain mode","enabled":false,
               "isBuiltIn":false,"filePath":"/u/.claude/output-styles/explain.md"}
            ]}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        val view = OutputStylesView.decode(list, activeStyleId = "custom-explain")
        assertEquals(2, view.styles.size)
        assertEquals("Explain", view.styles[1].name)
        assertEquals("custom-explain", view.activeStyleId)
        assertTrue(view.styles[0].isBuiltIn)
        assertNull(view.styles[0].filePath)
    }

    @Test
    fun `a null active style and a missing description decode tolerantly`() {
        val list = """
            {"styles":[{"id":"neo-dark","name":"Neo Dark","enabled":true,"isBuiltIn":true}]}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        val view = OutputStylesView.decode(list, activeStyleId = null)
        assertNull(view.activeStyleId)
        assertNull(view.styles.single().description)
        assertTrue(view.styles.single().enabled)
    }
}
