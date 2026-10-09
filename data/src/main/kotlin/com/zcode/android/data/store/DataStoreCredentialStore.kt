package com.zcode.android.data.store

import com.zcode.android.core.security.vault.CredentialStore
import com.zcode.android.data.proto.CredentialEntryProto
import com.zcode.android.data.proto.CredentialEnvelopeProto
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * DataStore-backed [CredentialStore] binding (§12.4: "passHash … DataStore file
 * (proto) value field, Tink AES-GCM"). The §43.2-4.6 deliverable formalizes the
 * proto envelope schema (ADR-008); the port surface is EXACTLY the Phase-3 blob port,
 * so this is a binding swap (see app/FileCredentialStore note).
 *
 * Bytes stored are OPAQUE Tink ciphertext — this class never sees plaintext secret
 * material (INV-3). The synchronous CredentialStore port is bridged with runBlocking:
 * envelopes are tiny, DataStore caches after first read, and callers are vault
 * internals (never UI/jank paths).
 *
 * §32.3: a corrupted envelope file fails closed (CorruptionException) — never
 * reinitialized, never destructive (the credential class has no destructive-migration
 * fallback).
 */
class DataStoreCredentialStore(
    private val dataStore: DataStore<CredentialEnvelopeProto>,
) : CredentialStore {

    override fun read(name: String): ByteArray? = runBlocking {
        dataStore.data.first().entriesList.firstOrNull { it.name == name }?.blob?.toByteArray()
    }
    override fun write(name: String, bytes: ByteArray) {
        runBlocking {
            dataStore.updateData { env ->
                val others = env.entriesList.filter { it.name != name }
                env.toBuilder()
                    .clearEntries()
                    .addAllEntries(others)
                    .addEntries(CredentialEntryProto.newBuilder().setName(name).setBlob(com.google.protobuf.ByteString.copyFrom(bytes)))
                    .setSchemaVersion(SCHEMA_VERSION)
                    .build()
            }
        }
    }

    override fun delete(name: String): Boolean = runBlocking {
        var removed = false
        dataStore.updateData { env ->
            val others = env.entriesList.filter { it.name != name }
            removed = others.size != env.entriesCount
            env.toBuilder().clearEntries().addAllEntries(others).build()
        }
        removed
    }

    override fun listNames(): List<String> = runBlocking {
        dataStore.data.first().entriesList.map { it.name }
    }

    override fun totalBytes(): Long = runBlocking {
        dataStore.data.first().entriesList.sumOf { it.blob.size().toLong() }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
