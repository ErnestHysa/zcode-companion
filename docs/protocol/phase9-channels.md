# Phase 9 Service Channels — terminal / git / file / model-selection

Evidence class: `[VERIFIED]` = read directly from the named source. Sources:
reference clone `zcode/repo` @ `29628c9` (read-only), read 2026-10-03.
Scope: the §13.3-classified Phase 9 channel subset. Everything here is
**read-only on the phone** — INV-9 (typed clients expose NO write methods) and
INV-16 (desktop paths are display-only; the client passes protocol-returned
paths through, never constructs/joins desktop paths).

Wire mechanics: every surface rides the same binary Channel protocol and the
relay rpc-frame envelope as `zcode-agent` (§7 of v4-wire.md): promise-call args
travel ARRAY-wrapped (`RpcValue.Arr([...])`, Phase 1 live finding 5); dynamic
event listens (`onX(arg)`) pass their single argument RAW
(proxy-channel.ts:127 `channel.listen(propKey, arg)` — confirmed against
`onDynamicConversationFrame` in production).

## 1. `terminal` — ITerminalService (packages/services/src/terminal/terminal.ts) `[VERIFIED]`

```ts
create(params: { cols: number; rows: number; cwd?: string }): Promise<{
  id, shell, fontFamily, fontSize?, theme?, fontFamilySource, windowsPty? }>
write(params: { id, data }): Promise<void>          // PHONE NEVER CALLS (INV-9)
resize(params: { id, cols, rows }): Promise<void>   // PHONE NEVER CALLS (view-only)
dispose(params: { id }): Promise<void>
onDynamicData(id: string): Event<string>            // dynamic event; listen arg = id (RAW)
onDynamicExit(id: number…id: string): Event<number> // dynamic event; listen arg = id (RAW)
```

Server-side listen semantics verified (terminalService.ts:427): the listen
argument is the terminal id; events fire per-terminal. View-only product rules
(§21): the app may `create` (the shell lifecycle stays desktop-owned) and
`dispose` ONLY — `write`/`resize` are absent from the Kotlin client type by
construction (CI API-surface check). Keyboard input is never sent. Gating:
(1) `HostCapabilities.localTerminal === true` (hello, §1 of v4-wire.md);
(2) explicit per-install user opt-in (Settings toggle, §21 security: terminal =
arbitrary shell → never enabled silently); (3) authorization check at attach —
if the endpoint refuses the channel (`ChannelError`/fault) or the capability
bit is absent, the feature renders its disabled/fallback state (§13.3:
"Disabled; never enabled silently"). `[REQUIRES VALIDATION]` whether the
production relay authorizes `terminal` for a `web-remote-replayable` phone
connection — live verification rides the Phase-12 manual canary; MockZCode
implements the verified surface with a scriptable refusal scenario.

## 2. `git` — IGitService read-only subset (packages/services/src/git/git.ts) `[VERIFIED]`

Phone-exposed methods (a strict subset; stage/unstage/discard/commit/push/
switchBranch/createBranchAndSwitch/generateCommitMessage are NOT in the Kotlin
client type — INV-9):

| Method | Params → Result (shared/src/git.ts) |
|---|---|
| `getRepositorySummary` | `{workspacePath}` → `GitRepositorySummary {repoRoot, branchName?, trackingBranchName?, headRefType: branch\|detached, ahead, behind, isDirty, isGitAvailable, isRepository, …}` |
| `getChanges` | `{workspacePath, sourceId: "unstaged"\|"staged"}` → `GitFileChange[] {path, repoRelativePath, workspaceRelativePath, kind: modified\|added\|deleted\|renamed, section: staged\|unstaged\|untracked\|conflicted, added, removed, isStaged, isUntracked, isConflicted}` |
| `getDiff` | `{workspacePath, path, staged?, sourceId?}` → `GitDiffResult {path, availability: patch\|binary\|truncated\|unavailable, patch: string\|null, beforeContent, afterContent, summary?}` — `patch` is unified-diff TEXT → the §22 parser's input plane |
| `getCommitGraph` | `{workspacePath, maxCount?, skip?}` → `{commits: [{hash, parents[], refs: [{name, kind}], subject, authorName?, authoredAtMs?}], hasMore}` |

## 3. `file` — IFileService read-only subset (packages/services/src/file/file.ts) `[VERIFIED]`

Phone-exposed methods (createScratchWorkspace / writeWorkspaceFileSearchIgnore /
applyWorkspaceFileSearchIgnoreTransform are NOT in the Kotlin client type —
INV-9; §23.4 read-only scope):

| Method | Params → Result (shared/src/protocol.ts) |
|---|---|
| `readdir` | `{path, includeHidden?}` → `FileEntry[] {name, path, type: file\|directory, isSymbolicLink?}` (old servers may omit isSymbolicLink → false) |
| `stat` | `{path}` → `FileStat {path, type, size?, mtimeMs?}` (size may be absent) |
| `readTextFile` | `{path, offset?, length?}` → `FileTextSlice {path, content, offset, bytesRead, totalBytes, truncated, isBinary}` |
| `readFileRange` | `{path, offset, length}` → top-level `Uint8Array` (raw bytes — RPC binary fast path; EOF returns short) |
| `readBinaryPreview` | `{path, maxBytes?}` → `FileBinaryPreview {path, dataBase64, totalBytes}` |
| `readMediaPreview` | `{path, maxBytes?}` → `FileMediaPreview {path, mediaType, dataBase64, totalBytes}` |
| `searchWorkspaceFiles` | `{rootPath, workspaceIdentity?, query, limit?, refresh?}` → `WorkspaceFileEntry[]` |

Download-to-device (§23.4) uses `readFileRange` chunking into app cache +
FileProvider share — the phone never writes desktop paths.

## 4. `model-selection` — IModelSelectionService (providerFacadeServices.ts:95) `[VERIFIED]`

`getView(input?) → ModelSelectionView {revision, providers: ModelSelectionProviderView[]
{providerId, providerName, templateId, models: [{modelId, config}]},
preferredSelection?, effectiveSelection?, selectionIssue?}`; `onDidChange`
event exists (untapped by the phone in Phase 9 — view is fetched per picker
open). R-classified (§13.3): the phone lists options for the picker; switching
happens ONLY via the v4 `switchModelConfig` command (§10.3 of v4-wire.md).

## 5. Client decode policy

Same graded INV-8 policy as the v4 planes: known method results decode with
required-field validation → typed fault on violation (caller surfaces a safe
failure, never a lenient interpretation); unknown/extra fields ride the raw
JSON; tolerant enums for closed wire sets (`GitChangeKind`, `GitChangeSectionId`,
`availability`, `headRefType`). Method refusal (channel error / unknown
channel) degrades the FEATURE (§13.3 fallback rows), never the connection
(§39.0 layered compatibility: service-layer incompatibility ≠ transport
failure).
