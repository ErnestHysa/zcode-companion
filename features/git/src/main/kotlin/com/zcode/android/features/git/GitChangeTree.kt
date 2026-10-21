package com.zcode.android.features.git

import com.zcode.android.domain.model.GitChangeEntry

/**
 * J1.2 (Phase 6C): the change list's tree shape + search — the desktop's
 * `git.tree.*` surface, gated to the sizes where a tree beats a flat list (the
 * desktop's own large-repo motivation). The builder is pure: directories nest from
 * `repoRelativePath` segments; [build] returns the SYNTHETIC ROOT (blank name) whose
 * files are the root-level entries and whose dirs are the top-level directories,
 * entries keeping their desktop order within each directory.
 */
object GitChangeTree {

    /** Sections larger than this render as a collapsible directory tree. */
    const val TREE_GATE = 40

    /** Sections larger than this offer the search field. */
    const val SEARCH_GATE = 10

    data class Dir(
        val name: String,
        /** The path from the repo root (e.g. `src/main`); blank for the root. */
        val path: String,
        val dirs: List<Dir>,
        val files: List<GitChangeEntry>,
    )

    fun build(entries: List<GitChangeEntry>): Dir {
        class MutableDir(val name: String, val path: String) {
            val dirs = LinkedHashMap<String, MutableDir>()
            val files = ArrayList<GitChangeEntry>()
        }

        val root = MutableDir("", "")
        for (entry in entries) {
            val rel = entry.repoRelativePath.ifBlank { entry.path }
            val segments = rel.split('/').filter { it.isNotEmpty() }
            when (segments.size) {
                0, 1 -> root.files.add(entry)
                else -> {
                    var current = root
                    var path = ""
                    for (segment in segments.dropLast(1)) {
                        path = if (path.isEmpty()) segment else "$path/$segment"
                        current = current.dirs.getOrPut(segment) { MutableDir(segment, path) }
                    }
                    current.files.add(entry)
                }
            }
        }

        fun fold(dir: MutableDir): Dir = Dir(
            name = dir.name,
            path = dir.path,
            dirs = dir.dirs.values.map(::fold),
            files = dir.files.toList(),
        )
        return fold(root)
    }

    fun search(entries: List<GitChangeEntry>, query: String): List<GitChangeEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.repoRelativePath.contains(q, ignoreCase = true) || it.path.contains(q, ignoreCase = true)
        }
    }
}
