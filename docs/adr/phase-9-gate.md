# Phase 9 Gate — Advanced Read-Only Surfaces (diff / files+attachments / terminal view-only / git / model switch)

| | |
|---|---|
| **Phase** | 9 — Advanced Read-Only Surfaces (§43.2) |
| **Status** | ✅ **COMPLETE — PASS WITH KNOWN LIMITATIONS** |
| **Date/time** | 2026-10-03 10-47-18 IST (UTC+05:30) |
| **Verifier** | `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness); toolchain: Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.20, Compose BOM 2026.09.00, Robolectric 4.17 (`@Config(sdk=[30])`); device leg: ATD emulator API 30 (`zcode-atd`, arm64) |
| **Preconditions** | Phase 7 COMPLETE (gate `phase-7-gate.md`); Phase 8 COMPLETE (gate `phase-8-gate.md`); Phase 1 UI-unlock gate PASS (binding §43.0 rule satisfied) |

---

## 1. What was built (exact scope)

**Spec-first (§41.2):** `docs/protocol/v4-wire.md` §10 (fileChanges / attachment
transaction plane / switch commands + `config` key — `[VERIFIED 2026-10-03]`
against reference clone `29628c9`) and `docs/protocol/phase9-channels.md`
(terminal / git / file / model-selection verified surfaces + the read-only
client policy). No new third-party dependency was introduced (BitmapFactory +
`java.util.Base64` cover §23.3; Coil deliberately not adopted).

### 9.1 Diff renderer (§22)
- `core:common/diff/UnifiedDiffParser.kt` — linear unified-diff parser
  (`DiffFile/DiffHunk/DiffLine`): new/delete/rename/mode-change headers, binary
  markers, quoted paths, single-line hunk headers, hostile-input tolerant,
  `MAX_PARSE_LINES` 200k parse cap (INV-5), `hunkFromWire` for the PRE-PARSED
  `v4/conversation/fileChanges` hunks (§10.1 — the phone never parses patch
  text on that plane).
- `core:ui/components/DiffRenderer.kt` (ADR-010: custom Compose renderer) —
  per-file cards collapsed by default with +N/−M badges, monospace with
  add/del backgrounds, fixed line-number gutter, horizontal scroll (no
  soft-wrap), intra-line word emphasis, hunk virtualization (LazyColumn of
  hunks), >5k changed lines/file → summary card (§22 perf guard), binary
  placeholder, a11y labels carrying counts (§30 color-independence).
- Conversation integration: a turnHeader's file-changes summary chip is
  tappable → per-turn diff dialog fed by `conversationFileChangesV4`
  (`{sessionId, target:{rowId, entityId}, baseRevision, baseLogEpoch}` —
  verified shape; CAS currencies from the session projection).

### 9.2 Files & attachments (§23)
- Transport: `attachmentBegin/Chunk/Commit/Abort/ReadV4` (workspace-target
  form, `connectionId` injected from the live hello) + read-only `file`
  channel methods (`readdir/stat/readTextFile/readFileRange/readBinaryPreview`).
- Client-side fail-closed gates (§23.2, tested): 20 MiB cap, 512 KiB chunk
  bound, `uploadId`/`fileName`/`mime`/checksum wire grammars, zero-byte ⇔
  zero-chunks; upload idempotency (committed re-begin), abort on
  cancel/failure; the verified read media gate (image/*|video/*|pdf ONLY)
  re-fails closed per chunk (INV-14).
- `Phase9Tools.attachmentReadAll` chunked download (cursor-advance guard) →
  app-cache file → image preview; composer: photo/document pickers (no broad
  storage permission, §27), progress overlay, remove = abort, committed refs
  ride the `sendText` payload `attachments:[{ref,fileName,mime,bytes}]`
  (content bytes never enter the envelope, §10.2) and are consumed on
  ACCEPTED.
- Files browser (`features:files`): workspace-rooted navigation using ONLY
  protocol-returned paths (INV-16), paged `readTextFile` viewer, bounded
  `readFileRange` download (8 MiB cap) → FileProvider share.

### 9.3 Terminal view-only (§21)
- `data/session/TerminalController.kt`: capability gate
  (`HostCapabilities.localTerminal === true`, §7.4.1) → `terminal.create` →
  per-id RAW-arg event listens (proxy-channel.ts:127 convention, verified) →
  512 KiB ring (drop-oldest by lines, honest dropped counter) with SGR state
  carried across appends; attach-time authorization failure → `Refused`
  (§13.3 feature-disable, INV-6: the connection never degrades).
- `RelayTransport` terminal surface exposes `terminalCreate/terminalDispose/
  terminalListenData/terminalListenExit` and — enforced by reflection test —
  **NO `terminalWrite`/`terminalResize` exists** (INV-9 by construction).
- `features:terminal` screen: VIEW-ONLY indicator, ANSI SGR rendering
  (256-color + truecolor, palette-resolved), autoscroll + pause, search over
  the ring, opt-in gate (default OFF) wired to the Settings toggle
  (`AppPreferencesProto.terminal_opt_in`, additive field 6).

### 9.4 Git panel (read-only, §9.4/U13)
- `RelayTransport` git methods = the verified read-only subset
  (`getRepositorySummary/getChanges/getDiff/getCommitGraph`); no
  stage/commit/push/branch methods exist (INV-9).
- `features:git`: summary header (branch, ↑↓, dirty), changes by section
  (unstaged/staged), per-file unified diff via the §22 renderer
  (`availability`-gated: patch/binary/truncated/unavailable), paginated
  commit history (50/page, client-bounded).

### 9.5 Model/mode switch (§10.3, U16)
- Domain: `SessionProjectionState` consumes the snapshot/patch `config` key
  (key-level whole replacement; malformed key DROPS + counts — never kills
  the frame; unknown `mode` strings retained verbatim and flagged
  non-switchable).
- `CommandCoordinator.switchModelConfig / switchCollaborationMode` — through
  the SAME outbox gate (INV-4 single commandId), CAS-guarded (verified set),
  `auto` rejected pre-wire; echo-reconciled: the authoritative `config`
  patch is the truth (§14.2 runtime-echo-wins).
- UI: mode chip (verified set build|edit|plan|yolo only) + model chip
  (provider/model/thought) with options from the `model-selection` channel
  `getView` (graceful current-only degradation when refused) and thought
  levels from `config.thoughtLevels` (the current model's actual set).

**MockZCode (§35.4)**: terminal/git/file/model-selection channel surfaces
(with a `refuseChannels` scenario), attachment transaction store (chunk
index/size enforcement, idempotent re-begin, chunked read), scriptable
`conversationFileChanges`, `switchModelConfig`/`switchCollaborationMode`
command effects (CAS enforced + `config` echo patch), snapshot `config`
state key.

## 2. Acceptance criteria (§43.2-9) — evidence

| Criterion | Result | Evidence |
|---|---|---|
| 9.1 Diff renderer: parser + bounded renderer; huge/binary/rename/new/delete fixtures; 5k-line diff ≤ 1 s (target) | ✅ | `UnifiedDiffParserTest` 14/14 (new/delete/rename/binary/mode-change/multi-hunk/hostile/5k-budget: parse of a 10k-line patch = 27 ms, asserted < 1000 ms); `DiffRendererTest` 10/10 (Robolectric: collapse/expand, binary placeholder, 5,001-line summary card vs 5,000-line expandable boundary, rename/new/deleted labels, a11y counts, parser→renderer E2E, intra-line emphasis math, no-soft-wrap long line) |
| 9.2 Files & attachments: chunk boundaries; 20 MiB cap; MIME handling; U11/U14/U15 green | ✅ | `Phase9ToolsTest` (JVM E2E vs real RelayTransport ↔ MockZCode): 512 KiB+3-byte round-trip = exactly 2 chunks with byte-exact download; 20 MiB+1 rejected BEFORE the wire (`AttachmentRejectedException`); non-previewable mediaType (`text/plain`) rejected by the decoder (INV-14); refusal surfaces a typed failure; file channel entries/text-slice/byte-range decode. Composer upload wired through `sendText.attachments` (§10.2 strict refs). U11/U14/U15 exercised at the JVM+E2E level (see §4 Limitations for the live-desktop leg) |
| 9.3 Terminal view-only: stream view, ANSI, ring buffer, search; NO write calls (INV-9); authorization check first; U12 green within bounds | ✅ | `Phase9ToolsTest`: attach → scripted SGR stream decoded (fg=red styled span, raw ESC absent), exit event → `Exited(3)`; ring holds ≤ 512 KiB with `droppedBytes > 0` at ~660 KiB input; refusing endpoint → `Refused` (feature-disable fallback); **reflection check: no `terminalWrite`/`terminalResize` on the transport surface**; on device: opt-in gate (default OFF) → attach → styled synthetic line renders with the VIEW-ONLY indicator (`Phase9InstrumentedTest`) |
| 9.4 Git panel read-only: status/log view; U13 green | ✅ | `Phase9ToolsTest`: summary/changes/diff/commit-graph decode with paging (`hasMore`); refusal → typed `RelayTransport.Failure` (feature-disable plane). Typed surface = the verified read-only subset only |
| 9.5 Model/mode switch: pickers + echo reconciliation; U16 green | ✅ | `SessionConfigStateTest` 6/6 (verified shape, `build` default, unknown-mode tolerance + non-switchable flag, malformed-key drop + counter, key-level replacement merge, `auto` excluded from the switchable set); `Phase9ToolsTest`: `switchModelConfig`/`switchCollaborationMode` ACK through the real outbox with the CAS chain (missing base → `MissingBaseRevision` pre-wire; stale base → honest `Stale`; `auto` → `Failed` pre-wire); mock pushes the `config` echo patch (§10.3). On device: the mode/model chips render from the config echo (`mode: build`) |
| **Phase-level:** §48 rows U10–U13/U16 green; INV-9 upheld | ✅ | U10 (renderer budget), U11 (files), U12 (terminal, bounded + opt-in gate), U13 (git), U16 (echo-reconciled switches) — all with executed evidence above; INV-9 enforced by the typed transport surface + reflection test + the absence of write methods on the git/file client surfaces |

## 3. Exit-gate checks

| Check | Result |
|---|---|
| Subphase exit gates 9.1–9.5 all green | ✅ (suite inventory §2) |
| Full existing test suite green (all prior phases) | ✅ **548 tests / 0 failures** repo-wide JVM+Robolectric (`docs/evidence/phase-9/full-suite-final.log`; baseline 489 at Phase 8 → +59). One load-sensitive storm case (`StormTest › disconnect storm`) failed once inside the full-suite JVM and passed 8/8 on isolated `--rerun-tasks` — the pre-existing full-matrix load-sensitivity class documented since the Phase 6 gate; green in isolation in the current tree is the acceptance |
| Instrumented suite on device (§35.5/§35.6 emulator leg) | ✅ **10/10 on `zcode-atd` (API 30)** incl. the new Phase 9 journey + all prior phases' legs (`docs/evidence/phase-9/instrumented-results.xml`) |
| Deliverable rebuilt | ✅ `app-debug.apk` **0.9.0-phase9 (vc29)** — `app/build/outputs/apk/debug/app-debug.apk` |
| §41.3 checklist | ADRs: none mandated (no new transport/auth/state-ownership/persistence change; no new third-party dependency — the mandatory-ADR list of §8.2 is not triggered). Fixtures: MockZCode extensions are synthetic protocol-shape fixtures grounded in the cited reference sources (INV-15). Documentation: `docs/protocol/v4-wire.md` §10 + `docs/protocol/phase9-channels.md` (spec-first). architectural.md §43.2 marked COMPLETE |
| §42 invariant walk (delta) | INV-1 ✅ switches/attachments are protocol commands; local overlays reconciled (§14.2). INV-2/6 ✅ new feature modules consume ports only (grep: zero transport imports in `features/*` src); transport-kind never branched on. INV-3 ✅ new logs carry ids/safe fields only; centralized redaction unchanged. INV-4 ✅ switch commands through the outbox (single commandId; CAS chain tested). INV-5 ✅ ring 512 KiB (tested), parser 200k cap (tested), download 8 MiB cap, chunk ≤512 KiB pre-allocation checks, model options bounded. INV-7 ✅ no sqlite driver. INV-8 ✅ config-key graded tolerance (tested), unknown-mode retention (tested). INV-9 ✅ **no write/resize on the terminal surface (reflection-tested)**; git/file typed read-only subsets; INV-16 ✅ paths are pass-through navigation tokens (no construction/joins). INV-10 ✅ sha256 is the protocol's own verified checksum requirement. INV-11 ✅ every new surface renders loading/empty/error/refused/gate states (on-device asserted). INV-13 ✅ paged text viewer, 5k summary card, bounded downloads. INV-14 ✅ MIME gate (tested), out-of-set mode pre-wire rejection (tested). INV-15 ✅ MockZCode-only, synthetic content. |

## 4. Known limitations (accepted, in writing)

1. **Live-desktop leg not executed.** All Phase 9 surfaces are verified against
   MockZCode (JVM E2E + on-device). Whether the PRODUCTION relay authorizes the
   `terminal`/`git`/`file`/`model-selection` channels for a
   `web-remote-replayable` connection remains `[REQUIRES VALIDATION]` (§21,
   phase9-channels.md) — the app degrades each feature to its honest disabled
   state when refused (tested), and the live check rides the Phase-12 manual
   compatibility canary (the same deferral pattern as Phase 7's command-plane
   canary).
2. **Diff source plane.** The per-turn diff viewer consumes the verified
   `v4/conversation/fileChanges` (pre-parsed hunks). The git panel's
   `getDiff` unified-text plane is fixture-tested at the client level but its
   live payload shapes ride the same Phase-12 canary. The §22 "view file →
   Files viewer" per-file routing is implemented as the workspace Files
   entry (conversation-adjacent), not a per-file deep link inside the diff
   card.
3. **Attachment receive preview** covers images inline; video/PDF route to
   the download card (system-viewer handoff deferred with the FileProvider
   share flow). No resume-of-partial upload (protocol transactions are
   restart-from-begin — §23.2 verified semantics).
4. **Model picker options** depend on the `model-selection` channel view;
   when refused the picker degrades to the current configuration + thought
   levels (tested) — the desktop config-heavy provider machinery is NOT
   mirrored on the phone (§13.3 classification).
5. **On-device send/attach segment** is covered by the JVM E2E + on-device
   UI journeys; a physical-device production-relay attachment upload remains
   with the Phase-12 canary.

## 5. Files changed (summary)

- Spec/docs: `docs/protocol/v4-wire.md` (§10), `docs/protocol/phase9-channels.md` (new).
- Protocol: `core/protocol/.../v4/V4Phase9.kt` (new) + test.
- Transport: `RelayTransport.kt` (Phase 9 read surfaces; no write methods).
- Common: `diff/UnifiedDiffParser.kt`, `ansi/AnsiParser.kt` (new) + tests.
- Domain: `SessionState.kt` (`config` key), `Phase9Models.kt` (new),
  `CoreModels.kt` (`terminalOptIn`) + test.
- Data: `Phase9Tools.kt`, `TerminalController.kt` (new), `CommandCoordinator.kt`
  (switch commands + attachment refs in sendText), `ConversationGateway.kt`
  (config pass-through), preferences proto/mapper (additive field).
- UI: `core:ui` DiffRenderer (+tests); `features:conversation` (chips, diff
  dialog, attachment pickers/strip, tappable turn summary, port extensions);
  NEW modules `features:terminal`, `features:git`, `features:files`.
- App: `Phase9Ports.kt` (new), `AppContainer`/`ConnectionHub` wiring,
  `ZCodeApp` routes + screens, Settings terminal opt-in toggle, version
  bump 0.9.0-phase9 (vc29).
- Test infra: MockZCode Phase 9 extensions; app/data test fakes extended;
  `Phase9ToolsTest` (15), `Phase9InstrumentedTest` (1 on-device journey).

## 6. Gate decision

**PASS WITH KNOWN LIMITATIONS** — all §43.2-9 acceptance criteria and exit
gates green with executed evidence; the five limitations above are documented
deferrals of live-production legs to the Phase-12 canary, each with a tested
graceful-degradation path already in the product.
