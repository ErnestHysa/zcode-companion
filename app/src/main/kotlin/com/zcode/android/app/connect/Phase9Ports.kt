package com.zcode.android.app.connect

import kotlinx.serialization.json.JsonObject
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.Phase9Tools
import com.zcode.android.data.session.TerminalController
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.domain.model.WorkspaceFileEntry
import com.zcode.android.domain.model.WorkspaceTextSlice
import com.zcode.android.domain.repo.PreferencesRepository
import com.zcode.android.features.files.port.FilesPort
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.terminal.port.TerminalPort
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 9 app-side port bindings (INV-2/INV-6: features see ONLY these — never
 * the transport). Each is a thin adapter over the hub-owned tool plane; the
 * §13.3 fallback semantics (channel refused → feature-disabled state) live HERE.
 */

// ---------------------------------------------------------------- terminal (§21)

class DefaultTerminalPort(
    private val controller: TerminalController,
    private val preferences: PreferencesRepository,
    private val log: com.zcode.android.core.common.log.ZLog,
) : TerminalPort {

    private val _optIn = MutableStateFlow(false)
    override val optIn: StateFlow<Boolean> = _optIn.asStateFlow()

    init {
        // Opt-in is persisted app-wide (single endpoint until Phase 10's registry).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
            preferences.observe().collect { _optIn.value = it.terminalOptIn }
        }
    }

    override suspend fun setOptIn(enabled: Boolean) {
        preferences.update { it.copy(terminalOptIn = enabled) }
        log.info("terminal.optIn", "enabled=$enabled")
        if (!enabled) detach()
    }

    override val state: StateFlow<TerminalPort.TerminalState> =
        MutableStateFlow<TerminalPort.TerminalState>(TerminalPort.TerminalState.OptInRequired).also { flow ->
            // Mirror the controller state; OptInRequired wins until opted in.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
                controller.state.collect { s ->
                    flow.value = if (!_optIn.value && s != TerminalController.State.Idle) {
                        TerminalPort.TerminalState.OptInRequired
                    } else {
                        mapState(s)
                    }
                }
            }
        }

    private fun mapState(s: TerminalController.State): TerminalPort.TerminalState = when (s) {
        TerminalController.State.Idle -> TerminalPort.TerminalState.Idle
        TerminalController.State.Attaching -> TerminalPort.TerminalState.Attaching
        is TerminalController.State.Live -> TerminalPort.TerminalState.Live(s.shell)
        is TerminalController.State.Exited -> TerminalPort.TerminalState.Exited(s.code)
        is TerminalController.State.Refused -> TerminalPort.TerminalState.Refused(s.reason)
        TerminalController.State.CapabilityOff -> TerminalPort.TerminalState.CapabilityOff
    }

    override val snapshot: StateFlow<TerminalPort.TerminalSnapshot> =
        MutableStateFlow(TerminalPort.TerminalSnapshot(emptyList(), emptyList(), 0, 0)).also { flow ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
                controller.snapshot.collect { s ->
                    flow.value = TerminalPort.TerminalSnapshot(s.lines, s.plainText, s.ringBytes, s.droppedBytes)
                }
            }
        }

    override suspend fun attach(): TerminalPort.TerminalState {
        if (!_optIn.value) return TerminalPort.TerminalState.OptInRequired
        return mapState(controller.attach())
    }

    override fun detach() = controller.detach()

    override fun search(query: String): List<Int> = controller.search(query)
}

// ---------------------------------------------------------------- git (§9.4)

class DefaultGitPort(
    private val hub: ConnectionHub,
) : GitPort {

    override suspend fun summary(): GitSummary? = run(
        "git.summary",
    ) { tools, wp, wi -> tools.gitSummary(wp) }

    override suspend fun changes(sourceId: String): List<GitChangeEntry> =
        run("git.changes") { tools, wp, _ -> tools.gitChanges(wp, sourceId) } ?: emptyList()

    override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = run("git.diff") { tools, wp, _ ->
        tools.gitDiff(wp, path, staged)
    }

    override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage? {
        val boundedMax = maxCount.coerceIn(1, 200)
        val boundedSkip = skip.coerceAtLeast(0)
        val result = run("git.commits") { tools, wp, _ -> tools.gitCommits(wp, boundedMax, boundedSkip) }
        return result?.let { GitPort.CommitsPage(it.first, it.second) }
    }

    override fun workspacePath(): String? = hub.activeWorkspaceTarget()?.first

    // ---------------------------------------------------------------- Phase 5B (A3.11): checkpoints

    /** The per-transport checkpoint client (the git-checkpoint channel trio). */
    private fun checkpoints() = hub.checkpoints()

    override suspend fun createCheckpoint(): com.zcode.android.domain.model.CheckpointMeta? {
        val wp = workspacePath() ?: return null
        return try {
            com.zcode.android.domain.model.CheckpointMeta.decode(checkpoints().createCheckpoint(wp))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("gitport.checkpointCreate", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
    }

    override suspend fun diffCheckpoints(fromCheckpointId: String, toCheckpointId: String): com.zcode.android.domain.model.CheckpointDiff? {
        val wp = workspacePath() ?: return null
        return try {
            com.zcode.android.domain.model.CheckpointDiff.decode(checkpoints().diffCheckpoints(wp, fromCheckpointId, toCheckpointId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("gitport.checkpointDiff", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
    }

    override suspend fun restoreCheckpoints(fromCheckpointId: String, toCheckpointId: String, force: Boolean): com.zcode.android.domain.model.CheckpointRestore? {
        val wp = workspacePath() ?: return null
        return try {
            com.zcode.android.domain.model.CheckpointRestore.decode(checkpoints().restoreBetweenCheckpoints(wp, fromCheckpointId, toCheckpointId, force))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("gitport.checkpointRestore", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
    }

    // ---------------------------------------------------------------- Phase 5D (ADR-021): the write tier

    private suspend fun <T> writeOp(tag: String, block: suspend (wp: String) -> T): T? {
        val wp = workspacePath() ?: return null
        return try {
            block(wp)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("gitport.$tag", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
    }

    override suspend fun stagePaths(paths: List<String>): Boolean =
        writeOp("stage") { hub.activeTransport().gitStagePaths(it, paths); true } ?: false

    override suspend fun unstagePaths(paths: List<String>): Boolean =
        writeOp("unstage") { hub.activeTransport().gitUnstagePaths(it, paths); true } ?: false

    override suspend fun discardPaths(paths: List<String>, staged: Boolean): Boolean =
        writeOp("discard") { hub.activeTransport().gitDiscardPaths(it, paths, staged); true } ?: false

    override suspend fun commit(message: String, stagedOnly: Boolean): com.zcode.android.domain.model.GitCommitResult? =
        writeOp("commit") { com.zcode.android.domain.model.GitCommitResult.decode(hub.activeTransport().gitCommit(it, message, null, stagedOnly)) }

    override suspend fun generateCommitMessage(includeUnstaged: Boolean): String? =
        writeOp("genCommit") { o ->
            val obj = hub.activeTransport().gitGenerateCommitMessage(o, includeUnstaged)
            obj.rpStr("message") ?: obj.rpStr("commitMessage")
        }

    override suspend fun push(): com.zcode.android.domain.model.GitPushResult? =
        writeOp("push") { com.zcode.android.domain.model.GitPushResult.decode(hub.activeTransport().gitPush(it)) }

    override suspend fun switchBranch(targetBranchName: String): com.zcode.android.domain.model.GitBranchResult? =
        writeOp("switchBranch") { com.zcode.android.domain.model.GitBranchResult.decode(hub.activeTransport().gitSwitchBranch(it, targetBranchName)) }

    override suspend fun createBranchAndSwitch(branchName: String): com.zcode.android.domain.model.GitBranchResult? =
        writeOp("createBranch") { com.zcode.android.domain.model.GitBranchResult.decode(hub.activeTransport().gitCreateBranchAndSwitch(it, branchName, null)) }

    override suspend fun localBranches(): com.zcode.android.domain.model.GitBranchList? =
        writeOp("branches") { com.zcode.android.domain.model.GitBranchList.decode(hub.activeTransport().gitLocalBranches(it)) }

    override suspend fun identity(): com.zcode.android.domain.model.GitIdentity? =
        writeOp("identity") { com.zcode.android.domain.model.GitIdentity.decode(hub.activeTransport().gitIdentity(it)) }

    private fun JsonObject.rpStr(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.takeIf { it.isNotBlank() }

    /** Channel-refusal wrapper: ANY failure → null (the §13.3 disabled state). */
    private suspend fun <T> run(op: String, block: suspend (Phase9Tools, String, String?) -> T): T? {
        val target = hub.activeWorkspaceTarget() ?: return null
        val workspacePath = target.first ?: return null
        return try {
            block(hub.phase9(), workspacePath, target.second)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            hub.log.warn("gitport.$op", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
    }
}

// ---------------------------------------------------------------- files (§23.4)

class DefaultFilesPort(
    private val hub: ConnectionHub,
    private val cacheDir: File,
    /** App-provided share (FileProvider + ACTION_SEND); null = download w/o share. */
    private val onShare: ((File, String) -> Unit)? = null,
) : FilesPort {

    override fun workspaceRoot(): String? = hub.activeWorkspaceTarget()?.first

    override suspend fun list(path: String): FilesPort.ListingResult = run("files.list") { tools ->
        FilesPort.ListingResult(tools.fileEntries(path), null)
    } ?: FilesPort.ListingResult(emptyList(), "workspace files unavailable on this connection")

    override suspend fun readText(path: String, offset: Long?, length: Long?): FilesPort.TextResult =
        run("files.readText") { tools ->
            FilesPort.TextResult(tools.fileText(path, offset, length), null)
        } ?: FilesPort.TextResult(null, "file read unavailable on this connection")

    override suspend fun binaryPreview(path: String, maxBytes: Long): FilesPort.BinaryPreviewResult =
        run("files.binaryPreview") { tools ->
            val o = tools.fileBinaryPreview(path, maxBytes)
            fun str(k: String): String? = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val b64 = str("dataBase64")
            FilesPort.BinaryPreviewResult(
                bytes = b64?.let { java.util.Base64.getDecoder().decode(it) },
                mediaType = str("mediaType"),
                totalBytes = (o["totalBytes"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull(),
                error = null,
            )
        } ?: FilesPort.BinaryPreviewResult(null, null, null, "preview unavailable on this connection")

    override suspend fun download(path: String, maxBytes: Long): FilesPort.DownloadResult {
        val tools = hub.phase9()
        val safeName = path.substringAfterLast('/').ifBlank { "download.bin" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(cacheDir, safeName)
        return try {
            var offset = 0L
            var total = 0L
            val chunk = 512L * 1024
            while (true) {
                val bytes = tools.fileBytes(path, offset, chunk)
                if (bytes.isEmpty()) break
                if (total + bytes.size > maxBytes) {
                    out.delete()
                    return FilesPort.DownloadResult(null, total, "file exceeds the ${maxBytes / (1024 * 1024)} MiB download cap")
                }
                if (offset == 0L) out.writeBytes(bytes) else out.appendBytes(bytes)
                total += bytes.size
                offset += bytes.size
                if (bytes.size < chunk) break
            }
            // Best-effort MIME by extension (display/share hint only).
            val mime = when (out.extension.lowercase()) {
                "txt", "md", "kt", "java", "ts", "js", "json", "yaml", "yml", "toml", "gradle", "kts", "sh" -> "text/plain"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
            onShare?.invoke(out, mime)
            FilesPort.DownloadResult(out, total, null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            out.delete()
            hub.log.warn("filesport.download", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            FilesPort.DownloadResult(null, 0, e.message ?: "download failed")
        }
    }

    private suspend fun <T> run(op: String, block: suspend (Phase9Tools) -> T): T? {
        if (hub.activeWorkspaceTarget()?.first == null) return null
        return try {
            block(hub.phase9())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            hub.log.warn("$op", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
    }
}
