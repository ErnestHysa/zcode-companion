# pass0 campaign LEDGER — the living campaign index (EDITABLE)

> **What this file is.** The campaign's working state: one row per finding, current
> status, fix shape, evidence pointer. The report
> (`pass0-full-verification-and-desktop-gap-2026-11-12.md`) is APPEND-ONLY and holds the
> receipts; THIS file is the only editable campaign artifact. Every session reads this
> file FIRST. When this file and the report disagree, the LATER report part wins and the
> ledger gets corrected — never the other way round.
>
> **Why it exists.** Ten passes re-narrated the same open defects because state lived
> only in 2,500 append-only lines. The ledger ends that: agents stop re-finding, the
> roadmap is generated mechanically from these rows when Ernest says so, and each future
> fix phase pulls its rows (with evidence anchors) from here — the vc52 campaign's
> 85-row roadmap + 85/85 audit worked exactly this way.

## How every session uses this file (the contract)

1. **Read this file, then the report's LAST part** (latest pass record + its fix-order +
   coverage-honesty sections). Then the rig recipes: report Part C + every pass's
   rig-learnings section (A0/D.A0/E.A0/F.A0/G.A0/H.A0/I/K.A0) — follow them exactly.
2. **STEP 0, always first tool action — desktop digest check** (see Desktop-of-record
   below). Changed → run the DELTA procedure before any app leg.
3. **Discovery pass**: work the DISCOVERY BACKLOG top-down (next unchecked items), then
   drive the latest coverage-honesty leftovers. Record, don't fix app code (mock/rig
   fixes ARE allowed — they unblock arms; commit them with finding IDs). **Live legs
   require the REAL rig** — emulator + MockZCode relay + on-device driving with
   receipts (uiautomator/screenshots/logcat/DB pulls); a docs-only or REA-only pass
   does NOT satisfy a live-leg backlog item.
4. **Fix round** (only when Ernest asks for a roadmap phase): execute the named phase's
   rows test-first, run suites, rebuild the APK, one row ≥ one commit
   `fix(<ID>): …`, tick the row here to FIXED with the commit sha.
5. **Append the pass part to the report; update THIS file** (statuses, backlog ticks,
   campaign-state block). Append-only rules for the report; earlier parts untouched.
6. Re-manifestations of OPEN rows get ONE line in the new part + a stamp bump in the
   row's `Stamps` column here — never a re-narration.

**Stop condition / roadmap trigger:** a full pass that opens ZERO new rows AND a fully
ticked backlog ⇒ tell Ernest the discovery campaign is saturated; he decides when to
build the roadmap (appended to the report bottom, vc52 format: phases by subsystem,
P2-first, exit gates, per-row Accept criteria).

## Campaign state (update every pass)

> **🛑 SATURATED (2026-11-14, pass 12) — passes 13–28 drove the arm matrix, coverage
> lists, send-ack/guard/effort/slash planes + fresh composer recon — passes 29–32 the
> attachment plane to EMPTY — pass 33 the timeline ROW-KIND render matrix — pass 34
> the toolCall row's PER-TOOL-KIND render matrix — pass 35 the Load-earlier/PAGING
> plane end-to-end — pass 36 the FIND-IN-CONVERSATION plane — pass 37 the
> ASSISTANT-ROW ACTION RAIL (the completed-row plane below the prose; feedback
> like/dislike had ZERO series receipts before it): the LIKE → echo round-trip
> receipted END-TO-END (wire `setAssistantFeedback` → mock `rowUpserted` echo →
> accent thumb + "Liked" a11y flip → the Room raw-json `feedback:"like"` arbiter →
> the echo SURVIVES a fresh process via the snapshot union, s12 "Liked" after
> force-stop), the DISLIKE flip + the explicit-null CLEAR (the key REMOVED from the
> row json), the SNAPSHOT-row like under the mock's new generated-row echo fidelity
> (the echoed upsert preserved "synthetic 320" verbatim — the old fallback would have
> re-texted the row on row.upserted), the copy pair FIRST SERIES (row_copy_agent +
> row_copy_code flips caught atomically, the OS clipboard preview chip showing the
> exact code text = the OS arbiter), the Fork/Retry capability flag-gate (only the
> MOCK_ROW_ACTIONS-stamped row carries them; Like/Dislike render on EVERY completed
> assistant row by entityId), and BOTH refusal-class faces FIRST SERIES on new arms:
> MOCK_FEEDBACK_FAIL → the keyed FEEDBACK_REFUSED copy verbatim with the rail
> unchanged + the DB unmutated, MOCK_CAS_STALE_TYPES=setAssistantFeedback → the typed
> SESSION_CHANGED warning — which EXPOSED **N-BT-1 (P4)**: the stale row-action
> banner renders the SWITCH-specific copy "try the switch again" (the renderer maps
> the shared SESSION_CHANGED key → notice_session_changed and discards
> rowActionNotice's own correct message; feedback live-receipted, retry/fork/rewind
> same path statically). Ride-along find receipts: the 3-digit count "1 of 200"
> DB-exact (159 window + 41 paged coexisting schemes) and the UNPLANNED **find-JUMP
> rowsRange trigger** (the jump to the first match fired the PAGE_EDGE prefetch pair
> ids 34/35; the paged rows joined the index live; the post-floor strip hiding
> ADJUDICATED HONEST — the jump-fetched pages covered the head, so F-P36-A's lie
> needs the race's partial-fetch sequence and did NOT reproduce here).**
> App: ZERO (APK byte-identical; `install -r` SKIPPED). **gateway.drop: 0.** Pass 38
> (Part AO, 2026-11-16 ~17:20) drove the TASK-MANAGEMENT plane first-series (the
> row menu's five never-driven legs + Phase 5A's pinned/groups/archived surfaces)
> and opened **F-P38-A** (the stale workspace-key ghost section), **F-P38-B** (the
> cold-open group-fetch swallow), **N-BU-1** (the status-bar-clipped action notice),
> **N-BV-1** (affordance-less empty groups), and **G102** (DESKTOP 3.18.1 REMOVED
> the legacy group verbs from zcode-task — the phone's whole custom-group plane
> faults against the real desktop; the plane moved to a task-groups channel with
> read/save/ungroup/move/admit; pass 8's "desktop has no management menu" census
> line CORRECTED — 3.18.1 gained its own menu incl. mutateTask routing); mock debts
> #23a/b/c opened+closed in-pass (the stale CONTROL task view: decorate +
> archive-exclude + delete-archived-sync — the vc51 "mock acks without mutating"
> attribution corrected); pass-8's create receipt stands over the gen3 createSession
> sendLost WATCH. **Pass 39 (Part AP, 2026-11-16 ~21:45) drove pass 38's full
> coverage-honesty list live** — the overflow/organize/sort/collapse census, the
> workspace chips, the + New task WATCH RESOLVED (create round-trips on a fresh
> gen), the color-dot flip ×2, the archived UNAVAILABLE face, and the unread CAS
> (arg on the wire + the refusal face on new arms) — opening **F-P39-A** (the
> move-sheet New-group path drops the pending move) and **N-BW-1** (the timeline
> collapse sentinel never survives a restart). **Pass 40 (Part AQ, 2026-11-16
> ~23:05) drove the git sheet's WRITE TIER + Branches plane END-TO-END (first
> series for every verb: switch/create/stage/discard/generate/commit/push, all
> wire-receipted against the NEW mock write-mutation arm `baa3f216`) + the
> workspace Manage sheet's leftover legs + the six read-only tool screens'
> functional census — opening **F-P40-B** (P2: the workspace-manager persistence
> TRIO is a silent no-op — preferences.pb byte-frozen while a Settings write
> lands on the same process), **F-P40-A** (P3: the switch/create acks re-fetch
> NOTHING in-place), **F-P40-C** (P3: the typed branch-issue ack is swallowed —
> sheet closes pre-result, onDismiss nulls the issue), **N-BX-1** (the
> JsonNull-blind decode renders "tracking: null"), **N-BY-1** (the Stage/Discard
> a11y labels carry the raw "%1$s"), **G103** (the catalog planes'
> detail-depth gap), **F-P5-A stamp 2** (the refused getChanges renders
> silent-empty Changes — the render site is dead-coded behind the port's
> null-swallow), and **F-P38-A stamp 4** (the ghost on the first boot dump).

- Report: `docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md` —
  Parts A–Z + AA + AB + AC + AD + AE + AF + AG + AH + AI + AJ + AK + AL + AM + AN +
  AO + AP + AQ = passes 0–40. **Latest: pass 40 (Part AQ, 2026-11-16 ~23:05).**
- Repo HEAD at pass start: `682c63b9` (pass 39's docs commit; main); pass 40 added
  ONE mock commit `baa3f216` (p40-mock-git-write-mutation: servedGitChanges() +
  the commit consumption + MOCK_GIT_BRANCH_ISSUE / MOCK_GIT_PUSH_FAIL arms;
  MockZCodePass40GitWriteSelfTest 6/6; mock suite **254/0/0**) + the docs commit.
  App: ZERO commits. APK of record: sha256 `ad449d7d…` / md5 `530ba4ff…`,
  versionCode 53 / 1.3.2 (byte-identical across the whole series; install SKIPPED
  again).
- Desktop of record: **/Applications/ZCode.app 3.18.1.10376, asar sha256 `ec475e6e…`,
  383,832,025 B** (re-verified at pass-39 STEP 0 — UNCHANGED, no DELTA). Pass-38
  census: the zcode-task method table extracted exact (AO.L) — every task verb
  EXCEPT the six group verbs survive; the group plane moved to the task-groups
  channel (read/save/ungroup/move/admit); the desktop GAINED its own task-row
  management menu (mutateTask routing) — pass 8's no-menu census line stale.
- Open rows: **37 F (35 defects + 2 obs) + 23 G + 3 standing + 22 smalls (+1 watch
  +1 rig)** — pass 40 opened F-P40-B (P2, the workspace-manager persistence trio
  is a silent no-op), F-P40-A (P3, the switch/create acks re-fetch nothing
  in-place), F-P40-C (P3, the typed branch-issue ack is swallowed), N-BX-1
  (P4, "tracking: null"), N-BY-1 (P4, the raw "%1$s" a11y labels), and G103
  (P4, the catalog planes' detail-depth gap); stamps F-P5-A ×2, F-P38-A ×4;
  the desktop of record UNCHANGED (no DELTA).
- Backlog: **8/8 DONE**; the discovery surface stays empty — pass 40's axis was
  Part AP's "did NOT drive" driveables (the Manage-sheet legs + sort-difference)
  + a fresh recon on the git sheet's Tier-B write row / BranchSheet / the
  read-only tool screens' functional depth (surfaces receipted as renders in
  passes 5/10, never DRIVEN). What remains is standing stops + F-P3-A-gated
  planes + fix-round items. **The mock-debt list has 0 open work-debts** (#25
  opened+closed in-pass).
- Rig left by pass 40: emulator `zcode-a16` UP (SEVENTEENTH consecutive inherited
  survival; healthy, wifi ENABLED, screenshot protection OFF — the pass's own
  discriminator toggle REVERTED at 22:44), relay gen4 ALIVE (nonce
  `mock-p40-gen4-fa8e87`, arms MOCK_GIT=1 + **⚠ STICKY: MOCK_GIT_BRANCH_ISSUE
  (every switch/create acks with issues until an inheritor pkills) +
  MOCK_GIT_REFUSALS=getChanges=fault.git.notAvailable (the Changes tabs will
  fault)**, MOCK_TASKMGMT=1), app pid 1838 inside synth-task-1's git sheet
  (post-refusal state), logcat running → /tmp/p40/logcat.log. Launchers
  /tmp/p40/relay-g{1,2,3,4}.sh. New rig learnings in Part AQ.A0: relay-launcher
  arms AFTER the exec line arm NOTHING (gen3 discarded — re-emit exports at the
  file TOP); Compose TextField text is invisible to uiautomator when the sheet
  reflows (screenshot arbiter); dialog dump-bounds trap did not re-bit (all
  three dialogs drove from screenshot bounds); per-char input text remains the
  recipe ("DISCARD" landed clean); FLAG_SECURE black-captures persist across
  screens until reverted.

### Desktop-of-record DELTA procedure (run whenever the digest check fails)

Re-extract the new asar → re-run the standing census greps (asar digest, host index.js
sha, service/channel registry, method tables, settings-domain counts, clientHello bits,
V2 device plane markers `/api/v1/remote/device` + `RelayV2IncomingMessage`, gen-ui
markers `genUiContext`/`genUiService`, mobile-push keys, bundled plugin count, theme
count) → diff every number against this ledger → open NEW G-rows for desktop gains →
append a DELTA part to the report. Public releases (zai-org/ZCode) carry no changelog
bodies after 3.14.3 — the census IS the changelog.

---

## F-rows — app defects

| ID | P | Status | Mechanism (one line) | Fix shape | Depends on | Evidence | Stamps |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-P40-B | 2 | OPEN (NEW pass 40) | the workspace-manager persistence TRIO (rename alias / reorder / selected-workspace persist) is a SILENT NO-OP at HEAD: Manage sheet → Rename → "Rig1" → Save (×2) and the chip-switch probe close their dialogs and mutate NOTHING — zero wire, zero UI echo (chips/headers/sheet all stay "Synthetic One"), and `no_backup/datastore/preferences.pb` byte-identical with mtime FROZEN at the previous pass's boot (77 B, no workspaceAliases map / no selectedWorkspaceKey, EVER), while a Settings-screen pref write on the SAME build+process lands (screenshot-protection byte-13 flip 22:39 + revert 22:44) — scoping the dead path to ZCodeApp.kt:742-755's three `startScope.launch { preferencesRepository.update {…} }` callbacks (G41's workspaceOrder/workspaceAliases/selectedWorkspaceKey); the wiring above them is proven (sheet Save → TaskListScreen:500 → the lambda; the sheet only renders when the callbacks are non-null); no crash, no wedged DataStore — the launches never execute their bodies (a 22:42 byte-identical rewrite during the row-tap sequence leaves micro-cause open: cancelled scope vs dropped transform — fix-round harness decides); F-P10-A's silent family on the workspace plane | move the writes off the composition-captured scope (app-level scope) + a rename-echo receipt; pin: a Robolectric harness asserting preferences.pb gains workspaceAliases after onWorkspaceRenamed + the chip recomposing under the alias | — | Part AQ.C (s22-s26, preferences{,2,3,4,5}.pb) | 1 |
| F-P40-A | 3 | OPEN (NEW pass 40) | the BranchSheet's switch/create WRITE ACKS re-fetch NOTHING in-place: `git.switchBranch ok`/`git.createBranchAndSwitch ok` fire (22:16/22:19) and the git PANE keeps rendering pre-switch data (branch block "main", comparison, Changes, History) until the screen is left and re-entered (the fresh LaunchedEffect(Unit) fetch heals: "rigp40" + the context-sheet git strip "rigp40 clean") — the BranchSheet itself re-reads localBranches at open (GitScreen:332) so the ✓ moves correctly, but the pane's summary/comparison/changes have no re-read on write acks; the commit handler by contrast calls fetchChanges() on success (GitScreen:470) — the asymmetry IS the fix shape (a shared refetch() on every write ack); the stale-viewport family (F-P38-A) on the git plane | fetchChanges() + a summary/comparison re-read on switch/create acks; pin: a compose test asserting the branch block flips after a stubbed ok ack without re-entry | — | Part AQ.B (s07/s12/s13) | 1 |
| F-P40-C | 3 | OPEN (NEW pass 40) | the branch TYPED-ISSUE ack is swallowed — a refused switch is indistinguishable from success: MOCK_GIT_BRANCH_ISSUE's desktop-faithful SUCCESS-with-issues ack (code/message/detail/paths, didChange=false) arrives (`git.switchBranch ok`) and NOTHING renders (the pane looks healthy, s29) — three stacked kills: GitScreen:517 closes the BranchSheet BEFORE the async result; the close fires onDismiss (:515) which sets `gitBranchIssue = null`; and the only render target is IssueLine INSIDE the closed sheet (GitWriteSheets:218) — a re-open shows nothing (already nulled); compounding: IssueLine renders the LOCALIZED line by code (8 known codes + branch_issue_unknown) and paths only — the wire's message/detail are decoded but never rendered (the "detail copyable" doc is unimplemented); the F-P0-C silent family on the branch plane | keep the sheet open on issues (close only on ok) or render the issue card pane-level; render message/detail (+ the copy affordance the doc promises); pin: a harness asserting the issue face renders at ack time with the message text | — | Part AQ.E (s29) | 1 |
| F-P39-A | 3 | OPEN (NEW pass 39) | the move sheet's "New group…" path silently DROPS the pending move: the sheet's New-group item clears `moveFor = null` BEFORE opening the editor (TaskListScreen.kt:823), and the editor's onCreate wires ONLY `onCreateGroup` (:834) → `TasksViewModel.createGroup` (:425-435) = createTaskGroup + loadGroups + the "Group created" success notice — the user's flow "Move to group… → New group… → create" ends with the task UNMOVED (zero `applyGroupedTaskViewOrder` in the whole pass log; a11y + DB task-2 still loose) and the new group INVISIBLE (the N-BV-1 empty-group skip) while a SUCCESS notice fires — the F-P10-A silent-no-op family on the move plane, worse for the success notice contradicting the outcome; receipted live: "shgrp" created ok (channel.call 21:19:13) → no move wire → no header | carry moveFor through the editor (create → move to the created group — the desktop companion's create-then-admit shape per G102) or re-open the sheet with the new group preselected; fold in the G102 move-copy fix; pin: a compose test opening the move sheet → New group → create → asserting the move wire fires and the row renders under the group | — | Part AP.E | 1 |
| F-P38-A | 3 | OPEN (NEW pass 38) | the task home renders STALE WORKSPACE-KEY SECTIONS forever: the "All" view enumerates every workspace key ever synced for the endpoint (`observeAllTasks` endpoint-scoped → client-side grouping; Daos.kt:20-21, TaskRepositoryImpl.kt:48-50) while the full-snapshot replace + the TTL/cap sweep are KEY-scoped (`replaceAll`/`evictAndSweep` take the workspace key; `staleTtlMs` defaults Long.MAX_VALUE; TaskRepositoryImpl.kt:52-93) — a workspace whose key stops being served (renamed/moved/spelling-drifted) leaves a ghost section with its last-synced rows presented as live; receipted: TWO "Synthetic One" sections (the live `/synthetic/ws-one` + the ghost `synthetic-ws-one` from a pre-pass-37-era spelling), the ghost row (a duplicate of the live pinned synth-task-1 minus its flags) frozen at its last sync across relay swaps + app restarts (DB: updatedAtMs untouched since ~14:36 vs the live key's 16:06 refresh; /tmp/p38/db/zcode.db), and the ghost rows are OPENABLE (the tap opened the live task's conversation, Part AO.J) | prune endpoint-scoped keys absent from the connect snapshot (or an across-keys TTL sweep); pin: a repo harness — replaceAll(keyA) then serve only keyB → keyA's rows/section age out | — | Part AO.C, AO.J | 4 (pass 39: re-manifested on gen1 s00/w1 AND gen4 s24 "7 h ago" across four relay swaps; NEW mitigation receipted — the workspace CHIP filter hides the ghost (the filter drops the dead key, s01) so the surface is All-view-only, Part AP.B; pass 40: re-manifested on the pass's FIRST boot dump s00 — two "Synthetic One" sections again, Part AQ rig-left) |
| F-P38-B | 3 | OPEN (NEW pass 38) | the custom-GROUP sections never load on a fresh open: the one-shot `LaunchedEffect(Unit) { onLoadGroups() }` (TaskListScreen.kt:195) races the transport connect — `port.listGroupedView()`'s guarded() returns null pre-connect and `?: emptyList()` (TasksViewModel.kt:422) swallows it; neither the CONNECTED transition nor anything else re-triggers, so the home renders ZERO custom-group sections for the whole session until a task action fires refresh(); receipted live: gen3's seeded "Backend" group invisible the entire session (s14: no header visually; ZERO `listGroupedTaskViewStructure` on the wire for the session's pid — the census grep returned 0) while the same session's move sheet (post-action loadGroups) listed both groups — the decode works, the cold-open fetch is the only lost one | re-run loadGroups on the CONNECTED transition (or retry-on-null in the VM); the same silent-empty family (F-P0-C); pin: a compose test composing the screen pre-connect then flipping CONNECTED asserts the groups fetch fires | — | Part AO.J intro, AO.N | 2 (pass 39: gen1's cold open LOST again (s00/w1 no header) and the Backend header appeared after a Settings round-trip re-composition (w5) — both halves of the race in one generation; gen4's cold open WON (header in the first post-boot dump) — the intermittency confirmed, Part AP.K) |
| F-P33-A | 3 | OPEN (NEW pass 33) | the generated-artifact preview card's Open affordance is DEAD at HEAD and SILENT in both failure stages: stage-2 — the download SUCCEEDS (FilesPort writes `File(cacheDir, "downloads")`, AppContainer.kt:258) but the FileProvider config `app/src/main/res/xml/file_paths.xml` declares ONLY `shared/` + `updates/` cache-paths, so `getUriForFile` throws IllegalArgumentException ("Failed to find configured root that contains …/cache/downloads/rig-p33-report.html", receipted live) and `ZCodeApp.kt:1130-1132`'s else branch is LOG-ONLY (G57's typed no-viewer notice matches only ActivityNotFoundException-class, never this IAE); stage-1 — an unserved ref faults `fault.file.notFound` at readFileRange → `filesport.download :: TransportFailure` → the download-null branch `ZCodeApp.kt:1110-1113` also LOG-ONLY (receipted live on the gen2 arm); the card renders, the button fires, the wire works, and the user gets NOTHING — the F-P0-C silent family on the preview-card plane; never live-driven in either campaign (zero artifact-open receipts in the pass0 series; the I2.8/G28/G57 static pins covered render + no-viewer fallback, not the handoff) | add `<cache-path name="downloads" path="downloads/" />` to file_paths.xml (stage-2, one line) + a typed notice on the download-null branch (stage-1, the N-BR-1/N-BS-1 notice plane); pins: a Robolectric getUriForFile harness over a cache/downloads file + a harness asserting the typed notice on result==null; re-drive the SUCCESS face (system-viewer chooser) after the fix | — | Part AJ.G | 1 (+pass-34 STATIC mechanism extension: the same root covers the NON-IMAGE attachment chip's external open — `fetchAttachment` writes `attachmentCacheDir = File(cacheDir, "attachments")` (AppContainer.kt:227) also absent from file_paths.xml, the same getUriForFile IAE family via ZCodeApp.kt:1052-1077; the fix shape gains `<cache-path name="attachments" path="attachments/" />`; live chip-tap leg = fix-round, Part AK.H.2) |
| F-P35-A | 3 | OPEN (NEW pass 35) | the Load-earlier error face is TOP-ANCHORED while the tap affordance is TAIL-ANCHORED — a faulted page fetch gives the user at the tail ZERO visible feedback: the tap affordance is the PINNED strip above the timeline (ConversationScreen.kt:563-581, always visible while hasMoreHistory — the user taps it wherever they are), but the error face renders as a LAZY-LIST ITEM at index 0/1 (`older-error`, ConversationScreen.kt:1127-1138, AttentionStrip "Couldn't load earlier messages" + Retry) — hundreds of rows above the tap point when the timeline is long; receipted live: gen2 fault arm → `port.pageFault :: TransportFailure: fault.rowsRange.rig` in logcat + the screen UNCHANGED at the tap point (s04: strip still "320 of 400", no banner) — the error only renders after ~60 gap-drags to the list top (s05); the F-P0-C silent family on the paging plane (the same geometry stars the `older-loading` face — subsumed by the same fix) | surface the error/retry in the PINNED strip slot (swap the strip's content on loadOlderError, or a second pinned strip / a SendNotice-keyed banner on the N-BR-1 plane); the shared errorUserMessage copy is already clean (errorCategoryMessage's error_task fallback — no raw-code issue, AL.F.3); pins: a compose test with a faulting port loader asserting the error is visible WITHOUT scrolling + the retry from the pinned slot succeeds | — | Part AL.D (s04/s05/s06), AL.E (the retry-success face the fix must preserve) | 1 |
| F-P36-A | 3 | OPEN (NEW pass 36) | the open-time prefetch race + the cache trim strand a stale hasMore=false — the Load-earlier strip hides while head rows are CACHE-ABSENT and unreachable for the session: the prefetch effect (ConversationScreen.kt:1092-1103, PAGE_EDGE=12) observes firstVisibleItemIndex=0 in the window BEFORE the tail-clamp sets anchoredToTail (:1063-1071) → loadOlder fires ×2 at open with beforeRowId=rows.first() (receipted 2/2 fresh opens, relay log lines 24/25+55/56, ZERO taps); the pages apply and the page-path writer sets `_hasMore.value = result.hasMore` (ConversationViewModel.kt:295) — honest false at that instant (the second page reached the store floor) — but the next enforceBound (append/snapshot-apply only; ConversationRepositoryImpl.kt:73/126 — the page path is deliberately unbounded per F-37-D :96) TRIMS the fetched head back to the 320 cache bound (DB: 82..401 → 84..401+1001+1002; 81-83 head rows evicted), and `_hasMore` is a MIRROR of the manager's authoritative flow (:145) whose only writer is the snapshot-path derivation (ConversationSessionManager.kt:248 — which says TRUE) that NEVER re-emits an unchanged value (MutableStateFlow same-value dedupe) — so the strip gate hides the affordance and the evicted head (ids 1..83) cannot be paged back until a fresh process re-subscribes (receipted: route pop/re-entry does NOT heal — w15; force-stop re-open DOES — w17 "320 of 400" again); the user-visible state lies ("no more history" while 81 rows the store HAS are cache-absent) | re-derive after the trim: hasMore = firstCachedRowId > windowFirstRowId at every enforceBound site (or re-read the manager's authoritative value at loadOlder completion instead of trusting the page response alone); pins: a repo harness paging to the floor with total > cache bound asserting the strip survives the post-page trim + the DB min(rowId) > windowFirstRowId invariant | — | Part AM.I (w2→w9→w13→w15→w17, relay lines 24/25+55/56, DB zcode{,2,3}) 2 (pass 37: the open race fired 0/3 fresh opens — intermittent; the find-JUMP is a DETERMINISTIC trigger (the jump to the first match scrolls to the cache head → the PAGE_EDGE pair, relay gen1 ids 34/35); the jump-path end face is HONEST — the pages cover the head (cache 1..402 complete, zero gaps) so the strip hiding is CORRECT there; the lie needs the race's partial-fetch + trim sequence; Part AN.J) |
| F-P31-A | 3 | OPEN (NEW pass 31) | the mid-upload user-tap Remove does NOT cancel the in-flight upload coroutine — the aborted transaction RESURRECTS the removed chip: `removeUpload` (`Ports.kt:1154-1167`) fires `attachmentAbort` (the mock deletes the staging entry, MockZCode.kt:3471-3475) + filters the chip, but the composer-scope job keeps running (`WriteComponents.kt:931-939` first-attempt launch / VM `:382` retry launch; VM `removeUpload` :376-378 fire-and-forget, no Job tracking) — the in-flight chunk completes on its DETACHED mock object and the per-chunk progress upsert (`Ports.kt:1112-1115`) re-adds the chip as ↑, then the next chunk errors `fault.attachment.unknownUpload` (MockZCode.kt:3417-3419) into the catch-all which upserts "failed" + payload (:1129-1137) — the user's explicit Remove is undone ~15 s later under a banner (first-attempt: the RAW-code "Attachment failed to upload (fault.attachment.unknownUpload)" — N-BO-1's 4th surface; retry: "Attachment retry failed — not sent."). Pass 32 EXTENSION (the silent face, live on MOCK_ATTACHMENT_KEEP_AFTER_ABORT `500a3bdf`): a server that accepts post-abort chunks resurrects the chip as ✓ READY under NO banner at all — receipted with TWO consecutive user-removes in ONE transaction (both aborts KEPT → commit ok 136 ms → `✓ 130.png (2.0 MB)` + armed FAB, no signal), and the resurrected chip SENT end-to-end (outbox ACKED carrying its committed ref, DB arbiter) — the resurrected attachment is fully REAL; the silent face is the worse UX (nothing tells the user their Remove lost) | track in-flight upload Jobs per uploadId and cancel in removeUpload (or guard BOTH re-add upserts on a user-removed set); DESKTOP CENSUSED (pass 32, AI.D): the desktop's remove is FINAL — per-chip AbortController aborted in remove + the V4 loop's per-chunk signal checks + staleness-guarded progress/completion callbacks (`v.current.get(r)!==i`) + transferService.cancel — the phone's analogue is the Job map + the guarded upserts; pin: a harness asserting remove-during-upload → the chip stays gone + the abort fires | — | Part AH.B–AH.D, AI.B–AI.D | 2 (pass 32: the silent ✓ variant + the consecutive composition + the resurrected-chip send + the desktop census) |
| F-P19-A | 3 | OPEN (NEW pass 19) | the G50 CAS-mismatch face is desktop-divergent: on `guard.heldQueueConfirmationStale` (host ack `{status:"failed", …}` — set-equality CAS via `applyHeldQueueDisposition`) the desktop RE-OPENS the held-queue confirmation with fresh ids (renderer `confirmationRequired`, re-reads `queue.items`, no error surfaced); the phone maps the failed ack to the generic refusal ERROR notice ("The desktop refused the send — see diagnostics", `ConversationViewModel.kt:542-548`) — reasonCode undifferentiated, no re-confirmation loop exists (grep receipt) | handle the typed reasonCode → re-open the disposition dialog with re-read ids (desktop parity) or minimally a distinct notice; pins: the guard.* grep + the V.C static shapes (outbox FAILED_TERMINAL + preserved draft) | — | Part V.B–V.E | 1 |
| F-P20-A | 3 | OPEN (NEW pass 20) | the phone's goal sends are structurally refused under the desktop's choice admission: the host's `sendGoalCommand` handler passes the payload's `heldQueueDisposition`/`expectedHeldQueueItemIds` through the SAME `applyHeldQueueDisposition` gate as sendText (zcode.cjs `SMc`→`n9e`→`Vqe`; absent → `heldQueueDispositionRequired` failed-ack; the desktop UI pre-gates via the confirmation dialog — ZERO renderer references to the reasonCode), but the phone's goal payload is `{text, displayText}` only (`CommandCoordinator.kt:220-224`, commented "not CAS-guarded"). Pass 21 EXTENSION (composer half): the composer-level G50 dialog DOES fire for slash-dispatched sends, and `send()` (`ConversationViewModel.kt:434-470`) receives the trio but only `routeSendPrompt` (:454) forwards it — every `sendSlash` branch (:459-470) drops it, so the user ANSWERS the dialog and the answer is discarded (receipted live: dialog → Keep → refused → FAILED_TERMINAL). The sheet half (`setGoal` :601-613) bypasses `send()` — no gate, no dialog, generic ERROR face, as pass 20 receipted. Pass 22 EXTENSION (compact variant): the G50 dialog fires for composer `/compact` sends too and the answer is DISCARDED the same way (`port.compact` = `simpleCommand("compact", sessionId, {})`) — but the wire OUTCOME stays host-faithful (the host's `vMc` consults routing mode, never a trio: the compact self-queues either way, live-receipted Y.E), so the discarded answer's only loss is the user's explicit "Clear queue & send" choice being silently dropped (the desktop never asks for compact at all) | composer half = pure PLUMBING (carry the answered trio into the goal/compact branches — or stop asking for compact); sheet half = gate on the choice state or a distinct notice keyed on the reasonCode; pins: W.B handler chain + W.C payload/surface pins + the outbox rows (goal sheet [20], composer-slash goal [21], compact ACKED/FAILED_TERMINAL pair [22]) | F-P19-A family (same guard) | Part W.B–W.E, X.B–X.D, Y.B/Y.E, AB.D | 4 (pass 22: the compact variant live — dialog + discard + the host-faithful queued outcome; pass 25: the THIRD goal entry point — slash-set `/goal <objective>` — rides the same trio-less port, outbox payloadJson text/displayText verbatim, AB.D.4) |
| F-P8-A | 2 | OPEN | cold start w/ DIRECT selected: deferred relay `persistEndpoint` rewrites `_endpoint` after resolve — silent abandonment, recovery dropped by `sourceKind` filter, "First sync failed" vs healthy direct, Retry completes it | guard `persistEndpoint` against overwriting a resolved non-relay selection; retry must reconnect the transport the UI shows; pins: Robolectric race harness + "no `hub.switched` without user tap" | — | Part K.A2 | 1 (+F-P9-A sibling mechanism) |
| F-P9-A | 2 | OPEN | relay killed MID-AUTHENTICATION → transport wedges in AUTHENTICATING permanently: zero sockets, zero ladder, zero logs, 8 min receipted; survives foreground/unlock; NO retry face (cached rows suppress the strip); active-endpoint tap = silent no-op; ONLY escape = cross-endpoint switch (away+back) | AUTHENTICATING watchdog (timeout → RECOVERING) + a stuck-face; pins: Robolectric kill-mid-handshake harness + netstat-zero + "no silent state" assert | — | Part L.A2 | 3 (pass 10: stale-relay variant, M.A3; pass 15: THIRD variant — relay ABSENT at the cold-start connect → 10s TIMEOUT → silent wedge, no ladder/face, force-stop escape; R.A0.3) |
| F-P9-B | 2 | OPEN (NEW pass 9) | typed disconnected-remote face never renders live: hub logs the error (workspace-bridge-error: remote-disconnected, verbatim errorText) but ConnectionCenter shows the generic offline face — the G67 card (`connection_remote_reconnect`, render condition lastError.messageKey==REMOTE_WORKSPACE_DISCONNECTED, ConnectionCenterScreen.kt:296) NEVER renders → the recovery verb workspace-reconnect-request is UI-unreachable; row snapshot stays stale under the arm | trace hub.connectFailed→lastError dropping the messageKey (SESSION-scope bridge error); pin: live-shape harness asserting the card renders with the arm armed | — | Part L.A2 | 1 |
| F-P13-A | 2 | OPEN | the workflowRun delta plane is DEAD on the CLOSE-CYCLE post-recovery path (pass 15 narrowed the row's scope): the detach path drops the local subscription record (relay killed → `recovery.detached (unsubscribed)` — detach() fires ONLY from closeConversation, ConnectionHub.kt:1027-1052 → RecoveryCoordinator.kt:130-149), the recovery re-materializes conversations via `relay.recovered :: subscriptions=N snapshotReplace=true` issuing NO subscribeConversationV4 (relay log empty of subscribes; app sends hello only), so the subscribe-ACK epoch record is never re-stamped → EVERY server-initiated delta frame (workflowRun.updated/removed, kind=ONLINE) arrives at the wire and drops at ConversationGateway.kt:261-274 (`gateway.drop … no subscription record`) for the life of the connection; the declared `workflowRunDeltas:true` (V4Plane.kt:672) is effectively false on that path and the UI stays correct only via the ~8s poll drum (F-P1-D). **STAY-OPEN boundary (pass 15, R.A4): a conversation that stays OPEN through any kill recovers FULLY — the record survives (zero detached lines), the recovery re-subscribes (begin during RECOVERING), the epoch re-stamps, and anchored deltas APPLY (seq=2 +6.07s, seq=3 +22.1s; zero gateway.drop in the whole pass)** | recovery must re-subscribe (or stamp epochs on the snapshotReplace ack) ON THE DETACHED-REOPEN path; pins: relay-log "no subscribe after recovered" + a gateway.drop assert on a post-close-cycle delta; stay-open resilience receipted (pass 15) | — | Part P.A3, R.A4 | 3 (pass 14: the fresh-process happy path RECEIPTED — Part Q.A1; pass 15: the stay-open boundary — deltas live across 2 kills, scope narrowed to the close cycle) |
| F-P0-B | 2 | OPEN | rows reconcile sweep STALEs state-plane-delivered PERMISSION interactions (the two reconcilers fight); card renders terminal-but-actionable | ONE LINE: `known.permission != null` exemption at `InteractionCoordinator.kt:697` + pin answering from BOTH planes | — | Part E.A2.4 (root cause) | 8 stamps (passes 3–9; pass 9's hook-review zero-rows receipt) |
| F-P3-A | 2 | OPEN | pinned pending-stack budget starves EVERY interaction card body to zero height (question body, hook items; LazyColumn unbounded main axis kills `weight(1f,fill=false)`) | card-agnostic: raise/scroll the pinned budget or render bodies outside it; pin hosts cards in a live `LazyColumn{item{}}` harness varying pinned-child count (pass 12: the starvation is composition-dependent — the approval card rendered FULL body on re-entry while question/hook cards vanished) | — | Part D.A2 (first), G.A2.1 (card-agnostic), O.A2 (variance) | 10 (passes 1–9 + 12 + 33: the hook-review card's starved hook items + disabled `Trust 0 selected` button under MOCK_HOOKS, AJ.B) |
| F-P11-A | 3 | OPEN (NEW pass 11) | conversation attachment chip has NO error face — a failed read leaves "Loading…" forever (produceState swallows failures; no unavailable state, no retry; recovered here only because the mock self-healed) | error/unavailable state on the chip (F-P0-C family) + Robolectric failing-loader harness; MOCK half FIXED `1e649eab` (eager registration, verified gen3) | — | Part N.A1/N.A2 | 1 |
| F-P11-B | 3 | OPEN (NEW pass 11) | Live-connection window picker's selection never reflects in the row subtitle in-session — Settings prefs projection doesn't re-collect after `preferencesRepository.update` (3 cycles; disk HAS 60 — `preferences.pb` field 8; re-entry renders 60); lesser: force-stop ~90 s after a write lost it (flush killed) | collect prefs via the live dataStore.data flow on the screen (or key the row on the pref) + subtitle-after-update pin | — | Part N.A2 (s36/s37/s40/s41/s44 + the pb pull) | 1 |
| F-P8-C | 2 | RE-VERIFIED-WORKING (pass 10) | conversation-timeline drag-scroll, reported dead at pass 8 HEAD, NO LONGER REPRODUCES at e9a8d206 — drag receipted 3 directions incl. reverse (Part M.A1); mechanism was open, no fix commit identified; the F-P8-E companion is REFUTED (pass 11) | optional drag regression pin for fix rounds | — | Part K.A2 (dead), M.A1 (working) | 1 + pass-10 re-verification |
| F-P8-E | 2 | REFUTED-WORKING (pass 11) | the pass-8 "followBottom cleared only by find-jump" pin was a MIS-READ — the gesture-direction release (scroll to head → release) AND the latched numbered jump FAB (`conversation_jump_latest` + UnreadBadge) both live-receipted at pass 11; remedy commit `4bb678e7` predates pass 8's claim | row stays: keep the release+FAB pins for fix rounds | — | Part N.A1/N.A3 (release, no-re-clamp, badge "4", jump+reset) | 1 |
| F-P10-A | 3 | OPEN (NEW pass 10) | the Commit sheet's Commit action SILENTLY no-ops when hub.activeWorkspaceTarget() resolves null: dialog closes, zero wire verbs (`git.commit` never sent), zero log lines (`gitport.commit` warn never fires), zero user face — the reads in the SAME sheet resolve elsewhere (git.getChanges ok) | resolve the write target the way the reads do (session-scoped) + a typed refusal face on the null-target path (`writeOp` :209 `?: return null` is the silent exit; the onCommit `if (hash != null) … else nothing` at GitScreen.kt:467-478 has no else) | — | Part M.A2 | 1 |
| F-P6-A | 2 | OPEN | permission ghost-split: store keeps DISPLAYED rows the card plane dropped (F-P0-B) AND generation-orphaned ghosts accumulate monotonically (4 ghosts / 26 h; broker posts shade notifications with NO answer surface) | card half = F-P0-B fix; store half = resolve generation-orphaned interactions in a reconcile sweep; ghost fixtures `/tmp/pass8/db/p8-2.db` + `/tmp/p9/db/p9-4.db` (5 unresolved / 83 rows, oldest 2026-11-12 19:59; note: the pass-10 wipe-data cleared the LIVE ghosts — the sweep code is still absent, fresh installs just have nothing to orphan yet) | F-P0-B (card half) | Part I (headline), K.A3, L.A3, O.A2 (census 0 orphans) | 4 (pass 12 census) |
| F-P6-B | 2 | OPEN | with armed stack, timeline renders ZERO rows (pinned budget + expanded queue panel eat the window) | re-balance pinned/timeline budget; queue collapse = F-P8-D | F-P8-D | Part I | 3 (pass 12: again, s05/s09) |
| F-P7-A | 2 | OPEN | Approvals-screen inbox answer strands: `submitFromStore` → RETRYABLE_KEPT (needs a session machine); banner remedy "open the task" unactionable on DIRECT (foreign workspace). Inbox answers FINE over relay (pass 8) — endpoint/machine-context-specific | interaction-scoped submit (shade path proves the shape); validate task row before advising | — | Part J | 2 (7,8) |
| F-P7-B | 2 | OPEN | shade action answered while connection down → interaction FAILED, no retry/queue/no user face; the COMMON path with Live-connection OFF | queue behind reconnect (inbox RETRYABLE_KEPT semantics) or visible "not delivered" re-post | — | Part J | 2 (pass 13: the SAME no-face outcome receipted for the typed server-rejection trigger — stale-generation answer → `status:"rejected"` → store FAILED + outbox FAILED_TERMINAL, card just clears, no notice; Part P.A5) |
| F-P1-B | 2 | OPEN | stale-generation interactions stay answerable 50+ min and ACK against a moved-on desktop; closeStored sweep misses rows (2 of 5) | expire stored interactions on generation/workspace identity mismatch + mock unknown-id error arm | — | Parts D.A2, E.A2.5 | 5 (pass 13: the gen5a card survived the gen5b snapshot swap and stayed answerable — AND the sibling question row got RESOLVED by the same swap, so the sweep is PARTIAL in both directions; Part P.A5) |
| F-P3-B | 2 | OPEN | automation "Go to session" NEVER loads its transcript — 4 skeleton rows forever, zero transcript wire calls | openRun must hydrate via **`readSessionMessages` — ALREADY CONSUMED** (ConversationPort.kt:318): wiring only, plus honest empty state | — | Part F.A2, K.B1 (verb found) | 2 (pass 17: re-manifested on the SEEDED run's unscripted session — no subscribe, no transcript wire, skeleton-forever (T.A2, s15b); **NARROWED**: the MANUAL run's workspace session LOADS its transcript via the subscribe snapshot — the skeleton is the unscripted-session shape only) |
| F-P3-C | 3 | OPEN | inbox ACK's resolution echo DROPPED when conversation unsubscribed (`gateway.drop … no subscription record`) | retain last-N deltas per session / subscribe-on-ACK / optimistic local resolve | — | Part F.A2 | 1 |
| F-P5-A | 3 | OPEN | refused git reads render SILENT-EMPTY sections (headers, zero rows, no error) — pinned refusal faces don't manifest on the live load path | typed-refusal face on section-empty + pin hosting the LIVE shape | — | Part H.A2 | 2 (pass 40: the LIVE Changes tab under MOCK_GIT_REFUSALS=getChanges=fault.git.notAvailable — `git.getChanges begin → refused` ×2 (22:55) and the section renders header → EMPTY → straight to History (s30); the render site EXISTS and is dead-coded (GitScreen:204 changesError?.let + git_changes_error/git_changes_retry) because the PORT swallows the refusal first — Phase9Ports.kt:124 `changes(...) = run("git.changes") {...} ?: emptyList()` maps ANY failure to null so fetchChanges's exceptionOrNull plumbing never sees a throw; the fix belongs to the port/loader boundary) |
| F-P2-B | 3 | OPEN | post-unpair resume card claims "Paired · Connect" from a fakeLink (`PairingViewModel.kt:50-56`) — structurally guaranteed | reduce to a genuine unpaired state (no fakeLink) | — | Part F.A2 (code pin) | 1 |
| F-P0-C | 3 | OPEN | file viewer renders SILENT BLANK panel on refused read (`FilesScreen.kt:225-237`) — no error, no retry | typed error face + WARN-to-UI | — | Parts A, E.A2.6, F.A2.5 | 3 (0,2,3) |
| F-P8-D | 3 | OPEN | queue panel has NO collapse affordance (always-expanded; desktop's has one) + header clipped under the sticky strip | two-way header toggle (desktop parity) + strip/header overlap fix | — | Part K.A2 (supersedes Part I's probe) | 4 (pass 18: always-expanded through both queue-strip legs, s08/s09) |
| F-P8-B | 3 | OPEN | switching to an already-CONNECTED endpoint throws ISE `connect() is the first-connection path` | `connectDirect()` → `recover()`/no-op when state==CONNECTED | — | Part K.A2 | 1 |
| F-P8-F | 3 | OPEN | Take-photo intent failure (API-36 camera denial) surfaces NOTHING in the attachment flow | typed honest face for intent-launch failure (silent-blank family) | — | Part K.A2 | 1 |
| F-P0-E | 3 | OPEN (unresolved) | ANR during rapid rig input into direct-endpoint form; causality unproven (app trace rotated away) | observation; rig recipe recorded (chunk DELs ≤8, pull `/data/anr/trace_*` immediately) | — | Part A.A2 | 2 (pass 10: the wipe-data first-boot ANR storm — app input-dispatch ANR in the same record as GMS/phone startup ANRs + a 15s watchdog block; M.A3) |
| F-P1-D | 3 | OPEN (observation) | open conversation polls workflowRuns+plans every ~8 s even idle/backgrounded | subscribe-on-open + deltas (capability already declared) or ≥30 s cadence | — | Part D.A2, K.A3 | 4 (pass 12: 52 polls in the gen2 relay log) |
| F-P0-D | 3 | RETRACTED | pass-0 terminal-attach silent no-op — unreproduced 5× (passes 2,3,4,5,8); Context-sheet route variant = N-BM-3 | keep typed-face work as hardening only | — | Part E.A2.8 | — |
| F-P2-A | 2 | REFUTED→SUPERSEDED | selection persistence REFUTED as a defect (survives force-stop, app-kill, full emulator reboot — passes 7, 8); the REAL root is F-P8-A's silent in-memory abandonment | closed in favor of F-P8-A | — | Part J, K.A0.7/K.A3 | — |
| F-P1-C | 3 | REFUTED | "push arm delivered nothing" = DB-pull race artifact (pass 2); arm healthy, later rig-fixed | — | — | Part E.A2.3 | — |
| F-P1-A | 2 | ABSORBED | question-card zero-height body → merged into F-P3-A (card-agnostic) | — | — | Part D.A2 | — |
| F-P0-A | 2 | ABSORBED | conversation-card refusal → root-caused pass 2 → merged into the F-P0-B/F-P3-A family | — | — | Parts A, E.A2.4 | — |
| F-P4-A | 2 | FIXED (rig) | mock push patch structurally excluded its own interaction | `pushPendingPermission` builds from unfiltered list + patch-content pin — commit `2b12fc46`, 37 mock tests green | — | Part G.A2.2, H.A1 | — |
| N-BK-1 (rig half) | 2 | FIXED (rig) | git fixtures in wrong field vocabulary (blank paths, +0−0, duplicated tabs, oid-vs-hash) | contract vocabulary rewrite — same commit `2b12fc46` | — | Part G.A2.3, H.A1 | — |

**Open F count: 37** (35 defects + 2 observations; pass 40 opened F-P40-B P2 —
the workspace-manager persistence trio is a silent no-op — F-P40-A P3 the
switch/create acks re-fetch nothing, and F-P40-C P3 the typed branch-issue
ack is swallowed) ·
FIXED: 2 (both rig) · RETRACTED/REFUTED/ABSORBED: 7.

## Current fix-order (consolidates K.D + the carried rows; supersedes every report fix-order)

1. **F-P8-A** → 2. **F-P0-B** (unblocks F-P6-A's card half) → 3. **F-P9-A** (pass 9:
   the AUTHENTICATING wedge strands every multi-endpoint reconnect; unblocks the
   endpoint-switch legs) → 4. **F-P13-A** (pass 13: the delta plane drops on every
   post-recovery connection; recovery must re-subscribe — small, self-contained,
   transport+gateway) → 5. **F-P8-C's drag pin** (pass 11: F-P8-E is REFUTED — the
   follow-release + jump FAB work; only a drag regression pin remains optional) →
   6. **F-P9-B** (pass 9: the un-rendering typed face; unblocks the remote-down
   recovery leg) → 7. **F-P6-A store half** (ghost sweep; fixtures
   `/tmp/pass8/db/p8-2.db` + `/tmp/p9/db/p9-4.db`) → 8. **F-P8-D** (queue collapse;
   re-test F-P6-B after) → 9. **F-P3-A** (card-agnostic pinned budget) → 10. **F-P10-A**
   (pass 10: the silent commit no-op; small, self-contained) → 11. **F-P11-B** (pass
   11: the window picker's stale projection; small, prefs-flow re-collect + pin) →
   12. **F-P11-A** (the attachment chip's error face) → 12a. **F-P31-A** (pass 31:
       the mid-upload Remove resurrection — cancel/track the in-flight upload job
       or guard the port's re-add upserts; pairs naturally with N-BR-1/N-BS-1's
       notice-plane one-liners) → 12b. **F-P33-A** (pass 33: the artifact-open
       dead handoff — the file_paths.xml one-liner + the typed notice on the
       download-null branch; same notice plane as N-BR-1/N-BS-1, same silent
       family as F-P0-C) → 12c. **F-P35-A** (pass 35: the paging error face's
       top-anchored silent failure — surface the error/retry in the PINNED
   strip slot; same silent family, and the retry-success face it must
   preserve is fully receipted in Part AL.E) → 12d. **F-P36-A** (pass 36:
   the stale hasMore after the open-time race + trim — re-derive at the
   enforceBound sites or re-read the manager at loadOlder completion;
   the strip-tap UX depends on it) → 13. **F-P3-B**
   (wiring-only hydrate) → 14. **F-P3-C** → 15. **F-P7-A / F-P7-B** → 16. **F-P5-A →
   F-P2-B → F-P0-C → F-P8-B → F-P8-F** → 17. **F-P1-B → F-P1-D** → 18. smalls
   (N-BM-1 → N-BP-1 → N-BM-3 → N-BN-1 → N-BK-1 app half → N-BJ-1 → N-BK-2 → N-BL-1 →
   N-BM-2 → N-BO-1 → N-BP-2 → N-BQ-1 → N-BR-1 → N-BS-1 → N-BT-1 → N-BX-1 → N-BY-1 →
   K-small-1/2) → 18a. **F-P40-B** (pass 40, P2: the workspace-manager persistence
       trio is a silent no-op — move the three ZCodeApp.kt:742-755 writes off the
       composition-captured scope + the preferences.pb pin; small, self-contained,
       the P2 of the pass-40 batch) → 18b. **F-P40-A + F-P40-C** (pass 40, P3 pair:
       the git pane's write-ack refetch + the typed branch-issue face — the same
       GitScreen write-handler surface, one session; F-P40-C gates the 8-code
       branch_issue copy map) → 19. **F-P19-A + F-P20-A** (passes
   19/20: the held-queue guard family — F-P19-A the sendText CAS-mismatch face
   divergence, F-P20-A the goal payload's trio omission; both small — a typed
   reasonCode branch on the existing SendNotice path / a goal-side gate) →
   20. parity queue per the G table + the opportunistic list.
   (Part V supersedes P; earlier orders superseded.)

## G-rows — desktop-vs-phone gaps (parity queue)

| ID | P | Status | Gap (one line) | Consume spec (exact) | Evidence |
| --- | --- | --- | --- | --- | --- |
| G81 | 3 | OPEN | gen-ui consume plane — desktop renders generated interactive UI (HTML in plugin-sandbox), phone zero files | 4-part spec: declare `genUiContext:true` hello bit; answer `runtimeCapabilities`; accept/forward the genUiContext array (max 100 × {path,state}) on send; render components (expand/collapse/tweak/submit/copyImage/retry + `genUi.desktopOnly` face). Minimum = honest "open on your desktop" face | Part F.B1 (complete) |
| G94 | 3 | OPEN (NEW pass 8) | worktree creation/fork plane (ManagedWorktree, 14 methods) — start-a-task-in-a-worktree has no phone counterpart, not even read-only. Pass 9: the plane is additionally AGENT-gated (worktreeCwd runtimeCapability → ManagedWorktreeService.supports, localhost only) | minimum face: `listCreations` + `onDidChangeCreations`/`onDidSettleCreation` | Part K.B1, L.B1 |
| G91 | 3 | OPEN | Plugins decode-only — `listPlugins` STRICT schema decoded, NO production screen consumes it (18 desktop bundled plugins invisible) | read-only Plugins viewer in the settings-viewer family (Usage/Memory pattern): list + enable-state only | Part I.2.4 |
| G93 | 3 | OPEN | V2 mobile-push plane has NO phone receiver (device registration `{device_mid}→{device_sid}`, Bearer JWT event POST, dispatcher w/ typed skip reasons) — when the relay socket is down, the desktop cannot reach the phone AT ALL | ARCHITECTURAL: push receiver + registration face; needs a cloud-transport decision first. **Enum residual CLOSED pass 9**: kind ∈ {question, approval, plan, task_completed, task_failed}, state ∈ {open, resolved}, producer = host chunk-7SAPNXNZ.js collectMobilePushEvents (NOT zcode.cjs — pass-8 attribution corrected) | Part J.2.1, K.B2, L.B2 |
| G89 | 3 | OPEN | hook review-reason enum (11 typed reasons) undecoded — phone card says only "N pending · M hooks"; saveHooks authoring remote-servable | decode half = safe minimum (reason enum + typed faces); saveHooks = consume/no-action decision | Part H.B3 |
| G82 | 3 | OPEN | memory viewer — desktop Memory UI (`memoryEnabled` toggle + project-memory viewer); phone zero surfaces | read-only mirror = `listProjectMemories` + `readProjectMemoryFile` (of 6 exact methods) | Part G.B3, H.B1 |
| G85 | 3 | OPEN | broadcastService vocabulary complete but consume/no-action UNDECIDED (`mobile-push:run/event` = V2 realtime arm; `state:*` sync family; task-groups claim protocol) | decision row; mobile-push:* feeds the G93 architecture | Part F.B3 |
| G60 | 4 | STANDS | plan-claim/rewards/purchase desktop-only (`codingPlan` 2,463 renderer hits; `claimManualPlan`; phone zero claim surfaces) | unchanged since pass-31; re-measured 3.18.1 | Part B.B2, G.B7 |
| G83 | 4 | OPEN | clientScenes — ONE verb (`getSnapshot`); smallest consume on the board | one-verb read mirror | Part H.B1 |
| G84 | 4 | OPEN | outputStyle mirror — `listStyles` + `getActiveStyle` (of 7) | read-only mirror | Part H.B1 |
| G95 | 4 | OPEN (NEW pass 8) | feedback/support-intake plane (Feedback, 15 methods incl. device-snapshot + log-archive attach) — phone diagnostics export could feed `attachLogsFromExport` | inventory row → consume decision | Part K.B1 |
| G96 | 4 | OPEN (NEW pass 9) | conversation selection-comment/annotate plane (批注) — desktop gates the selection→comment entry on the conversationQuoteComment agent capability; phone conversations have no annotation entry | inventory row (renderer feature, self-hiding); consume = the phone quote plane has no comment concept | Part L.B1 |
| G98 | 3 | OPEN (NEW pass 10) | the desktop's SOURCE-SERVER/compute planes — it connects DIRECTLY to SSH servers (180 i18n keys: full host/port/auth password·privateKey/asset-install-mode form), WSL (45), Docker (35), sourceServer (60) as workspace hosts; the phone's "remote" is only the desktop's relay — zero counterpart, not even read-only | read-only mirror of source servers (the phone already decodes sourceServerId in task rows — pass-8's bridge resolution); authoring stays desktop-only | Part M.B3 |
| G99 | 4 | OPEN (NEW pass 10) | the desktop's WHITEBOARD plane (65 keys: named persisted boards, pen/eraser/color/strokeWidth/undo/redo/addToChat, typed missing-board face) vs the phone's send-only Sketch canvas | inventory row; consume minimum = honest "open on desktop" face for whiteboard artifacts if they surface in conversations | Part M.B3 |
| G100 | 4 | OPEN (NEW pass 10) | the desktop's model-TRAJECTORY viewer (165 keys, 模型调用轨迹: per-model-call usage input/output) — the phone's Usage screens are aggregate | P4-smallest: decode the trajectory payload for the Usage viewer if the channel serves it; else inventory-only | Part M.B3 |
| G101 | 4 | OPEN (NEW pass 10) | the CUA permission-status plane: `CuaPermissionService` getStatus (typed status incl. grantOwner/accessibilityProbeOk/screenRecording/screenCaptureProbeOk/idle + typed reasons) + restartHelper (macOS-only), 25-key desktop panel — the phone decodes PUSHED observations only and can never show the desktop's CUA health proactively | read-only status mirror minimum (one card fed by getStatus); restartHelper = consume/no-action decision | Part M.B1, M.B3 |
| G97 | 3 | OPEN (NEW pass 9) | the desktop's web-remote-control companion plane (90 i18n keys: QR→browser connectUrl, session lifecycle, 12-way typed failure taxonomy, workspace/task switching, bot channels feishu/lark/weixin/telegram, singlePageNote one-page guard, pullMobileLogs, settings.mobilePush desktop toggle) — the phone is BLIND to it: no face for a browser session controlling its workspace, no kicked/conflict notice, bot-channel access undocumented phone-side | honest surfaces minimum (a companion-state mirror + a kicked/conflict face); the plane itself stays desktop-served. **Pass-10 census additions:** the companion's client mode (`web-remote-replayable`) is restricted to a 49-channel allowedChannels Set (exact list in M.B2) with McpSync scoped to 4 verbs; the desktop's `remote.*` namespace (460 keys) is the desktop-side device-away faces | Part L.B3, M.B2 |
| G86 | 4 | OPEN | share-code IMPORT plane (`importShare` + progress phases) — desktop-only | inventory first | Part F.B4 |
| G90 | 4 | OPEN | coding-plan RESET trio (`requestCodingPlanResetOpportunity`, `useCodingPlanReset`, `markCodingPlanResetHistoryRead`) — phone renders quota reset read-only | decode + typed faces | Part H.B1 |
| G87 | 4 | OPEN | real-time voice-input ASR plane (ticketed sessions, chunk upload) — phone relies on OS IME dictation (dead on API 36, F-34 class) | inventory row (build unlikely) | Part G.B4 |
| G88 | 4 | OPEN | 22 desktop themes vs phone's 5 (shipped zai pair = true parity) | cosmetic; inventory only | Part G.B5 |
| G92 | 4 | OPEN | `configOptions` decode-only (select/boolean + current values, one adapter from a phone render) | adapter + render | Part I.2.5 |
| G102 | 3 | OPEN (NEW pass 38) | WIRE DRIFT — desktop 3.18.1 REMOVED the legacy group verbs from the zcode-task channel: `createTaskGroup`/`renameTaskGroup`/`updateTaskGroupColor`/`deleteTaskGroup`/`listGroupedTaskViewStructure`/`applyGroupedTaskViewOrder` have ZERO byte-hits in the whole bundle (the zcode-task table now serves every OTHER task verb: setTaskPinned/setTaskUnread/deleteTask/renameTask/archiveTask/unarchiveTask/deleteArchivedTask/listArchivedTasks/resumeTask/…); the group plane moved to a dedicated `task-groups` channel exposing read()/save({id?,title?,color?})/ungroup(groupId)/move({node,sourceGroupId,groupId,anchor?})/admit({tasks,placement}) + `state:task-groups` broadcasts (renderer calls read+admit); the vc51 receipts prove 3.14.5 SERVED the legacy verbs live ("zcode-task.applyGroupedTaskViewOrder ok in 67 ms") — the phone's ENTIRE custom-group plane (Phase 5A A3.9) faults unknownMethod against the REAL desktop while working against the mock (which faithfully speaks the legacy protocol); the phone-side drift face includes the misleading "Couldn't move the task — unpin it first if it is pinned" (TasksViewModel.kt:465 — renders for ANY failure) | migrate the phone's group calls to the task-groups channel's read/save/ungroup/move/admit shapes (save unifies create+rename+recolor; move replaces the read-modify-write), or capability-gate the group UI honestly; fold in the move-failure copy fix; pins: the 3.18.1 method-table census (Part AO.L) + a transport-fake harness asserting the phone's group verbs against a task-groups-channel fake | Part AO.L | 1 |
| G103 | 4 | OPEN (NEW pass 40) | the read-only catalog planes have NO DETAIL/DEPTH consume: all six settings tool screens (Skills / MCP servers / Subagents / Providers / Bots / Commands) render current seeded lists with honest posture copy but the rows are NON-CLICKABLE BY CONSTRUCTION (Skills: ZERO clickable=true nodes) — no skill detail (the desktop opens skill bodies), no MCP server detail beyond the playwright typed-error face, no subagent/bot/provider row detail, no command detail; pass-40 census data: Providers now lists a SECOND provider family (OPENAI-COMPATIBLE glm-4.6) + Preview badges (glm-5.1-flash); Bots lists TWO bots incl. the draft·off state (bot-discord-1 on /synthetic/ws-two); MCP's typed failure face (Suggested steps + ECONNREFUSED + connection_failed) re-receipted | the G91/G82 viewer-family shape: a detail bottom-sheet per plane (tap row → the item's fields read-only); minimum = Skills detail (the highest-value catalog); pin: a compose test asserting a skill row opens a detail sheet with the body text | Part AQ.D | 1 |
| WATCH-4 | — | STANDING | V2 device-registration/pairing plane shipped on desktop; phone pairing stays V1 — flip is BREAKING (draft PR, human promotes) | AAP BREAKING path | Parts B, D.B2, K.B1 |
| Settings depth | — | STANDING | 51 settings domains (modelProvider 3,217 keys …); phone covers the conversation/notification/privacy/appearance slice; `workspaceFileSearch`(90)/`dynamicWorkflow`(45) are decode-vs-UI candidates; shortcuts/browser/migration desktop-legit | G92-class candidates flagged | Part J.2.3, B.B3 |

### Parity/opportunistic queue (not defects; ride alongside consumer work)

- **Terminal streaming wiring** — `terminalListenData/Exit` transport-ready, ZERO feature
  callers (`V4Plane.kt:1699-1709`); one subscription + render in the terminal feature.
- **BashOutput taxonomy line** — one `put("BashOutput", TASK_CONTROL)` in ToolKindTaxonomy.
  **Pass 34: the hole CONFIRMED live and GRACEFUL** (Part AK.C: no kind subtitle, the
  substring "bash" fallback buckets it EXECUTE so it groups correctly) — the one-liner
  buys only the subtitle.
- **Find two-scope parity (pass 36)** — the desktop's find-in-task is TWO-SCOPED
  (conversation ↔ changes with a scope toggle + jump-to-changes; the declared scope
  is "messages + file changes", CmdOrCtrl+F) vs the phone's single text-row scope
  (UserInput + AssistantText only — reasoning/tool rows unfindable by construction,
  Part AM.J/AM.D). Minimum = a changes-scope mirror or an honest scope note in the
  find bar; the kind-coverage widening rides the same line.
- **`inputOrigin:"mobile"`** on send — one line, desktop-side attribution.
- **`inputFormat` bits** (supportsImage/Video/Pdf) — decode in modelOptions for the
  "image attachments to the agent" ask.
- **Unread-badge sync** (`setTaskUnread`) + **systemService.info** endpoint-detail face.
- **Archived-task DELETE verbs + controller-V4 signal family** — protocol-exact spec
  behind auto-archive/unread (Part K.B1).
- **Draft-promotion pair** (`promoteDeferredDraftSession`/`closeDeferredDraftSession`) —
  consume/no-action decision (desktop twin of the phone's local draft plane).
- **`DeviceSyncState.getSnapshot`** — the read twin of the G93 plane (getSnapshot is a
  FAMILY: DeviceSyncState/ClientConfig/Skills).
- **turnHeader row-state plane (pass 13)** — the phone decodes the turnHeader row kind
  but consumes only its boundary (the "── Turn" separator, V4Conversation.kt:288); the
  row's STATE vocabulary ("running" etc.) is decode-only — the task machine keys on the
  `control` block instead (SessionState.kt:199-222). Receipted live on MOCK_TURN_RUNNING:
  no running face, no Stop-from-header — correct at HEAD (control is the state channel),
  recorded so nobody re-hunts it as a defect.
- **gitCheckpoint createCheckpoint** — unconsumed verb behind the "create one before
  risky changes" empty state (K.A2 small: wire a button or reword).
- **NO-ACTION forever list** (never "port" these): sync ×5, credential/oauth/onboarding/
  fileWatcher/userConfig, AppControl (CUA driver), CuaPipSession lifecycle, Bots
  authoring depth (read-only by ADR), git WRITE half (stage/commit/push — read-only
  posture), pluginManagement 17 methods, voice-input service.

**Open G count: 23 + 3 standing** (G60, WATCH-4, settings depth; G93's enum residual closed; G102 opened pass 38 — the group-verb wire drift; G103 opened pass 40 — the catalog planes' detail-depth gap).

## N-rows — smalls

| ID | P | Status | One line | Evidence |
| --- | --- | --- | --- | --- |
| N-BM-1 | 3 | OPEN | load-earlier strip total is a stale subscribe-time snapshot ("420 of 339") | Part J, K.A3 (+ pass-35 stamp: the mechanism re-receipted at HEAD with a FAITHFUL total — "320 of 400" → "370 of 400", X live / Y frozen, ConversationSessionManager.kt:241; the pass-6-era "impossible claim at the true end" sub-claim NARROWED — the end face is honest at HEAD, the strip hides on hasMore=false, Part AL.C) |
| N-BM-3 | 3 | OPEN | Context-sheet → Terminal route: Attach is a silent no-op (main terminal screen attach works — sheet variant dead) | Part J |
| N-BK-1 (app half) | 2 | OPEN | History silent-empty needs a "No commits yet" empty state (rig half FIXED `2b12fc46`) | Part G.A2.3 |
| N-BJ-1 | 4 | OPEN | attachment image previews have NO size/aspect cap (1×1 seed renders ~300×220dp) | Part F.A2, K.A3 (+ pass-11 stamp: s04 unchanged) |
| N-BK-2 | 4 | OPEN | automation Edit sheet schedule chips clip at phone width | Part G.A2 (+ pass-16 stamp 2: "Weekda"/"Cr" clipped live under the arms, S.A3) |
| N-BL-1 | 4 | OPEN | workflow-run Run/Manage icon buttons subtree-unlabeled (TalkBack silence) — ONLY unlabeled clickables found | Part H.A2 |
| N-BM-2 | 4 | OPEN | child-session view: generic title, skeleton-forever on unscripted children | Part J (+ pass-17 stamp: the automation run's Go-to-session on an unscripted session renders the generic "Conversation" title over the skeletons, T.A2) |
| K-small-1 | 4 | OPEN | checkpoint empty state says "create one" with NO create affordance (verb unconsumed) | Part K.A2 |
| K-small-2 | 4 | OPEN | "First sync failed" renders alongside CACHED rows (wording/state nit) — pass 9: the strip can be entirely ABSENT when the F-P9-A wedge suppresses the refresh | Part K.A2, L.A3 |
| N-BN-1 | 4 | OPEN (NEW pass 10) | Plugins screen: the per-plugin enable switches are UNLABELED (plugin_switch_plugin-prisma / -review carry no content-desc, no text children — TalkBack silence on the row's only interactive element) | Part M.A1, M.A2 |
| N-BO-1 | 4 | OPEN (NEW pass 11) | the changes viewer's error face interpolates the RAW fault code ("Could not load changes: fault.fileChanges.none" — `changes_error` %s, Phase9Components.kt:379) — the G66 fault→human mapping (2af339b0) doesn't cover this Phase9 surface | Part N.A2 (+ pass-13 stamp: SECOND surface receipted live — the quota reset failure face renders "Reset failed · fault.mock.unscripted:useCodingPlanReset", `quota_reset_error + " · " + err` at CodingPlanQuotaSection.kt:456; Part P.A1. **Pass-30 stamp 3: THIRD surface** — the attachment FAILED notice renders the raw fault code in the user banner ("Attachment failed to upload (fault.attachment.commitFail) — not sent.", `ConversationViewModel.kt:371` interpolating `result.message` = `TransportFailure`'s developerDiagnostic via `ErrorMapping.taskRejected`, `V4Plane.kt:30/:628-631`; Part AG.C). **Pass-31 stamp 4: FOURTH surface** — the resurrected-chip first-attempt
banner interpolates `fault.attachment.unknownUpload` verbatim ("Attachment failed to
upload (fault.attachment.unknownUpload) — not sent.", s13; `TransportFailure`'s
developerDiagnostic through the same `ErrorMapping.taskRejected` chain; Part AH.B) |
| N-BP-1 | 3 | OPEN (NEW pass 13) | the Usage screen DOUBLE-applies the Scaffold `inner` padding (PullToRefreshBox line 141 + the inner Column line 146 — the only viewer with two `padding(inner)` applications; all 11 sibling viewers have exactly one) → a ~400px dead band under the top bar that is ALSO A DEAD TOUCH ZONE: scroll and pull-to-refresh gestures starting inside it are swallowed (8 receipted attempts, zero re-queries); content scrolls only from drags begun below the band | drop the second `.padding(inner)`; pin: a compose test dragging from the band region asserts the list scrolls | Part P.A1 |
| N-BP-2 | 4 | OPEN (NEW pass 13) | Daily-trend x-labels render "2026-" for every row — `day.date.take(5)` (UsageScreen.kt:613) slices the ISO date the decoder feeds; the slice assumes an MM-DD shape | slice after the year (or format the full date); pin on the label text | Part P.A1 |
| N-BQ-1 | 4 | OPEN (NEW pass 22) | the effort switch's typed stale face is DEAD CODE: `setThoughtLevel` rides the PROTOCOL method whose host layer answers a JSON-RPC ERROR on revision mismatch (FQ → `Dl -32009`, zcode.cjs sPi `~0xe99b3a` — the `{status:"stale"}` ack exists only in the command dispatcher), the phone collapses any throw to FAILED (`Ports.kt:1000-1013` has only ACCEPTED/FAILED; `V4Plane.kt:1429` never inspects status; `ChannelSession.kt:69-76` keeps only the message), so `ConversationViewModel.kt:336`'s SESSION_CHANGED branch can never fire — the effort switch renders the GENERIC "desktop refused" ERROR where its whole family (mode/model/queue-verbs) renders the honest "try again" warning (live receipt: relay -32009 line → logcat TransportFailure → generic banner s07, chip unchanged; desktop contrast: the renderer's own facade has NO CAS at all — the check serves only remote clients, i.e. exactly the phone) | map the protocol-plane error by its revision-mismatch identity so STALE propagates (or re-shape the call); pin: a transport-fake harness asserting setThoughtLevel STALE propagation; re-drive the live face after the fix | Part Y.B/Y.D, Z.E, AA.D | 3 (pass 23: `medium` — same -32009 → generic face + unchanged chip; high [22], medium [23], xhigh [24] — the 4-radio sheet's first live observation, arm level-agnostic so the triple saturates what the rig can differentiate) |
| N-BR-1 | 4 | OPEN (NEW pass 28) | the composer attachment chip's Retry SUCCESS leaves the stale offline banner displayed: `uploadAttachment` sets the ATTACHMENT_OFFLINE notice on BlockedOffline (`ConversationViewModel.kt:369`) but `retryUpload` (:384-391) mutates NO notice on `Committed` — after a successful retry (wire begin/chunk/commit all ok) the banner keeps claiming "Offline — the attachment was not sent. Retry when connected." beside the ✓-ready chip until the next send's ACCEPTED clears it or the user taps ✕ (`dismissSendNotice` :426); live receipt: s16 single frame (✓ chip + stale banner simultaneously) → Retry committed → banner cleared only by the following send (s17). Pass 29 EXTENSION: `removeUpload` (:376-378) mutates NO notice either — the Remove path is the SECOND lying window, receipted live on BOTH chip states (error-state Remove AND ready-state Remove each leave the banner up, chip-less and still claiming "not sent"; AF.C/AF.E). Pass 30 EXTENSION (the CROSS-CHIP window): the banner is per-conversation, not per-chip — chip B's SUCCESSFUL retry (✓ ready, s10) leaves chip A's stale ATTACHMENT_FAILED banner ("Attachment failed to upload (fault.attachment.commitFail) — not sent.") displayed verbatim | clear the keyed notice in retryUpload's Committed branch AND in removeUpload (one-liner each — the cross-chip shape falls out of the same fix); pin: a harness asserting retry→committed→banner-gone + remove→banner-gone | — | Part AE.E, AF.C/AF.E, AG.D (receipt + pins) | 3 (pass 30: the cross-chip retry-success window — s10 the ✓ 130.png chip + chip A's FAILED banner single-frame) |
| N-BS-1 | 4 | OPEN (NEW pass 30) | the MIXED-state send (one ✓ ready + one ✗ failed chip) silently EXCLUDES the failed chip from the payload with NO per-send signal: the ready-only filter (`Ports.kt:391`) drops it, the ACCEPTED consumption (:417-421) removes only ready chips and mutates no notice about what was left behind — the message goes out as if complete; the user's only trace is the lingering ✗ chip (which stays for the NEXT send to trip over); live receipt: s10 (✓ 130.png + ✗ rig-p30-mid.bin) → send → outbox `ACKED/{"text":"mirobexed ","attachments":[130.png ONLY — 1-element array]}` → s12 (the ✓ chip consumed, the ✗ chip LINGERING, the banner cleared by ACCEPTED, no exclusion notice) | a keyed notice on ACCEPTED-with-excluded-chips ("Sent without <name> — the upload failed") or a pre-send confirm; pin: a harness asserting the mixed send surfaces the exclusion | N-BR-1 (same notice plane) | Part AG.D (receipt + pins) | 1 |
| N-BT-1 | 4 | OPEN (NEW pass 37) | the stale ROW-ACTION banner renders the SWITCH-specific copy: `rowActionNotice`'s STALE branch (`ConversationViewModel.kt:1147`) passes the correct "The session changed on the desktop — try again." with the SHARED `SendNotice.Key.SESSION_CHANGED`, but the banner renderer resolves BY KEY (`WriteComponents.kt:1751` → `R.string.notice_session_changed`, strings.xml:231 = "…— try the **switch** again.") and discards the per-path message (args only for ATTACHMENT_FAILED) — so every stale row action renders switch copy: feedback LIVE-receipted (gen3 MOCK_CAS_STALE_TYPES=setAssistantFeedback → the banner verbatim, s14; rail unchanged), and the same-path trio retryTurn :990 / forkAssistant :1023 / applyFileRewind :1119 statically; the switch call sites (:322/:337/:351) render their own copy correctly; the queue verbs' stale faces carry their own "Queue changed" key (unaffected) | a distinct key for the row-action stale (ROW_ACTION_STALE → notice_row_action_stale "…— try again.") or render notice.message for SESSION_CHANGED when non-blank; pins: a harness asserting the feedback-stale banner does NOT contain "switch" + the four rowActionNotice call sites enumerated | — | Part AN.G (receipt + pins) | 1 |
| N-BU-1 | 4 | OPEN (NEW pass 38) | the tasks ACTION NOTICE renders in the STATUS-BAR zone: the lifecycle notice ("Marked read" s04, "Group created" s18, "Moved to group" s19) draws at screen top y≈25 overlapping the status bar/clock — no top-inset on the notice slot (tasks_action_notice); readable but in the OS layer | pad the notice into the inset area (or a snackbar slot); pin: a compose test asserting the notice's bounds start below the status-bar inset | — | Part AO.M | 2 (pass 39: the CAS REFUSAL notice "Couldn't update the task — try again" renders in the same status-bar slot, s24 — the face class covers refusals too, Part AP.J) |
| N-BV-1 | 4 | OPEN (NEW pass 38) | EMPTY custom groups are AFFORDANCE-LESS: the group sub-section render skips zero-member groups (`byGroup[g.id] ?: return@forEach`, TaskListScreen.kt:1283-1290) so the header — and with it the ONLY Edit (✏️)/Delete affordances — never renders; the orphan group stays in every move sheet forever; receipted live: after move-out the "Rig p38 green-to-blue" header vanished while the move sheet still listed the group (re-move needed to reach Delete) | render empty groups' headers (count 0) or expose delete via the move sheet/overflow; pin: a compose test asserting an empty group is deletable from the list | — | Part AO.J | 2 (pass 39: the freshly created "shgrp" group rendered NO header (empty → skip) — and the strand fed F-P39-A's dropped move, s15-class w21/w22, Part AP.E) |
| N-BW-1 | 4 | OPEN (NEW pass 39) | collapse-all from the TIMELINE organize NEVER survives a restart: `collapseAll` maps `state.groups`' workspaceKeys (TaskListScreen.kt:331) which in timeline mode is the single sentinel `TIMELINE_GROUP_KEY = "timeline"` (TasksViewModel.kt:70) — the set persists to the file store faithfully, but the restart always lands in WORKSPACE organize (organizeBy is view-local, TasksViewModel.kt:102, BY-DESIGN reset) whose keys are real workspace paths, so the persisted sentinel matches NOTHING and every section renders expanded; receipted: collapse-all (all headers "Tap to expand", 83 nodes) → force-stop → relaunch → "Tap to collapse" everywhere (w24/s17 → w26); the desktop's GAP-14 port contract ("persisted per workspace key so it survives app restart") is defeated by the key mismatch | persist per-mode key namespaces (timeline vs workspace) or translate the sentinel on load; pin: a compose test collapsing in timeline → recomposing in workspace asserting the collapsed faces | — | Part AP.G | 1 |
| N-BX-1 | 4 | OPEN (NEW pass 40) | the git branch block renders the LITERAL "tracking: null" after a branch switch to an upstream-less branch: `Phase9Tools.strOrNull` (Phase9Tools.kt:471) is JsonNull-BLIND — `(this[key] as? JsonPrimitive)?.content` matches JsonNull (whose content is the STRING "null", non-blank) so the mock's merged-summary `trackingBranchName: JsonNull` decodes to "null" instead of null, and `stringResource(git_tracking, s.trackingBranchName ?: "—")` (GitScreen.kt:253) renders the raw literal; receipted live: post-create-and-switch re-entry branch block "rigp40 / tracking: null" (s13); the codebase's own Phase9Ports.rpStr (:269) HAS the `it !is JsonNull` filter — the two helpers disagree | add the JsonNull filter to Phase9Tools.strOrNull (one line, same shape as Phase9Ports.rpStr); pin: a decode harness over a JsonNull trackingBranchName asserting null | — | Part AQ.B (s13) | 1 |
| N-BY-1 | 4 | OPEN (NEW pass 40) | the git Changes rows' Stage/Discard/Unstage a11y labels carry the RAW format string: the semantics use `stageCdFmt.replace("%s", path)` (GitScreen.kt:768/:781) but the resources are POSITIONAL — `git_stage_cd` = "Stage %1$s" (strings.xml:73; zh-rCN too) — so `%s` never matches and every TalkBack user hears "Stage %1$s" (dump receipted: content-desc "Stage %1$s" / "Discard %1$s" ×3 rows, w7); the visible labels are fine (plain "Stage") — a11y-only | use `%1$s` in the replace (or positional formatArgs); pin: an a11y assertion on the row's content-desc containing the path | — | Part AQ.B (w7) | 1 |
| K-small-3 | 4 | WATCH | one-off terminal route pop (unreproduced — watch, don't chase) | Part K.A2 |
| N-BJ-2 | 4 | REFUTED→FOLDED | unpair IS reachable (Settings bottom, pass 4); fakeLink half folded into F-P2-B | Part G.A2.4 |
| N-BM-4 | — | RIG | Dictate leg kills the emulator (cause unproven); leg undriven | Part J |

**Open smalls: 22 (+1 watch +1 rig)** — pass 13 added N-BP-1 (P3) and N-BP-2 (P4);
pass 22 added N-BQ-1 (P4, the effort stale face's dead branch); pass 28 added
N-BR-1 (P4, the stale offline-attachment notice); pass 30 added N-BS-1 (P4, the
mixed-state send's silent exclusion); pass 37 added N-BT-1 (P4, the stale
row-action banner's switch copy); pass 38 added N-BU-1 (P4, the status-bar-clipped
action notice) and N-BV-1 (P4, the affordance-less empty groups); pass 39 added
N-BW-1 (P4, the timeline collapse sentinel never survives a restart); pass 40
added N-BX-1 (P4, the JsonNull-blind decode renders "tracking: null") and
N-BY-1 (P4, the Stage/Discard a11y labels carry the raw "%1$s").

## Mock-fidelity debts (rig, not app)

1. Accepts ANY interactionId — add the unknown-id typed error arm (unblocks honest-failure faces).
2. ~~`fault.file.notFound` on seeded `readTextFile` (listing-only seeds)~~ **CLOSED pass 16** (`9b6e2292`): seedWorkspaceFiles seeds default content for EVERY path (explicit fileText keeps precedence); the main.rs open receipted live (S.A1).
3. ~~Snapshot doesn't list the once-per-connection PUSHED interaction → rows sweep legitimately STALEs it.~~ **CLOSED pass 18 fixed-by-prior-work** (`2b12fc46`, pass 5): `snapshotPendingInteractions` serves the FULL pending list once the fresh-push delivered (`interactionPushDelivered`) — the only exclusion window is BEFORE its own patch lands (the RIG-P1 anti-race, by design). Desktop evidence: the host pane resolves the active permission card FROM `snapshot.pendingInteractions` and the notification producer filters that snapshot list — a delivered interaction IS snapshot-listed on the desktop, so the mock's post-delivery inclusion is the faithful shape. The F-P0-B stale-sweep receipts stand on their pinned row-plane mechanism; this confound is gone.
4. ~~Static session-config echo re-asserts the effort chip~~ **effort half CLOSED pass 16 (S.A2 — does not reproduce at HEAD; the G3 merges + thoughtLevelConfig mutation fixed it in earlier mock work; chip "max"→"high" stable 8s+)** ~~the ROUTING half stands: the composer delivery picker (Guide tap) fires no wire verb~~ **ROUTING half CLOSED pass 17 (T.A1 — BY-DESIGN): the config-chip delivery picker is the G49 ONE-SHOT `deliveryOverride`; no pick-time verb is the contract, the icon TINT is the armed face, and the wire carries `requestedDelivery` on the next send — receipted live on the full trio** (`MOCK: sendText trio heldQueueDisposition=keepQueueAndSend requestedDelivery=guide expectedHeldQueueItemIds=[q-rig-1, q-rig-2]`, `sendConversationCommandV4 ok in 1805ms`).
5. ~~Raw optionId labels (`allowOnce`) instead of human labels (desktop sends `label: c.name`)~~ **CLOSED pass 16** (`9b6e2292`): options now carry `label: "Allow once"/"Allow always"/"Deny"` (unknown ids verbatim), self-tested; the on-device RENDER is F-P3-A-gated (both compositions starved — S.A2).
6. ~~Push arm is CONVERSATION-SUBSCRIBE-anchored (recipe: reconnect with conversation open, lock inside the arming window).~~ **CLOSED pass 18 by-adjudication**: the anchoring IS the desktop shape (the interaction plane rides the conversation subscription — pass-6/8 receipts); `155a3c02` armed it that way on purpose so the FRESH notification path is genuinely exercisable. The recipe stands as the drive procedure, not as an open debt.
7. ~~`MOCK_REMOTE_DOWN_MS` / `MOCK_MD_FILE` arms exist but were never driven (gen2 append-after-exec bug).~~ **CLOSED pass 18** — the parenthetical was already the closure: both arms driven pass 9 (Part L), healthy since.
8. ~~**The automations plane needs `MOCK_OFFPEAK=1` ALONGSIDE `MOCK_AUTOMATIONS=1`** — without it the app's `off-peak-task.list` faults (`fault.mock.unscripted:offPeakTaskList`) and home renders the "This feature isn't available" face (pass-10 receipt; the launcher /tmp/p10/relay-g1.sh carries the fix).~~ **CLOSED pass 18 by construction** (`6846727d`): `seedOffPeakRigList` extracted and served by MOCK_AUTOMATIONS=1 when MOCK_OFFPEAK is unset (the desktop's automations ARE scheduled/off-peak tasks — a session serving listAutomations always serves the list); MOCK_OFFPEAK=0 opts out. Self-tested (the 5/5 pass-18 file); the plane itself stays receipted by passes 10/16/17 (code path unchanged — no live re-drive needed).
9. ~~The scripted attachment's registration raced the app's cached-row read~~ **FIXED pass 11** (`1e649eab`: eager registration in setScriptedAttachment; cold-start read ok 306ms, zero unknownRef on gen3). Related note: `MOCK_MD_FILE` takes a FILE PATH, not a flag — `MOCK_MD_FILE=1` crashes the relay at boot (FileNotFoundException: 1).
10. ~~The scripted reset~~ **CLOSED pass 14** (`8361b769`): `MOCK_RESET_SUCCESS=1` scripts `{used:true}` + stamps the use; every later status read serves the CONFIRMED progression (consumed opportunity + usedAt + unread cursor) — the success face receipted live (Q.A2). **The resetStatusQueue VARIANT is also closed (pass 15, `66844fbe`): queue entries serve EXPLICITLY (only the unread fold applies — the pass-14 fold no longer confirms them in place) and `MOCK_RESET_QUEUE_PRE=1` arms the ladder leg** — the reconciliation retry ladder receipted live (R.A3).
11. ~~The off-peak relay-start anchor~~ **CLOSED pass 14** (`8361b769`): `MOCK_OFFPEAK_COMPLETE_ANCHOR=first-list` arms the flip at the notifier's baseline seed; live-verified (the flip fired mid-life after the baseline in gen3/gen3b, Q.A3).
12. ~~The frame-class filter~~ **CLOSED pass 14** (`8361b769`): `MOCK_DISCONNECT_AFTER_DATA_FRAMES=<n>` counts DATA pushes only; live-verified at =2 (survived handshake + 70s of polls, died at the 2nd data push, Q.A4).
13. ~~The deltas relay-start anchor~~ **CLOSED pass 14** (`8361b769`): `MOCK_RUN_DELTA_ANCHOR=subscribe` arms the schedule at the first conversation subscribe; live-verified (the deltas landed at subscribe+6.1s/+22.1s on a relay 5 min old, Q.A1).
14. **(pass 13, RIG)** the API-36 emulator NAT: after a server-side abrupt socket close (the disconnect arm), the emulator's IPv4-mapped-IPv6 path blackholes NEW app flows (SYN-SENT hangs) while pure-IPv4 device flows (`nc -z 10.0.2.2`) still succeed — recovery = full emulator restart. Not an app defect (the same stack connected 10+ times before). **Pass-14 addendum: it is PROBABILISTIC — pass 14's pass-start pkill armed it once (one dead AUTHENTICATING, Q.A0.1) and six later abrupt-close swaps recovered normally.** **Pass 18: reclassified STANDING RIG-ENV knowledge** — not an open work item (unfixable in mock code; the recovery is the recipe). Did not manifest this pass: two idle-socket drops recovered honestly WITHOUT a restart (Part U).
15. ~~**(pass 14)** `markCodingPlanResetHistoryRead` acks but never clears `hasUnreadHistory`~~ **CLOSED pass 15** (`66844fbe`): the mark now CLEARS the unread cursor on every later status read (the desktop's server behavior); the pass-14-declared arrival counter went live. Live-verified (relay "reset history read" line, R.A2).
16. ~~**(pass 14)** the scripted reset doesn't mutate the entitlement meters~~ **CLOSED pass 15** (`66844fbe`): a stamped use mutates the used window's limit entry (delta 10, clamped; remaining/percentage recomputed) on later getEntitlementSnapshot reads. Live-verified: Weekly 49%→59% beside the completion note (R.A2, s05).
17. ~~**(pass 16)** the mock's resync SNAPSHOT omits delta-pushed effect rows — `pushRowDelta` rows live only in the resync opLog, never in the snapshot builder's row source — so after a force-resync (the watchdog's escalate) pushed rows VANISH from the timeline~~ **CLOSED pass 17** (`05ba5c18`: the stall arm's live frames + silent backlog rows join the authoritative `appendedRows` tail, so `snapshotRows(...) + appendedRows.values.toList()` carries them; self-tested 2 ways) — **live receipt T.A3**: under the exact pass-16 force shape (`attempt=2 force=true` → `snapshotReconcile rows=166`) the timeline RETAINS the stall rows (frames 147-150 render; pass 16 saw them vanish).
18. ~~**(pass 17)** the mock never served `inputRouting.mode "choice"` (only startNow/enqueue) — the phone's held-queue CHOICE disposition dialog (G50) was structurally undrivable~~ **CLOSED in the same pass** (`05ba5c18`: the `MOCK_INPUT_ROUTING=<mode>` arm pins the desktop's 5-mode enum `startNow|enqueue|guide|reject|choice` with a STICKY flag that suppresses the send/stop flips — fail-fast on a bad value per H.A0.2; + the sendText trio log line + the clearQueueAndSend server-side honoring). Live receipt: the dialog rendered first-ever and the full trio rode one sendText (T.A1).
19. ~~**(pass 18)** the mock ignored `requestedDelivery:"queue"` on sendText — the desktop answers `inputAccepted{delivery:"queue"}` and HOLDS the input as a queue item (the bot path marks the task waiting/Queued), but the mock appended user/assistant rows immediately, un-queuing the send and hiding the G49 one-shot "Queue this send" override's whole point~~ **CLOSED in the same pass** (`6846727d`: the sendText arm enqueues `q-rig-N` + `pushQueuePatch` + the routing echo and appends NO rows; composes with the clear disposition — empty→exactly the new item — and keep — grows; self-tests 5/5, suite 172/0/0). **Live receipt U.A1**: trio `keepQueueAndSend + queue + [q-rig-1,q-rig-2]` → q-rig-101 appended → strip 2→3, then `startNow + clearQueueAndSend + [q-rig-1,q-rig-2,q-rig-101]` → strip GONE + immediate rows.
20. ~~**(pass 19)** the mock honored `heldQueueDisposition` blindly — no held-queue CAS, so the G50 cross-side mismatch face was structurally undrivable~~ **OPENED+CLOSED in the same pass** (`0a037a60`: `MOCK_QUEUE_GHOST_APPEND_MS=<ms>` arms the host-faithful set-equality CAS on trio-bearing sendText — mismatch answers the desktop ack `{status:"failed", reasonCode:"guard.heldQueueConfirmationStale", message:"paused queue changed after send confirmation opened"}` and mutates NOTHING — plus the one-shot SILENT ghost append `<ms>` after the first trio (no patch, no revision bump), the race driver; requires MOCK_QUEUE=1 + MOCK_INPUT_ROUTING=choice; self-tests 7/7, suite 179/0/0). **Live receipt V.D**: CAS-ok leg (accepted, rows appended) → ghost lands silently → CAS-MISMATCH leg → the phone's refusal notice + draft preserved + outbox `FAILED_TERMINAL/guard.heldQueueConfirmationStale` (the DB arbiter).
21. ~~**(pass 20)** the send-ack taxonomy's driveable faces had no arms — the GOAL half of the admission envelope (a disposition-less sendGoalCommand under choice must answer the host's `heldQueueDispositionRequired` failure ack) and the stale face class (no way to force a `status:"stale"` ack) were structurally undrivable~~ **OPENED+CLOSED in the same pass** (`642616ef`: `heldQueueGoalAdmissionRejection` — the goal half gated on the SAME `heldQueueCasArm` envelope as the sendText CAS, old rigs' shapes unchanged, host-verbatim message; `MOCK_CAS_STALE_TYPES=a,b,c` — listed command types answer `{status:"stale", reasonCode:"proto.staleRevision"}`, sticky, mutates nothing; self-tests `MockZCodePass20RigSelfTest` 8/8, suite **187/0/0**). **Live receipts W.D**: the goal-refusal leg (wire → ERROR notice → outbox `FAILED_TERMINAL/heldQueueDispositionRequired`, payloadJson WITHOUT the trio) and the stale leg (wire → the typed "Queue changed on the desktop" WARNING → outbox `FAILED_STALE/proto.staleRevision`).
22. ~~**(pass 30)** the attachment transaction's non-offline failure faces had no arms — no way to fail a begin/chunk/commit WHILE CONNECTED (the port's `UploadResult.Failed` was only reachable via a mid-transaction disconnect, which collides with the BlockedOffline re-classification) and no way to catch the ↑ NN% uploading chip (512 KB commits are instant)~~ **OPENED+CLOSED in the same pass** (`d21db7a0`: `MOCK_ATTACHMENT_FAIL=<begin|chunk|commit>` — the named phase answers the RPC ERROR `fault.attachment.<phase>Fail` while CONNECTED, so the transport throws `TransportFailure` → the port's GENERIC Failed path (chip failed, payload RETAINED, Retry live); unknown values arm nothing; `rejectAttachments` still outranks it on begin; `MOCK_ATTACHMENT_CHUNK_DELAY_MS=<ms>` delays each attachmentChunkV4 answer; self-tests `MockZCodePass30RigSelfTest` 6/6, suite **204/0/0**). **Live receipts AG.B–AG.D**: 2 forced commit-fails (mid.bin retry + 130.png first attempt — RETRY_FAILED and FAILED-with-raw-fault faces), the ↑25% chip caught twice, and the gen2 clean-arms ready commit for the mixed send.
23. ~~**(pass 38)** the CONTROL (bootstrap) task view was a stale second surface — it served raw `MockTask.toJson()` rows while every management verb (setTaskPinned/setTaskUnread/renameTask/deleteTask/archiveTask/deleteArchivedTask) mutated only the decorated CHANNEL surfaces~~ **OPENED+CLOSED in the same pass** (THREE facets, three commits: `6b9e882f` p38-mock-decorate — the view flows through decorateMeta + rename/deleteTask sync the bootstrapTasks objects (the live receipt: mark-read ok → "Marked read" → the dot/bold/menu still claimed unread, DB untouched; the vc51-era "mock acks without mutating" attribution CORRECTED to this stale-view mechanism); `046fec07` p38-mock-archive-exclude — the view excludes archivedTaskIds (the live receipt: push applied tasks=2 → the refresh re-applied tasks=3 within 1s, the archived row back on home); `9b5f9ad0` p38-mock-deletearchived-sync — deleteArchivedTask syncs bootstrapTasks (the live receipt: the deleted task resurrected on home AND its group's member list starved — groupedStructure reads directTasks); `a65058e1` pins the createSession effect (view 3→4). Suite **241/0/0**. On the REAL desktop the control view derives from the same DB as the channel list, so all three facets are rig-only. **Residual WATCH (not a debt):** the gen3-era v4-command reply path never acked createSession (`command.sendLost` after 20s) — the pass-38 WATCH in AO.K; re-drive before hunting. → **RESOLVED pass 39 (AP.D): the create round-trips on a fresh generation (tasks.create → createSession acked, zero sendLost, the row rendered + DB `sess-created-1`); the hang was the stale-gen3 jar.**
24. ~~**(pass 39)** the createSession effect hard-coded the created task's `workspaceLabel` to "Synthetic Workspace" regardless of the source workspace — on the live rig the created row under `/synthetic/ws-one` carried the divergent label and the phone's home rendered an incoherent section header ("Synthetic Workspace · 3 tasks" whose row subtitles all read "Synthetic One"; DB-arbitered r3.db)~~ **OPENED+CLOSED in the same pass** (`f6213c82` p39-mock-created-label: the created task inherits the label from a same-path sibling bootstrap row, default fallback unchanged; self-test asserts "Synthetic One"; suite 248/0/0).
25. ~~**(pass 40)** the ADR-021 git WRITE tier's acks mutated `script.stagedPaths`/`discardedPaths` while `getChanges` kept serving the STATIC RigGitFixtures fixture — the phone staged, re-fetched, and saw the same rows (the recorded ack-without-mutate class ON THE GIT PLANE; nothing consumed the lists; commit left all three rows served)~~ **OPENED+CLOSED in the same pass** (`baa3f216` p40-mock-git-write-mutation: `servedGitChanges()` derives the changes view from the write tier's mutations — staged paths serve section "staged", discarded/committed rows drop, matching the phone's workspaceRelativePath keys against the fixture's absolute `path`; `commit` consumes the served rows it commits so the post-commit fetchChanges() sees the shrink; + MOCK_GIT_BRANCH_ISSUE / MOCK_GIT_PUSH_FAIL arms; MockZCodePass40GitWriteSelfTest 6/6, suite **254/0/0**). **Live receipt AQ.B**: Stage → `stagePaths ok` → the row MOVED tabs (Unstaged 2→1, Staged 1→2, s14); Discard via the typed gate → the row GONE; commit → Changes EMPTY (s21). Residuals (NOT debts — mock-fidelity notes): History never gains the committed row (script.gitCommits static); the summary stays isDirty=false/ahead=0 through the commit; the comparison fixture is branch-invariant.

## DISCOVERY BACKLOG (tick when the findings are appended as rows)

- [x] **1. Desktop Mobile-Remote UI/UX audit** — DONE pass 9 (Part L.B3): the plane is
  webRemoteControl.* (90 keys) — QR→browser connectUrl companion, session lifecycle,
  12-way typed failure taxonomy, workspace/task switching, bot channels, singlePageNote,
  pullMobileLogs, settings.mobilePush desktop toggle → **G97 (P3)** phone-side blindness.
- [x] **2. Channel method tables — DONE pass 10 (Part M.B1):** the last 8 tables
  extracted exact (ProviderSettings 15, ModelSelection 3, SourceServerWorkspace 6,
  ZCodeTask controller family = Xz's 7 routed + 4 bot passthroughs + the WindowController
  channel's 14 members, ConversationShare 13 + typed stubs, CuaPermission 2, MediaPreview
  1 ⚠ CLOSED, WindowProjectChanges 2 ⚠ CLOSED — both ⚠ attributions resolved as
  host-served). ~41 tables now exact across Parts G–K+M + the 49-channel exposure policy
  (M.B2). No ⚠ remain.
- [x] **3. Renderer route/screen inventory — CLOSED as a dead angle** (no URL-route
  vocabulary exists: 2,862 lazy chunks, scene/state-driven nav — Part K.B3; do not
  re-hunt). **The i18n namespace diff DONE pass 10 (Part M.B3):** 84 desktop namespaces /
  6,515 keys adjudicated → G98/G99/G100/G101 + the settings-domain census stands from
  pass 7 (51 domains). The namespace diff is EXHAUSTED — every no-counterpart namespace
  is now either rowed, folded into a row, or classified desktop-legit.
- [x] **4. The unassessed clientHello/runtimeCapabilities bits** — DONE pass 9 (Part L.B1):
  all 10 traced + the full 13-name schema; NEW rows G96 (selection-comment 批注) and
  G94/G91 adjacency notes; phone answers NOTHING (zero runtimeCapabilities handling).
  sessionLifecycleWorkspace + extensionInputSource are desktop-reserved (zero consumers).
- [x] **5. Named residuals — DONE (G93 enum half pass 9, ⚠ attributions pass 10)** (Part
  L.B2, M.B1): kind ∈ {question, approval, plan, task_completed, task_failed}, state ∈
  {open, resolved}; producer = host chunk-7SAPNXNZ.js collectMobilePushEvents. **The
  MediaPreview/WindowProjectChanges ⚠ attributions CLOSED pass 10** — both are
  host/index.js-served (createMediaPreviewService's prepare; createWindowProjectChangesService's
  getCapabilities+onDidChange).
- [x] **6. Coverage leftovers (rolling)** — pass 9 closed: the lockscreen leg CORE
  (push-while-locked over LIVE receipted 2×; the shade-ACTION tap = STANDING RIG STOP),
  MOCK_REMOTE_DOWN_MS ✓, MOCK_MD_FILE ✓, mention-panel tabs ✓. **Pass 10 closed:** the
  diagnostics plane, the 17-screen a11y audit (N-BN-1), the automations plane's
  unavailable-face, the font-scale matrix (1.15× max), the zai-dark theme matrix, the
  fresh-install pairing chain, F-P8-C's drag re-verification. **Pass 11 closed:** the
  user-row long-press ACTIONS plane (all four actions), F-P8-E's follow-release leg
  (REFUTED — the release + numbered jump FAB work), the MD_FILE render half (light
  rig), the changes viewer (typed error face + the MOCK_FILECHANGES happy path), the
  Licenses share, the What's-new empty state, the Live-connection window picker
  vocabulary. **Pass 12 closed the LAST driveable piece:** the Notification detail
  LEVEL toggle (driven WORKING both directions, disk-arbitered via the STRING values
  in preferences.pb; the pass-11 "Minimal/Standard dialog" naming corrected — it is a
  direct two-value toggle row, no dialog). REMAINING (all non-driveable): question
  card options post-F-P3-A-fix (F-P3-A open), Take-photo happy path (impossible —
  stop), widget add-to-home (standing stop), re-drive FIXED rows once fix rounds
  start, the shade-ACTION approve (standing stop), TalkBack runtime (real device).
  **ITEM CLOSED — backlog 8/8; campaign SATURATED at pass 12 (Part O).**
  **Pass 15 (Part R) drove the Part-Q coverage-honesty DRIVEABLE leftovers:**
  the Usage range chips (CLOSED no-defect — the chips drive, the wire carries
  the `"all"|"7d"|"30d"` enum per ViewerTools.kt:31-37, the rig fixture is
  range-invariant), the reset meter/unread residuals (#15/#16 CLOSED live), the
  reconciliation retry ladder (first receipt — 3 reads, +250 ms from the
  previous read's completion), the disconnect filter at limit=1 (one frame
  earlier than =2; honest recovery), and the F-P13-A stay-open boundary. What
  remains on that list is standing stops + F-P3-A-gated planes only.
- [x] **7. Phone self-audit expansion — DONE pass 10 (Part M.A1/M.A2/M.D):** the a11y
  sweep now covers 17 screens beyond the pass-3/5 conversation+settings+home set (one
  new small: N-BN-1); the diagnostics-export content receipted (1,174 lines,
  redaction-verified); settings-search edges N/A (this build's Settings has no search
  field); font matrix driven at the phone's real max (1.15×) + reverted; zai-dark
  driven + reverted. REMAINING within this item: TalkBack runtime behavior (the census
  is static AX; no TalkBack session was driven — a real-device leg if ever wanted) and
  the locale matrix (zh round-trip was receipted pass 5).
- [x] **8. Shipped-content catalogs** — plugin inventory 18 (matches pass-35), slash-20
  parity EXACT (pass 6), settings-domain census done (51 domains, pass 7). DONE unless a
  desktop delta changes the catalogs.

## Prompt templates (paste-ready; the ledger + report tail make them self-adjusting)

**Discovery pass (the default — canonical, audit-verified 2026-11-14):**
```
Continue the pass0 verification campaign: live-verify the ZCode Companion Android app
against its macOS desktop, find everything missing or broken, and record it.

READ FIRST (in this order):
1. docs/evidence/pass0-LEDGER.md — the whole file (rows, fix-order, DELTA procedure,
   discovery backlog, campaign state incl. the audited rig note).
2. The LAST "# Part" section of
   docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md (latest pass
   record + its fix-order + coverage-honesty).
3. The rig recipes: report Part C + sections D.A0, E.A0, F.A0, G.A0, H.A0, K.A0 + the
   Part I/J concurrency forensics. Non-negotiables they encode: `pkill -f
   MockRelayMainKt` NEVER by port (qemu NAT); taps at SCREENSHOT-derived bounds for
   dialogs (dump bounds lie ~200 px); restart `adb logcat -T 1` after EVERY emulator
   restart; absent log lines are inconclusive — the Room DB pull is the arbiter.

STEP 0 — DESKTOP OF RECORD (your first tool action):
  defaults read /Applications/ZCode.app/Contents/Info.plist CFBundleShortVersionString
  shasum -a 256 /Applications/ZCode.app/Contents/Resources/app.asar
Compare against the ledger. UNCHANGED → proceed. CHANGED → run the ledger's DELTA
procedure BEFORE any app leg (re-extract, re-census every standing grep, diff all
numbers, open new G-rows), and stamp the new digest into the ledger.

STEP RIG — LIVE EMULATION (mandatory for every live leg; REA/docs-only work never
satisfies one):
- Reuse or boot: `adb devices`; kill zombie relays with `pkill -f MockRelayMainKt`;
  cold-boot = AVD zcode-a16, NO `-no-window`, flags `-no-snapshot-load -gpu
  swiftshader_indirect -no-audio -no-boot-anim`, then `svc wifi disable`.
- FIRST in-app action after boot: Settings → Privacy → "Screenshot protection" OFF
  (FLAG_SECURE blacks every capture otherwise); verify a ~1.4 MB-class PNG.
- Build + install at HEAD: `./gradlew :app:assembleDebug :tools:mockzcode:jar` then
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`; restart
  `adb logcat -T 1`; receipts go in a fresh /tmp/p<N>/ per pass.
- Relay: per generation set a FRESH `MOCK_GEN_NONCE` (format mock-p<N>-gen<M>-<id>;
  the var is MOCK_GEN_NONCE — report Part C's "MOCK_GEN_ID" is a wrong name, do not
  copy it), launch via the plain `java -cp` recipe (/tmp/p0/relay.sh pattern) with
  ONLY the arms the backlog item names — env-inherited, set BEFORE the exec line
  (appending after it arms nothing); MOCK_PORT_OVERRIDE/MOCK_PUSH_MS_OVERRIDE honored.
  Stored endpoints survive relay restarts (constant mock passHash) — no re-pairing.
- Drive ON-DEVICE: uiautomator dumps (count nodes with `grep -o '<node' | wc -l` — the
  XML is one line), screenshots, logcat, and DB pulls
  (`run-as com.zcode.android.app cat databases/zcode.db{,-wal}`) as the arbiter.

STEP 1 — DISCOVERY BACKLOG: work the ledger's next unchecked item(s) with the method
named there. An item is done when its findings are appended as new F/G/N rows
(mechanism + code pin + receipt) and the checkbox is ticked.

STEP 2 — COVERAGE LEFTOVERS: drive the latest pass's "Coverage honesty" list (latest
first). Skip anything already receipted unless its code changed.

RULES:
- Never re-narrate an OPEN row — ONE line + a Stamps bump in the ledger. Never
  re-receipt FIXED/REFUTED/ABSORBED/STANDS surfaces unless their code changed.
- App code: RECORD, don't fix. Mock/rig fixes ARE allowed (they unblock arms) —
  commit them as `fix(mock): <ID> …`.
- If another session may share the rig, claim generations in /tmp/pass-coord-*.md and
  nonce-anchor every receipt.
- Append the new pass part to the report (append-only; earlier parts untouched),
  ending with the verification-stamp block: time, generations, artifact paths, APK +
  desktop digests, and the what-was-NOT-driven list. Update the ledger (statuses,
  stamps, backlog, campaign state incl. the rig-left note).
- Commit everything locally as ErnestHysa <takis312@hotmail.com> (`docs(pass0):
  pass-<N> record …` for docs, `fix(mock): …` for rig fixes). NEVER push.
```

**Fix round (after roadmap phases exist):**
```
Read docs/evidence/pass0-LEDGER.md, then open
docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md at the roadmap,
and implement PHASE <N> ONLY — nothing else. Each row: re-read the finding + its
ledger entry, TEST-FIRST (failing pin per fix, live-shape harnesses where the report
names them), fix, run the module suites (testDebugUnitTest + pure-JVM `test` tasks),
rebuild the debug APK, commit `fix(<ID>): …` per finding, tick the row FIXED in the
ledger with the commit sha. Follow the deliverable-completion rule (regression pins,
module suites — `testDebugUnitTest` for Android modules + `test` for the pure-JVM
modules, APK rebuilt so a fresh artifact exists, commit, state the artifact path +
version marker). Re-verify each FIXED row live on the rig under the discovery
template's STEP RIG rules. Commit the report/ledger update locally too
(`docs(pass0): fix-round <N> record …`); nothing pushed.
```

**Desktop delta pass (run whenever the desktop updates):**
```
/Applications/ZCode.app was updated. Re-extract the asar and run the DELTA procedure
in docs/evidence/pass0-LEDGER.md: re-run every standing census grep, diff each number
against the ledger, open NEW G-rows (especially mobile-remote/UI/UX planes), re-check
the backlog items the delta invalidates, then diff the phone at HEAD against each new
row. Stamp the new digest into the ledger's desktop-of-record, append a DELTA part to
the report, update the ledger, and commit everything locally (`docs(pass0): desktop
delta …`). Nothing pushed.
```

## History

- 2026-11-16 (pass 40) — Part AQ executed on Part AP's "did NOT drive" driveables +
  a fresh recon axis: the git sheet's Tier-B write row / BranchSheet (surfaces
  receipted as renders in passes 5/10, never DRIVEN) + the six read-only tool
  screens' functional depth. THREE new F rows: **F-P40-B** (P2, the
  workspace-manager persistence TRIO silently loses every mutation — the
  preferences.pb byte-frozen arbiter + the Settings-write discriminator scoping
  the dead path to ZCodeApp.kt:742-755's three startScope launches), **F-P40-A**
  (P3, the switch/create acks re-fetch NOTHING in-place — re-entry heals; the
  commit handler's fetchChanges() asymmetry is the fix shape), **F-P40-C** (P3,
  the typed branch-issue ack swallowed — sheet closes pre-result, onDismiss
  nulls, IssueLine lives in the closed sheet; the wire's message/detail never
  rendered). TWO new smalls: **N-BX-1** (P4, Phase9Tools.strOrNull is
  JsonNull-blind → "tracking: null"; Phase9Ports.rpStr has the filter — the
  helpers disagree) and **N-BY-1** (P4, the Stage/Discard a11y labels carry the
  raw "%1$s" — positional resources vs the "%s" replace). ONE new G row:
  **G103** (P4, the catalog planes' detail-depth gap + the census updates:
  Providers gained OPENAI-COMPATIBLE glm-4.6 + Preview badges, Bots two bots
  incl. draft·off). Stamps: **F-P5-A ×2** (the refused getChanges renders
  silent-empty Changes — the render site is dead-coded behind Phase9Ports.kt:124's
  null-swallow) and **F-P38-A ×4** (the ghost on the first boot dump). First-series
  WORKING receipts: the ENTIRE git write tier live (switch/create with the RIG-5
  summary+list mutation, Stage MOVING tabs, the typed DISCARD gate, Generate's
  typed message, commit consuming the rows + PushConfirm → push) — the mock
  debt #25 (write-tier visibility) opened+closed in-pass. Mock: ONE commit
  `baa3f216` (suite 254/0/0). App: ZERO changes (APK byte-identical, install
  skipped). Rig learnings (AQ.A0): launcher arms AFTER exec arm NOTHING (gen3
  discarded); Compose TextField text invisible in reflowed-sheet dumps;
  FLAG_SECURE black-captures persist until reverted. Rig left UP (gen4
  mock-p40-gen4-fa8e87 — ⚠ STICKY typed-issue + getChanges-refusal arms, an
  inheritor must pkill + relaunch; app pid 1838 in synth-task-1's git sheet).

- 2026-11-16 (pass 39) — Part AP executed on Part AO.N's coverage-honesty list (the
  backlog held at 8/8, so the undriven legs WERE the axis; all eight items
  adjudicated live). ONE new F row: **F-P39-A** (P3, the move sheet's "New group…"
  path silently DROPS the pending move — `moveFor = null` fires before the editor
  opens, create-only handler, task-2 unmoved + the group invisible while a SUCCESS
  notice fires). ONE new small: **N-BW-1** (P4, collapse-all from the timeline
  persists the inert "timeline" sentinel and the always-reset organize makes it
  never survive a restart). Stamps: F-P38-A ×3 (re-manifested gen1+gen4; the CHIP
  filter hides the ghost — All-view-only), F-P38-B ×2 (the race both halves in one
  generation: cold-open lost, Settings round-trip won; gen4's cold open won),
  N-BV-1 ×2 (the created shgrp invisible), N-BU-1 ×2 (the CAS refusal notice in
  the status-bar slot). The pass-38 coverage list CLOSED in full: the 8-item
  overflow census + Organize-by-timeline + Collapse/Expand-all round-trip, the
  workspace chips (All/Synthetic One filter + the Manage sheet first-series),
  Sort-by-created (fixture-invariant — ordering can't differ on same-batch seeds),
  the + New task WATCH RESOLVED (createSession round-trips on a fresh gen; the
  pass-38 hang was the stale jar), the color-dot VISIBLE flip ×2 (Gray→Blue,
  Blue→Orange — the chips-before-field recipe), the archived viewer's UNAVAILABLE
  face (new MOCK_ARCHIVED_FAULT arm → the typed fault → the sheet face), and the
  unread CAS arg receipted ON THE WIRE for the first time + the REFUSAL face
  (MOCK_UNREAD_CAS=1 real comparison pass leg; =force drift mode → the typed
  fault.unread.stale → "Couldn't update the task — try again" with the row
  unmutated, DB-arbitered). organize/sort reset adjudicated BY DESIGN (view-local,
  the G69 comment). Mock: THREE commits — `45b481f9` (the arms + the wire-arg
  log), `f6213c82` (debt #24: the created task inherits the workspace label — the
  incoherent "Synthetic Workspace" header was mock-fed), `49e7dceb` (the force
  mode); suite **248/0/0**. App: ZERO changes (APK byte-identical, install
  skipped). Rig learnings (AP.A0): force-stop→am-start silent no-ops twice (use
  `am start -W`); IME reflow lags the dump; chips-before-field; input text
  mangled both ends; cold-start taps misdirect. Rig left UP (gen4
  mock-p39-gen4-71c9e3 — ⚠ STICKY MOCK_UNREAD_CAS=force refusal arm, an inheritor
  must pkill + relaunch; app pid 30523 on the tasks home).

- 2026-11-16 (pass 38) — Part AO executed on a FRESH axis: the TASK-MANAGEMENT
  plane (the row menu's five never-driven legs + Phase 5A's pinned/groups/archived
  surfaces; the only series receipt before it was pass 8's Archive wire leg + the
  menu render). TWO new F rows: **F-P38-A** (P3, the stale workspace-key ghost
  section — the home enumerates every key ever synced while the replace/sweep are
  key-scoped; the DB-arbitered `synthetic-ws-one` ghost frozen since ~14:36 across
  relay swaps and restarts, its rows openable) and **F-P38-B** (P3, the cold-open
  group-fetch swallow — LaunchedEffect(Unit) races the connect, guarded null
  swallowed by `?: emptyList()`, zero listGroupedTaskViewStructure on the wire all
  session; the seeded Backend group invisible). TWO new smalls: **N-BU-1** (P4, the
  action notice renders in the status-bar zone) and **N-BV-1** (P4, empty groups
  render no header → no Edit/Delete affordance). ONE new G row: **G102** (P3, the
  3.18.1 wire drift — the six legacy group verbs REMOVED from zcode-task; the plane
  moved to a task-groups channel with read/save/ungroup/move/admit; the vc51
  receipts prove 3.14.5 served them live) + the parity CORRECTION (3.18.1 GAINED
  its own task-row menu incl. mutateTask routing — pass 8's no-menu census stale).
  First-series WORKING receipts: the armed fixtures (pinned section/glyph/provider
  chip; unread dot/bold/a11y), the full 7-item menu + BOTH gates (Resume hidden for
  RUNNING/WAITING; Move hidden when pinned — 2 receipts), mark read/unread
  round-trips (wire+render+DB+menu-label coherence), pin/unpin (the row MOVES
  sections — impossible pre-fix), rename end-to-end, the archived viewer trio
  (empty face; Unarchive; Delete permanently → gone everywhere, DB count 0),
  resume, group create/move/edit(rename+recolor wires)/delete + confirm, the
  ghost-row tap consequence. Mock: FOUR commits — the stale-CONTROL-view trio
  (#23a decorate `6b9e882f`, #23b archive-exclude `046fec07`, #23c delete-archived
  sync `9b5f9ad0`) + the createSession pin `a65058e1`; suite **241/0/0**; the
  vc51-era "mock acks without mutating" attribution CORRECTED (the row MOVES with
  the fixed view — receipted live). WATCH: the gen3 createSession v4 reply hung
  (command.sendLost 20s) — pass 8's create receipt stands, re-drive before hunting.
  App: ZERO changes (APK byte-identical, install skipped). Rig learnings (AO.A0):
  channel verbs receipt on APP LOGCAT; the dialog dump-bounds trap at ~35% delta;
  dropdown re-anchor after mutations; IME reflow moves sheet chips (the color-flip
  face unreceipted); input text dropped a char again. Rig left UP (gen3
  mock-p38-gen3-e5f8a1 MOCK_TASKMGMT=1 — ⚠ jar predates 9b5f9ad0/a65058e1; app
  pid 27097 inside synth-task-1's conversation).

- 2026-11-16 (pass 37) — Part AN executed on a FRESH axis: the assistant-row ACTION
  RAIL (the completed-row plane below the prose) — feedback like/dislike had ZERO
  series receipts before it. NEW row N-BT-1 (P4, the stale row-action banner renders
  the SWITCH-specific copy — the renderer maps the shared SESSION_CHANGED key and
  discards rowActionNotice's own message; feedback live-receipted, retry/fork/rewind
  same path statically). First-series receipts: the LIKE echo round-trip END-TO-END
  (wire → rowUpserted → accent thumb + "Liked" a11y flip → Room raw-json arbiter →
  SURVIVES a fresh process via the snapshot union, s12); the DISLIKE flip + the
  explicit-null CLEAR (the key REMOVED); the SNAPSHOT-row like under the mock's new
  generated-row echo fidelity ("synthetic 320" preserved verbatim — the old fallback
  would have re-texted the row on row.upserted); the copy pair (row_copy_agent +
  row_copy_code flips caught, the OS clipboard chip showing the exact code text =
  the OS arbiter); the Fork/Retry flag-gate (one stamped row carries them,
  Like/Dislike render on every completed assistant row by entityId); the REFUSED
  face (new MOCK_FEEDBACK_FAIL arm → the keyed FEEDBACK_REFUSED copy verbatim + the
  DB unmutated); the STALE face (MOCK_CAS_STALE_TYPES=setAssistantFeedback → the
  typed warning that exposed N-BT-1). Ride-along find receipts: the 3-digit count
  "1 of 200" DB-exact (159 window + 41 paged) and the UNPLANNED find-JUMP rowsRange
  trigger (the PAGE_EDGE pair ids 34/35; the post-floor strip hiding ADJUDICATED
  HONEST — the cache completes; F-P36-A stamps 2: the open race is intermittent
  0/3, the jump deterministic). Desktop: feedback parity EXACT (schema + renderer
  vocabulary) — no G row. Mock: ONE commit df1485fb (MOCK_FEEDBACK_FAIL +
  generatedRows echo fidelity + self-tests 10; suite 235/0/0). App: ZERO changes
  (APK byte-identical, install skipped). Rig learnings (AN.A0): relay restarts
  reset the feedback echo (mock-fidelity note); the OS clipboard chip is the
  clipboard arbiter; the status sheet is a header tap-hazard (~1.21 scale bit);
  tail scroll-locks resist swipes/flings — the find JUMP is the scroll driver.
  Rig left UP (gen3 mock-p37-gen3-c9d041 — STICKY stale arm, an inheritor must
  pkill + relaunch clean; app on sess-forked-1, find bar open "synthetic 320").

- 2026-11-16 (pass 36) — Part AM executed on a FRESH axis: the find-in-conversation
  plane (G5 vc51) — its first live census in the series, staged by pass 35's
  flagged AL.A0.2 edge. NEW row F-P36-A (P3, the open-time prefetch race + the
  cache trim strand a stale hasMore=false — the strip hides while head rows are
  cache-absent; route re-entry does NOT heal, a fresh process does; trigger
  receipted 2/2 fresh opens with ZERO taps). AL.A0.2 CLOSED as pass-35's own
  composer-misdirect artifact (the drafts-table receipt `synthetic 82`
  @1794819509563 ≈ 09:01; the real field-typed query matches "1 of 1" + jump).
  First-series receipts: the boundary query + the scrollToItem jump; the REASONING
  kind exclusion (visible-but-unfindable s02 + the "1 of 5" count arithmetic);
  match cycling + wrap-around; case-insensitivity; the LIVE index regrow on send
  ("1 of 1"→"1 of 3" with zero field interaction) + jump-sticks (followBottom
  released); the highlight split in ONE frame (user-row accent fill vs the
  assistant markdown no-fill, s03); the paged-scheme count "1 of 41" DB-exact.
  Desktop census: the 3.18.1 find is TWO-SCOPED (conversation ↔ changes, "messages
  + file changes", CmdOrCtrl+F) vs the phone's single text-row scope → ONE
  opportunistic-queue line; the G5 comment's desktop symbol names are absent from
  the shipped bundle (minified). AL.C's strip-tap wire attribution stamped
  AMBIGUOUS (the same two-call sequence fires at open with no taps; the growth
  faces stand). Mock: ZERO changes; App: ZERO changes (APK byte-identical, install
  skipped). Rig learnings (AM.A0): the relay stdout BUFFERING lies to `grep -c`
  (two misreads); the per-keystroke jump chase; the composer-misdirect trap. Rig
  left UP (gen1 mock-p36-gen1-d2e8a7, MOCK_ROWS_TOTAL=400; app on sess-forked-1,
  find bar open "SYNTHETIC 82", store fully loaded).
- 2026-11-16 (pass 35) — Part AL executed on the Load-earlier/PAGING plane (§19.4
  scroll-back) — the ONE driveable item pass 34's coverage list had skipped as
  "undrivable without a mock paging arm" (recon: the arm ALREADY existed —
  MOCK_ROWS_TOTAL, RIG-P3 vc52 Phase 10). NEW row F-P35-A (P3, the paging error
  face's top-anchored silent failure — the fault fires and the user at the tail
  sees NOTHING; the error renders at lazy-list index 0/1, the tap affordance is the
  pinned strip; the F-P0-C silent family). N-BM-1 stamped (the frozen-total
  mechanism re-receipted at HEAD with a FAITHFUL total; the "impossible claim at
  the true end" sub-claim NARROWED — the end face is honest at HEAD, the strip
  hides on hasMore=false). The pass-3/4-era totalCount arithmetic artifact CLOSED
  by the mock fix. First-series receipts: the strip TAP → the first-ever
  `conversationRowsRangeV4` wire verbs (320→370→the full 401, DB-arbitered
  contiguous pages 32..81/1..31), the FAULT face ("Couldn't load earlier
  messages" + the errorCategoryMessage copy "Conversation changed — please try
  again" + Retry — clean copy, NOT raw-code), the retry-refail face (strip
  persists), the retry-SUCCESS face (error clears, the prefetch cascade completes
  the store 320+50+31=401, the paged rows literally render "synthetic row 61"),
  and the auto-prefetch edge on both polarities (gen3 cascade / gen2 re-fault
  drum). Mock: ONE commit `9657bd93` (snapshotTotalCount + pageRangeFault +
  MockZCodePass35PagingSelfTest 5 cases, suite 225/0/0). App: ZERO changes (APK
  byte-identical, install skipped). Rig learnings (AL.A0): gap-drags re-confirmed
  (slow 250 ms gap-drags beat 80 ms gap-flings here — origin decides, velocity
  secondary); find-in-conversation "No matches" for a cached row (unexplored
  edge); BACK drops the IME before closing the find panel. Rig left UP (gen3
  mock-p35-gen3-c5d944, MOCK_ROWS_TOTAL=400 only; app pid 18901 at the list top,
  store fully loaded).
- 2026-11-16 (pass 34) — Part AK executed on a FRESH axis (the pass-33 precedent one
  render level deeper): the toolCall row's PER-TOOL-KIND render matrix. ZERO new rows;
  the full status face matrix receipted live (pendingApproval/cancelled/running/
  unknown in one frame + error's INPUT/ERROR blocks), BOTH new group faces ("Changes
  · 2 files · 4.3s" — the file-count face — and "Execute · 2 commands · 5.3s") with
  SUMMED durations + EXPANSION to member cards (Edit/Write/Bash/BashOutput; the
  BashOutput taxonomy-hole no-subtitle face confirmed graceful), the G66 recovery
  card driven END-TO-END (Retry → the real `retryTurn` wire command + the mock's
  "retried reply" row, DB rowId 1001; Report issue → nav.reportIssue with the error
  attached → the Send feedback form PREFILLED; the Switch-model tap recorded as the
  leg's boundary), the C7 lazy full-output fetch on BOTH faces (the SERVED slice
  replacing the capped preview on a real getTaskSnapshotToolCallsSlice body — a verb
  the mock never answered before; the honest fault → the retained-preview-copy
  fallback), the Changes members with path metas — and the UNPLANNED first-series
  receipt: the DOUBLE-fork's `port.fork outcome=Stale child=null` honest refusal
  (the happy fork re-receipted: Accepted → nav.fork → in-place switch to "Fork of
  synth-task-2"). F-P33-A gained a STATIC mechanism extension (the attachment chip's
  cache/attachments/ root — same IAE family, fix shape gains the third cache-path).
  Mock: ONE commit `edf7aeb0` (MOCK_ROW_TOOLS + the slice handler; suite 220/0/0).
  App: ZERO changes (APK byte-identical, install skipped). Rig learnings (AK.A0):
  card-originated drags are swallowed — gap-drags are THE scroll recipe; the FAB
  always works; IME toggles reflow dialogs. Rig left UP (gen2 mock-p34-gen2-4eb017,
  seed arms; app inside the forked child).
- 2026-11-16 (pass 33) — Part AJ executed on a FRESH axis (the pass-28 precedent,
  applied to the decoder's row-kind vocabulary): NEW row F-P33-A (P3, the artifact
  preview card's Open affordance DEAD at HEAD and SILENT in both failure stages —
  download ok → FileProvider has no configured root for cache/downloads/ → IAE →
  log-only; unserved ref → fault.file.notFound → log-only; the F-P0-C silent family,
  never live-driven in either campaign). SEVEN first-series WORKING receipts: the
  hooks monitor groups (blocked/failed red + blockReason + skipped-hidden + the red
  group arrow + running), the subagent row's LIVE child-session open
  (`nav.subagent :: open sess-child-9` — the child renders N-BM-2's generic-title
  skeleton), the artifact preview card + the quiet fallback, the INV-8 unknown-kind
  face (`Unsupported entry · futureWidget`, DB-census-confirmed), the non-compact
  `checkpoint` marker quiet row, the STREAMING reasoning row (auto-expanded → tapped
  collapsed), and the failed-compaction Retry END-TO-END (relay `command type=compact`
  + outbox `compact|{}|ACKED|accepted`). F-P3-A stamps 10 (the hook-review card's
  starved body under MOCK_HOOKS). Mock: TWO commits `f9ab77b2` + `2e46a172`
  (MOCK_ROW_KINDS — openable + quiet-fallback + unserved artifacts, unknown-kind row,
  checkpoint marker, streaming reasoning; RowKind gains ARTIFACT;
  snapshotRowsForTest seam; self-tests updated, suite 213/0/0). App: ZERO changes
  (APK byte-identical, install skipped). Rig learnings: am-start fallback (monkey
  exits -5); FLING scrolls where slow drags move ~0 px (DB pull arbitrates data
  presence); on-device md5 needs the full /data/app path (run-as-relative returns
  the empty-file hash); screenshot-vs-dump tap spaces bit again. Rig left UP
  (gen2 mock-p33-gen2-d41c92, seed arms only, nothing sticky; app pid 16309 on
  synth-task-2).
- 2026-11-16 (pass 32) — Part AI executed on Part AH's coverage-honesty list (all
  three driveables in one pass): NEW row count ZERO — F-P31-A stamps 2 (the
  silent ✓ resurrection on the new MOCK_ATTACHMENT_KEEP_AFTER_ABORT arm — TWO
  user-removes in ONE transaction, both aborts KEPT, commit ok, the chip back as
  ✓ READY with an ARMED FAB and NO banner; the resurrected chip SENT with its
  committed ref ACKED in the outbox — the DB arbiter; the desktop census: remove
  is FINAL there — AbortController + per-chunk signal checks + staleness-guarded
  callbacks + transferService.cancel, none ported). Mock: ONE commit `500a3bdf`
  (the keep arm + attachmentAbortMutate seam + self-tests 4/4, suite green).
  App: ZERO changes (APK byte-identical, install skipped). Rig learnings: the
  photo-picker consent card eats the stale tile tap (re-dump after Dismiss —
  the grid reflows up); banner-dismiss reflows the chip row −132px; the
  final-state dump ghosted at the headline frame (screenshot is the arbiter);
  Read-preview coords needed ×1.17 this layout; mock RPC answers serialize
  behind chunk sleeps. Rig left UP (gen1 keep-arm + 8 s, app pid 14585,
  composer clean). **The attachment plane's driveable surface is EMPTY — the
  campaign's discovery surface is exhausted; the roadmap remains Ernest's call.**
- 2026-11-16 (pass 31) — Part AH executed on Part AG's coverage-honesty list:
  NEW row F-P31-A (P3, the mid-upload Remove resurrection — the removed chip
  comes back ~15 s later as ✗ with payload under a banner; receipted live on
  BOTH entry paths: first-attempt (raw-code banner, N-BO-1 stamps 4) and retry
  (generic banner); mechanism = removeUpload aborts the wire but never cancels
  the in-flight upload job, whose detached-chunk completion + error-catch upserts
  re-add unconditionally); the AttachmentRejectedException residual statically
  adjudicated UNREACHABLE (stands). Zero mock changes (the pass-30 CHUNK_DELAY
  arm at 15000 sufficed); zero app changes (APK byte-identical, install
  skipped). Rig learnings: the mid-upload leg must run tile-tap→Remove→resurrection
  inside ONE bash command (the turn-latency wall is the real blocker — the
  pass-30 4 s window note confirmed); relay-down gaps outlasting the ladder walk
  to DEGRADED (no conversation Retry affordance; force-stop escapes); the chip's
  Remove is a TEXT node; one abort id arrived at the mock 3× (transport resend,
  observed not rowed). Rig left UP (gen2 15 s arm — ⚠ slow uploads for the
  inheritor; app pid 14585, the ✗ 130.png residue chip + retry-failed banner).
- 2026-11-15 (pass 30) — Part AG executed on Part AF's coverage-honesty list
  (all five named residuals in one pass): NEW row N-BS-1 (P4, the mixed-state
  send's silent exclusion — the DB-arbitered 1-element payload + the lingering ✗
  chip, s12); N-BR-1 stamps 3 (the cross-chip retry-success window, s10);
  N-BO-1 stamps 3 (the attachment FAILED banner's raw fault code — the class's
  third surface). WORKING receipts, all first-series: the TOO_LARGE face (21 MB
  pick → verbatim WARNING + NO chip + zero wire — the pre-wire gate), the ↑ NN%
  uploading render (caught twice at 25% — s06-retry-2 + s09-imgup-3), the
  non-offline ATTACHMENT_FAILED face (s09-imgup-7, with the raw fault code), and
  the ATTACHMENT_RETRY_FAILED face (s06-retry-6). Mock: ONE commit `d21db7a0`
  (debt #22 opened+closed — MOCK_ATTACHMENT_FAIL + MOCK_ATTACHMENT_CHUNK_DELAY_MS;
  self-tests 6/6, suite 204/0/0). Zero app changes (APK byte-identical, install
  skipped). Rig learnings: the DocumentsUI drop streak broke on the photopicker
  trip (4-of-4 DocumentsUI vs photo-picker surviving); the natural drop is a
  free staging tool (BlockedOffline pick → payload-retained chip → one Retry
  drives the armed transaction); the + menu ghost-dumped again; the TOO_LARGE
  notice renders below the composer and survives an open + menu. Rig left UP
  (gen2 clean arms, app pid 9038, the failed mid.bin chip rig residue, logcat →
  /tmp/p30/logcat.log).
- 2026-11-15 (pass 29) — Part AF executed on Part AE's coverage-honesty leftovers
  (its exactly-two named driveables): ZERO new rows; N-BR-1 stamps 2 (the Remove
  path — the row's second lying window — receipted live on BOTH chip states:
  error-state Remove (AF.C, s04 chip-less + banner) and ready-state Remove (AF.E,
  w30), removeUpload :376-378 mutates no notice, zero wire verbs either way).
  WORKING receipts, all first-series: the FIRST multi-attachment send (two ✓
  chips → `sendText | ACKED | {"text":"two chips probe","attachments":[txt 43 B
  text/plain, 124.png 154253 B image/png]}` — the campaign's first 2-element
  attachments array, AF.D) and the ready-state Remove followed by a send with NO
  attachments key in the payload (AF.E, the contrast row directly above leg A's in
  the same outbox table); PLUS the menu-inserted `/goal ` args-hint spelling (the
  GAP-12 contract live on /goal → the verbatim EmptyGoal face, zero wire, draft
  preserved — AF.F, s08). Bonus single-frame s06: TWO ✓ chips + the stale offline
  banner simultaneously (the banner's third simultaneous lie). Static pin: the
  send payload filters to READY chips only and consumes them on ACCEPTED
  (Ports.kt:391/417) — a failed chip beside a ready one is excluded AND lingers
  (undriven; AF.G). Zero mock changes; zero app changes (APK byte-identical,
  install skipped). Rig learnings: the composer reflow moves the +/Send bounds
  every state change (re-dump each time); the menu dump-invisibility variance
  FLIPPED (+ menu visible, slash dropdown invisible); DocumentsUI trips drop the
  socket 3/3 (BlockedOffline-at-pick is the honest face, Retry recovers); ONE
  unattributed route-pop to Tasks home after a Retry committed (receipts clean;
  watch, don't chase). Rig left UP (gen2 clean arms, app pid 9038 on
  synth-task-1, logcat → /tmp/p29/logcat.log).
- 2026-11-15 (pass 28) — Part AE executed on three FRESH composer axes (the
  coverage lists had emptied to standing/gated-only): the slash-menu PICK tap
  (GAP-12 insert contract live; `/compact ` trailing-space → `compact ACKED/{}` —
  the parser saturation now covers menu-inserted spellings), the composer STOP
  morph FIRST SERIES (morph both ways + the GAP-A9 draft-always-wins contract +
  `command type=stop` → `stop ACKED/{}` + morph-back), and the FIRST non-image
  attachment (OpenDocument → `rig-p28-notes.txt` chip → offline-failed upload →
  typed Retry/Remove face → Retry committed → ✓ chip → `sendText` with
  `mime:"text/plain"` + ref, ACKED). NEW row **N-BR-1** (P4: the Retry success
  leaves the stale offline banner up — s16 single-frame proof; cleared only by the
  next send). ZERO mock changes; ZERO app changes (APK byte-identical; the
  `install -r` SKIPPED on digest equality — the device already held the HEAD
  artifact). Rig learnings: the + DropdownMenu renders INVISIBLY to screencap AND
  dump (dumpsys-window-frame + 132 px/row calibration; a blind tap hit Sketch);
  screenshot taps need NO scale correction (the offsets are the Read-preview
  scale); per-connection mock request ids; DocumentsUI dump-drivable while pixels
  lag; BACK-pops-route again. Rig left UP (gen1 clean arms, app pid 9038 on
  synth-task-1, logcat → /tmp/p28/logcat.log).
- 2026-11-15 (pass 27) — Part AD executed on Part AC's coverage-honesty list (its
  exactly-two named leftovers): ZERO new rows (SIXTH consecutive); ZERO mock
  changes; ZERO app changes (APK byte-identical). Two legs receipted first-series,
  both WORKING: the `/goal resume` ACCEPTED happy path (relay resumeGoal → outbox
  `ACKED|{}` on the pass-26 pause shape, draft cleared, GOAL_RESUME_REFUSED
  correctly silent — the parser's final untyped verb; the family's faces now
  exhaustively receipted) and the `/compact`-with-attachment `:55` fallthrough
  (chip `121.png (74 B)` staged → RAW sendText WITH the attachments payload, ZERO
  compact on the wire, YOU row raw + AGENT echo — the gate receipted on both its
  spellings). The slash-parser table at HEAD is FULLY saturated. Rig learnings:
  per-char `input text` is THE typing recipe (keyevent-per-letter garbled, one-chunk
  interleaved, fresh-focus first chunk drops); the slash menu intercepts keyevents
  while open (BACK closes it; cursor DEL/RIGHT/insert repairs the draft); re-dump
  before every composer interaction; picker tiles render black — AX-desc mtime
  identity; the relay-up-first recipe against the inherited post-pkill
  AUTHENTICATING wedge (one force-stop relaunch recovered it). Rig left UP (gen1
  clean arms, app pid 9038 on synth-task-1, logcat → /tmp/p27/logcat.log).
- 2026-11-15 (pass 26) — Part AC executed on Part AB's coverage-honesty list + the
  slash-parser table's remaining branches: ZERO new rows (FIFTH consecutive); ZERO
  mock changes. Seven legs receipted first-series, all WORKING: the /plan dispatch's
  non-accepted arm (stale switchCollaborationMode → verbatim warning + draft
  preserved + zero sendText — ConversationViewModel.kt:494-503's else fires honestly;
  mapOutcome Ports.kt:669-678 maps the stale ack without throwing), the /goal+
  attachment fallthrough (the :55 gate → RAW plain sendText WITH the attachment,
  zero sendGoalCommand — the AB.F leftover closed), the unknown-command contract
  (/review probe → plain sendText verbatim), the /target alias (SendGoal, objective
  carried — same port as /goal, no F-P20-A census change), the /goal replace strip
  (:69, prefix stripped), the /goal pause ACCEPTED happy path (first ACKED pauseGoal,
  pass 23 had only its stale face), and the /compress alias (compact ACKED, generic
  accepted without the pass-22/23 marker arm). Rig learnings: the banner-✕/FAB trap
  re-manifested (one send eaten — check for a banner before every send tap); the +
  dropdown's Photo row is IME-clipped (close the IME first; BACK with the dropdown
  open closes the DROPDOWN); keycode=29+(letter−A) (43=O, T=48, R=46, C=31); the
  picker keeps tile selections across dismiss/reopen (a stray 86.png chip removed);
  `content query --projection` single-column per flag on API 36. Rig left UP (gen2
  clean arms, app pid 7090 on the conversation, logcat → /tmp/p26/logcat.log).
- 2026-11-15 (pass 25) — Part AB executed on Part AA's coverage-honesty list + the
  slash-parser plane: ZERO new rows (FOURTH consecutive); F-P20-A stamps 4 (the
  slash-set goal entry point — the accidental-but-valid `/goal status` leg became the
  receipt: parser unknown-action fallthrough → SendGoal → outbox ACKED with the
  trio-less payloadJson verbatim). WORKING receipts, all first-series: the followup
  no-op (Part AA's mis-filed item — setFollowupMode 0→0 wire-arbitered), the three
  LOCAL refusal faces (EmptyGoal/UnsupportedGoal/UnsupportedPlanShortcut: verbatim
  banners, zero wire, draft+attachment preserved), the /plan dispatch end-to-end
  (switchCollaborationMode + sendText ACKED, the genuine seq=2 config delta applied
  + watchdog re-arm, chip flip plan↔yolo in both directions — the gen2 swap's echo
  re-assert receipts the reverse leg). Mock: ONE commit `9b4a48d9` (the command-type
  log line; suite 198/0/0; the first live outputs `command type=sendText` /
  `=switchCollaborationMode` on gen2). id=27: gen1's lone unattributed accepted
  non-outbox command frame (~357 B, ~1.1 s post-sendText) — NOT reproducible on two
  gen2 re-drives; a logged curiosity, NO row, the type line names any recurrence.
  Rig learnings: AX-radio artifacts BOTH ways (checked=false on filled + ~200 px
  bounds drift), the KEYCODE-ADJACENT input drop (first letter after keyevent-76;
  35=G, 44=P), photo-picker scan+reopen + the MediaStore-id rename, the swap-echo
  re-assert. Rig left UP (gen2 clean arms, app pid 5305 plan-mode, logcat p25).
- 2026-11-15 (pass 24) — Part AA executed on Part Z's coverage-honesty list: ZERO
  new rows (THIRD consecutive); N-BQ-1 stamps 3 (the xhigh radio's first live
  observation — high/medium/xhigh under the same -32009 collapse; zero new outbox
  rows, setThoughtLevel correctly never rides the command outbox). The
  effort-sheet plane FULLY adjudicated: the max→max no-op WORKING (the :267-268
  `if (!selected)` guard — zero wire traffic, dialog closes, chip unchanged); the
  level census data-driven-complete (desktop toolbar = SIX localized levels
  low/medium/high/xhigh/max/ultra, wire schema = free string array, phone renders
  the echo verbatim; `low`/`ultra` absence = mock-echo fidelity — inventory note,
  NO G row); BOTH admission residuals pinned unreachable phone-side (every
  sendText funnels through send()→routeSendPrompt threading the trio;
  port.sendGoal has NO trio params = F-P20-A's omission itself). The Part-Z
  "medium/high/max radios" summary corrected: the mock's 4-level seed (incl.
  xhigh) predates pass 23 (`0e4479c4`). No mock changes. Rig learnings: the
  uiautomator attribute-ORDER trap (fixed-order regexes silently miss nodes;
  parse per-node, search attributes independently); the FIRST inherited-emulator
  survival in four passes (but the pass-23 relay's sticky compact arm made the
  pkill-before-reuse discipline live); the residue draft's third `install -r`
  survival. Rig left UP (gen1 sticky -32009 arm, app pid 4881 relay-1, composer
  holds `/goal pause`, logcat → /tmp/p24/logcat.log).
- 2026-11-15 (pass 23) — Part Z executed on Part Y's coverage-honesty list: ZERO
  new rows (second consecutive); N-BQ-1 stamps 2 (the effort MEDIUM level — same
  -32009 → generic face + unchanged chip). The stale-face class now stands on SIX
  typed command families (setAutoDrain + setFollowupMode receipted live: typed
  queue-changed banner + strip/chip unchanged + FAILED_STALE payloads); the GOAL
  pause/resume stale faces receipted on BOTH entry points (slash generic WARNING /
  sheet-icons generic ERROR, same copy) and ADJUDICATED in-family honest — NO row
  (the banner render ignores Kind: WARNING and ERROR render the identical red ✕
  treatment; the resume copy names the stale cause, the pause copy anticipates a
  retry; the missing enumerated STALE branch is a fix-round one-liner per handler,
  receipts stand as the contract). The compact startNow/null passthrough receipted
  END-TO-END on the NEW marker arm (no G50 dialog → accepted → draft cleared → the
  manual marker pair `⟳ Compressing context` → `✓ Context compressed 164K →
  38.5K tokens` rendered live from the wire compact — first series; queue
  untouched; `compact|ACKED|accepted|{}`). Mock: ONE commit `d24a32a7` opened+closed
  in-pass (passthrough markers + casStaleAck STRUCTURAL compact exclusion +
  MOCK_GOAL_STATUS; self-tests 4/4, suite 198/0/0). Seven wire legs, seven outbox
  rows, zero gateway.drop, zero real FATALs. Rig learnings: derive launchers with
  sed (a hand-copied classpath dropped a group-dir segment); BACK pops the route
  when no sheet is open; ghost dumps at sheet open/close; Kind-invisible banner
  render. Rig left UP (gen2 sticky arms, app on the conversation, relay-5).
- 2026-11-15 (pass 22) — Part Y executed on Part X's coverage-honesty list: ZERO
  new F rows; NEW N-BQ-1 (P4, the effort stale face's dead branch — the protocol
  method's host error `Dl -32009` collapses to FAILED, the enumerated
  SESSION_CHANGED copy unreachable); F-P20-A stamps 3 (the G50 dialog fires for
  composer /compact too and its answer is discarded — but the wire outcome stays
  host-faithful: the compact self-queues under choice either way, live-receipted).
  Mock: TWO arms opened+closed in-pass (`8af56472` — MOCK_SET_THOUGHT_LEVEL_STALE
  [the FQ error shape on the protocol method] + MOCK_COMPACT_COMMAND [the host vMc
  mirror: self-queue + the compactOperationLock typed rejection]; self-tests 7/7,
  suite 194/0/0). Legs receipted live: the three queue-verb stale faces (sendNow/
  reorder/delete — the class now on FOUR families), the effort refusal (generic
  face, chip unchanged), compact queued (strip 2→3, /com… row, draft cleared,
  ACKED/{}) and the duplicate refused (compactOperationLock banner, draft
  preserved, FAILED_TERMINAL/rejected/{}). Zero gateway.drop. Rig learnings:
  KEYCODE 76 = slash (74 = `;`); the "Try out your stylus" system IME dialog
  hijacks input invisibly to uiautomator; hyperlink taps open Chrome; composer
  arbiter = screenshot. Rig left UP (gen1 sticky arms, q-rig-compact-101 IN the
  queue — the next compact will lock; app pid 4123 on relay-7).
- 2026-11-15 (pass 21) — Part X executed on Part W's coverage-honesty list + the
  gate-coverage static: ZERO new rows; F-P20-A stamps 2 (the composer-slash `/goal`
  variant receipted end-to-end — the G50 dialog FIRES for slash sends and the
  answered disposition is DISCARDED at `send()`→`sendSlash` (`:434-470`, only
  `routeSendPrompt` :454 forwards) — the composer-half fix is pure plumbing; the
  sheet half unchanged); the stale faces on BOTH switch families receipted live
  (`switchCollaborationMode` 11:59, `switchModelConfig` 12:05 — identical typed
  SESSION_CHANGED warnings, chips unchanged, FAILED_STALE/proto.staleRevision with
  the switch payloads); the admission gate enumerated handler-complete (exactly
  sendText + sendGoalCommand; `/compact` self-queues; stop/switches/queue verbs
  ungated). Mock: NO changes (debt-#21 arms covered both legs; the ghost armed and
  never fired). Rig learnings: ghost-node mid-transition dumps (w14/w17) + the
  `%s`-for-spaces recipe. Rig left UP (gen1 spent, app pid 2888 on relay-6).
- 2026-11-15 (pass 20) — Part W executed on the send-ack TAXONOMY axis: NEW
  F-P20-A (P3, the goal-side trio omission — same guard family as F-P19-A);
  the stale face class receipted first-series and adjudicated NO-DEFECT (typed
  honest WARNING vs the desktop's silent recoverFromStaleAuthority); mock debt
  #21 OPENED+CLOSED (`642616ef` — the goal half of the #20 admission envelope +
  MOCK_CAS_STALE_TYPES; self-tests 8/8 + suite 187/0/0); the taxonomy sweep
  CLOSED (guard.* → F-P19-A, heldQueueDispositionRequired → F-P20-A, the stale
  trio → no row, everything else generic-failed both sides). Both legs live
  (wire → face → outbox DB). Rig learnings: the input-text drop is
  slash-adjacent (`/` own-chunk + KEYCODE 35 workaround; 77=`@`, 61=TAB);
  dialog tap truth varied (DUMP bounds won twice; rendered-screenshot coords
  are ~17% off); the goal-refusal notice persists. Rig left UP (gen1 spent,
  app on the conversation, stale notice live, garbage composer draft = rig
  residue).
- 2026-11-15 (pass 19) — Part V executed on Part U's LAST undriven driveable
  coverage item (the G50 cross-side CAS-mismatch face): NEW F-P19-A (P3, the
  desktop-divergent face — desktop re-opens the confirmation with fresh ids,
  phone renders the generic refusal ERROR); mock debt #20 OPENED+CLOSED
  (`0a037a60` — MOCK_QUEUE_GHOST_APPEND_MS: the host-faithful CAS + the silent
  ghost append; self-tests 7/7 + suite 179/0/0); the host guard pinned from the
  shipped zcode.cjs (set-equality → ack status:"failed" + the verbatim
  reasonCode; renderer confirmationRequired). Both CAS legs receipted live
  (wire → notice → outbox DB arbiter); zero gateway.drop. Rig left UP (gen1
  spent, app on the conversation, draft p19casB preserved).
- 2026-11-15 (pass 18) — Part U executed on the STEP-2 coverage-honesty axis
  (pass-17 leftovers) + the mock-debt axis: ZERO new rows; the G49/G50 one-shot
  matrix COMPLETED (queue + startNow values wire-receipted live; the queue value
  riding the debt-#19 server-side HOLD — strip 2→3 then the clear composition);
  the run-row Delete confirm-face adjudicated NO-DEFECT statically both sides;
  mock debts #3 (fixed-by-prior-work `2b12fc46`), #6 (by-adjudication), #7
  (documentation), #8 (by construction `6846727d`) CLOSED, #19 opened+closed in
  the same commit (self-tests 5/5, suite 172/0/0), #14 reclassified STANDING
  RIG-ENV — the debt list is fully adjudicated (0 open work-debts). F-P8-D
  stamps 4. Two idle-socket drops recovered honestly (relay-4/relay-8); zero
  gateway.drop. Rig left UP (gen1 spent arms, app CONNECTED on relay-8).
- 2026-11-15 (pass 17) — Part T executed on the LAST two gated compositions
  (MOCK_QUEUE + MOCK_AUTOMATION_RUNS): ZERO new rows; the complete G49/G50 send
  trio receipted on ONE wire call under the new MOCK_INPUT_ROUTING=choice arm;
  all five queue verbs wire-true (edit 223ms / reorder 551ms / send-now 499ms /
  delete 285ms — auto-drain was pass 12's); the automations-runs plane receipted
  end-to-end (History sheet + all four filter chips + runAutomationNow 677ms +
  the typed duplicate snackbar + deleteAutomationRun) and Go-to-session's
  working half (the workspace-session run loads via the subscribe snapshot);
  F-P3-B stamps 2 (narrowed to the unscripted-session shape) + N-BM-2 stamps 2.
  Mock debts: #4 CLOSED by-adjudication (the delivery picker is the G49 one-shot
  — no pick-time verb BY DESIGN), #17 CLOSED (fix `05ba5c18` + the live force-
  resync receipt T.A3: `attempt=2 force=true` → `snapshotReconcile rows=166`,
  the stall rows retained), #18 opened+closed in the same commit (the
  MOCK_INPUT_ROUTING arm + the sendText trio log + clearQueueAndSend honoring;
  self-tests 5/5, suite 167/0/0) — 5 OPEN (#3, #6, #7, #8, #14). Zero
  gateway.drop. Rig left UP (gen3 TURN_STALL journaling, app on synth-task-1).
- 2026-11-15 (pass 16) — Part S executed on the open MOCK-DEBT axis + the P.A2
  watch-note: ZERO new rows; mock debts #2/#5 CLOSED (`9b6e2292`, self-tests 7/7
  + suite 162/0) with the Files content read's FIRST working receipt (main.rs,
  14 seeded lines) and the labels' wire shape pinned (UI render F-P3-A-gated);
  debt #4's effort half CLOSED-by-prior-work (chip "max"→"high" stable; the
  routing half stays MOCK_QUEUE-gated); debt #17 opened (the resync snapshot
  omits pushed effect rows). The automations editor receipted WORKING end-to-end
  (updateAutomation 02:00→03:30 + createAutomation "RigProbe", first in-series);
  N-BK-2 stamps 2. The REAL frames>0 stall driven (MOCK_TURN_STALL arm): the
  attach-recovery replayed seq 3..6, the watchdog re-kicked at 90.1s, the heal
  SILENT in both windows — P.A2's watch-note CLOSED (no surface fires when data
  is fully delivered). Zero gateway.drop for the pass. Rig left UP (gen3
  TURN_STALL looping, app on the conversation, logcat → /tmp/p16/logcat.log).
- 2026-11-15 (pass 15) — Part R executed on the STEP-2 coverage-honesty axis
  (both named axes saturated): ZERO new rows; FOUR first-series WORKING receipts
  (the Usage range chips' first drive — no defect, the range enum is
  wire-carried; the reset re-drive under the FIXED mock — Weekly 49%→59% meter +
  the unread clear, debts #15/#16 CLOSED live; the reconciliation RETRY LADDER —
  3 reads, the delay measured from the previous read's completion; the
  STAY-OPEN recovery resilience — deltas fully live across two kills, zero
  gateway.drop in the whole pass). **F-P13-A NARROWED** (stamps 3): the
  delta-plane drop is close/detach-CYCLE-bound (detach() fires only from
  closeConversation — code-pinned), not universal; its fix shape stands for that
  path. F-P9-A stamps 3 (third trigger variant: relay absent at cold-start
  connect → 10s TIMEOUT → silent wedge). Mock debts #15/#16 CLOSED + the
  reset-statusQueue variant armed and driven (`66844fbe`, self-tests 9/9 +
  155/0). The hook-card plane adjudicated F-P3-A-gated (standing). Rig left UP
  (gen4 spent-delta arms, app on the conversation, logcat → /tmp/p15/logcat.log).
- 2026-11-14 (pass 14) — Part Q executed on the arm-matrix axis's last legs: ZERO
  new rows; FOUR first-series WORKING receipts (the delta happy path on a fresh
  process — subscribe-anchored, 2 applies/0 drops; the G90 reset SUCCESS chain —
  typed confirm → {used:true} → first-poll COMPLETED → markHistoryRead → the
  "Quota reset" note; the background off-peak notification — FGS Live-connection
  relay-swap → 1.5s background reconnect → notify.show → "Task completed" in the
  shade; the mid-life disconnect — the data-frame filter died exactly at the 2nd
  data push, honest ~1.2s recovery + typed send-refusal face). The foreground-
  consumed off-peak completion was ADJUDICATED as §9.9 by-design (the home card is
  the in-app surface; both halves receipted live; Part Q.A3 — do not re-hunt).
  Mock debts #10–#13 CLOSED (`8361b769`, self-tests 11/11 + 146/0); #15/#16
  opened; #14 amended (the blackhole is probabilistic). F-P13-A stamps 2. The
  arm matrix is EXHAUSTED — the campaign is saturated on both axes; roadmap
  awaits Ernest. Rig left UP (gen4 spent-arm, app on the conversation, Live
  connection ON, logcat → /tmp/p14/logcat.log).
- 2026-11-14 (pass-13 supersession note) — the `c2bd7bcb` rig-left correction (a
  concurrent session documenting the post-pass-12 soft-reboot and "repair") was
  written into the campaign-state block that pass 13's record replaced; the same
  reboot event is pass 13's P.A0.4/P.A4 lifecycle (that session's "app relaunched
  (pid 2616), reconnected to gen2" was actually pass 13's post-cold-boot gen5a
  launch — the nonce-bearing relays tell them apart). The correction's facts live
  on here; nothing else from it was lost.
- 2026-11-14 (pass 13) — Part P executed on the never-driven-arm matrix axis:
  NEW F-P13-A (P2, the delta plane drops on every post-recovery connection —
  wire-arrival + gateway.drop receipted 2×, mechanism pinned to the recovery's
  missing re-subscribe), NEW N-BP-1 (P3, the Usage screen's double `padding(inner)`
  dead band is ALSO a dead touch zone), NEW N-BP-2 (P4, "2026-" trend labels);
  stamps N-BO-1 2 (the quota-reset raw-fault surface), F-P7-B 2 (the typed
  rejection trigger), F-P1-B 5 (partial sweep both directions). WORKING receipts:
  the Usage plane end-to-end (first data-bearing — quota meters, range chips,
  cards, heatmap, trend, pie, G20 plan charts, reset status), the G90 reset verb
  chain (typed-confirm dialog → useCodingPlanReset → typed failure face),
  MOCK_TOOLS tool cards + markdown strikethrough, the stream-watchdog ladder
  (90s → re-kick → escalate → resync → replay; silent self-heal watch-note), the
  answer-with-error chain (typed rejection → FAILED + FAILED_TERMINAL/rejected +
  card cleared). Mock debt #1 FIXED `91ec7ade` (6/6 self-tests); debts #10–#14
  opened. The off-peak in-life notification leg undriven (NAT flake + arm
  semantics; recipe recorded). Rig left UP (gen5b, logcat2).
- 2026-11-14 (pass 12) — Part O executed: ZERO new rows; the backlog's last driveable
  piece (notification LEVEL toggle) driven WORKING both directions + disk-arbitered;
  FIVE first-series WORKING receipts (the interaction-behavior chain Settings→chips→
  wire, the Auto-send/auto-drain toggle, the card-plane approval ANSWER — pass 0's
  silent no-ops are gone at HEAD, store lifecycle DISPLAYED→RESOLVED receipted — and
  the background-work plane strip→sheet→backgroundBashOutputV4 output panel); stamps
  F-P3-A 9 (shape-narrowing variance receipt: starvation is composition-dependent),
  F-P8-D 3, F-P6-B 3, F-P6-A census 4 (0 orphans at HEAD), F-P1-D 4. Rig: the
  canonical `svc wifi disable` step BREAKS the NAT on the API 36 AVD (O.A0.1 — do
  not run it); prefs.pb stores the level + interactionBehavior as protobuf STRINGS.
  **Campaign SATURATED — backlog 8/8, zero-new-rows pass; roadmap build awaits
  Ernest.** Rig left UP (gen2 armed, app connected).
- 2026-11-14 (pass 11) — Part N executed: the user-row ACTION plane driven end-to-end
  (Copy/side-session/Share/Edit — the side session receipts the full wire+navigate
  chain to sess-side-1), F-P8-E REFUTED-WORKING (the pass-8 pin was a mis-read; the
  gesture release + numbered jump FAB work), the changes viewer (typed error face +
  MOCK_FILECHANGES happy path), the Live-connection window picker driven (3 cycles →
  F-P11-B's stale projection, disk-verified via preferences.pb field 8), the
  attachment chip's no-error face (F-P11-A) + the mock race fixed `1e649eab`
  (verified gen3), the endpoint row pinned inert-by-design, Licenses share + What's
  new + the notification-detail copy receipted, N-BO-1 opened. Rig left UP + gen3
  alive (light rig + MD_FILE + FILECHANGES, Live ON 60-min window).
- 2026-11-14 (pass 10) — Part M executed: backlog items 2/3/7 DONE (the last 8 channel
  tables exact + the ⚠ attributions closed + the 49-channel exposure policy; the i18n
  namespace diff exhausted → G98/G99/G100/G101; the a11y audit of 17 never-swept screens
  → N-BN-1; the diagnostics plane receipted). NEW rows: F-P10-A (P3, the silent commit
  no-op), N-BN-1. F-P9-A 2nd stamp, F-P0-E 2nd stamp. F-P8-C re-verified WORKING (3
  directions). Rig: the PM wedge (launch -92 with a healthy resolver table → wipe-data
  recovery), the fresh-install pairing chain receipted end-to-end, mock debt #8
  (automations needs MOCK_OFFPEAK=1). Rig left UP + connected (gen1d).
- 2026-11-14 (pass 9) — Part L executed: backlog items 1/4/5 done (webRemoteControl
  plane → G97; runtimeCapabilities 13-name schema → G96 + G93 enum residual CLOSED;
  zcode.cjs attribution corrected), 2 new P2 defects F-P9-A/F-P9-B, the 3-pass-owed
  lockscreen leg CORE receipted (shade-tap = standing rig stop), MOCK_REMOTE_DOWN_MS +
  MOCK_MD_FILE first-driven, mention-panel tabs closed, ghost census 5/83 (2+ days).
  Rig left: emulator UP, PIN 1234, Live connection ON (one-tap undo), ZERO relays.
- 2026-11-14 — ledger created (from Parts A–K, passes 0–8) by the pass-8 review session,
  then audited against the repo + machine the same day: all spot-checked code pins
  verified TRUE (InteractionCoordinator :694-700, ConversationViewModel :71-74/:1291,
  ZCodeApp :182-196, ConnectionHub :672-685/:1650, MockZCode :1332-1340, PairingViewModel
  :50-56, V4Plane :1451/:1699-1709, ConversationScreen :453/:1043-1072, WriteComponents
  :676, FilesScreen :225-237, ConversationPort :318, APK hash ad449d7d/530ba4ff).
  Corrections this audit: open-F count 19→20 (18+2 obs), open-G standing 2→3 (G60),
  F-P0-B stamps corrected to passes 3-8 (pass 7 restored, pass 2 reclassified as the
  root-cause pin), F-P3-A stamps 6→7 (pass 6 restored), F-P6-A stamps 3→2 (pass 7
  withdrawn), consolidated fix-order section added, emulator-found-down noted in the
  rig line, backlog 2→3 items touched, "39+ tables" → ~33 exact + 2 ⚠ + 7 no-table.

- 2026-11-14 (pass 12 correction) — the pass-12 rig-left note was amended: ~10 min
  after the pass closed, the emulator launcher-wrapper exit coincided with a device
  SOFT-REBOOT (verified uptime 0 min, no app FATAL). Rig repaired in-session:
  logcat restarted, app relaunched (pid 2616) + reconnected to gen2 (drum active);
  all settings persisted. The report's Part O stamp block predates the reboot — this
  ledger line is the correction (the report is append-only).
