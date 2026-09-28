package com.zcode.android.spike

import com.zcode.android.core.protocol.bytes.ProtocolException
import java.net.URI

/**
 * QR pairing-parameter ingestion per architectural.md §12.2 (normative).
 * Parse + validate + compatibility gate; write nothing on invalid input (E2).
 */
data class PairingParams(
    val endpointOrigin: String,
    val route: String, // e.g. /remote/v4
    val deviceSid: String,
    val passHash: String,
    val issuedAtMs: Long?,
    val deviceMid: String?,
    val desktopName: String?,
    val appVersion: String?,
) {
    val routeIsLegacyV3: Boolean get() = route.endsWith("/remote/v3")

    companion object {
        private val SID_RE = Regex("^d_[A-Za-z0-9]{4,48}$")
        private val SUPPORTED_ORIGINS = setOf("https://zcode.z.ai", "https://zcode.chatglm.site")

        fun parse(rawUrl: String, nowMs: Long = System.currentTimeMillis(), staleWarningMs: Long = 10 * 60 * 1000): PairingParams {
            val uri = try {
                URI(rawUrl.trim())
            } catch (e: Exception) {
                throw ProtocolException("pairing link is not a URL")
            }
            if (uri.scheme !in setOf("https", "http")) {
                throw ProtocolException("pairing link must be https")
            }
            val origin = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            val route = uri.path ?: ""
            if (!route.endsWith("/remote/v4") && !route.endsWith("/remote/v3")) {
                throw ProtocolException("not a ZCode remote pairing route: $route")
            }
            val params = rawUrl.substringAfter('?', "").split('&')
                .mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                        java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
                }
                .toMap()
            val sid = params["sid"] ?: throw ProtocolException("pairing link missing sid")
            if (!SID_RE.matches(sid)) throw ProtocolException("deviceSid format invalid")
            val hash = params["hash"] ?: throw ProtocolException("pairing link missing hash")
            // Validate, don't assume (§12.2): expect base64-ish of a 32-byte digest but
            // tolerate other lengths — decode check only.
            val hashBytes = try {
                java.util.Base64.getDecoder().decode(hash)
            } catch (e: IllegalArgumentException) {
                try {
                    java.util.Base64.getUrlDecoder().decode(hash)
                } catch (e2: IllegalArgumentException) {
                    throw ProtocolException("hash is not base64")
                }
            }
            if (hashBytes.size !in 16..64) throw ProtocolException("hash length implausible (${hashBytes.size} bytes)")
            val t = params["t"]?.toLongOrNull()
            if (t != null) {
                if (t > nowMs + 60_000) throw ProtocolException("QR timestamp is in the future")
            }
            return PairingParams(
                endpointOrigin = origin,
                route = route,
                deviceSid = sid,
                passHash = hash,
                issuedAtMs = t,
                deviceMid = params["mid"],
                desktopName = params["name"],
                appVersion = params["app_version"],
            )
        }

        /** True when the QR looks stale (default 10 min heuristic → warning, not rejection). */
        fun isPossiblyStale(p: PairingParams, nowMs: Long, staleWarningMs: Long = 10 * 60 * 1000): Boolean =
            p.issuedAtMs != null && (nowMs - p.issuedAtMs) > staleWarningMs

        fun supportedOrigin(p: PairingParams): Boolean = p.endpointOrigin in SUPPORTED_ORIGINS
    }
}

/**
 * CredentialSigner — the *capability* surface: answers challenges without exposing
 * material (§12.4/§26.3). HMAC construction is the desktop-verified one (§7.6, INV-10):
 * proof = base64url(HMAC-SHA256(key = passHash-string-bytes, msg = "${nonce}|${role}|${sid}")).
 * The passHash STRING (not its decoded bytes) is the HMAC key — matching Node's
 * createHmac("sha256", passHashString) in the shipping bundle, byte for byte.
 */
class CredentialSigner(private val passHash: String) {
    fun proof(nonce: String, role: String, sid: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(passHash.encodeToByteArray(), "HmacSHA256"))
        val msg = "$nonce|$role|$sid"
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(msg.encodeToByteArray()))
    }
}
