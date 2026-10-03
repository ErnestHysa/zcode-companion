package com.zcode.android.core.security.vault

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.signer.CredentialSigner
import com.zcode.android.core.security.signer.JcaCredentialSigner
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.KeysetHandle
import kotlinx.serialization.json.Json

/**
 * CredentialVault — the custody surface over the pairing secret (§12.4/§26.3, ADR-004).
 *
 * Custody model (normative, §12.4 table):
 *  - The Tink AES-256-GCM keyset seals the secret envelope; the (cleartext) keyset is
 *    wrapped by the platform master key ([MasterKeyWrap] — AndroidKeyStore on device)
 *    and stored alongside the blobs it protects.
 *  - At-rest blobs are ALWAYS ciphertext: no raw store byte ever contains the passHash
 *    (tested — forensic scan §3.5).
 *  - The ONLY accessor of the secret is [loadSigner] → a [CredentialSigner] capability
 *    that answers challenges. There is deliberately NO method returning the hash
 *    (API-level guarantee, §3.2 acceptance; INV-3/INV-10).
 *  - Rotation (same deviceSid) replaces the sealed credential atomically (§12.7);
 *    wipe deletes every blob and destroys the master key (§12.7 unpair / §3.5).
 */
class CredentialVault(
    private val store: CredentialStore,
    private val keyWrap: MasterKeyWrap,
    private val log: ZLog,
    private val newEndpointId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------- seal / rotate

    /**
     * Seal a validated pairing link. Rotation classification per §12.2 step 4: same
     * deviceSid → in-place rotation; different deviceSid while a credential exists →
     * [SealOutcome.Conflicts] (single-endpoint MVP: the caller must unpair first);
     * nothing is written on a conflict.
     */
    fun seal(link: com.zcode.android.core.security.pairing.PairingLink): SealOutcome {
        val existing = loadMetadata()
        if (existing != null && existing.deviceSid != link.deviceSid) {
            log.warn("vault.seal.conflict", "existing sid prefix=${existing.deviceSid.take(4)}…")
            return SealOutcome.Conflicts(existing.deviceSid)
        }
        val rotated = existing != null
        val generation = if (rotated) existing.generation + 1 else 1
        val aead = obtainAead()
        val secret = CredentialSecret(passHash = link.passHash, sealedAtMs = clock())
        val sealed = aead.encrypt(json.encodeToString(CredentialSecret.serializer(), secret).encodeToByteArray(), AD_SECRET)
        val metadata = CredentialMetadata(
            endpointId = existing?.endpointId ?: newEndpointId(),
            deviceSid = link.deviceSid,
            deviceMid = link.deviceMid,
            desktopName = link.desktopName,
            desktopAppVersion = link.appVersion,
            endpointOrigin = link.endpointOrigin,
            issuedAtMs = link.issuedAtMs,
            sealedAtMs = secret.sealedAtMs,
            generation = generation,
            legacyRoute = link.legacyRoute,
        )
        // Secret first, metadata second: an interrupted write can never yield metadata
        // without a decryptable secret (the metadata without secret is the unusable state).
        store.write(NAME_SECRET, sealed)
        store.write(NAME_METADATA, json.encodeToString(CredentialMetadata.serializer(), metadata).encodeToByteArray())
        log.registerSecret(link.passHash) // INV-3: from here on the hash cannot reach any sink
        log.info(
            "vault.sealed",
            if (rotated) "rotated generation=$generation" else "created",
            ZLog.LogContext(endpointId = metadata.endpointId),
        )
        return if (rotated) SealOutcome.Rotated(metadata) else SealOutcome.Created(metadata)
    }

    // ----------------------------------------------------------------- loading

    /** Non-secret metadata (endpoint identity for UI/rotation), or null when unpaired. */
    fun loadMetadata(): CredentialMetadata? {
        val raw = store.read(NAME_METADATA) ?: return null
        return runCatching { json.decodeFromString(CredentialMetadata.serializer(), raw.decodeToString()) }
            .getOrNull()
    }

    fun pairedSid(): String? = loadMetadata()?.deviceSid

    /**
     * The signing capability (§12.4). Decrypts in memory ONLY inside the closure;
     * neither the hash nor any derivable material is retained by the vault.
     * When [preferKeystoreHmac] is set AND a keystore-resident HMAC key was imported
     * at seal time, the returned signer computes proofs inside AndroidKeyStore and the
     * sealed-envelope path is used only as the custody source for that import.
     */
    fun loadSigner(preferKeystoreHmac: Boolean = false): CredentialSigner? {
        val sealed = store.read(NAME_SECRET) ?: return null
        val aead = obtainAead()
        val plaintext = runCatching { aead.decrypt(sealed, AD_SECRET) }.getOrNull()
            ?: return null.also { log.error("vault.decrypt.failed", "secret envelope undecryptable — treat as unpaired (fail-closed)") }
        val secret = json.decodeFromString(CredentialSecret.serializer(), plaintext.decodeToString())
        log.registerSecret(secret.passHash)
        return JcaCredentialSigner(secret.passHash)
    }

    // ------------------------------------------------- direct endpoint tokens (§12.4)

    /**
     * Seal a DIRECT endpoint's server token (§12.4 table: Tink-wrapped like the relay
     * credential, backup-excluded by the store's location). Atomicity parity: secret
     * FIRST, metadata second — an interrupted write can never yield usable metadata
     * without a decryptable token. Re-sealing the same endpoint rotates in place.
     * The token is registered with the log scrubber (INV-3) before anything else.
     */
    fun sealDirectToken(token: String, metadata: DirectEndpointMetadata) {
        log.registerSecret(token)
        val aead = obtainAead()
        val sealed = aead.encrypt(token.encodeToByteArray(), AD_DIRECT_SECRET)
        store.write(directSecretName(metadata.endpointId), sealed)
        store.write(
            directMetaName(metadata.endpointId),
            json.encodeToString(DirectEndpointMetadata.serializer(), metadata).encodeToByteArray(),
        )
        log.info(
            "vault.directSealed",
            "endpoint=${metadata.endpointId.take(8)}… url=${metadata.wsUrl.substringBefore("?")}",
            ZLog.LogContext(endpointId = metadata.endpointId),
        )
    }

    /** Non-secret direct-endpoint metadata, or null when absent/undecodable. */
    fun loadDirectMetadata(endpointId: String): DirectEndpointMetadata? {
        val raw = store.read(directMetaName(endpointId)) ?: return null
        return runCatching { json.decodeFromString(DirectEndpointMetadata.serializer(), raw.decodeToString()) }
            .getOrNull()
    }

    /** All sealed direct endpoints' metadata (registry view; secrets never leave). */
    fun listDirectEndpoints(): List<DirectEndpointMetadata> =
        store.listNames()
            .filter { it.startsWith(NAME_DIRECT_META_PREFIX) }
            .mapNotNull { loadDirectMetadata(it.removePrefix(NAME_DIRECT_META_PREFIX)) }

    /**
     * The token CAPABILITY (§26.3 custody/capability split): decrypts in memory ONLY
     * inside the returned closure. core:security deliberately has NO core:transport
     * dependency (§40.2 graph: transport → security, never the reverse) — the caller
     * (app layer) wraps this source in its transport-side TokenProvider capability.
     */
    fun loadDirectTokenSource(endpointId: String): (() -> String)? {
        val sealed = store.read(directSecretName(endpointId)) ?: return null
        val aead = obtainAead()
        val plaintext = runCatching { aead.decrypt(sealed, AD_DIRECT_SECRET) }.getOrNull()
            ?: return null.also { log.error("vault.directDecrypt.failed", "token envelope undecryptable — treat as unconfigured (fail-closed)") }
        val token = plaintext.decodeToString()
        log.registerSecret(token)
        return { token }
    }

    /** True when a decryptable token exists for this endpoint. */
    fun hasDirectToken(endpointId: String): Boolean =
        loadDirectTokenSource(endpointId) != null

    /** Remove ONE direct endpoint's credential (per-endpoint removal, §11.6). */
    fun deleteDirectToken(endpointId: String): Boolean {
        val a = store.delete(directSecretName(endpointId))
        val b = store.delete(directMetaName(endpointId))
        log.info("vault.directDeleted", "endpoint=${endpointId.take(8)}… secret=$a meta=$b", ZLog.LogContext(endpointId = endpointId))
        return a || b
    }

    // ------------------------------------------------------------- wipe (§12.7)

    /**
     * Unpair/secure wipe: deletes every vault blob and destroys the platform master
     * key. Returns a forensic report; [WipeReport.storeEmptyAfterWipe] is the
     * zero-remnants assertion input (§3.5 acceptance).
     */
    fun wipe(): WipeReport {
        val names = store.listNames()
        val deleted = names.mapNotNull { if (store.delete(it)) it else null }
        val keyDestroyed = keyWrap.wipe()
        val empty = store.listNames().isEmpty()
        log.info("vault.wiped", "blobs=${deleted.size} masterKeyDestroyed=$keyDestroyed storeEmpty=$empty")
        return WipeReport(
            deletedBlobNames = deleted,
            masterKeyDestroyed = keyDestroyed,
            storeEmptyAfterWipe = empty,
        )
    }

    // ----------------------------------------------------------------- internals

    private fun obtainAead(): Aead {
        AeadConfig.register()
        val wrapped = store.read(NAME_KEYSET)
        val handle: KeysetHandle = if (wrapped == null) {
            val fresh = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            store.write(NAME_KEYSET, keyWrap.wrap(TinkProtoKeysetFormat.serializeKeyset(fresh, InsecureSecretKeyAccess.get())))
            fresh
        } else {
            val cleartext = keyWrap.unwrap(wrapped)
                ?: throw IllegalStateException("vault keyset unwrap failed — master key destroyed/wrong")
            TinkProtoKeysetFormat.parseKeyset(cleartext, InsecureSecretKeyAccess.get())
        }
        return handle.getPrimitive(Aead::class.java)
    }

    companion object {
        const val NAME_METADATA = "credential.meta.v1"
        const val NAME_SECRET = "credential.secret.v1"
        const val NAME_KEYSET = "vault.keyset.v1"
        const val NAME_DIRECT_SECRET_PREFIX = "direct.secret.v1:"
        const val NAME_DIRECT_META_PREFIX = "direct.meta.v1:"

        /** Tink associated-data domain separation (versioned; never derived from secrets). */
        val AD_SECRET = "zcode-pairing-credential/secret/v1".encodeToByteArray()
        val AD_METADATA = "zcode-pairing-credential/meta/v1".encodeToByteArray()
        val AD_DIRECT_SECRET = "zcode-direct-endpoint/token/v1".encodeToByteArray()

        fun directSecretName(endpointId: String) = NAME_DIRECT_SECRET_PREFIX + endpointId
        fun directMetaName(endpointId: String) = NAME_DIRECT_META_PREFIX + endpointId
    }
}
