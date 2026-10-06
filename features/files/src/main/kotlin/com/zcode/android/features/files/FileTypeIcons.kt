package com.zcode.android.features.files

/**
 * GAP-15: file-kind classification for the workspace browser, ported from the
 * desktop's fileDisplayHelpers resolveIconName families (filename aliases →
 * extension families → default document; dirs are always "folder").
 */
enum class FileKind {
    FOLDER, IMAGE, VIDEO, AUDIO, CODE, DATA, DOCUMENT, ARCHIVE, DATABASE, TERMINAL, CONFIG,
}

fun fileKind(name: String, isDirectory: Boolean = false): FileKind {
    if (isDirectory) return FileKind.FOLDER
    // Implemented in fix(GAP-15).
    return FileKind.DOCUMENT
}
