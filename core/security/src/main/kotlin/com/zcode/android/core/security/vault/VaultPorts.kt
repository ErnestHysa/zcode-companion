package com.zcode.android.core.security.vault

/**
 * CredentialStore port — blob persistence under stable names (§12.4: the credential
 * envelope lives in a DataStore-backed store on device, Phase 4.6 formalizes the proto
 * schema; until then the app binds this port to a private-file store inside
 * `noBackupFilesDir`, which Android backup rules already exclude — §12.4/§26.2-T6).
 *
 * Implementations MUST store bytes opaquely; no component outside core:security may
 * read the store (INV-2/credential isolation). `listNames` exists for wipe-completeness
 * forensics (§3.5) — names only, never values.
 */
interface CredentialStore {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String): Boolean
    fun listNames(): List<String>
    /** Remaining byte footprint across all blobs (diagnostics/forensics). */
    fun totalBytes(): Long
}

/**
 * MasterKeyWrap port — platform key custody for the Tink keyset (§12.4: "Tink AES-GCM,
 * key wrapped by Android Keystore AES key (StrongBox if available)"). The device
 * implementation holds an AndroidKeyStore AES-256-GCM key; test implementations use a
 * software key. `wipe()` destroys the platform key material where the platform allows.
 */
interface MasterKeyWrap {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray?
    /** Destroy the master key. Returns true when a key existed and was destroyed. */
    fun wipe(): Boolean
}
