package com.zcode.android.core.security.vault

import kotlinx.serialization.Serializable

/**
 * Credential custody models (§12.1/§12.4). The SECRET envelope ([CredentialSecret],
 * carrying the passHash) only ever exists as Tink AEAD ciphertext at rest and inside
 * the signer capability in memory. [CredentialMetadata] is deliberately secret-free:
 * it is the ONLY credential view the UI can ever obtain (§26.3 custody/capability
 * split; INV-3).
 */
@Serializable
data class CredentialMetadata(
    /** Android-side endpoint id (uuid v4 at first pairing, §12.5/§9.13). */
    val endpointId: String,
    val deviceSid: String,
    val deviceMid: String,
    val desktopName: String,
    val desktopAppVersion: String,
    /** Verified QR endpoint origin (non-secret endpoint identity, §9.13) — used to
     *  rebuild the WebSocket URL on relaunch (§15-S2 resume path). */
    val endpointOrigin: String = "",
    /** QR issue timestamp (display only; §12.2 stale heuristics). */
    val issuedAtMs: Long,
    val sealedAtMs: Long,
    /** Rotation generation — bumped on every same-sid rotate (§12.7). */
    val generation: Int,
    /** True when sealed against a legacy `/remote/v3` route (compat note, §39). */
    val legacyRoute: Boolean,
)

@Serializable
data class CredentialSecret(
    /** The QR `hash` — HMAC-SHA256 key material as the base64 STRING (§7.6 semantics). */
    val passHash: String,
    val sealedAtMs: Long,
)

/** `CredentialVault.seal` outcome — rotation classification per §12.2 step 4 / §12.7. */
sealed class SealOutcome {
    /** No previous credential for this deviceSid — new endpoint credential created. */
    data class Created(val metadata: CredentialMetadata) : SealOutcome()

    /** Same deviceSid already sealed — credential ROTATED in place (§12.7). */
    data class Rotated(val metadata: CredentialMetadata) : SealOutcome()

    /** A DIFFERENT deviceSid was already sealed — MVP is single-endpoint: caller must
     *  unpair first (replace endpoint = destructive, needs explicit user action). */
    data class Conflicts(val existingSid: String) : SealOutcome()
}

/**
 * Direct-endpoint descriptor metadata (§12.4 "Server token" row + §11.3 registry):
 * deliberately SECRET-FREE — the token itself only ever exists as Tink ciphertext
 * at rest and inside the [TokenProvider] capability in memory (INV-3, §26.3).
 */
@Serializable
data class DirectEndpointMetadata(
    val endpointId: String,
    val displayName: String,
    /** ws(s)://… URL of the direct endpoint (registry identity, not a secret). */
    val wsUrl: String,
    /** server-info serverId (display/diagnostic; opaque). */
    val serverId: String? = null,
    /** server-info version at add time (compat-gate display). */
    val serverVersion: String? = null,
    val addedAtMs: Long,
)

/** Forensic wipe report (§3.5 / §12.7 unpair). */
data class WipeReport(
    val deletedBlobNames: List<String>,
    val masterKeyDestroyed: Boolean,
    val storeEmptyAfterWipe: Boolean,
)
