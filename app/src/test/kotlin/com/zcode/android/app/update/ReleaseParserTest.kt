package com.zcode.android.app.update

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the update check (T4) — the GitHub Releases metadata parser, pinned against the SEEDED fixture
 * (fixtures/releases/github-release-fixtures.json, the shape tools/release/publish.mjs
 * writes). Shape-faithful per rule 4: exact field values, fail-closed cases.
 */
class ReleaseParserTest {

    private fun fixtureCase(name: String): String {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/releases/github-release-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("github-release-fixtures.json not found")
        val root = Json.parseToJsonElement(file.readText())
        val cases = root.jsonObject["cases"]!!.let { it as kotlinx.serialization.json.JsonArray }
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!
        return data.toString()
    }

    @Test
    fun `parses the T3-published release with the meta block`() {
        val info = ReleaseParser.parse(fixtureCase("release-with-meta"))
        assertNotNull(info)
        info!!
        assertEquals("0.11.5", info.versionName)
        assertEquals(37L, info.versionCode)
        assertEquals("app-release.apk", info.apkAssetName)
        assertEquals("https://example.github/releases/v37/app-release.apk", info.apkUrl)
        assertEquals("3.14.5", info.maxTestedDesktop)
        assertEquals("1111111111111111111111111111111111111111111111111111111111111111", info.sha256Hex)
        assertEquals("v0.11.5-vc37", info.tag)
        assertNull(info.sha256Url)
    }

    @Test
    fun `falls back to the -vcN tag suffix when the meta block is absent`() {
        val info = ReleaseParser.parse(fixtureCase("release-tag-only"))
        assertNotNull(info)
        info!!
        assertEquals(38L, info.versionCode)
        assertEquals("0.11.6", info.versionName)
        assertEquals("https://example.github/releases/v38/app-release.apk", info.apkUrl)
        assertNull(info.sha256Hex)
    }

    @Test
    fun `takes the sha256 from the sibling asset when not inline`() {
        val info = ReleaseParser.parse(fixtureCase("release-sha-from-asset"))
        assertNotNull(info)
        assertEquals(
            "https://example.github/releases/v39/app-release.apk.sha256",
            info!!.sha256Url,
        )
        assertNull(info.sha256Hex)
    }

    @Test
    fun `fails closed when the release carries no APK asset`() {
        assertNull(ReleaseParser.parse(fixtureCase("release-no-apk")))
    }

    @Test
    fun `fails closed when no version code can be established`() {
        assertNull(ReleaseParser.parse(fixtureCase("release-no-version")))
    }

    @Test
    fun `fails closed on malformed JSON`() {
        assertNull(ReleaseParser.parse(fixtureCase("release-malformed")))
    }

    @Test
    fun `unknown fields are tolerated (INV-8) and the meta block survives prose`() {
        val metaJson = """
            {"versionName":"0.12.0","versionCode":41,"maxTestedDesktop":"3.14.6"}
        """.trimIndent()
        val bodyText = "Notes line one.\n\n<!-- ${ReleaseParser.META_MARKER} $metaJson -->\nThanks"
        val doc = kotlinx.serialization.json.buildJsonObject {
            put("tag_name", kotlinx.serialization.json.JsonPrimitive("v0.12.0-vc41"))
            put("assets", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive("app-release.apk"))
                    put("browser_download_url", kotlinx.serialization.json.JsonPrimitive("https://x/app-release.apk"))
                    put("future_field", kotlinx.serialization.json.buildJsonObject {
                        put("a", kotlinx.serialization.json.JsonPrimitive(1))
                    })
                })
            })
            put("author", kotlinx.serialization.json.buildJsonObject {
                put("login", kotlinx.serialization.json.JsonPrimitive("someone"))
            })
            put("some_new_toplevel", kotlinx.serialization.json.JsonPrimitive("ignored"))
            put("body", kotlinx.serialization.json.JsonPrimitive(bodyText))
        }
        val info = ReleaseParser.parse(doc.toString())
        assertNotNull(info)
        assertEquals(41L, info!!.versionCode)
        assertEquals("0.12.0", info.versionName)
        assertEquals("3.14.6", info.maxTestedDesktop)
        assertTrue(info.apkUrl.endsWith("/app-release.apk"))
    }

    @Test
    fun `meta block with a non-numeric versionCode fails closed`() {
        val meta = ReleaseParser.parseMeta(
            "<!-- zcode-android-release-meta {\"versionName\":\"0.12.0\",\"versionCode\":\"abc\"} -->",
        )
        assertNull(meta)
    }
}
