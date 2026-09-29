package com.zcode.android.app.diagnostics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Round-7b: the persisted diagnostics store — rotate-on-start (previous session
 * preserved), capped files, never-throws append, redacted-line pass-through.
 * INV-3: the store only ever sees pre-redacted lines from the ZLog sink.
 */
class DiagnosticsFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `rotate moves current to previous and starts fresh`() {
        val store = DiagnosticsFileStore.inDir(tmp.newFolder())
        store.append("line-1")
        store.append("line-2")
        store.rotateOnStartup()
        val fresh = store.readCurrent().trim()
        assertTrue(fresh.contains("diagnostics session start"), "current starts with a session header")
        val prev = store.readPrevious()
        assertTrue(prev.contains("line-1") && prev.contains("line-2"), "previous keeps the old session")
    }

    @Test
    fun `append never exceeds the cap - oldest lines dropped`() {
        val store = DiagnosticsFileStore.inDir(tmp.newFolder())
        val big = "x".repeat(1_000)
        repeat(500) { store.append("entry-$it $big") } // ~500 KB + overhead → capped
        val current = store.readCurrent(10L * 1024 * 1024)
        assertTrue(current.length <= 400_000 + 5_000, "file must be capped, was ${current.length}")
        assertTrue(current.contains("entry-499"), "newest entries retained")
    }

    @Test
    fun `readPrevious caps at the requested window`() {
        val store = DiagnosticsFileStore.inDir(tmp.newFolder())
        store.append("first")
        repeat(200) { store.append("filler-$it ${"y".repeat(500)}") }
        store.rotateOnStartup()
        val capped = store.readPrevious(2_000)
        assertTrue(capped.length <= 2_500, "capped read honored, was ${capped.length}")
        assertTrue(!capped.contains("first"), "old lines dropped by the cap")
    }
}
