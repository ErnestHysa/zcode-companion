package com.zcode.android.data.session

import com.zcode.android.domain.model.ClientScenesView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * pass-0 Phase 10 — G83's decoder pin. `client-scenes` channel `list`
 * (asar 3.18.1 `ec475e6e…`: `createClientScenesService` = ONE verb over
 * GET /api/v1/client/scenes) → `{code, msg, data:[{scene, options}]}`; the
 * phone consumes the `draft-suggestion` scene's prompts (each prompt item's
 * `defaults` map resolves option-group items whose `contents` carry en|cn).
 * Tolerant: referenced-but-missing options SKIP the prompt (never guessed);
 * `code !== 0` is a business error (the honest degraded face).
 */
class Pass0Phase10ClientScenesDecodeTest {

    @Test
    fun `decodes the draft-suggestion prompts with en-cn fallback`() {
        val payload = """
            {"code":0,"msg":"ok","data":[
              {"scene":"draft-suggestion","options":{
                 "prompts":{"items":[
                    {"id":"p1","defaults":{"a":["opt-1"]},"img":"https://x/img.png"},
                    {"id":"p2","defaults":{"a":["opt-missing"]}}
                 ]},
                 "a":{"items":[
                    {"id":"opt-1","contents":{"en":"Summarize recent commits","cn":"总结最近的提交"}},
                    {"id":"opt-2","contents":{"cn":"只有中文"}}
                 ]}
              }},
              {"scene":"other-scene","options":{}}
            ]}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        val view = ClientScenesView.decode(payload)
        assertEquals(2, view.sceneCount)
        assertEquals(1, view.suggestedPrompts.size)
        val prompt = view.suggestedPrompts.single()
        assertEquals("p1", prompt.id)
        assertEquals("Summarize recent commits", prompt.text)
        assertEquals("https://x/img.png", prompt.imageUrl)
    }

    @Test
    fun `cn-only resolved option feeds the prompt text; missing reference skips`() {
        val cnOnly = """
            {"code":0,"msg":"ok","data":[
              {"scene":"draft-suggestion","options":{
                 "prompts":{"items":[{"id":"p2","defaults":{"a":["opt-2"]}}]},
                 "a":{"items":[{"id":"opt-2","contents":{"cn":"只有中文"}}]}
              }}
            ]}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        assertEquals("只有中文", ClientScenesView.decode(cnOnly).suggestedPrompts.single().text)

        val missing = """
            {"code":0,"msg":"ok","data":[
              {"scene":"draft-suggestion","options":{
                 "prompts":{"items":[{"id":"p2","defaults":{"a":["opt-missing"]}}]},
                 "a":{"items":[]}
              }}
            ]}
        """.trimIndent().let { Json.parseToJsonElement(it).jsonObject }
        assertTrue(ClientScenesView.decode(missing).suggestedPrompts.isEmpty())
    }

    @Test
    fun `a business error (code!=0) degrades honestly`() {
        val bad = Json.parseToJsonElement("""{"code":12001,"msg":"quota","data":[]}""").jsonObject
        assertTrue(ClientScenesView.decode(bad).businessError)
    }

    @Test
    fun `a malformed payload decodes to the honest empty face (INV-8)`() {
        val junk = Json.parseToJsonElement("""{"unexpected":"shape"}""").jsonObject
        val view = ClientScenesView.decode(junk)
        assertTrue(view.suggestedPrompts.isEmpty())
        assertTrue(view.sceneCount == 0)
    }
}
