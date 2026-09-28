package com.zcode.android.core.security.pairing

/**
 * QR pairing-link ingestion — architectural.md §12.2 (normative) + §6.2 (verified QR
 * builder). Pure functions: a rejected link writes NOTHING anywhere (E2) — callers
 * observe the result type and only proceed to `CredentialVault.seal` after every gate.
 *
 * §12.2 rules implemented here:
 *  1. accepts scanner output and manual paste/typed link (identical path);
 *  2. require route suffix `/remote/v4` (accept `/remote/v3` with a legacy flag →
 *     compatibility warning, §39); require params `sid` (format `d_` + base62),
 *     `hash` (base64 of a plausible digest — 44 chars expected for 32 bytes, validated
 *     not assumed), `t` (epoch ms sanity: never in the future; older than the stale
 *     window → warning, NOT rejection), `mid`, `name`, `app_version`;
 *  3. origin must be a verified endpoint origin (§6.2: prod `https://zcode.z.ai`, test
 *     `https://zcode.chatglm.site`) — loopback/`10.0.2.2`-class dev origins are
 *     accepted with the `devOrigin` flag (developer/mock use only, never shipped
 *     credentials against them by default UI copy);
 *  4. duplicate query parameters are rejected (ambiguous input = fail closed);
 *  5. everything above is side-effect free.
 *
 * INV-14: malformed/hostile input fails closed to a typed rejection — never a
 * best-effort interpretation.
 */
object PairingLinkParser {

    /** Verified endpoint origins (§6.2, bundle QR builder). */
    val SUPPORTED_ORIGINS: Set<String> = setOf("https://zcode.z.ai", "https://zcode.chatglm.site")

    private const val MAX_RAW_LENGTH = 8_192
    private const val MAX_PARAM_LENGTH = 1_024
    private val SID_RE = Regex("^d_[A-Za-z0-9]{8,64}$")
    private val VERSION_RE = Regex("""^\d+\.\d+\.\d+([-+.][A-Za-z0-9._-]+)?$""")

    /** Envelope rules: `t` never in the future; default stale-warning window 10 min (§12.2). */
    const val DEFAULT_STALE_WARNING_MS: Long = 10 * 60 * 1000L

    sealed class Result {
        /** All gates passed. [staleWarning] and [legacyRoute] degrade to warnings, never blocks. */
        data class Ready(
            val link: PairingLink,
            val staleWarning: Boolean,
            val legacyRoute: Boolean,
            val devOrigin: Boolean,
        ) : Result()

        /** Fail-closed rejection — zero state writes (E2 / §12.2 step 5). */
        data class Rejected(val reason: Reason, val detail: String) : Result()
    }

    /** Typed rejection reasons (§33-class: parser rejection surfaces a safe failure). */
    enum class Reason {
        NOT_A_URL,
        UNSUPPORTED_ORIGIN,
        WRONG_ROUTE,
        MISSING_PARAM,
        BAD_SID,
        BAD_HASH,
        FUTURE_TIMESTAMP,
        DUPLICATE_PARAM,
        OVERSIZED_INPUT,
        BAD_APP_VERSION,
    }

    fun parse(
        raw: String,
        nowMs: Long,
        staleWarningMs: Long = DEFAULT_STALE_WARNING_MS,
    ): Result {
        if (raw.length > MAX_RAW_LENGTH) return Result.Rejected(Reason.OVERSIZED_INPUT, "link exceeds length bound")
        val trimmed = raw.trim()
        val uri = try {
            java.net.URI(trimmed)
        } catch (e: Exception) {
            return Result.Rejected(Reason.NOT_A_URL, "not a URI")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme == null || host == null || uri.rawQuery == null) {
            return Result.Rejected(Reason.NOT_A_URL, "missing scheme/host/query")
        }
        // Userinfo trick (https://zcode.z.ai@evil.invalid/…) — URI.host is the real host.
        if (uri.userInfo != null) return Result.Rejected(Reason.UNSUPPORTED_ORIGIN, "userinfo in URL")

        val origin = "$scheme://$host" + if (uri.port > 0) ":${uri.port}" else ""
        val hostOrigin = "$scheme://$host"
        val devOrigin = hostOrigin in DEV_ORIGINS
        if (origin !in SUPPORTED_ORIGINS && !devOrigin) {
            return Result.Rejected(Reason.UNSUPPORTED_ORIGIN, "origin not a verified ZCode endpoint")
        }
        if (scheme == "http" && !devOrigin) {
            return Result.Rejected(Reason.UNSUPPORTED_ORIGIN, "cleartext origin outside dev loopback")
        }

        // Exact verified routes (tighter than the §12.2 "suffix" wording: a QR builder
        // never emits /<other>/remote/v4, and fail-closed rejects lookalike paths).
        val route = uri.path ?: ""
        val legacyRoute = route == "/remote/v3"
        if (route != "/remote/v4" && !legacyRoute) {
            return Result.Rejected(Reason.WRONG_ROUTE, "route is not /remote/v4 (or legacy /remote/v3)")
        }

        val params = LinkedHashMap<String, String>()
        for (pair in uri.rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq <= 0) null else urlDecode(pair.substring(0, eq))
            val v = if (eq <= 0) null else urlDecode(pair.substring(eq + 1))
            if (k == null || v == null) return Result.Rejected(Reason.MISSING_PARAM, "malformed query pair")
            if (k.length > MAX_PARAM_LENGTH || v.length > MAX_PARAM_LENGTH) {
                return Result.Rejected(Reason.OVERSIZED_INPUT, "parameter exceeds length bound")
            }
            if (params.containsKey(k)) return Result.Rejected(Reason.DUPLICATE_PARAM, "duplicate '$k'")
            params[k] = v
        }

        val sid = params["sid"] ?: return Result.Rejected(Reason.MISSING_PARAM, "sid missing")
        if (!SID_RE.matches(sid)) return Result.Rejected(Reason.BAD_SID, "deviceSid is not d_+base62")

        val hash = params["hash"] ?: return Result.Rejected(Reason.MISSING_PARAM, "hash missing")
        val hashBytes = try {
            java.util.Base64.getDecoder().decode(hash)
        } catch (e: IllegalArgumentException) {
            return Result.Rejected(Reason.BAD_HASH, "hash is not base64")
        }
        // §12.2: 44 chars expected for a 32-byte digest — validate, don't assume.
        if (hashBytes.size !in 16..64) return Result.Rejected(Reason.BAD_HASH, "hash length implausible (${hashBytes.size}B)")

        val issuedAtMs = params["t"]?.toLongOrNull() ?: return Result.Rejected(Reason.MISSING_PARAM, "t missing/not epoch ms")
        if (issuedAtMs > nowMs + FUTURE_GRACE_MS) {
            return Result.Rejected(Reason.FUTURE_TIMESTAMP, "QR timestamp is in the future")
        }

        val mid = params["mid"] ?: return Result.Rejected(Reason.MISSING_PARAM, "mid missing")
        if (mid.isBlank() || mid.length > MAX_PARAM_LENGTH) return Result.Rejected(Reason.MISSING_PARAM, "mid blank/oversized")
        val name = params["name"] ?: return Result.Rejected(Reason.MISSING_PARAM, "name missing")
        if (name.isBlank() || name.length > MAX_PARAM_LENGTH) return Result.Rejected(Reason.MISSING_PARAM, "name blank/oversized")
        val appVersion = params["app_version"] ?: return Result.Rejected(Reason.MISSING_PARAM, "app_version missing")
        if (!VERSION_RE.matches(appVersion)) return Result.Rejected(Reason.BAD_APP_VERSION, "app_version not semver-ish")

        val link = PairingLink(
            endpointOrigin = origin,
            route = route,
            deviceSid = sid,
            passHash = hash,
            issuedAtMs = issuedAtMs,
            deviceMid = mid,
            desktopName = name,
            appVersion = appVersion,
        )
        val stale = (nowMs - issuedAtMs) > staleWarningMs
        return Result.Ready(link, staleWarning = stale, legacyRoute = legacyRoute, devOrigin = devOrigin)
    }

    private fun urlDecode(s: String): String? = try {
        // String-charset overload: the Charset variant is API 33+ (minSdk 26).
        @Suppress("DEPRECATION")
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        null
    }

    /** Dev-only origins (§6.2: "localhost dev" class). Loopback + emulator host alias. */
    private val DEV_ORIGINS: Set<String> = setOf(
        "http://localhost", "http://127.0.0.1", "http://10.0.2.2",
        "https://localhost", "https://127.0.0.1", "https://10.0.2.2",
    )

    /** `t` values slightly ahead of local clock (clock skew) are tolerated. */
    const val FUTURE_GRACE_MS: Long = 60_000
}

/**
 * A VALIDATED pairing link (value type). `passHash` is a SECRET (§9.13) — it must flow
 * only into [com.zcode.android.core.security.vault.CredentialVault.seal]; never logged
 * (INV-3), never persisted elsewhere, never rendered.
 */
data class PairingLink(
    val endpointOrigin: String,
    val route: String,
    val deviceSid: String,
    val passHash: String,
    val issuedAtMs: Long,
    val deviceMid: String,
    val desktopName: String,
    val appVersion: String,
) {
    val legacyRoute: Boolean get() = route == "/remote/v3"

    /**
     * WebSocket endpoint URL derived from the QR origin (§6.2 verified origin set;
     * Phase 1 live finding: the relay requires `?mid=<deviceMid>` — appended by the
     * transport layer, which owns [EndpointDescriptor.wsUrl] semantics).
     */
    fun wsUrl(): String = when {
        endpointOrigin.startsWith("https://") -> endpointOrigin.replaceFirst("https://", "wss://") + "/ws"
        endpointOrigin.startsWith("http://") -> endpointOrigin.replaceFirst("http://", "ws://") + "/ws"
        else -> throw IllegalStateException("unsupported origin scheme")
    }
}
