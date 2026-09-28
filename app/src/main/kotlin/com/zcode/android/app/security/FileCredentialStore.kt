package com.zcode.android.app.security

import android.content.Context
import com.zcode.android.core.security.vault.CredentialStore
import java.io.File

/**
 * Device CredentialStore binding (§12.4): private files under `noBackupFilesDir` —
 * excluded from Android backup by the OS itself, plus the explicit
 * dataExtractionRules. Blobs are Tink ciphertext produced by [com.zcode.android.core.security.vault.CredentialVault];
 * this class stores opaque bytes only.
 *
 * Phase 4.6 formalizes the DataStore-proto envelope schema (ADR-008); until then this
 * binding keeps the exact same port surface, so the migration is a binding swap.
 */
class FileCredentialStore(context: Context) : CredentialStore {
    private val dir: File = File(context.applicationContext.noBackupFilesDir, "vault").apply {
        if (!exists()) mkdirs()
    }

    private fun fileFor(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "invalid blob name" }
        return File(dir, name)
    }

    override fun read(name: String): ByteArray? {
        val f = fileFor(name)
        return if (f.isFile) f.readBytes() else null
    }

    override fun write(name: String, bytes: ByteArray) {
        // Atomic replace: temp file + rename (§12.7 rotation atomicity).
        val f = fileFor(name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "atomic replace failed for $name" }
        }
    }

    override fun delete(name: String): Boolean = fileFor(name).let { if (it.isFile) it.delete() else false }

    override fun listNames(): List<String> =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { it.name } ?: emptyList()

    override fun totalBytes(): Long = listNames().sumOf { fileFor(it).length() }
}
