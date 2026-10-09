package com.zcode.android.core.common.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** §22 parser fixtures — huge/binary/rename/new/delete per Phase 9.1 (§43.2-9.1). */
class UnifiedDiffParserTest {

    @Test
    fun `parses simple modification`() {
        val patch = """
            diff --git a/src/Main.kt b/src/Main.kt
            index 1234567..89abcde 100644
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -1,4 +1,5 @@ package header
             fun main() {
            -    println("old")
            +    println("new")
            +    println("extra")
             }
        """.trimIndent()
        val files = UnifiedDiffParser.parse(patch)
        assertEquals(1, files.size)
        val f = files[0]
        assertEquals("src/Main.kt", f.path)
        assertFalse(f.isNew); assertFalse(f.isDeleted); assertFalse(f.binary)
        assertEquals(1, f.hunks.size)
        val h = f.hunks[0]
        assertEquals(1, h.oldStart); assertEquals(4, h.oldLines)
        assertEquals(1, h.newStart); assertEquals(5, h.newLines)
        assertEquals(2, f.addedLines); assertEquals(1, f.removedLines)
        val types = h.lines.map { it.type }
        assertEquals(listOf(DiffLineType.CONTEXT, DiffLineType.DEL, DiffLineType.ADD, DiffLineType.ADD, DiffLineType.CONTEXT), types)
        // Line numbering: context at 1/1, del at old 2, adds at new 2 and 3, context at old 3/new 4.
        assertEquals(1, h.lines[0].oldNo); assertEquals(1, h.lines[0].newNo)
        assertEquals(2, h.lines[1].oldNo); assertNull(h.lines[1].newNo)
        assertNull(h.lines[2].oldNo); assertEquals(2, h.lines[2].newNo)
        assertNull(h.lines[3].oldNo); assertEquals(3, h.lines[3].newNo)
        assertEquals(3, h.lines[4].oldNo); assertEquals(4, h.lines[4].newNo)
    }

    @Test
    fun `parses new file`() {
        val patch = """
            diff --git a/new.txt b/new.txt
            new file mode 100644
            index 0000000..789abcd
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
        """.trimIndent()
        val files = UnifiedDiffParser.parse(patch)
        val f = files.single()
        assertTrue(f.isNew)
        assertEquals("new.txt", f.path)
        assertEquals(2, f.addedLines)
        assertEquals(0, f.removedLines)
    }

    @Test
    fun `parses deleted file`() {
        val patch = """
            diff --git a/gone.txt b/gone.txt
            deleted file mode 100644
            index 789abcd..0000000
            --- a/gone.txt
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -bye
        """.trimIndent()
        val f = UnifiedDiffParser.parse(patch).single()
        assertTrue(f.isDeleted)
        assertEquals(1, f.removedLines)
    }

    @Test
    fun `parses rename`() {
        val patch = """
            diff --git a/old-name.kt b/new-name.kt
            similarity index 95%
            rename from old-name.kt
            rename to new-name.kt
            @@ -1,1 +1,1 @@
            -val a = 1
            +val a = 2
        """.trimIndent()
        val f = UnifiedDiffParser.parse(patch).single()
        assertTrue(f.isRenamed)
        assertEquals("new-name.kt", f.path)
        assertEquals("old-name.kt", f.oldPath)
    }

    @Test
    fun `parses binary marker as placeholder`() {
        val patch = """
            diff --git a/logo.png b/logo.png
            index 1234567..89abcde 100644
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent()
        val f = UnifiedDiffParser.parse(patch).single()
        assertTrue(f.binary)
        assertEquals("logo.png", f.path)
        assertTrue(f.hunks.isEmpty())
    }

    @Test
    fun `parses mode-change-only entry`() {
        val patch = """
            diff --git a/script.sh b/script.sh
            old mode 100644
            new mode 100755
        """.trimIndent()
        val f = UnifiedDiffParser.parse(patch).single()
        assertTrue(f.isModeChangeOnly)
        assertTrue(f.hunks.isEmpty())
    }

    @Test
    fun `multiple files and multiple hunks`() {
        val patch = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1,1 +1,1 @@
            -x
            +y
            @@ -10,1 +10,1 @@
            -p
            +q
            diff --git a/b.txt b/b.txt
            --- a/b.txt
            +++ b/b.txt
            @@ -1,1 +1,1 @@
            -1
            +2
        """.trimIndent()
        val files = UnifiedDiffParser.parse(patch)
        assertEquals(2, files.size)
        assertEquals(2, files[0].hunks.size)
        assertEquals(10, files[0].hunks[1].oldStart)
        assertEquals(1, files[1].hunks.size)
    }

    @Test
    fun `single-line hunk header without comma`() {
        val patch = """
            diff --git a/x b/x
            --- a/x
            +++ b/x
            @@ -2 +2 @@
            -old
            +new
        """.trimIndent()
        val h = UnifiedDiffParser.parse(patch).single().hunks.single()
        assertEquals(2, h.oldStart); assertEquals(1, h.oldLines)
        assertEquals(2, h.newStart); assertEquals(1, h.newLines)
    }

    @Test
    fun `empty line inside hunk body is context`() {
        val patch = """
            diff --git a/x b/x
            --- a/x
            +++ b/x
            @@ -1,2 +1,2 @@
             first
            
             second
        """.trimIndent()
        val h = UnifiedDiffParser.parse(patch).single().hunks.single()
        assertEquals(3, h.lines.size)
        assertEquals(DiffLineType.CONTEXT, h.lines[1].type)
    }

    @Test
    fun `content line looking like header inside hunk is body`() {
        // A removed line that is exactly "---" must not reset file parsing.
        val patch = """
            diff --git a/x b/x
            --- a/x
            +++ b/x
            @@ -1,2 +1,2 @@
            ---- separator
            +++ added separator
        """.trimIndent()
        val f = UnifiedDiffParser.parse(patch).single()
        assertEquals(1, f.hunks.size)
        assertEquals(DiffLineType.DEL, f.hunks[0].lines[0].type)
        assertEquals("--- separator", f.hunks[0].lines[0].content)
        assertEquals(DiffLineType.ADD, f.hunks[0].lines[1].type)
        assertEquals("++ added separator", f.hunks[0].lines[1].content)
    }

    @Test
    fun `hunkFromWire maps pre-parsed hunks`() {
        val h = UnifiedDiffParser.hunkFromWire(
            oldStart = 3, oldLines = 2, newStart = 3, newLines = 3,
            lines = listOf(" context", "-gone", "+here", "+also"),
        )
        assertEquals("@@ -3,2 +3,3 @@", h.header)
        assertEquals(3, h.oldStart); assertEquals(2, h.oldLines)
        assertEquals(3, h.newStart); assertEquals(3, h.newLines)
        assertEquals(DiffLineType.CONTEXT, h.lines[0].type)
        assertEquals(4, h.lines.size)
        assertEquals(2, h.lines.count { it.type == DiffLineType.ADD })
    }

    @Test
    fun `5k-line diff parses well under the open budget`() {
        // §48 U10 acceptance: 5k-line diff ≤ 1 s open (engineering target).
        val sb = StringBuilder()
        sb.append("diff --git a/big.txt b/big.txt\n--- a/big.txt\n+++ b/big.txt\n")
        for (i in 0 until 50) {
            sb.append("@@ -${i * 100 + 1},100 +${i * 100 + 1},100 @@\n")
            repeat(100) { j -> sb.append("-old line $i $j\n+new line $i $j\n") }
        }
        val patch = sb.toString()
        // Generator sanity: ≥ the 10 000 body lines were produced (50 hunks × 200 lines).
        assertTrue(patch.lineSequence().count() >= 10_000)
        val start = System.nanoTime()
        val files = UnifiedDiffParser.parse(patch)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val f = files.single()
        assertEquals(5_000, f.addedLines)
        assertEquals(5_000, f.removedLines)
        assertTrue(elapsedMs < 1_000, "5k-line parse took ${elapsedMs}ms (budget 1000ms)")
    }

    @Test
    fun `hostile input never throws`() {
        // No `diff --git` header → no files; hunk-less garbage → best-effort, never a throw.
        val garbage = "not a diff\n@@ garbage @@\n\u0000\u0001\n--- a\n+++ b\n@@ -x +y @@\nrandom"
        val files = UnifiedDiffParser.parse(garbage)
        assertTrue(files.isEmpty() || files.all { it.path.isNotBlank() || it.hunks.isNotEmpty() })
        assertEquals(0, UnifiedDiffParser.parse("").size)
    }

    @Test
    fun `parse line cap holds (INV-5 bound)`() {
        val sb = StringBuilder()
        repeat(UnifiedDiffParser.MAX_PARSE_LINES + 5_000) { sb.append("+line\n") }
        val files = UnifiedDiffParser.parse(sb.toString())
        val totalLines = files.sumOf { it.hunks.sumOf { h -> h.lines.size } }
        assertTrue(totalLines <= UnifiedDiffParser.MAX_PARSE_LINES, "parsed $totalLines > cap")
    }
}
