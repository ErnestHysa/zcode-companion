package com.zcode.android.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A GitHub-Releases APK update (§14.2 T4; §37 primary channel). [versionCode] is the
 * machine-readable truth for "a newer release exists"; it comes from the release's
 * machine metadata block (written by the T3 pipeline) or falls back to the `-vcN`
 * tag suffix — never from prose.
 */
data class ReleaseInfo(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkAssetName: String,
    /** Expected digest (hex) when the release publishes it inline (T3 metadata). */
    val sha256Hex: String?,
    /** URL of a sibling `<apk>.sha256` asset when the digest is not inline. */
    val sha256Url: String?,
    val tag: String,
    val notes: String?,
    val maxTestedDesktop: String?,
)

/**
 * Parses the GitHub "latest release" JSON document. Tolerant by INV-8 (unknown
 * fields ignored); FAILS CLOSED to null when no APK asset or no versionCode can
 * be established — a release the app cannot identify must never be offered.
 */
object ReleaseParser {

    /** Written by tools/release/publish.mjs on every T3 release. */
    const val META_MARKER = "zcode-android-release-meta"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): ReleaseInfo? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val tag = root.string("tag_name") ?: return null
        val assets = root["assets"] as? kotlinx.serialization.json.JsonArray ?: return null
        val assetObjects = assets.filterIsInstance<JsonObject>()
        val apkAssets = assetObjects.mapNotNull { a ->
            val name = a.string("name") ?: return@mapNotNull null
            val url = a.string("browser_download_url") ?: return@mapNotNull null
            name to url
        }.filter { (name, _) -> name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null
        val meta = root.string("body")?.let { parseMeta(it) }
        val apkName = meta?.apkName
            ?.let { wanted -> apkAssets.firstOrNull { it.first.equals(wanted, ignoreCase = true) }?.first }
            ?: apkAssets.first().first
        val apkUrl = apkAssets.first { it.first == apkName }.second
        val shaFromAsset = assetObjects
            .firstOrNull { it.string("name") == "$apkName.sha256" }
            ?.string("browser_download_url")
        val versionCode = meta?.versionCode ?: versionCodeFromTag(tag) ?: return null
        return ReleaseInfo(
            versionName = meta?.versionName ?: versionNameFromTag(tag) ?: tag,
            versionCode = versionCode,
            apkUrl = apkUrl,
            apkAssetName = apkName,
            sha256Hex = meta?.sha256?.lowercase(),
            // The T3 inline digest is authoritative; the sibling asset is only the
            // fallback when the release publishes no inline digest.
            sha256Url = if (meta?.sha256 != null) null else shaFromAsset,
            tag = tag,
            notes = root.string("body"),
            maxTestedDesktop = meta?.maxTestedDesktop,
        )
    }

    /** `<!-- zcode-android-release-meta {"versionName":…,"versionCode":…,…} -->` */
    fun parseMeta(body: String): ReleaseMeta? {
        val idx = body.indexOf(META_MARKER)
        if (idx < 0) return null
        val start = body.indexOf('{', idx)
        if (start < 0) return null
        // Take the balanced JSON object starting at `start`.
        var depth = 0
        var end = -1
        var inString = false
        var escape = false
        for (i in start until body.length) {
            val c = body[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        val obj = runCatching { json.parseToJsonElement(body.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val versionCode = (obj["versionCode"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        return ReleaseMeta(
            versionName = obj.string("versionName"),
            versionCode = versionCode,
            maxTestedDesktop = obj.string("maxTestedDesktop"),
            sha256 = obj.string("sha256"),
            apkName = obj.string("apk"),
        )
    }

    /** `v0.11.5-vc37` / `0.11.5-vc37` → 37 (the T3 tag convention when meta is absent). */
    fun versionCodeFromTag(tag: String): Long? =
        Regex("-vc(\\d+)$").find(tag.removeSuffix("/"))?.groupValues?.get(1)?.toLongOrNull()

    fun versionNameFromTag(tag: String): String? =
        Regex("^v?([0-9]+\\.[0-9]+\\.[0-9]+.*)-vc\\d+$").find(tag)?.groupValues?.getOrNull(1)

    data class ReleaseMeta(
        val versionName: String?,
        val versionCode: Long,
        val maxTestedDesktop: String?,
        val sha256: String?,
        val apkName: String?,
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
