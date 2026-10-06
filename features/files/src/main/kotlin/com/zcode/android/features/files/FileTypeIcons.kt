package com.zcode.android.features.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale
import com.zcode.android.features.files.R

/**
 * GAP-15: file-kind classification for the workspace browser, ported from the
 * desktop's fileDisplayHelpers resolveIconName families (filename aliases →
 * extension families → default document; dirs are always "folder").
 */
enum class FileKind {
    FOLDER, IMAGE, VIDEO, AUDIO, CODE, DATA, DOCUMENT, ARCHIVE, DATABASE, TERMINAL, CONFIG,
}

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico")
private val VIDEO_EXT = setOf("mp4", "mov", "mkv", "avi", "webm", "m4v")
private val AUDIO_EXT = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac")
private val CODE_EXT = setOf("kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "php", "scala")
private val DATA_EXT = setOf("json", "yaml", "yml", "xml", "csv", "properties", "ini")
private val ARCHIVE_EXT = setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar", "jar")
private val DATABASE_EXT = setOf("sql", "db", "sqlite", "sqlite3")
private val TERMINAL_EXT = setOf("sh", "bash", "zsh", "fish", "bat", "cmd", "ps1")
private val CONFIG_EXT = setOf("conf", "cfg", "env")
private val DOCUMENT_EXT = setOf("md", "txt", "rst", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx")

fun fileKind(name: String, isDirectory: Boolean = false): FileKind {
    if (isDirectory) return FileKind.FOLDER
    val lower = name.lowercase(Locale.ROOT)
    // Filename aliases first (desktop resolveIconName: env/config/docker/lock/makefile).
    if (lower.startsWith(".env") || lower.startsWith("dockerfile") ||
        lower.startsWith("docker-compose") || lower.contains("config") ||
        lower.endsWith(".lock") || lower == "makefile" || lower.endsWith(".gitignore") ||
        lower.startsWith(".git")
    ) {
        return FileKind.CONFIG
    }
    if ('.' !in lower) return FileKind.DOCUMENT
    return when (val ext = lower.substringAfterLast('.')) {
        in IMAGE_EXT -> FileKind.IMAGE
        in VIDEO_EXT -> FileKind.VIDEO
        in AUDIO_EXT -> FileKind.AUDIO
        in CODE_EXT -> FileKind.CODE
        in DATA_EXT -> FileKind.DATA
        in ARCHIVE_EXT -> FileKind.ARCHIVE
        in DATABASE_EXT -> FileKind.DATABASE
        in TERMINAL_EXT -> FileKind.TERMINAL
        in CONFIG_EXT -> FileKind.CONFIG
        in DOCUMENT_EXT -> FileKind.DOCUMENT
        else -> {
            // "tar.gz"-style compound suffixes collapse to their last family.
            if (ext == "tar" || ext == "tgz") FileKind.ARCHIVE else FileKind.DOCUMENT
        }
    }
}

/** GAP-15 a11y label per kind (§31: words from resources). */
fun fileKindLabelResource(kind: FileKind): Int = when (kind) {
    FileKind.FOLDER -> R.string.file_kind_folder
    FileKind.IMAGE -> R.string.file_kind_image
    FileKind.VIDEO -> R.string.file_kind_video
    FileKind.AUDIO -> R.string.file_kind_audio
    FileKind.CODE -> R.string.file_kind_code
    FileKind.DATA -> R.string.file_kind_data
    FileKind.DOCUMENT -> R.string.file_kind_document
    FileKind.ARCHIVE -> R.string.file_kind_archive
    FileKind.DATABASE -> R.string.file_kind_database
    FileKind.TERMINAL -> R.string.file_kind_terminal
    FileKind.CONFIG -> R.string.file_kind_config
}

@androidx.compose.runtime.Composable
fun fileKindLabel(kind: FileKind): String = androidx.compose.ui.res.stringResource(fileKindLabelResource(kind))

fun fileIconVector(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Filled.Folder
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.Videocam
    FileKind.AUDIO -> Icons.Filled.Audiotrack
    FileKind.CODE -> Icons.Filled.Code
    FileKind.DATA -> Icons.Filled.DataObject
    FileKind.DOCUMENT -> Icons.Filled.Description
    FileKind.ARCHIVE -> Icons.Filled.FolderZip
    FileKind.DATABASE -> Icons.Filled.Storage
    FileKind.TERMINAL -> Icons.Filled.Terminal
    FileKind.CONFIG -> Icons.Filled.Settings
}
