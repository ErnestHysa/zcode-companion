# ZCode Companion — pass 0: full emulation verification + macOS-desktop gap audit (2026-11-12)

> **Pass 0 (first pass of a NEW report series — the vc51 series file
> `vc51-full-verification-and-desktop-gap-2026-10-28.md` is FORMAT/PRECEDENT ONLY and stays
> append-only on its own series; nothing here edits it).** Scope per Ernest's request: (1) spin a
> live mock relay + the Android emulator, run the app, verify every feature; (2) reverse the
> installed macOS ZCode desktop (updated 2026-11-11 to 3.18.1 — "pre-latest, one behind") with a
> fresh asar census and diff everything against the Android app; (3) document every gap. This file
> is APPEND-ONLY: later passes append new parts, never edit earlier ones.
>
> **Environment of record.** HEAD `7edc5374` (main, clean tree — v1.3.2-vc53 publish record),
> debug APK `app-debug.apk` sha256-prefix `ad449d7d…` built fresh this pass (`gradlew
> :app:assembleDebug :tools:mockzcode:jar`, 17 s), versionCode 53 / 1.3.2. Emulator AVD
> `zcode-a16` (Android 16 / API 36, 1080×2340) cold-booted `-no-snapshot-load -gpu
> swiftshader_indirect -no-audio -no-boot-anim`. MockZCode relay via the plain `java -cp` recipe
> (mockzcode.jar + protocol/common/transport + kotlin-stdlib 2.4.20, coroutines 1.9.0,
> serialization 1.9.0, okhttp 5.1.0, mockwebserver3 5.1.0, okio 3.17.0 from `~/.gradle/caches`),
> FULL 23-arm flag set + `MOCK_INTERACTION_PUSH_MS=25000` + per-generation nonces
> (`mock-p0-gen1-a1b2c3` → `gen2-9d8e7f` → `gen3-4c5d6e` → `gen4-7f8a9b`) + a DIRECT-mode
> generation on :18200. Desktop under audit: `/Applications/ZCode.app` **3.18.1** (CFBundleVersion
> 3.18.1.10376), asar sha256-prefix `ec475e6e…`, 383,832,025 B, mtime 2026-11-11 20:59 — the
> same digest the vc52 campaign's WATCH-4 measured; extracted this pass (31,587 files / 373 MB)
> for the census. Public GitHub releases (zai-org/ZCode) stop at **v3.17.2** (2026-11-04) and
> carry NO changelog bodies after 3.14.3 — the release notes cannot source the skipped-version
> delta; the asar census below is the ground truth. Disk was pre-freed 469 MiB → 6.2 GiB
> (npm cache 2.7 G, codex-runtimes 1.6 G, pnpm store 706 M, old /tmp pass dirs) — recorded
> because the 8 GB machine cannot run gradle + emulator + extraction on a full disk.
>
> **Verification stamp:** 2026-11-12, verified ~19:08–20:30, GLM-5.3-Flash in ZCode (ZCode
> Companion pass-0 rig), everything local, nothing pushed.

## Part A — pass-0 live emulation walk (receipts, not impressions)

### A0. Rig blockers found and solved this pass (each will bite future passes)

1. **`-no-window` kills `screencap`** — the first boot used `-no-window` (not in the historical
   recipe) and EVERY capture came back black (25 KB PNGs) while the AX tree, taps and wire were
   fully alive; even system Settings captured black. Reboot WITHOUT `-no-window` fixed capture
   (splash rendered, then… see 2). Never add `-no-window` to this rig.
2. **FLAG_SECURE is now default-ON (F-37-F)** — with capture fixed, the app's main window still
   captured black: SurfaceFlinger shows `Layer [128] (Secure) com.zcode.android.app/MainActivity`.
   That is the pass-37 screenshot-protection feature (§27 default), not a defect — but it blacks
   every rig screencap. **Rig recipe: Settings → Privacy → "Screenshot protection" OFF** (tap the
   switch at the About section); the toggle cleared FLAG_SECURE LIVE (no restart — the F-37-F
   pin's behavior, receipted live), after which every screen rendered in capture. The toggle-ON
   default remains correct for real users; rigs turn it off first. 1364 KB → 1.4 MB PNGs after.
3. **The emulator reboot stales the logcat pipe** — the nohup `adb logcat` died silently at the
   emulator restart; ~40 minutes of the walk had NO app-side logs (receipts came from the relay
   log + the Room DB instead). Recipe: restart `adb logcat -T 1` after ANY emulator restart, and
   treat the relay log + DB pulls as the fallback ground truth.
4. **The stale relay from a prior session holds :18100** — BindException on gen1 launch;
   `pkill -f MockRelayMainKt` first (never by port). Same for the DIRECT port (:18200) — a stale
   holder from a pre-campaign session had to be waited out. `/tmp/p0/relay.sh` now honors
   `MOCK_PORT_OVERRIDE` and `MOCK_PUSH_MS_OVERRIDE` (the hardcoded values cost two probe rounds).
5. **GBoard hijacks taps that land on its toolbar** — two taps meant for app UI hit the IME's
   Lens button and launched Google Lens (+ its camera permission dialog) mid-walk. When the IME
   is open, tap ONLY coordinates verified to be above the keyboard band, or close the IME first
   (BACK closes the IME; ESC does not).
6. **The ANR dialog's buttons are outside the app's AX package** — the dump (filtered to
   `com.zcode.android.app`) showed 12 nodes and "Wait"/"Close app" were untappable via text;
   dismiss by coordinates.

### A1. Verified working this pass (every receipt observed live; wire receipt where relevant)

| Surface | Pass-0 receipt |
| --- | --- |
| Cold-boot auto-reconnect (×7 generations) | Launch → `auth matched` → `CONNECTED` → `hub.bootstrapApplied tasks=3 workspaces=1 dropped=0` + `bootstrapReplayed (no re-fetch)` + `update.check desktop=3.14.3 UpToDatenull`; survived emulator reboot + 4 relay generations + `am kill` process death |
| Home/Tasks list | Session header (Connected · MockBook), approvals badge (2 → 6 with pending interactions), filter chips (All/Synthetic One/Manage), automations hub ("Nightly digest · Daily at 02:00 · Next 3 runs · On · active" + Run now/Pause/History/Edit/Delete + "+ New automation"), Workflows (release-notes/project + Run + Manage), Pinned row with model chip, WORKSPACE group + "+ New task" + Search — all rendered + all arm surfaces live |
| Approvals inbox answer **(end-to-end wire)** | Inbox rows (Bash permissions with Allow once/Deny, the Question card's "Open task to answer" redirect) → "Allow once" → **outbox `resolveInteraction` `{"optionId":"allowOnce"}` state=ACKED ackStatus=accepted in 237 ms**; relay served `sendConversationCommandV4`; the card cleared from the inbox |
| Question card + find + session state | "Agent needs your input" card renders with Keep waiting/Send answers/Skip; find-in-conversation "rig0" → **"1 of 2"** with prev/next; "Conversation query map" sheet with Jump-to-query anchors; session bar flipped **Connected · Completed → Connected · Running** live on send |
| Composer + running state | "rig0 composer probe" sent → `sendConversationCommandV4` (relay id=193); during the run the composer flips to "Keep typing to queue follow-up changes" + Queued delivery chip + **"Stop the agent"** button; header · Running |
| Queue verbs | "Send now" on queued follow-up one → relay `sendConversationCommandV4 (id=89)`; strip **"Queued (2) · paused" → "Queued (1) · paused"** (authoritative round-trip); Move up/down + Edit + Delete + Auto-send toggle render |
| Context sheet (SessionContextSheet) | Status capsule "4m 59s · Iteration 3 · 1 background" + Expand status (Session duration / Iteration / Running background tasks + "Collapse to capsule"); **Goal "Ship it" · verifying · Pause goal + the iteration timeline (iter 1 pass ✓, iter 2 notSatisfied — 2 criteria unmet)**; **Todos 1/2** (Write the changelog completed); **Workflow runs** (lint-all Running, release-notes Stopped); **Plans "Ship the release" 1/3** with per-item ✓/▸/○; **git strip "main, clean"**; chip row Files/Git/Terminal/Model calls/Share |
| Files browser | read-only badge, ws-one root, folder navigation (src → main.rs/lib.rs), Up + breadcrumb; viewer opens with Back/Download |
| Terminal consent (E7) | VIEW-ONLY header + the sensitive-surface copy + "Enable terminal (view-only)" → `terminal.optIn :: enabled=true` logged, view flips to Attach state |
| Model calls sheet | "Search the conversation and model calls" + sampled call rows (main · glm-5.3 · 4200ms · tool_calls "Checking the workspace state" tools: bash; main · glm-5.3 · 950ms "RateLimitError: 429") |
| Share family | Mode chooser ("Share conversation / Plain text — Readable transcript / Markdown — Formatted export / **Print (PDF) — Hand the transcript to the system print service**") → Print opened the **system print spooler** (Select a printer / Copies / Paper size) — the Phase-13 print plane receipts live |
| Notifications permission flow (F-34-B) | "Notifications are off · Approval requests from your desktop can't reach you · Turn on" → deep-links to the system notification page ("All ZCode Companion notifications", "You haven't allowed notifications from this app") → toggle ON → the app banner cleared LIVE (home renders Connected without the banner) |
| Connection ladder | `pkill MockRelayMainKt` → **RECONNECTING** faces live ("Reconnecting…" session bar + "Connection lost — reconnecting…" + "Reconnecting to MockBook…") → gen4 restarted → **CONNECTED** in ~12 s (no tap needed) |
| DIRECT-mode server contract (RIG-P4) | `MOCK_MODE=direct` on :18200: **wrong-token 401 / no-token 401 / right-token 200** on `/api/server-info` — byte-matches the Phase-14 exit-gate receipt; the in-app form renders (Name/URL/Token masked with Show secret/Fallback URLs/"The token is sealed on-device and never leaves except to this server"/Cancel/Add, confirm gated on scheme validation) |
| Settings planes | Endpoints (MockBook relay v3.14.3 · Connected, Add direct endpoint), read-only planes (Usage, Plugins, Skills, MCP servers, Subagents, Providers, Bots, Workspace hooks, Commands) with their sub-copy, Auto-archive, Conversation follow-mode, Notifications detail (Minimal + the honest "Blocked by Android — turn notifications on first" ×4 + Live-connection window), Privacy (Report device state OFF, No analytics copy, Screenshot protection), About (Licenses / "You are on the latest build" / What's new), Unpair + the desktop-side Stop warning |
| Robustness legs | Rotation (landscape 2400-wide reflow: session bar/config/Context/Find all present) + back to portrait, both without state loss; `am kill` process death → relaunch → Connected with the draft/task intact; **ZERO `FATAL EXCEPTION`, ZERO `ANR in com.zcode`** in the full-pass logcat |
| Widget | The app's appwidget provider is registered (appwidget dumpsys lists a zcode entry); binding was RIG-4-verified in the vc51 series |
| Mock gen-nonce plane | Every scripted interaction id carried its generation nonce (DB: `mock-approval-1-mock-p0-gen3-4c5d6e`, `q-rig-1-mock-p0-gen3-4c5d6e`, `probe3`) — no cross-generation id collisions |

### A2. Defects found this pass (NEW ledger rows)

- [ ] **F-P0-A (P2, app) — the conversation approval card refused an answer the inbox later
  delivered on the wire.** Tap "allowOnce" on the head approval card → NO wire call; the composer
  notice renders **"Already handled on the desktop."** (`notice_answer_already_resolved` via
  `ConversationViewModel.answerInteraction` → `InteractionCoordinator.submit` →
  `machine.state in TERMINAL_MACHINE_STATES`). Five minutes later the SAME interaction
  (`mock-approval-1-mock-p0-gen3-4c5d6e`) answered from the APPROVALS INBOX hit the wire and
  **ACKED** (`state=ACKED ackStatus=accepted`, relay `sendConversationCommandV4`). So: the card
  renders actionable, the machine is terminal, the store is not (`interactions` row lifecycle
  DISPLAYED, resolvedAtMs=NULL) — a machine/store/render three-way divergence on the conversation
  card path. User-visible: approvals look dead in the conversation while the inbox still works.
  Suspect: the stale sweep marked the in-memory machine terminal without persisting (the
  F-36-B/A11 family), then a later reconcile re-created the interaction from the row
  (DISPLAYED in store) while the stale machine survived.
- [ ] **F-P0-B (P2, app) — the fresh-pushed approval was STALEd while still pending on the
  desktop.** The RIG-3 fresh push **DID arrive** (interactions row `probe3`, full PERMISSION
  payload, created ~5 s after gen3 subscribe — this RETRACTS this pass's mid-walk hypothesis that
  the push arm was dead; it was stacked below the fold and the truncated `rpc.recv` log lines are
  un-greppable). At the next reconcile, `probe3` was flipped **STALE** (resolvedAtMs set) 15 ms
  after the row-anchored approval was (re)created — i.e. an authoritative list dropped the pushed
  interaction the sweep then killed, while the mock still held `pendingPermission`. The card then
  renders actionable with a terminal machine (feeding F-P0-A). The desktop's 5-minute fuse
  (autoResolution) is NOT the cause (the mock served no fuse for probe3).
- [ ] **F-P0-C (P3, app) — the file viewer renders a SILENT BLANK panel on a refused read.**
  Open `src/lib.rs` in the Files browser → `file.readTextFile begin` → **`refused:
  fault.file.notFound`** (mock fidelity debt — seeded files are listing-only) →
  `files.readText :: TransportFailure` logged → the viewer shows an EMPTY content panel, no
  error text, no retry affordance (`FilesScreen.kt:225-237` only assigns `downloadState` when
  `first.slice != null`; a null slice with no surfaced error leaves the panel blank). The
  honest-failure rule from the FIELD ledger (ApprovalsScreen's "answered but cards stay active"
  fix) needs a sibling here.
- [ ] **F-P0-D (P3, app) — the terminal Attach is a silent no-op.** After opt-in ("Enable
  terminal" → `terminal.optIn :: enabled=true`), tapping Attach produces NO log line, NO wire
  call (relay call histogram has zero terminal calls) and NO error surface — the Idle/"Not
  attached." card just stays. `TerminalScreen.kt:132` → `port.attach()` →
  `Phase9Ports.kt:86` → `controller.attach()` dies before the wire with the exception swallowed
  by the launch. Same silent-failure family as F-P0-C; both need a typed error face.
- [ ] **F-P0-E (P3, observation, causality unresolved) — an ANR dialog fired during rapid rig
  input into the direct-endpoint form.** "ZCode Companion isn't responding" at 20:18:34
  (system_server trace `/data/anr/trace_00` captured; the APP's own trace was rotated away before
  it could be read — a rig-recipe gap). The live SIGQUIT trace four minutes later shows the main
  thread IDLE (epoll wait in Looper) and the app recovered with the form intact. The dialog code
  is clean (plain `onValueChange` state sets, no per-keystroke validation
  `SettingsScreen.kt:528+`), so the flood of 45 rapid DEL keyevents + clipboard ops during the
  IME flush is the likely cause — but it is NOT proven. Recipe for future passes: chunk deletions
  ≤8 per burst with sleeps, and pull `/data/anr/trace_*` IMMEDIATELY when the dialog appears.

### A3. Walk-coverage notes (not defects)

- The hook-trust flow (checkboxes → Trust → confirm → `grantWorkspaceHookTrust`) and the
  mock.png attachment zoom viewer were **not re-driven live this pass** — both are
  geometry-occluded in the fully-armed rig (the pinned card stack covers the rows) and both carry
  full receipts from the vc51 series (pass-14 and the vc52 F-27-B fix pins respectively).
- The mock's `fault.file.notFound` on `readTextFile` for seeded workspace files is **mock
  fidelity debt** (listing-only seeds); recorded so nobody chases it as an app bug.
- The relay gen3 socket dropped once mid-pass (`relay-4 … abnormal close 1006`) and the app
  recovered autonomously on the next attempt — consistent with the ladder receipts, no action.

## Part B — macOS desktop 3.18.1 (`ec475e6e…`) vs the Android companion

### B1. Changelog sourcing (the honest note)

The user's skipped-version delta cannot come from release notes: the public
[zai-org/ZCode releases page](https://github.com/zai-org/ZCode/releases) stops at v3.17.2
(2026-11-04) and every release after v3.14.3 carries NO changelog body (platform list + build
commit + SHA256SUMS only). v3.15.x/3.16.x were never published publicly. The installed desktop
(3.18.1, from the localhost generic update feed, pending zip
`ZCode-3.18.1-mac-arm64.zip` downloaded 2026-11-11 21:54) is AHEAD of the public release page —
so the asar census below is the only complete source for "what's new".

### B2. Desktop census on the extracted asar (31,587 files; `out/{main,host,renderer,…}`)

| Plane | 3.18.1 measurement | Phone side (pass-0 grep of `core/ features/ data/`) |
| --- | --- | --- |
| gen-ui | **SHIPPED**: `genUi` 121 hits / `genUiContext` 28 / `genUiService` 15 / `gen-ui` 10 files (`genUiTweaks/Styles/State/ContextUnsupported`) | **0 files — GAP (new G81)** |
| Rewards / purchase plane (G60 family) | `claimManualPlan` 2 files, `rewards` 7, `purchase` 4 | **0 files — GAP stands** |
| V2 device-registration (WATCH-4) | SHIPPED and re-measured identical: `/api/v1/remote/device`, `RelayV2IncomingMessage`, `parseWebRemoteControlRelayV2IncomingMessage`, `webRemoteControlRelayV2Device` (6 files), `device_sid` (5) | `device_sid` decode only (1 file) — pairing still V1; per WATCH-4 any flip is BREAKING (draft PR, human promotes) |
| workflowRunDeltas gate (G48/G51) | 6 files | **6 files — parity** (vc52 Phase 11/14) |
| autoResolution / 5-min fuse | `autoResolution` 7 files, `snoozeInteractionAutoResolution` 6 | **9 / 5 files — parity** |
| Settings tree | **2,029 distinct `settings.*` keys** (pass-17 measured 1,920 — the tree GREW ~109 keys on 3.14.x→3.18.1) | ~20 rows / 8 sections (the G-family settings-depth gap stands, now wider) |
| W2 families (TodoPanel, TaskSearchDialog, MessageChangeSummaryPanel, image-preview-editor, ConversationShareLinkRow) | **still ABSENT on the desktop** (0 hits) | absent too — no drift, nothing to port |
| CUA / compact / checkpoint | present (43 / 26 files) | phone has CUA row + compact notice + checkpoint-conflict arms (vc52) — parity on the served shapes |

### B3. Gap rows opened/confirmed for the roadmap (order = recommended fix order)

1. **F-P0-A + F-P0-B (P2)** — fix the approval answer-gate divergence FIRST (it is the app's
   core safety surface). Suggested shape: persist the machine's terminal transition (or re-derive
   from the store) so the conversation card and the inbox consult ONE source of truth; add a pin
   that answers the same interaction from BOTH paths and asserts one ACK.
2. **G81 (P3, new gap) — gen-ui consume plane.** The desktop renders generated UI contexts
   (`genUiContext`/`genUiService`/tweaks/styles/state); the phone has zero gen-ui code. Scope:
   decode the served gen-ui frames, render a bounded view (or an honest "the desktop is showing
   generated UI" surface), pin against the mock once the mock arms a gen-ui frame.
3. **F-P0-C + F-P0-D (P3)** — the silent-failure pair (file viewer blank panel; terminal attach
   no-op). Both need typed error faces + a WARN-to-UI surface + pins.
4. **G60 (P4, stands)** — plan-claim/rewards/purchase remains desktop-only (phone zero claim
   surfaces) — unchanged from pass-31's census, re-measured on 3.18.1.
5. **WATCH-4 (standing)** — V2 device-registration plane shipped on the desktop; phone pairing
   stays V1 until the AAP BREAKING decision. Re-grep markers unchanged
   (`/api/v1/remote/device`, `RelayV2IncomingMessage`).
6. **Settings depth (standing G-family)** — 2,029 keys vs ~20 rows; unchanged in kind, re-measured.

### B4. Where the phone is BETTER than the desktop (unchanged families, re-stamped)

- Phone-unique: offline-cached projections + reconnect ladder UX, per-task shade notifications
  with task-scoped deep links, Screenshot protection (FLAG_SECURE) as a user preference, the
  find-in-conversation query map, the DIRECT-mode client (server-info + ws token contract) on a
  phone form factor, minimal-notification detail mode (never tools/paths/titles).
- Desktop-only planes remain: full settings depth, gen-ui, rewards/purchase, MCP/hooks/commands
  authoring (phone shows them read-only BY DESIGN per the ADR boundary family).

## Part C — rig learnings for the next pass (the §A0 list is the recipe)

1. Rig boot: `zcode-a16` cold, NO `-no-window`, `-no-snapshot-load -gpu swiftshader_indirect
   -no-audio -no-boot-anim`; `svc wifi disable` after boot (single-home eth0 — the pass-14
   dual-home flap lesson held).
2. First rig action in Settings: screenshot protection OFF (FLAG_SECURE blacks every capture
   otherwise); verify with a 1.4 MB-class PNG.
3. Relay: `/tmp/p0/relay.sh` (plain java -cp; `MOCK_PORT_OVERRIDE`/`MOCK_PUSH_MS_OVERRIDE`
   honored); `pkill -f MockRelayMainKt` between generations; fresh `MOCK_GEN_ID` per generation.
4. Restart `adb logcat -T 1` after every emulator restart; when the relay log and app log
   disagree, pull the Room DB (`run-as com.zcode.android.app cat databases/zcode.db{,-wal}`) —
   the DB settled two disagreements this pass (the push-arrival retraction; the ACK receipt).
5. IME discipline: taps near an open IME hit GBoard's toolbar (Lens hijack ×2); close the IME
   (BACK) before tapping composer/buttons; `input text` still interleaves across fields — one
   field per IME session, verify the EditText text after EVERY type, and chunk DEL bursts ≤8.
6. The AX-vs-paint trap has a THIRD face now: screencap-black (FLAG_SECURE) with a healthy AX
   tree — check SurfaceFlinger's `(Secure)` layer marker before blaming the renderer.

## Verification stamp

Pass 0 completed 2026-11-12 ~20:30; 7 relay generations (4 relay + 1 direct + 2 transient
reconnects), 39 screenshots in `/tmp/p0/shots/`, receipts in `/tmp/p0/logcat-*.log`,
`/tmp/p0/relay-gen*.log`, and the pulled Room DBs in `/tmp/p0/db/`. APK at HEAD byte-fresh
(`ad449d7d…`); desktop asar digest unchanged from WATCH-4 (`ec475e6e…`). Nothing pushed; the
rig was left running (emulator + gen4 relay + direct :18200) for Ernest's own poke.

---

# Part D — pass 1 (APPENDED 2026-11-12, later the same day): deeper emulation walk + the REA 3.18.1 reverse dig

> **Pass 1 scope per Ernest's follow-up: "dig deeper than pass 0."** Same report file, APPEND-ONLY;
> Parts A–C above are pass 0 and were NOT edited. This pass re-armed the rig (4 fresh relay
> generations), drove the surfaces pass 0 skipped or only rendered, re-drove both pass-0 P2s with
> store/wire-level evidence, and mined the desktop 3.18.1 asar (digest UNCHANGED `ec475e6e…`, so
> every pass-0 census number still stands) with REA receipts + a deeper extraction census for the
> implementable side of G81 and the WATCH-4 V2 plane.
>
> **Environment of record.** HEAD `62602b9a` (main, clean — the pass-0 docs commit), debug APK
> byte-identical `ad449d7d…` (rebuilt up-to-date in 11 s, 239 tasks), versionCode 53 / 1.3.2.
> Emulator `zcode-a16` COLD-booted this pass (44 s, the SystemUI ANR-on-cold-boot recurred and was
> dismissed — same benign class as pass-31's monkey leg). Relay generations: gen1
> `mock-p1-gen1-e2f3a4` (full 23-arm) → gen2 `mock-p1-gen2-b7c9d0` (full) → gen3
> `mock-p1-gen3-c4d5e6` (full, `MOCK_INTERACTION_PUSH_MS=3000` probe) → gen4 `mock-p1-gen4-d8e9f0`
> (LIGHT rig: 12 rows, attachment + row-actions arms only — the timeline-deocclusion recipe, see
> D.A0.4). Desktop under audit: the same `/Applications/ZCode.app` 3.18.1.10376 asar
> (`ec475e6e302595fe…2b10`, 383,832,025 B — byte-verified at pass-1 start), extraction reused from
> pass-0 at `/tmp/p0/asar-out` (31,587 files). REA receipts this pass: `open_binary` on the asar
> (digest-bound session), `analyze_javascript_application` on `out/plugin-sandbox`
> (evidence `ev_6dc928c859ccb6e9…`, 2 parsed JS files / 135,175 AST nodes / record-and-continue,
> interrupted-but-retained — the analyzer's timeout is itself recorded), bundle exported to
> `/tmp/p1/rea-plugin-sandbox.json` (1.39 MB).
>
> **Verification stamp:** 2026-11-12, verified ~20:35–21:50, GLM-5.3-Flash in ZCode (ZCode
> Companion pass-1 rig), everything local, nothing pushed. 24 screenshots in `/tmp/p1/shots/`,
> relay/logcat receipts in `/tmp/p1/*.log`, Room DB pulls in `/tmp/p1/db/`.

## D.A0 — rig learnings (each will bite the next pass)

1. **The slash palette renders in a Compose `Popup` window and is INVISIBLE to uiautomator
   dumps** — `WriteComponents.kt:1152-1173` (N-BD-1's anchored overlay). A dump with the palette
   open shows the composer alone; the palette's rows carry no AX nodes in the app package. Two
   consequences: (a) palette verification needs SCREENSHOTS, never dumps; (b) a tap aimed at dump
   coordinates can land on the OPEN-AND-INVISIBLE palette and silently pick a command — this pass
   a card tap turned into a `/compact ` composer autocomplete (the accidental receipt that the
   catalog pick→autocomplete contract works).
2. **Dialog AX bounds go stale after an IME close and the dump LIES by ~200 px.** The automation
   editor re-lays out when the keyboard closes, but `uiautomator dump` kept reporting the
   pre-IME-close bounds (`Create` at y≈1712) while the screenshot showed it at y≈1932 — every
   dump-derived tap hit the empty scrim and looked like a "dead button". Pass-1 chased this as a
   candidate defect (F-P1-B below was drafted, then RETRACTED in-pass): the screenshot-derived tap
   fired `createAutomation` on the first try. Recipe: for any Compose dialog, take a screenshot at
   decision time and tap SCREENSHOT-derived coordinates (×1080/920 for the scaled view); treat
   dump bounds for dialogs as advisory only.
3. **The logcat pipe goes half-deaf mid-session** (re-confirmed, new face): frames kept arriving
   (DB grew, patches round-tripped) while `rpc.recv` DEBUG lines stopped for whole minutes —
   pass-0's "restart logcat after restarts" recipe needs "…and treat ABSENT log lines as
   inconclusive, never as absence; the Room DB is the arbiter."
4. **The light-rig variant is the recipe for the occluded-timeline legs**: launch the mock with
   ONLY `MOCK_ROWS=12 MOCK_ATTACHMENT=1 MOCK_ROW_ACTIONS=1` (no approval/question/hooks/queue
   arms). The pinned stack empties, the timeline becomes fully scrollable, and the rewind /
   zoom / row-action surfaces are all reachable — gen4 drove every leg that pass 0 skipped.
5. The `1×1` mock attachment is a RED pixel — its thumbnail renders as a solid red block and was
   briefly suspected as an empty-error banner; recorded so nobody chases it again.

## D.A1 — verified working this pass (receipts pass 0 did not have)

| Surface | Pass-1 receipt |
| --- | --- |
| Cold-boot + auto-reconnect (4 generations) | gen1 `hub.phase → CONNECTED` + `bootstrapApplied tasks=3 workspaces=1 dropped=0` at 20:41:08; gen2/gen3 identical; gen4 after `am kill` → relaunch → **CONNECTED in ~6 s** (21:34:14→21:34:20) with a fresh mock handshake (`helloConversationV4` id=3) |
| pauseGoal refusal (MOCK_REFUSE_COMMANDS) | Context sheet → Pause goal → outbox `pauseGoal` = **FAILED_TERMINAL / rejected**, relay `sendConversationCommandV4` — the honest typed-refusal branch (GOAL_PAUSE_REFUSED) runs live |
| Plain-text export | Share → Plain text → the SYSTEM share sheet opened with the real transcript ("Synthetic task ——— Assistant: The coordinator batches deltas per frame and memoizes parsed blocks per row id…") + copy + targets |
| Markdown export | Share → Markdown → `# Synthetic task` / `**Assistant:**` formatted preview in the same sheet |
| Find-in-conversation | "coordinator" → **"1 of 20"** + prev/next; "rig0" on the fresh generation → honest "No matches" (pass-0's 1-of-2 was over its own sent rows) |
| Rewind (dialog + wire) | gen4 light rig → "Undo files" → **full preview dialog**: "Safe to rewind 2" (src/version.ts ↩ 1 edit, scratch/probe.txt − 1 edit), "Cannot safely rewind 1" (src/config.ts ! "Modified after the checkpoint"), "Ignored 1" (tmp/build.log ~ "bash/shell changes ignored") → Apply → outbox **`applyFileRewind {"target":{"rowId":15,"entityId":"ent-15"}}` = ACKED/accepted** |
| Row actions (assistant row) | Like → **`setAssistantFeedback {"target":{"rowId":13},"feedback":"like"}` ACKED**; Retry → **`retryTurn` ACKED** (a "retried reply" row rendered live); Fork → **`forkAssistant` ACKED** AND the app NAVIGATES to the new child session — header **"Fork of synth-task-1"**, a real task row (Idle · Synthetic Workspace · 14 min ago) in the home list, carrying its own approval card projection |
| G28 attachment zoom viewer (F-27-B fix LIVE) | Tap the mock.png thumbnail → **full-screen viewer opens (ImageView + Close)** — the vc52 forwards fix (ConversationScreen.kt:154-169/616-625) verified on-device; pass-31's "dead tap" receipt is closed |
| User-row long-press sheet | Copy / **Ask in a side session** / Share selection / Edit — the user-row action plane renders |
| Slash palette | "/" opens the anchored palette (popup), a pick autocompletes the composer to "/compact " — see D.A0.1 for the visibility gotcha |
| Effort chip (G52 family) | Config chip expands inline (`mode: yolo` / `Effort · max` / model) → Effort → "high" → relay **`setThoughtLevel` (id=33)** + the registry-fallback **`resyncConversationV4`** pair — the wire contract works; the chip re-renders "max" because the mock's static session-config echo re-asserts it (mock fidelity debt, not app) |
| Automation authoring (G29) | "+ New automation" → full editor (Title/Prompt, 8 schedule chips, time "09:00 (24h, desktop clock)", live summary "Daily at 09:00"→"Every N minutes", Ends Never/On date, Max runs, Model, Permission mode) → filled + Create → **relay `createAutomation` (id=47) + `listAutomations` (id=48)** |
| MCP servers plane content | docs-search (http·legacy·0 tools·Disabled), github (stdio·modern·**12 tools**·Connected), playwright (sse·modern·0 tools·Failed) + the error face "Failed to connect to the MCP server. Try again later." |
| Plugins plane content | prisma-toolkit 2.4.0 (official · On) + code-review (none · Off) with descriptions |
| Honest degradation on a light rig | Automations section → **"This feature isn't available on the connected desktop."**; the Plugins plane after process death → "Plugins are unavailable · Connection lost — reconnecting… · Retry" (route restored, honest face, Retry affordance) |
| Process death (am kill) | 0 procs after kill → relaunch → CONNECTED in ~6 s; a brief **UNPAIRED transient face** (21:34:14) before AUTHENTICATING — worth one glance in the next fix round but the ladder converged without user action |
| Widget picker | The launcher's Widgets picker lists **"ZCode Companion · 1 widget"** (the live add-to-home drag is launcher-fiddly via adb; the binding itself is RIG-4-receipted in the vc51 series) |
| Polling cadence (observation) | With the conversation open, `conversationWorkflowRunsV4`/`conversationPlansV4` fire every ~8 s (48+23 calls across gen1's ~6.5 min) — see F-P1-D |

## D.A2 — defects found this pass (NEW ledger rows; order = fix order)

- [ ] **F-P1-A (P2, app) — the pinned AskUserQuestion and hook-trust cards render with ZERO-height
  answer bodies in the live conversation; both are unusable, and hook trust has NO other phone
  surface.** Live evidence (gen1/gen2/gen3, 1080×2340): the question card renders title + "Keep
  waiting" + the questions-variant footer (`userinput_questions_submit` "Send answers" +
  `userinput_decline`) with the body column ABSENT from the AX tree — no prompt, no per-question
  option rows (`userinput_q0_opt_*`), no custom field; "Send answers" is permanently disabled
  (`enabled=false`), the hook-review card likewise renders only title + warning + "2 pending · 3
  hooks" with NO hook items and NO trust buttons, and the Approvals inbox carries NO hook-review
  rows at all. Mechanism: both cards put their scrollable body in
  `Modifier.weight(1f, fill = false).verticalScroll(...)` (`WriteComponents.kt` UserInputCard body;
  `HookTrustReviewCard.kt:117-118`), and the pinned host is a **LazyColumn**
  (`ConversationScreen.kt:440-452`, `heightIn(max = pinnedBudget)`) whose items measure with an
  UNBOUNDED main axis — in unbounded columns Compose distributes ZERO remaining space to weighted
  children, so the body measures 0. The vc52 Phase-9 comment's premise ("each card is measured with
  the full budget as its own cap — no zero-share starvation") is exactly the wrong assumption for
  lazy items. The PinnedCardSubmitPinTest passes because the test hosts cards in a BOUNDED root —
  the live LazyColumn host is the untested part. ApprovalCard is unaffected (its buttons are plain
  non-weighted children). User impact: an AskUserQuestion can only be answered via "Open task to
  answer"→inbox redirect, and a session blocking on WORKSPACE HOOK TRUST (a code-execution
  permission decision — ADR-024's whole reason for the card) has NO working phone remedy. Fix
  shape: replace `weight(1f, fill=false)` with an explicit bounded body (`heightIn(max = …)` on the
  scrollable Column, or size the card to content and let the pinned budget scroll), keep the
  pinned footer, and add a pin that hosts the card in a `LazyColumn { item { … } }` harness (the
  live shape) asserting the option rows are visible.
- [ ] **F-P1-B (P2, app + mock) — stale-generation interactions stay answerable for 50+ minutes and
  ACK "successfully" against a desktop that no longer knows them.** The Approvals inbox renders
  rows from pass-0 generations ("Permission — Bash · task Synthetic task 2 · 50 min ago") with
  live Allow once/Deny buttons; answering one sent `resolveInteraction
  {"interactionId":"mock-approval-push-1-mock-p0-gen4-7f8a9b","answer":{"optionId":"allowOnce"}}`
  (a DEAD generation's push row) → **outbox ACKED/accepted** and the row cleared. The mock accepts
  any interactionId (fidelity debt — a real desktop returns already-resolved/unknown, and the
  phone's failure face for that error is thereby untestable on the rig), but the APP-side half is
  real: the store rows from dead generations never close (`mock-approval-1-mock-p0-gen4-…`
  DISPLAYED and `q-rig-1-mock-p0-gen4-…` REQUESTED 50+ min later, surviving three fresh
  generations; the 20:42:02 `reconcile.closeStored` pass closed only TWO of the five stale ids).
  This is the F-36-B family re-manifested with a sharper user face: actionable buttons on
  interactions from a desktop that has moved on. Fix direction: expire stored interactions whose
  generation/workspace identity no longer matches the connected relay's authoritative snapshot
  (the closeStored sweep exists — it is missing rows), and add the mock's unknown-id error arm so
  the honest-failure face is drivable.
- [ ] **F-P1-C (P3, mock/rig) — the interaction-push arm (`MOCK_INTERACTION_PUSH_MS`) delivered
  NOTHING across three full generations this pass**, while the identical jar + flags delivered
  probe3 in pass 0. Evidence: gen1/gen2/gen3 produced NO `mock-approval-push-1-*` store row, no
  app-side receipt, no wire line — with the env verified present in the relay process
  (`MOCK_INTERACTION_PUSH_MS=25000`/`3000`), the armed-on-first-subscribe code path confirmed
  (`MockZCode.kt armInteractionPush` → `pushPendingPermission`), and the DELTAS path otherwise
  healthy (queue "Send now" → patch → authoritative round-trip "Queued (2)→(1)" receipted). The
  mechanism is unresolved (the push coroutine's `journalAndEmit → emitDeltasFrame` chain has no
  visible gate that could silently drop, so the arm may be racing its own arming flag across
  reconnects — flagged, not concluded). Consequence: the RIG-3 fresh-push shade leg and the
  F-P0-B reproduction are currently UNDRIVABLE; re-arm investigation belongs in the next fix
  round before any push-plane work.
- [ ] **F-P1-D (P3, app, observation) — the open conversation polls `conversationWorkflowRunsV4` +
  `conversationPlansV4` on an ~8 s cadence** (48 + 23 calls in gen1's ~6.5 minutes, continuous
  while the screen is open, even fully idle). On a real desktop this is a persistent request drum
  for every open conversation. Consider: subscribe-on-open with deltas (the
  `workflowRunDeltas` capability the phone ALREADY declares — G48), or a ≥30 s cadence.
- [ ] **F-P0-A sharpened (no code change — pass-0 row stands with new mechanism detail).** The
  conversation approval card exists ONLY when the relay serves a PERMISSION entry in
  `pendingInteractions` — `MOCK_APPROVAL` seeds only the tool-row plane (`setPendingApproval` →
  conversation tool row + Approvals inbox), the `pendingPermission` slot is filled exclusively by
  the (currently dead, F-P1-C) push arm, so with a standard rig the conversation shows NO approval
  card while the inbox badge still counts the tool-row-derived store row. Where the card DID
  render this pass (the gen3 push eventually landed late, after an auto-resubscribe at connect),
  two allowOnce taps were **full silent no-ops** — no wire, no notice captured, card still
  actionable, store row STALE (`mock-approval-push-1-mock-p1-gen3-c4d5e6` created 20:54:38,
  STALEd 20:57:17 while the mock still held it pending = **F-P0-B reproduced live**). The
  machine/store/render three-way divergence from pass 0 stands, now with the card-plane
  precondition documented: fix the served-list/source-of-truth divergence FIRST (pass-0 B3.1
  shape), and the card-tap silent no-op will resolve with it.

## D.B — REA 3.18.1 reverse dig (deeper than pass-0's census; digest unchanged `ec475e6e…`)

### B1. gen-ui plane — G81 is now an IMPLEMENTABLE SPEC, not just a hit-count

The desktop's gen-ui is a first-class remote-client plane: `genUiService` sits in the connection
services registry beside `fileService/gitService/terminalService/settingService` and is exposed
through the **remote workspace proxy** (`createHostRemoteWorkspaceProxyState`) — i.e. the phone's
transport COULD consume it today, exactly like it consumes file/git services.

- **Transport surface**: service methods `readDocument(document)` / `getState(target)` /
  `setState({target, state})` / `onStateChanged` (host; per-workspace state files under
  `gen-ui-state/<id>.json`, size-capped; document ids `gen-ui:<uuid>`).
- **Capability gate**: `sendFollowUp` with `genUiContext` throws **`proto.genUiContextUnsupported`**
  unless `capabilities.genUiContext === true` — the phone already declares a clientHello
  capabilities block (`workflowRunDeltas`, G48 pin at
  `RelayTransportClientHelloCapabilitiesTest`), so the G81 fix is: declare `genUiContext: true`
  once the consume plane ships; until then the desktop itself rejects gen-ui-context sends from
  the phone.
- **Message shape**: `sendFollowUp { prompt, source: { kind: 'genUi', path, title } }` with
  `genUiContext` riding the send; content payload carries
  `contentKind: 'gen-ui' | 'extension'` (+ `pluginId`/`serverName` for extension content),
  `scopeId`, and **`html: string`** — the interactive view is HTML rendered in the
  `plugin-sandbox` (its own bundle: `out/plugin-sandbox/gen-ui.js`, 640 KB, zod-validated;
  `visualize.html/css` shell; CSS tweaks arrive as `--gen-ui-[a-z-]+` custom properties).
- **Desktop UI faces** (the complete i18n vocabulary, EN): `genUi.title` "Interactive view";
  `genUi.waiting` "Generation completes…"; `loading`/`failed` "Unable to load interactive view" +
  `retry`; `offline` "Workspace is offline. Reconnect and retry"; `incomplete` "This response
  didn't finish, the interactive view wasn't loaded"; `invalid` "Interactive view reference is
  invalid"; `expand`/`expanded` (zoom viewer); `tweak` "Adjust design" → `submit` "Submit design
  changes" + `reset`/`original` (the design-tweak loop); `confirm` "Interactive view requests to
  send a message" (the send-consent gate); `from` "From interactive view {name}"; `copyImage`;
  and **`genUi.desktopOnly` "Open this interactive view in the desktop app"** — the desktop
  ALREADY renders a non-desktop fallback face, so the phone's honest "the desktop is showing a
  generated interactive view" surface (G81's suggested shape) is the product-sanctioned minimum;
  a real consume plane is the upgrade.
- Phone side re-measured: **0 files** match genUi/genUiContext — G81 stands.

### B2. V2 device-registration plane (WATCH-4) — the concrete migration inventory

`RelayV2Device` ×12 files, `RelayV2IncomingMessage`, plus a REST+WS surface:

| Endpoint | Method | Role |
| --- | --- | --- |
| `/api/v1/remote/device` | POST | register (device-picker flows) |
| `/api/v1/remote/device` | GET | list (the desktop's "device-picker"/"device-sync-state" UI) |
| `/api/v1/remote/device/delete` | POST | unregister |
| `/api/v1/remote/token` | POST | token exchange |
| `/api/v1/remote/ws` | (upgrade) | the V2 relay websocket |
| `/api/v1/remote/notification/status` + `/events` | GET/POST | **NEW in this census** — a remote-notification status + Bearer-authenticated event-reporting plane (`reportNotification`) |

Phone side: still `device_sid` decode only (1 file); V1 pairing unchanged; per WATCH-4 the flip
is BREAKING (draft PR, human promotes). The notification/events plane deserves a second look in
the next sweep — it may be the desktop-side counterpart of the phone's shade-notification feed.

### B3. Settings census (same method as pass 0)

**2,099 distinct `settings.*` keys** (pass-0: 2,029 — +70). Namespace histogram (top): plugins
1,193 label strings / source 850 / usage 715 / **mcp 555** / **skills 505** / **subagents 430** /
**commands 330** / **hooks 300** / **shortcuts 285** / browser 241 / migration 230 / **memory
195** / mcpServers 145 / computerUse 70 / personalization 55 / dynamicWorkflow 45 /
taskAutoArchiveDays 25 / appearance 21. The phone's ~20 rows / 8 sections map to appearance,
taskAutoArchiveDays and slivers of the rest — the G-family settings-depth gap stands and widened
again; the newly VISIBLE namespaces for the roadmap are **shortcuts** (a full keyboard-shortcuts
plane the phone has no counterpart for — 4 incidental files), **memory** (a memory-plane the
phone lacks; the read-only Settings has no Memory entry), and **migration**.

### B4. Small plane drift

- **Voice-input taxonomy**: the desktop's voice plane carries per-OS permission guides, `silent`,
  `noDevice`, `deviceBusy`, `unauthorized` error faces + a permission-granted toast; the phone
  (`features/conversation/voice/`) has two faces (MIC_PERMISSION / UNAVAILABLE, F-34-D). Small
  gap row: port the `deviceBusy`/`silent`/`noDevice` faces onto the phone's VoiceError.
- Absent on BOTH sides (no drift, nothing to port): agentTeams, workflowsv2, dictation,
  checkpointTimeline, sessionNotes. `canvas` hits are HTML-canvas usage, not a plane.

### B5. REA receipts

`open_binary` bound the asar (sha256 `ec475e6e…2b10`, archive provider); the JavaScript
application analysis ran on `out/plugin-sandbox` (evidence `ev_6dc928c8…`, 2 parsed JS files,
135,175 visited AST nodes, integrity record-and-continue, analysis interrupted at the client
window and retained as complete-record evidence — exported to `/tmp/p1/rea-plugin-sandbox.json`).
`trace_feature`/binary-census tools are correctly unsupported for an asar target (typed
capability errors received — recorded so future passes skip straight to the directory analyzer +
extraction census).

## D.C — where the phone is BETTER than the desktop (new receipts this pass)

- The honest-degradation faces on a capability-light desktop ("This feature isn't available on
  the connected desktop", "Plugins are unavailable · Retry") — the desktop shows no such
  counterpart for phone-only planes.
- The fork child-session is OPENED live in the same flow (forkAssistant → "Fork of synth-task-1"
  screen + home row) — the desktop's childSessionId remains a decoded-but-unused field on the
  phone's P4-smallests list; the FORK path (user-invoked) is the live one.
- Process-death recovery with route restore + per-plane honest faces, and the ~6 s reconnect
  ladder, again.

## D.D — fix-order recommendation (replaces pass-0 B3.1's order)

1. **F-P1-A** — the hook-trust unreachability is a safety-surface blocker; smallest fix (bounded
   body) + a live-shape pin.
2. **F-P0-A + F-P0-B** — unchanged openers, now with the card-plane precondition (the card needs
   a served PERMISSION entry; the push arm must work to reproduce) documented.
3. **F-P1-B** — stale-generation closure + the mock's unknown-id error arm.
4. **F-P1-C** — mock push-arm re-arm (blocks the push-plane legs).
5. **G81** per D.B1: minimum = the honest "Interactive view is open on your desktop" face wired
   to the served gen-ui blocks + the `genUiContext` capability left false; full = the
   genUiService consume (readDocument/getState/setState) + a sandboxed HTML surface.
6. **F-P1-D** polling cadence + the voice-taxonomy smalls (D.B4).

### Coverage honesty (what pass 1 did NOT drive)

Unpair/re-pair (full pairing loop — prior receipts from the vc47 field round + vc51 fresh-pairing
stand; not re-driven to protect the rig mid-pass), Workflows Run (the light rig doesn't serve
workflows; pass-0's hub receipt stands), theme/font/locale live toggles (vc51 pins + pass-31
receipts stand), and the shade-notification fresh-push leg (blocked by F-P1-C).

## Pass-1 verification stamp

Pass 1 completed 2026-11-12 ~21:50; 4 relay generations (3 full + 1 light) + 1 process-death
cycle; 24 screenshots in `/tmp/p1/shots/`; receipts in `/tmp/p1/logcat-gen*.log`,
`/tmp/p1/relay-gen*.log`, Room DB pulls in `/tmp/p1/db/`. APK byte-identical (`ad449d7d…`);
desktop digest unchanged (`ec475e6e…`). Nothing pushed. Rig left: emulator + gen4 light relay on
:18100.

---

# Part E — pass 2 (APPENDED 2026-11-12, same day, later still): the whole-app re-walk + the 38-service remote-surface reverse

> **Pass 2 scope per Ernest's re-ask ("verify every single thing… dig deeper than previous passes").**
> Same report file, APPEND-ONLY; Parts A–D above are passes 0–1 and were NOT edited. This pass
> re-armed the rig (3 fresh relay generations + a DIRECT generation), drove the surfaces passes
> 0–1 skipped (workflow Run acceptance, automations Run now, attachment upload, voice, the git
> sheet's full depth, the endpoint detail plane, unpair→re-pair with BOTH consent gates), pinned
> the pass-0/1 defect cluster to CODE with the root causes below, and mined the desktop 3.18.1
> asar (digest RE-VERIFIED byte-identical `ec475e6e…2b10` at pass-2 start) down to the REMOTE
> SERVICE REGISTRY — the definitive 38-service contract the phone could consume.
>
> **Environment of record.** HEAD `e4948499` (main, clean), debug APK byte-identical `ad449d7d…`
> (rebuilt up-to-date in 10 s, 239 tasks), versionCode 53 / 1.3.2. Emulator `zcode-a16` warm from
> the pass-1 rig (same process). Relay generations: gen1 `mock-p2-gen1-a2b4c6` (full 23-arm,
> `MOCK_INTERACTION_PUSH_MS=5000`) → gen2 `mock-p2-gen2-b4d6f8` (full + `MOCK_AUTOMATION_RUNS=1`,
> 3 s push) → gen3 `mock-p2-gen3-c6d8e0` (full, 3 s push) + DIRECT `mock-p2-direct` on :18200
> (`MOCK_MODE=direct`, `MOCK_PORT_OVERRIDE=18200` — the port override is REQUIRED; relay.sh
> hardcodes MOCK_PORT=18100 which collides with a live relay gen). Desktop under audit: the same
> `/Applications/ZCode.app` 3.18.1.10376 asar, `ec475e6e302595fe…2b10`, 383,832,025 B — opened in
> REA (`open_binary`, archive provider) and analyzed (`analyze_javascript_application` on
> `out/host`, evidence `ev_6899615b6bfa8c43acb202ab34a9e3eb5143e700d1b16a0c7f5167cd62062fc8`,
> complete-record retention, dir digest `3d4aa76f…`, exported to `/tmp/p2/rea-host-bundle.json`,
> 471,621 B). The pass-0 extraction at `/tmp/p0/asar-out` (31,587 files) served the census greps.
>
> **Verification stamp:** 2026-11-12, verified ~21:45–22:45, GLM-5.3-Flash in ZCode (ZCode
> Companion pass-2 rig), everything local, nothing pushed.

## E.A0 — rig learnings (each will bite the next pass)

1. **The DB-pull race produces FALSE ABSENCE.** Pass-2's mid-walk pull showed NO push row six
   seconds after the patch was proven delivered (gateway.frame receipt) — the row appeared in the
   next pull with its ORIGINAL createdAt. The `run-as cat db + cat db-wal` two-step is not a
   consistent snapshot; a checkpoint between the two cats loses the row from BOTH files. Recipe:
   treat a missing row as inconclusive while the app is live; re-pull after quiescence, or prefer
   the app's logcat lifecycle lines as the arbiter of "did it arrive".
2. **`input text` loses TAIL characters on long payloads, and the loss point VARIES with chunk
   size**: 178-char one-shot → 39 survived; 7×~25-char chunks → 149 (29 tail chars lost); 13×
   ~14-char chunks → 174 (2 lost); 11×~16-char chunks with per-chunk dot-count verification →
   176/176. The ONLY reliable recipe is the verified-chunk loop: after every chunk, read the
   field's own length hint (the pairing field masks content as • dots — count THEM), and on a
   short count `MOVE_END` + retype exactly the missing tail. Continue must be tapped at
   SCREENSHOT-derived coordinates (the 22:34 Continue tap at dump coordinates silently no-op'd —
   the D.A0.2 dialog-bounds trap again).
3. **The pairing field is a •-mask** — its dot count is the length oracle. Any future link-typing
   leg should script the count-verify-repair loop (E.A0.2) instead of trusting `input text`.
4. **The emulator's NAT dropped the ws socket TWICE this pass** (22:04:25 and 22:12:32, both
   `abnormal close 1006 Software caused connection abort`) with NO mock-side disconnect arm set;
   both recovered autonomously (17 s and <1 s ladders). A THIRD failure face: the BACKGROUND
   reconnect ladder timed out twice (10 s connect timeouts at relay-8/relay-9) until the app
   foregrounded — background reconnect cadence is throttled by design; foreground resumes
   instantly. Also: the DIRECT reconnect while backgrounded hit a typed
   `ANDROID local-network-blocked` candidate face (Android 16's local-network gate) — the honest
   typed-face machinery handles it; foreground unblocks.
5. **`MOCK_AUTOMATION_RUNS` was never armed in passes 0–1** — the automation Run-history sheet's
   "This feature isn't available on the connected desktop" face was a RIG gap, not app behavior
   (the mock serves the plane when armed; the flag existed all along).
6. REA MCP object-params arrive at the tool as JSON STRINGS through this harness (`expected
   object, received string` on `inspect_analysis_view`'s source/view and `export_evidence_bundle`'s
   overwrite) — boolean/object params fail validation, string/path params work. Recipe: use the
   bundle-summary + export paths for receipts; don't burn time on the view calls.

## E.A1 — verified working this pass (new receipts, wire-level where relevant)

| Surface | Pass-2 receipt |
| --- | --- |
| Push plane end-to-end (gen1) | subscribe 21:50:12.7 → **patch arrived 21:50:17.8** (`gateway.frame seq=2..2` + `manager.deltas ops=1`) → row `mock-approval-push-1-mock-p2-gen1-a2b4c6` created by the patch — the RIG-3 arm works at HEAD (see E.A2.3 for what pass-1 actually saw) |
| Background push + shade (gen3) | app backgrounded → push patch `seq=2..2` at +3 s → **shade group posted**: "ZCode needs your approval" + "ZCode has a question for you" (dumpsys: channel=permissions, AUTO_CANCEL, contentIntent=startActivity) — the F-34-E notification ask + the RIG-3 off-foreground delivery leg both live |
| Automations Run now | home → Run now → **`runAutomationNow` (id=113)** + `listAutomations` (id=114) re-list — the wire verb receipts |
| Run-history sheet | renders Run history + "Nightly digest" + filter chips (All/In progress/Succeeded/Failure) + the honest "Scheduled tasks only run while your computer is awake" caption — content needs the `MOCK_AUTOMATION_RUNS` arm (E.A0.5) |
| Workflow Run ACCEPTED end-to-end | Run sheet renders params ("Run release-notes / Before tagging a release / version * (required)") + Run disabled until filled → version typed → **`port.startWorkflow :: name=release-notes outcome=Accepted`** → `tasks.openRun` → navigated into the run-in-task conversation → the run appears **Running** in the Context sheet's Workflow runs (release-notes Running, lint-all Running) — the full run-in-task plane receipts |
| Composer slash send | `/compact` typed → palette → send → `sendConversationCommandV4 (id=146)` ok 81 ms (slash text rides the normal message plane; interpretation is the desktop's) |
| Attachment upload protocol | + → Attach a file → system picker → `p0dump.xml (9.1 KB)` chip → **`attachmentBeginV4 (id=9)` → `attachmentChunkV4 (id=10)` → `attachmentCommitV4 (id=11)`** — the begin/chunk/commit contract receipts LIVE for the first time (a mid-pick socket drop surfaced the chip's **Retry** affordance; Retry completed the upload) |
| Voice plane | + → Dictate → system mic-permission dialog → grant → **"Voice input isn't available on this device"** (the honest F-34-D UNAVAILABLE face on the mic-less emulator) |
| Running-state faces | the sent message started the mock run: composer → "Keep typing to queue follow-up changes" + Queued chip; session bar **Connected · Completed → Connected · Running** live; row actions Fork/Retry/Delete render |
| Context sheet live values | Status "4m 59s · Iteration 3 · 1 background"; Goal "Ship it · verifying · iteration 3" + iteration timeline; Todos 1/2; **Workflow runs (release-notes Running — MY submitted run, live)**; Plans 1/3; git strip main/clean |
| Git sheet full depth | read-only badge; branch block "main / tracking: origin/main / main → origin/main / **2 changed file(s) vs the tracking branch**" with per-file **+42 −0 / +3 −7**; Commit…/Branches…; Changes → Unstaged (2); Checkpoints/Snapshot tabs render (both tabs served the same content on this rig — snapshot arm only) |
| Files browser + viewer | ws-one root → src → main.rs/lib.rs list; lib.rs tap → `file.readTextFile refused: fault.file.notFound` (mock listing-only debt) — viewer face below in E.A2.6 |
| Terminal attach (F-P0-D re-verify) | opt-in persisted → Attach → **`terminal.create begin → ok` + `terminal.attached :: id=term-1`** — screen: **VIEW-ONLY · "live — /bin/zsh"** + Pause/Search + Copy visible output/Detach. The pass-0 silent no-op did NOT reproduce on the same APK (`ad449d7d…`) — pass-0's receipt was transient |
| Endpoint detail plane | Settings → MockBook row → "MockBook · desktop 3.14.3 · Check for updates · Last refreshed… · Connection states" ladder faces (Not paired/Connecting: Validating a pairing link/Proving identity) — the connection-ladder diagnostics plane renders |
| DIRECT connect (full) | form (Name/URL ws://10.0.2.2:18200/ws/Token masked + Show secret/Fallback URLs) → Add → **`direct.connected :: via=10.0.2.2:18200 candidatesTried=1`** → `off-peak-task.list ok` + `zcode-task.listTasks ok` on direct-1 → **`hub.directTasksApplied :: tasks=3 workspaces=1`**; home shows BOTH endpoints (MockBook relay Connected + "P2Direct · Direct server · v3.14.3 · Connected"); Skills plane renders over the DIRECT endpoint |
| Unpair → re-pair (full loop, both gates) | Settings bottom → Unpair this device → confirm "Unpair from MockBook?" → **`relay.closed :: unpair` + `hub.unpaired :: active endpoint + registry + selection cleared`** → pairing route → Paste pairing link → validator gauntlet (see E.A2.2) → **stale-link gate** ("This QR code may be stale · MockBook · 3.14.3 · Continue anyway") → **E7 security-setup consent** ("Security setup · Continue anyway") → **AUTHENTICATING → CONNECTING → CONNECTED 22:43:04** + `hub.bootstrapApplied tasks=3 workspaces=1 dropped=0` — the whole ladder with both fail-closed consent gates receipts live |
| Reconnect resilience | two spontaneous 1006 drops recovered autonomously (17 s / <1 s); the reconnect handshake (hello→initialize→resyncSessionsIndex→resyncWorkspaceConfig→subscribe) re-served cleanly with per-connection rpc-id reset |

## E.A2 — defects, refutations, and root causes found this pass (order = fix order)

1. **F-P2-A (P2, app, NEW) — the activity recreate silently CLOBBERS the user's DIRECT-endpoint
   selection back to the relay.** Live chain: P2Direct added 22:21:24 → `hub.directTasksApplied`,
   home shows "P2Direct · Connected" → the app's activity recreated fresh at 22:23:14 (launcher
   relaunch after BACK-exit; `onCreate savedInstanceState=false`) → 22:23:15.967
   `direct.candidateFailed :: PROTOCOL channel closed: endpoint switch advance=true` (the DIRECT
   transport killed by a switch IT did not choose) → 22:23:17.371 `hub.connect :: cold-start
   connect begin (kind=RELAY_ZAI)` → 22:23:17.620 `hub.switched :: endpoint=c23888ef… kind=
   RELAY_ZAI`. **No user action in between.** Root cause, code-pinned: `ZCodeApp.kt:182-196`'s
   `LaunchedEffect(paired)` else-branch (the "cold-restore edge") calls
   `container.hub.onPairingCompleted()` on EVERY recreation where a relay pairing exists, and
   `ConnectionHub.kt:661-667 onPairingCompleted` sees `active ≠ relay-meta` →
   `scope.launch { switchTo(meta.endpointId) }` — force-adopting the relay over the persisted
   `selectedEndpointId` (which `resolveActiveEndpoint` at :543-571 otherwise honors, DIRECT
   included, with the §12.6 dead-token fail-closed). The §11.6 multi-endpoint feature therefore
   survives a recreate ONLY while the user never recreates — i.e. never, on Android. Fix shape:
   the cold-restore else-branch must not adopt the relay when `preferences.selectedEndpointId`
   names a live non-relay endpoint — pass an "adoptOnlyIfNone" flag into `onPairingCompleted`
   (or check `_endpoint.value != null` before the :665 switch). Pin: add a direct endpoint in a
   Robolectric recreate harness, recreate the activity, assert the active endpoint is still the
   DIRECT one and `hub.switched kind=RELAY_ZAI` never logs.
2. **F-P2-B (P3, app, NEW) — after unpair, the pairing welcome still claims "Paired with
   MockBook · Connect".** `hub.unpaired` cleared vault/registry/selection (receipted), but the
   pairing screen's resume card renders from the VM's retained `link` (`PairingScreens.kt:100`,
   `welcome_resumed_with`) — the wording says PAIRED for a device whose credentials are wiped,
   above a Connect button that can only fail/re-pair. Same family as the vc51 "stale-link
   Continue anyway" papercut; fix = clear the pairing VM's link on unpair (or gate the resume
   card on a live vault credential).
3. **F-P1-C REFUTED at HEAD — the mock push arm is healthy; pass-1's "delivered nothing" was the
   DB-pull race (E.A0.1) + the immediate STALE confound (below).** This pass the arm fired on
   schedule in gen1 (5 s), gen2 (3 s, backgrounded), gen3 (3 s, backgrounded — the shade leg);
   every patch arrived (`gateway.frame seq=2..2`) and every row was created by the patch. No
   mock change between passes — the jar is the same build. The F-P1-C ledger row should be
   re-labeled "measurement artifact, refuted pass 2" and the push-arm re-arm TODO dropped.
4. **F-P0-A + F-P0-B ROOT CAUSE PINNED (code-level) — the rows-plane reconcile sweep kills
   state-plane-delivered interactions, and the two reconcilers FIGHT.** Chain, all receipted on
   gen1: (a) push patch creates the interaction live (21:50:17.8); (b) the next
   `recovery.snapshotReconcile` (rows=53) runs `InteractionCoordinator.reconcileFromSnapshot`
   whose sweep (`data/src/main/kotlin/com/zcode/android/data/session/InteractionCoordinator.kt:694-700`)
   exempts user-input (U9) and rows-anchored (T2) machines but NOT state-plane-delivered
   PERMISSION interactions (`fromRowsSnapshot=false, userInput=null`) → `ResnapshotLacks` →
   **STALE** while the desktop's authoritative pendingInteractions still lists it (mock holds
   `pendingPermission`); (c) the F-27-A state-plane re-list RESETS the machine live
   (`reconcileFromSessionState` :744-756), but the rows sweep runs 36 ms later (21:51:28.779
   state reconcile vs .815 rows reconcile) and re-STALES it — the card renders actionable with a
   terminal machine; (d) the tap lands in `ConversationViewModel.answerInteraction` →
   `submit`'s terminal guard (:179) → **"Already handled on the desktop."** with zero wire calls
   (receipted live twice this pass). Meanwhile the SAME generation's inbox ACKed a stale row
   (`mock-approval-1-mock-p0-gen4-7f8a9b` RESOLVED 21:58:47 via
   `sendConversationCommandV4 id=105`) — the divergence face, again. **Fix shape (one line
   class): extend the :697 exemption to `known.permission != null` (a permission machine the
   rows plane never anchored is the STATE plane's to own — its own disappearance leg at :815-825
   already resolves it truthfully), or track `lastStatePlaneOpenIds` and skip those ids in the
   sweep.** This closes the card-plane precondition pass-1 documented: with the exemption, the
   conversation card stays answerable and F-P0-A's refusal disappears.
5. **F-P1-B re-manifested live at HEAD** — the inbox rendered two "1 h ago" rows from the DEAD
   pass-0 gen4 (task Synthetic task 3 / Synthetic task 2) with live Allow once/Deny; the tap
   ACKed against the current relay (`sendConversationCommandV4 id=105`, store RESOLVED
   21:58:47) while the mock's pendingPermission belonged to a different generation. Mock
   fidelity debt (accepts any interactionId — a real desktop returns unknown/already-resolved)
   stands; the app-side expiry sweep still misses dead-generation rows (pass-1's closeStored
   covered 2 of 5).
6. **F-P0-C re-verified byte-identical at HEAD** — `file.readTextFile refused:
   fault.file.notFound` → `files.readText :: TransportFailure` → the viewer renders a SILENT
   BLANK panel (screenshot `24-file-viewer.png`: title/read-only/breadcrumb/Back/Download and an
   empty content box — no error text, no retry). The FilesScreen typed-error-face gap stands.
7. **F-P1-A re-reproduced on a fresh generation with one sharpening** — the AskUserQuestion card
   renders title + "Keep waiting" + footer ("Send answers" enabled=true this pass — pass-1's
   "permanently disabled" was render-state-specific) with the body/options ABSENT from the AX
   tree, and the hook-review card renders "2 pending · 3 hooks" with NO hook items/buttons; the
   tool-row approval card (plain-children) renders its FULL option body on the same screen
   (`allowOnce/allowAlways/Do not ask again` visible in shot `04-approval-card.png`) — the
   LazyColumn zero-share weighting diagnosis stands, now with the healthy-vs-broken contrast on
   ONE screen.
8. **F-P0-D RETRACTED to "unreproduced"** — terminal attach works at HEAD (`terminal.attached
   :: id=term-1`); pass-0's silent no-op did not reproduce on the same APK. Keep the typed-error
   face work as a hardening row only.
9. **Deep-link child-tap nuance (observation, not a defect)** — tapping the approval child
   notification resumed the app into the task conversation, but that conversation was already
   the top route, so the deep-link extras were not distinguishable from a plain resume
   (`app deeplink :: onNewIntent data=null` — the intent carries no data URI; navigation rides
   extras). A clean leg (navigate HOME first, then tap) belongs in the next verification pass;
   the notification→app resume path itself is receipted.

## E.B — the REA 3.18.1 reverse dig, part 3: the remote-service contract (digest unchanged `ec475e6e…`)

### B1. The clientHello capability vocabulary — zod-verified, complete

The desktop's `clientHello` schema (out/host/index.js, `.strict()`) accepts EXACTLY:

```
clientKind: "desktop" | "web" | "mobileRemote" | "mobileApp"   (optional)
capabilities: {
  workspaceHookReviewUi: boolean  (optional)
  workflowRunDeltas:     boolean  (optional)
  pluginDiscoveryUi:     boolean  (optional)   ← NEW to the census (16 hits)
  historyFirst:          boolean  (optional)   ← NEW (15 hits)
}
```

- The phone (V4Plane.initializeV4, `core/transport/V4Plane.kt:659-677`) declares
  `workflowRunDeltas: true` + `workspaceHookReviewUi: true` and sends a clientKind
  (`CLIENT_KIND_DEFAULT`). **The phone does NOT declare `pluginDiscoveryUi` or `historyFirst`**
  — two dormant, schema-legal capability bits.
- A second hello schema (the desktop↔agent plane) carries MORE flags: `pluginDiscovery`,
  `inputPrediction`, `residentRowsRange` — desktop-internal vocabulary; not remote-client
  surface, recorded to stop future passes re-deriving it.
- `sendFollowUp`'s request schema carries **`inputOrigin: enum(["desktop","mobile"])`** — the
  send plane is origin-tagged end-to-end. Worth one grep in a later pass: does the phone's
  sendConversationCommandV4 declare inputOrigin=mobile? (The phone's messages were accepted all
  passes, so it is optional — but declaring it would let desktop-side analytics/attribution see
  mobile sends.)
- The G81 bit stands unchanged: `clientSupportsWorkflowRunDeltas` is the only gate function in
  host (`M2`), and the genUiContext gate (`proto.genUiContextUnsupported`) remains the phone's
  missing capability.

### B2. THE 38-SERVICE REMOTE REGISTRY — the definitive "what the phone could consume" list

`createHostRemoteWorkspaceProxyState`'s registry array (out/host/index.js, `var gz=[…]`) — the
authoritative remote-client surface, byte-extracted this pass:

```
fileService, genUiService, gitService, gitCheckpointService, systemService, terminalService,
settingService, onboardingRecordService, credentialService, broadcastService, zcodeTaskService,
zcodeAgentService, zcodeSessionService, conversationShareService, botsService, fileWatcherService,
oauthService, providerSettingsService, modelSelectionService, mediaPreviewService,
sourceServerWorkspaceService, usageStatsService, codingPlanSubscriptionService,
clientScenesService, skillsService, skillSyncService, mcpSyncService, pluginSyncService,
userConfigSyncService, pluginsService, pluginManagementService, subagentsService, commandsService,
hooksService, memoryService, outputStyleService, settingsSyncService, promptAttachmentTransferService
```

(the window-internal service list adds `feedbackService` — 39 total; `automationService` and
`usageService` are NOT service names — automations ride `zcodeAgentService`/`zcodeTaskService`
methods, usage rides `usageStatsService`).

Phone-coverage matrix (the pass-2 gap ledger, order = recommended fix order):

| # | Desktop service | Phone today | Verdict |
| --- | --- | --- | --- |
| 1 | `memoryService` (renderer: 5 hits — a **Memory viewer UI with `memoryEnabled` toggle + `projectMemoryViewerAvailable` + a `remoteDevice:` flag in the component props**) | **zero surfaces** (pass-1 flagged the settings namespace; now pinned to a SERVICE) | **NEW GAP G82 (P3)** — a memory viewer plane the phone could mirror read-only, like Plugins/Skills |
| 2 | `clientScenesService` (renderer: 6 hits) | the phone has Presentation/Coding faces (F-36-C family) but no scenes CONSUME plane | **NEW GAP G83 (P4)** — inventory the scenes vocabulary before deciding |
| 3 | `outputStyleService` (renderer: 2) | none | **NEW GAP G84 (P4)** — desktop personalization plane, read-only candidate |
| 4 | `broadcastService.onMessage` (renderer: **36 hits** — a real broadcast UI plane) | none | **NEW GAP G85 (P3)** — the largest un-inventoried remote plane; census its message vocabulary next pass |
| 5 | `codingPlanSubscriptionService` (renderer: **2,463 `codingPlan` hits**) | none (G60 stands) | G60 DEEPENED — the plan/subscription plane is one of the desktop's biggest UI investments |
| 6 | `zcodeTaskService.setTaskUnread` (renderer: 8) | the phone tracks its own read state; no desktop unread badge sync | **NEW SMALL** — unread-badge sync over the remote plane |
| 7 | `systemService.info` | the phone gets desktop version via pairing metadata only | **NEW SMALL** — a system-info face for the endpoint detail plane |
| 8 | `conversationShareService` | the phone shares text/markdown/print LOCALLY (pass-0 receipts) | verify next pass whether the desktop's share SERVICE offers link-sharing the phone lacks (G-family candidate) |
| 9 | `zcodeSessionService.createSession` | the phone has + New task (task mgmt plane) but no bare session create | verify: is the phone's New task routed through this? |
| 10 | `onboardingRecordService` / `credentialService` / `oauthService` / `fileWatcherService` / `userConfigSyncService` / `settingsSyncService` / `skillSyncService` / `mcpSyncService` / `pluginSyncService` | n/a — desktop-credential or desktop-sync machinery the phone must NOT consume (security boundary ADR-024 family) | NO-ACTION (recorded so the roadmap never "ports" them) |
| 11 | everything else (file/git/terminal/setting/task/agent/session/skills/mcp/plugins/subagents/commands/hooks/usage/model/media/sourceServer/promptAttachment) | the phone consumes these today (the read-only planes + conversation/files/terminal/attachment planes) | PARITY, receipts in E.A1 |

### B3. Method-inventory cross-check (consumer-grounded)

The phone calls 34 distinct methods (extracted from the app source into
`/tmp/p2/phone-methods.txt`); every non-template name greps GREEN in the asar
(`conversationWorkflowRunsV4` → host+main+renderer+scheduler, `listAutomationRuns` →
host+renderer, `getTaskTokenUsage` → host, `setThoughtLevel` → 5 dirs, …). The MISSING-from-asar
names (`zcode-task.listTasks`, `file.readTextFile`, `git.status`, `terminal.create`,
`usage-stats.getEntitlementSnapshot`, …) are TEMPLATE-BUILT channels on the desktop
(`zcode-task.${op}`, `file.${op}`) — the literal-grep diff cannot see them; the SERVICE registry
above is the authoritative surface, and every phone channel's prefix rides a registry service.
`startWorkflow` is a sendConversationCommandV4 COMMAND TYPE (the phone's wire receipt in E.A1),
not a channel — recorded so nobody hunts for a `startWorkflowV4`.

### B4. V2 device plane — re-measured, unchanged

`reportNotification` lives in out/main only (2 hits) — the Bearer-authenticated notification
event-reporting plane is DESKTOP-side; no new phone-side obligation surfaced this pass. WATCH-4
markers re-verified unchanged (`/api/v1/remote/device`, `RelayV2IncomingMessage`).

### B5. REA receipts (pass 2)

`open_binary` re-bound the asar (sha256 `ec475e6e302595fe…2b10` — byte-identical to pass-0/1, so
every census number stands); `analyze_javascript_application` on `out/host` (dir digest
`3d4aa76f…`) retained complete-record evidence `ev_6899615b6bfa8c43acb202ab34a9e3eb5143e700d1b16a0c7f5167cd62062fc8`
(8 limitations, `authority: shipped-artifact`), exported to `/tmp/p2/rea-host-bundle.json`
(471,621 B). The harness quirk (E.A0.6) blocked the view/export-boolean calls; the bundle
summary + file export carry the receipts.

## E.C — where the phone is BETTER than the desktop (new receipts this pass)

- The attachment upload plane's **typed Retry affordance** on a mid-flight socket loss (the
  chip degrades honestly and completes on retry — the desktop's transfer plane shows no such
  per-attachment recovery face in the census).
- The pairing gauntlet's **two fail-closed consent gates** (stale-link + security-setup) before
  any credential lands — both rendered and both enforced live this pass.
- The reconnect ladder's honest typed faces even for ANDROID-OS-level blocks
  (`local-network-blocked` candidate face while backgrounded) — recovered instantly on
  foreground.
- The endpoint multi-plane (relay + DIRECT simultaneously listed with independent Connected
  states) — modulo F-P2-A's recreate clobber, which is the fix that would make the feature
  actually stick.

## E.D — fix-order recommendation (supersedes D.D)

1. **F-P0-A/F-P0-B one-line-class fix** (E.A2.4: the `:697` exemption) + the live-shape pin
   (answers from BOTH planes assert one ACK) — this is the app's core safety surface and the
   smallest diff on the board.
2. **F-P2-A** (E.A2.1: the recreate clobber) + pin — the multi-endpoint feature is dead weight
   until the selection survives a recreate.
3. **F-P1-B** expiry sweep + the mock's unknown-id error arm (unchanged from pass-1).
4. **F-P1-A** the LazyCard weighted-body fix (unchanged, re-receipted this pass with the
   healthy/broken contrast).
5. **F-P2-B** the unpair-resume wording (tiny).
6. **F-P0-C** the file-viewer typed error face (unchanged; F-P0-D's face work folds in here as
   hardening).
7. **G82 memory viewer (read-only)** — new, small, high-visibility parity win.
8. **G85 broadcastService census → consume/no-action decision**; **G60/G81** unchanged; the
   capability bits (`pluginDiscoveryUi`, `historyFirst`, `inputOrigin=mobile`) as opportunistic
   adds alongside their consumer work.

### Coverage honesty (what pass 2 did NOT drive)

The G28 zoom viewer / rewind / fork legs (light-rig recipe from pass-1 stands; this pass stayed
on the full rig), theme/font/locale toggles (vc51 pins + pass-31 receipts stand), the widget
add-to-home drag (launcher-fiddly; RIG-4 binding receipt stands), and the automation Run-history
CONTENT with `MOCK_AUTOMATION_RUNS` armed (the arm was set on gen2/gen3 but the History sheet
was not re-opened after the re-arm — one leg for the next pass).

## Pass-2 verification stamp

Pass 2 completed 2026-11-12 ~22:45; 3 relay generations + 1 DIRECT generation + 1 full
unpair→re-pair cycle; 35 screenshots in `/tmp/p2/shots/`; receipts in `/tmp/p2/logcat-gen*.log`,
`/tmp/p2/relay-*.log`, Room DB pulls in `/tmp/p2/db/`, the phone-method inventory at
`/tmp/p2/phone-methods.txt`, REA bundle at `/tmp/p2/rea-host-bundle.json`. APK byte-identical
(`ad449d7d…`); desktop digest unchanged (`ec475e6e…`). Nothing pushed. Rig left: emulator +
gen3 relay (the re-paired MockBook) on :18100 + the DIRECT mock on :18200.

# Part F — pass 3 (APPENDED 2026-11-13, the day after): the full feature walk + the gen-ui protocol reverse

> Pass 3 of the pass-0 series. Rig: zcode-a16 emulator (Android 16), debug APK at HEAD
> (md5 `530ba4ff…`, versionCode 53 / 1.3.2, byte-fresh build), TWO relay generations
> (`mock-p3-gen1` then `mock-p3-gen2`, both fully armed as pass-2's gen3) + the DIRECT
> mock on :18200. Desktop digest UNCHANGED (`ec475e6e…`, 3.18.1) — every pass-0/1/2
> census number stands; this pass goes DEEPER on gen-ui (G81), the broadcast plane
> (G85), the clientHello capability schema, and the share/session verify items pass 2
> left open. Screenshots in `/tmp/p3/shots/` (59), logs in `/tmp/p3/`, DB pulls in
> `/tmp/p3/db/`, asar extraction at `/tmp/p3-asar/` (dir digests match pass-2).

## F.A0 — rig learnings (each will bite the next pass)

1. **Stale relays from a prior session survive REBOOTS of the work day**: pass 2 left TWO
   relays running (18100 relay-mode + 18200 DIRECT) holding a dead gen3 session. `pkill -f
   MockRelayMainKt` both, relaunch ONE fresh per generation — never by port (qemu NAT rule).
2. **The hand-rolled relay classpath is a booby trap**: my first gen1 launch crashed at
   startup with `NoClassDefFoundError: kotlinx/serialization/StringFormat` because the
   script mapped serialization-CORE to serialization-JSON's hash dir (copy-paste). The app
   then timed out auth honestly (10s) against a relay that didn't exist. Fix + relaunch;
   the app's reconnect ladder converged without intervention.
3. **`adb shell input text` now drops DOTS too**: `input text "ws://10.0.2.2"` → field got
   `ws://1` (everything after the first `.` eaten). Escape hatch that WORKED: type the
   digits with `input text` and emit `.` as keyevent 56 (PERIOD). Spaces via `%s`; `&` via
   `\&` (both recorded earlier). Long payloads still interleave ACROSS FIELDS (the Add-
   endpoint dialog got `ws://1rig-direct-` in the FALLBACK field while Name got `P3D`) —
   type one field at a time, verify the dump between chunks, and expect 2-3 fixups per
   form. `rig-direct-token` needed two chunks (`rig-direct` + `-token`).
4. **The home-header buttons have the AX-vs-paint offset** (the gaps-and-polish recipe):
   painted bell at x≈632 but the Approvals BUTTON's touch target is `[673,158][805,290]`
   (center 739,224) — taps on the painted icon do nothing. Settings = `[838,191][904,257]`.
   Tap the AX bounds, always.
5. **`uiautomator` XML is ONE LINE** — `grep -c node` counts LINES (always 1), so a healthy
   dump looks dead. Count `grep -o '<node' | wc -l`. My first reads said "1 node / empty AX"
   on the fork screen; the real count was 125 — I almost recorded a FALSE a11y-collapse
   finding. Same family: python `re.finditer(r'<node[^>]*/>')` only matches SELF-CLOSING
   nodes; uiautomator nests, so clickable scans must parse differently (or use the
   one-line grep forms).
6. **The IME covers the send FAB**; `keyevent 111` (ESC) does NOT dismiss it (and wanders
   routes). `keyevent 4` (BACK) closes the IME first — then `Send message` resolves in the
   AX at `[894,2118][1026,2250]`. The keyboard's own ➤ action key does NOT trigger the
   app's send.
7. **The dialog retains values across activity recreation** (rememberSaveable): the app
   sat on the Add-direct dialog overnight; after the app was backgrounded/foregrounded the
   fields still held P3Direct / the URL / the token. Dialog "recall" is a feature — budget
   rig legs for it (clear before retype).
8. **`am start` on a live app says "intent delivered to top-most instance" and does
   nothing**; BACK-exit to launcher first (process stays alive — pid survived), then
   `am start` reproduces the F-P2-A cold-restore path deterministically.
9. **monkey exits -5 on this image** ("injection permission") — `am start -n` is the
   reliable launcher.
10. **The app survived the whole pass unplanned-over-night**: relay reconnects relay-1 →
    relay-15 across the emulator's clock rolling past midnight, zero crashes, GC healthy
    (`Background concurrent mark compact GC … 49% free`) — the reconnect ladder is now
    receipts across ~11 hours of wall clock, not minutes.

## F.A1 — verified working this pass (receipts; pass-2 gaps in bold)

1. **AUTOMATION RUN HISTORY CONTENT** (the pass-2 "not driven" leg): History chip → the
   Run-history sheet renders real rows ("1 h ago · Scheduled · 100s"), status filter chips
   (All/Succeeded/Failure/In progress) that FILTER (Failure → honest "No runs yet."),
   per-run Go-to-session + Delete-run actions, and the honest footnote "Scheduled tasks
   only run while your computer is awake." `listAutomationRuns ok in 276ms` on the wire.
2. **Go to session navigates** (`tasks.openRun :: task=synth-op-session-1`) — into the
   automation session conversation (see F.A2.2 for what's broken there).
3. **The send plane end-to-end**: composer → `Send message` →
   `sendConversationCommandV4 ok in 1516ms` + three ONLINE deltas (`gateway.frame seq=5..7`
   → `manager.deltas ops=1` each). The queued-follow-up face renders ("Keep typing to
   queue follow-up changes" + Queued chip + stop button) while the turn is live.
4. **Queue dispatch**: full-text queue rows ("queued follow-up one/two") with
   ↑/↓/Send now/Edit/Delete; Send now → `sendConversationCommandV4` 373 B `ok in 135ms`.
5. **Retry** (pass-2 gap): row action bar → Retry → 421 B command `ok in 224ms` + two new
   deltas (`seq=8..9`), the mock echoed a "retried reply" row. **Fork** (pass-2 gap):
   Fork → 50 B command → navigated to "**Fork of synth-task-1**" — a real forked child
   session rendering the FULL transcript: AGENT 9:05 PM "synthetic" + row actions, YOU
   9:08 PM "here is the screenshot" with the attachment image, the file-changes row
   ("2 files +5 −1 · view"), the "Turn · 4s" header, and fresh Fork/Retry actions on the
   forked rows. Row-level 👍/👎 actions render on both sessions.
6. **CUA row**: "Computer use · 3 actions" renders with expand chevron (MOCK_CUA arm).
7. **Files plane**: read-only badge, ws-one tree (src/docs/README.md/Cargo.toml); the
   viewer happy path renders line-numbered content with Back/Download
   (`file.readTextFile ok`). The typed-error leg is F.A2.5.
8. **Git plane**: `git.getChanges` + `git.getCommitGraph` + `git.getBranchComparison` all
   ok; Checkpoints honest empty state ("No snapshots in this session — create one before
   risky changes"), branch strip (main, tracking origin/main, "2 changed file(s) vs the
   tracking branch", per-file +42−0/+3−7), Commit…/Branches… actions.
9. **Terminal plane**: VIEW-ONLY badge, idle, Pause/Search; **Attach wires at HEAD**
   (`terminal.create ok` → `terminal.attached :: id=term-1`) — F-P0-D stays retracted
   (3rd pass).
10. **Model calls plane**: the call list renders latencies + kinds
    ("glm-5.3 · 4200ms · tool_calls", "tools: bash") and typed errors ("RateLimitError:
    429").
11. **Share plane**: four export modes (Plain text / Readable transcript / Markdown /
    Print (PDF) with descriptions); Markdown fired the REAL system share sheet
    ("Sharing text" + Copy text + Quick Share/Drive/Messages targets).
12. **Context sheet**: Status (4m 59s · Iteration 3 · 1 background), goal ("Ship it ·
    verifying · iteration 3") with iteration verdicts (pass / notSatisfied "iter 2 — 2
    criteria unmet"), Todos 1/2, Workflow runs (lint-all Running / release-notes Stopped),
    Plans 1/3 ("Tag and publish"), git strip (main · clean · Open), and the Files/Git/
    Terminal/Model calls/Share chip row.
13. **The Approvals inbox WORKS end-to-end**: badge "2" → Approvals screen → live rows
    ("Permission — Bash · echo synthetic" with Allow once/Deny; "Question · Which
    channel?" with Open task to answer). Allow once → `approvals.answer option=allowOnce`
    → `sendConversationCommandV4` 375 B `ok in 90ms` → row removed from the list. The
    inbox is the interaction plane that actually answers (the conversation card plane is
    F.A2.1).
14. **DIRECT endpoint lifecycle, fully green**: Add dialog validates BEFORE saving with a
    typed inline error ("Direct endpoint rejected the token — check it in Settings →
    Endpoints") + the sealed-token wording; with the right token → `hub.directAdded
    endpoint=d8daaa46… workspaces=1` → endpoint switch → `direct.connected via=10.0.2.2:18200
    candidatesTried=1` → `hub.directTasksApplied tasks=3 workspaces=1`; the endpoint list
    shows both (MockBook relay + P3Direct Direct server · Connected) with Remove on the
    DIRECT row.
15. **Deep-link clean leg CLOSED** (pass-2's nuance): gen2 push notification ("ZCode has a
    question for you", once per connection) → app backgrounded to launcher → shade tap →
    landed DIRECTLY in the target task conversation. The notification → conversation
    routing is verified from a true background start.
16. **The question-card refusal face is TYPED where it fires**: a "Keep waiting" tap on a
    dead machine produced the "Already handled on the desktop." notice (the vc52 Phase-7
    SAYABLE contract) — see F.A2.1 for where it doesn't fire.

## F.A2 — defects, root causes, and re-verifications found this pass (order = fix order)

1. **F-P3-A (P2, app, SHARPENS F-P0-A + F-P1-A into one statement) — the pinned
   interaction-card plane is UNANSWERABLE at HEAD, while the inbox answers the same
   interactions fine.** Machine-level receipts (Room DB pull): the seed question machine
   `q-rig-1-mock-p3-gen1` sits in **DISPLAYED forever** (resolvedAt null, ~3 h); the
   push permission `mock-approval-push-1-mock-p3-gen1` went **STALE** (rows sweep) and
   was **re-pended to DISPLAYED** by the state-plane re-list (the vc52 Phase-7 churn),
   i.e. the two reconcilers still FIGHT, now observed in both directions. UI receipts:
   the question card renders header + footer ONLY — no question text, no options (the
   seeded payload HAS them: "Which channel? Tag the release / Cut a branch" + multi-select
   "Which extras?" — verified in the DB payloadJson) — `Send answers` is grey AND a
   silent no-op in every machine state (3 taps, zero wire), `Skip — continue without my
   answer` is equally dead; the inner-card swipe that should reveal the body (the
   N-BG-1/D4 vc52-Phase-9 contract) does not. The fully-rendered permission card's
   `allowOnce` tap is also a silent no-op on both machine states, while the SAME card's
   `Keep waiting` wired once (400 B `ok in 281ms` + a re-pend delta) and refused once
   (typed ALREADY_RESOLVED toast) — the option paths return **INERT, which the ViewModel
   swallows by design** (`else -> Unit // SUBMITTED / INERT (button disabled) are silent`,
   ConversationViewModel.kt:1291) so dead buttons LOOK enabled and give NOTHING back.
   The vc52 Phase-7 "never a silent no-op" promise only covers ALREADY_RESOLVED and
   DUPLICATE_NO_OP. Fix shape: (a) render the question body / stop the height starvation,
   (b) make INERT a typed notice like its two siblings, (c) keep the options-plane
   (inbox) as the canonical answer surface for permissions.
2. **F-P3-B (P2, app, NEW) — the automation "Go to session" conversation NEVER LOADS its
   timeline: four permanent skeleton rows, zero transcript wire calls.** Receipts:
   `tasks.openRun :: task=synth-op-session-1` fired; afterwards the app polls
   `zcode-agent.conversationWorkflowRunsV4` every ~8 s (workflow plane, fine) but NEVER
   issues any session-transcript fetch/subscribe (`zcode-agent.*` history/rows channels
   absent from the log after openRun); the timeline renders 4 grey placeholder bars
   indefinitely (screenshots 06/06b, 60 s apart, identical). The same openRun from the
   inbox row would strand the user the same way. Fix shape: openRun must trigger the same
   transcript subscribe/hydrate the task-row tap path does (and render an honest empty
   state if the run's session truly has no rows — today it shows skeletons, which reads
   as "loading forever").
3. **F-P3-C (P3, app, NEW) — an inbox ACK's resolution echo is DROPPED when the
   conversation isn't subscribed**: ACKing the permission from the Approvals screen
   produced `gateway.drop :: session=synth-task-3 kind=ONLINE seq=3..3 (no subscription
   record)` — the desktop's confirmation delta for the session the app just answered in
   is thrown away because no conversation subscription exists outside the conversation
   screen. The card plane consequently kept showing "Approval needed" until a later
   reconcile. The same ACK inside the conversation arrives fine (`gateway.frame …
   manager.deltas`). Fix shape: retain last-N deltas per session for replay on next
   subscribe (or subscribe-on-ACK), and/or let the ACK resolution update the local
   machine optimistically.
4. **F-P2-A RE-REPRODUCED LIVE (2nd pass, the exact recorded chain)**: add P3Direct →
   `DIRECT_SERVER → CONNECTED`, home green → BACK-exit to launcher (process alive) →
   relaunch → 08:59:19 `direct.connected … connectionId=direct-3` → 08:59:22
   `hub.phase (DIRECT_SERVER) → CONNECTED` → 08:59:22.757 `→ UNPAIRED` → 08:59:24
   `hub.connect :: cold-start connect begin (kind=RELAY_ZAI)` → 08:59:24.652
   `hub.switched :: endpoint=5ec4eb22… kind=RELAY_ZAI`. **No user action in between.**
   The pass-2 root-cause pin stands (ZCodeApp.kt:182-196 cold-restore else-branch →
   `onPairingCompleted()` → ConnectionHub.kt:661-667 force-adopts the relay over the
   persisted `selectedEndpointId`). The multi-endpoint feature is dead weight until this
   one guard lands.
5. **F-P0-C re-verified BYTE-IDENTICAL (3rd pass)**: `file.readTextFile refused:
   fault.file.notFound` → the viewer renders full chrome (read-only badge, ws-one,
   Back, Download) and a SILENT BLANK content area — no error text, no retry
   (screenshot 38).
6. **N-BJ-1 (P4, app, NEW — solves pass-3's "red block mystery") — attachment image
   previews have NO intrinsic-size/aspect cap.** The seeded transcript's user row
   "here is the screenshot" carries the mock's 1×1 PNG (`MOCKZCODE` A3.1 arm: base64
   `…AQEB…`, pixel `00 ff 00 00` = pure red, verified by decoding); the app renders it
   as a ~300×220dp SOLID RED rectangle (screenshots 09/17/31; pixel-sampled #FF0000).
   It reads as a broken render but is the honest image — just unbounded. Desktop
   attachment previews are size-capped. Fix shape: max-width/height + aspect-fit in the
   attachment renderer. (Bonus: this block's appearances/disappearances across pass-3
   screenshots were pure scroll geometry — no rendering bug at all.)
7. **N-BJ-2 (P4, app, NEW) + F-P2-B code-pin — unpair is UNREACHABLE from the paired UI,
   and its post-state manufactures the stale wording.** No entry point exists: the
   Settings endpoint row for the relay is a dead tap (rendered clickable
   [0,426][1080,579], taps absorbed, no nav.screen), the Manage chip opens the
   Workspaces dialog, and the PAIRING route is only auto-navigated when unpaired
   (ZCodeApp.kt:96/180). The unpair buttons live ON the pairing screen
   (PairingScreens.kt:106 `resume-unpair`, :433 `connected-unpair`) — reachable only via
   a pasted link, and after `UnpairConfirmed` the ViewModel reduces to
   `State.Connected(pendingLink ?: fakeLink())` (PairingViewModel.kt:50-56) — a FAKE
   link whose resume card claims "Paired · Connect" (F-P2-B's defect wording,
   structurally guaranteed at HEAD). Fix shape: surface unpair on the relay endpoint
   row/detail, and reduce to a genuine unpaired state (no fakeLink).
8. **F-P1-B class (mock fidelity debt) re-noted**: the mock still accepts any
   interactionId and its snapshot doesn't list the once-per-connection PUSH interaction,
   so the app's rows sweep legitimately STALEs it — a real desktop would list what it
   pushed. The F-P3-A receipts above are the app-side truth; the mock arm deserves the
   snapshot-lists-what-it-pushes fix so the next pass's card-plane legs are clean.
9. **Small (observation)**: the automation run-history row tap does not expand further
   (summary + actions only) — fine as designed, recorded so a future pass doesn't chase
   it; the elided queue text in synth-task-3 ("queue…") vs full text in synth-task-1 is
   width/starvation-dependent, same family as F-P3-A's card squeeze.

## F.B — the REA 3.18.1 reverse dig, part 4: gen-ui protocol + capability schema + broadcast plane (digest unchanged `ec475e6e…`)

Method receipts: asar sha256 re-verified byte-identical; `open_binary` + `inspect_artifact`
hit the KNOWN unpacked-native integrity mismatch (`sharp-darwin-arm64.node`, packager-signed
after archive write) — record-and-continue is the standing policy; `inspect_artifact`'s
complete response exceeds the 10 MiB stdio budget (evidence retained
`ev_cb332768d0fcdb23…`); REA cannot address asar-internal paths, and `extract_artifact`
timed out/cleaned up — so the sources were extracted with `@electron/asar` to
`/tmp/p3-asar` (464 MB) and the host bundle analyzed by
`analyze_javascript_application` (retained `ev_23c63be939795256…`, host dir digest
`3d4aa76f…` — IDENTICAL to pass-2's, so the graph evidence stands).
`trace_feature` binds the binary provider, not JS evidence — literal censuses are
shell-side greps on the extraction (same reproducible method as passes 24-31).

### B1. G81 DEEPENED: the gen-ui protocol is now a complete implementable spec

- **Capability bit**: the remote clientHello schema carries `genUiContext: boolean
  optional`; the desktop's own hello declares `genUiContext: true`.
- **Gate**: `ensureGenUiContextSupport` requests `runtimeCapabilities` and throws
  `proto.genUiContextUnsupported` unless `genUiContext === true` (cached per-connection in
  a WeakMap, re-thrown on change). The send path calls it BEFORE any sendText that
  carries gen-ui state.
- **Payload**: `genUiContext` rides BOTH the desktop-agent `sendText` schema and the
  REMOTE V4 send command — an array (max **100**) of `{path, state}` items
  (`.strict()`, state `unknown`), transformed further; scope key =
  `buildGenUiScopeKey` = `[workspaceIdentity?.trim() || workspacePath, sessionId]`.
- **Renderer vocabulary** (21 strings): cancel, collapse, confirm, copyImage,
  desktopOnly, expand, expanded, failed, incomplete, invalid, loading, offline,
  original, reset, retry, send, submit, title, tweak, waiting + genUiTweaks/Styles/State.
  `genUi.desktopOnly` implies per-component desktop-only gating.
- **What the phone needs for G81** (all four): declare `genUiContext: true` in hello;
  answer `runtimeCapabilities`; accept/forward the `genUiContext` array on its send
  command; render the components (expand/collapse/tweak/submit/copyImage/retry + the
  desktopOnly refusal face). Everything else is already schema-legal.

### B2. The FULL remote clientHello capability schema — 13 bits, all dormant opt-ins

`highspeedCardOwner, pluginDiscoveryUi, sessionLifecycleWorkspace, sessionDirectorySource,
sessionWorkspaceRouting, genUiContext, historyFirst, independentPlanState,
residentRowsRange, worktreeCwd, extensionInputSource, conversationQuoteComment,
pluginExtensionSummary` (+ `hostCapabilities` in the ACK: sessionDirectorySource,
sessionWorkspaceRouting). The phone declares TWO bits on a DIFFERENT hello
(`workflowRunDeltas`, `workspaceHookReviewUi`) — pass-2's 4-bit census was the
desktop↔agent hello; THIS is the remote-client vocabulary, complete. Each bit is a
schema-legal phone opt-in awaiting consumer work.

### B3. G85 ANSWERED: the broadcastService channel vocabulary (complete)

- HOST sends: `bots:group-state` (×7, payloads `{botId, chatId?}` — the bots plane),
  `state:projects` and `state:task-groups` (empty-payload "state changed, re-read"
  signals — and `state:task-groups` rides a CLAIM protocol: acquireClaim/commitClaim/
  releaseClaim/tryClaim with a channel-router muxing it to a second source),
  **`mobile-push:run`** (onRunStarted) and **`mobile-push:event`** (onNotification) —
  the V2 notification plane's realtime arm, relayed to the device-registration webview
  via `postMessage({type: Broadcast, message:{channel, payload}})`.
- RENDERER subscribes: the dynamic `state:<key>` prefix family (`state:codereset-
  autoplayed`, `state:projects`, `state:task-groups`, …) + `bots:task`/`bots:task-stream`.
- Phone relevance: `mobile-push:*` is how the desktop would push run/notification events
  to the companion in realtime (today the phone polls); `state:*` is a generic
  state-slice sync plane the phone could mirror read-only. Consume/no-action decision
  belongs to the roadmap, but the vocabulary census pass-2 asked for is DONE.

### B4. The pass-2 verify items, closed

- **#8 conversationShareService**: NOT local share — a **share-code IMPORT plane**:
  `importShare({shareCode, clientRequestId, targetWorkspacePath?, targetWorkspaceIdentity?})`
  with `onDynamicImportProgress` phase events (login_required → loading → complete),
  consumed next to `windowControllerService`. **NEW GAP G86 (P4)**: conversation/task
  import-by-code exists on desktop only; the phone shares text/markdown/PDF locally.
- **#9 zcodeSessionService.createSession**: the desktop uses the service for WORKSPACE
  SESSION LIFECYCLE (`readWorkspacePresentation({workspacePath, workspaceIdentity})`,
  side-pane/terminal/remote session plumbing, provider+configOptions restore) — the
  phone's New-task path maps to `zcodeTaskService`, which is the correct plane. PARITY,
  no action.
- **inputOrigin** (pass-2's open question): the V4 send schema accepts
  `inputOrigin: enum(["desktop","mobile"]).optional()`; the PHONE NEVER SENDS IT (zero
  hits in the app source). One-line opportunistic add for desktop-side attribution.
  The same schema also shows dormant `genUiContext`, `highspeed`, `sharedContextRefs`
  fields on the send command.
- **Window-internal services beyond the remote-38** (beyond pass-2's feedbackService):
  `clientConfigService, cloudContentService, marketingTouchService, offPeakTaskService,
  highspeedCardService, pluginUiBridgeService, pluginUiSamplingService,
  pluginUiAppToolsService, remoteWorkspaceService`. All desktop-internal (NO-ACTION),
  with one nuance worth recording: `offPeakTaskService` is NOT in the remote-38
  registry even though the phone ships an off-peak authoring plane (G29/vc51) — the
  phone's off-peak rows ride the automation/task planes, not a remote off-peak service.

## F.C — where the phone is BETTER than the desktop (new receipts this pass)

- The DIRECT endpoint dialog's **validated-before-save** token flow (server round-trip
  before save, typed inline rejection, "sealed on-device and never leaves except to this
  server") — the desktop census has no equivalent pre-save credential validation face.
- The Approvals inbox answers interactions from OUTSIDE the conversation (the desktop
  census's remote plane answers in-place); the conversation card plane's failure makes
  this the load-bearing path — and it holds (F.A1.13).
- Run-history honest empty states per status filter ("No runs yet." under Failure) and
  the awake-only scheduling footnote surfaced in-sheet.

## F.D — fix-order recommendation (supersedes E.D)

1. **F-P3-A** — the card plane: render the question body, gate INERT behind a typed
   notice, keep the inbox canonical for permissions; pins for the re-pend churn both
   directions. (Absorbs F-P0-A/B + F-P1-A.)
2. **F-P3-B** — openRun transcript hydrate (or honest empty state).
3. **F-P3-C** — per-session delta retention (or subscribe-on-ACK) + optimistic local
   resolve.
4. **F-P2-A** — the cold-restore relay adoption guard (2nd live repro; smallest diff on
   the board, per pass-2's pin).
5. **F-P0-C** — the file-viewer typed error face (3rd pass; fold F-P0-D hardening here).
6. **N-BJ-1** — attachment preview size cap.
7. **N-BJ-2 + F-P2-B** — unpair entry point + genuine unpaired state.
8. **Parity queue**: G86 share-code import (inventory first), G82 memory viewer
   (read-only), G85 consume/no-action decision, G81 gen-ui (now a full spec — the four
   parts in B1), opportunistic capability bits (`inputOrigin: "mobile"` first — one
   line).

### Coverage honesty (what pass 3 did NOT drive)

Theme/font/locale toggles (vc51 pins + pass-31 receipts stand), the widget add-to-home
drag (RIG-4 binding receipt stands), rewind/fork on the SEED sessions other than the
fork flow's own target (the fork leg exercised fork-of-synth-task-1 end-to-end), MOCK_
AUTOMATION_RUNS authoring beyond run-now/history (Edit/Delete actions unexercised), the
lock-screen / DIRECT-mode notification arms (pass-37 recipes), and the question card's
options once the F-P3-A body fix lands (the retest belongs to the fix round).

## Pass-3 verification stamp

Pass 3 completed 2026-11-13 ~09:20; 2 relay generations + 1 DIRECT generation + 1 add/
switch/recreate cycle; 59 screenshots in `/tmp/p3/shots/`; receipts in `/tmp/p3/relay-*.log`,
`/tmp/p3/*.xml`, Room DB pulls in `/tmp/p3/db/` (interaction machines queried for the
F-P3-A machine-level proof); asar extraction at `/tmp/p3-asar/` with the host-bundle REA
evidence `ev_23c63be939795256…` retained in-session. APK at HEAD (md5 `530ba4ff…`,
versionCode 53 / 1.3.2); desktop digest unchanged (`ec475e6e…`, host dir `3d4aa76f…`).
Nothing pushed. Rig left: emulator + gen2 relay on :18100 + the DIRECT mock on :18200,
app paired to both endpoints.

# Part G — pass 4 (APPENDED 2026-11-13, later the same day): the never-driven arms + the method-level remote census

> Pass 4 of the pass-0 series. Rig: zcode-a16 emulator (Android 16), the pass-3 APK
> re-installed byte-identical (md5 `530ba4ff…`, versionCode 53 / 1.3.2 — HEAD's only
> commits since the build are docs-only), ONE relay generation (`mock` gen2, the max-armed
> stack PLUS six never-driven arms) + the DIRECT mock on :18200 (same arms, unused by the
> walk). The cold boot reconnected to the stored relay endpoint WITHOUT re-pairing —
> discovery: the mock's `passHash` is `Base64(ByteArray(32){it.toByte()})` = the CONSTANT
> bytes 0..31, so every relay generation honors every stored endpoint (a rig property, not
> an app one). Desktop digest UNCHANGED (`ec475e6e…`, 3.18.1, byte-verified at open) — all
> pass-0..3 census numbers stand; this pass's REA work goes METHOD-level (per-service
> inventories, the full channel Set, VoiceInput, themes, bundled plugins).
> Screenshots in `/tmp/p4/shots/` (28), DB pulls in `/tmp/p4/db/`, extraction `/tmp/p3-asar/`
> (host index.js sha256 prefix `e31718b8fa8a7d3f`), scripts `/tmp/p4/{rig.py,svc_table.py}`.

## G.A0 — rig learnings (each will bite the next pass)

1. **The timed mock arms fire from RELAY START, not from client connect**: `MOCK_G52_KEYS_MS`
   and `MOCK_CUA_PERMISSION_MS` arm `Thread.sleep(delayMs)` at mock construction — a +25s/+90s
   arm with a slow pairing/connect burns the push into the void (gen1 lost both). Anchor arms
   ≥ 90s when the app still has to cold-boot. (`MOCK_INTERACTION_PUSH_MS` IS
   subscribe-anchored — but see G.A2.2: the push never reaches the wire anyway.)
2. **The mock's pairing proof is a rig CONSTANT**: `ByteArray(32){it.toByte()}` → the same
   link hash every generation. Stored endpoints survive relay restarts indefinitely; re-pair
   legs must WIPE the endpoint (or expect none of the pass-2/3 typing pain).
3. **`lsof -tiTCP:<port> -sTCP:LISTEN` is the SAFE kill discriminator** for the two-mock rig
   (relay + DIRECT share one command line and one main class): the LISTEN state matches only
   the servers, never qemu's ESTABLISHED NAT sockets. Plain `-tiTCP:` (both states) is the
   emulator-killing footgun the vc47 memory warns about.
4. **The IME covers sheet Save/Cancel buttons** (automation Edit sheet): after typing into a
   dialog field, `keyevent 4` (close IME) BEFORE dumping tap targets — two Save taps went
   into the field (one COMMITTED A TRAILING PERIOD into the title via the IME) before the
   IME was dismissed and the real Save resolved.
5. **`input text` auto-period**: the keyboard committed "P4edited" as "P4edited." — a trailing
   dot appears in EditText dumps after `input text`. Expect +1 junk char; strip by MOVE_END+DEL
   before assertions on exact text.
6. **BACK chains pop sub-screens AND exit the app**: from a settings sub-screen, three BACKs
   reached Home and the fourth exited to the launcher (process alive). The dock label
   "ZCode Companion" then false-matches `rig.py find` — anchor on screen-independent labels.
7. **The draft-protection dialog fires EVERY leave** while a draft exists ("/acme" typed, left,
   dialog; re-enter, draft restored "/cme" — the drafts plane works end-to-end), and it BLOCKS
   the first BACK from the conversation. Budget the extra Leave tap in every conversation exit.

## G.A1 — verified working this pass (receipts; never-driven-in-series items in bold)

1. **Auto-resolution countdown, LIVE on-device (the G77 fuse face)**: "Agent needs your input"
   card renders "Auto-continues in 513s" ticking down to 470s across screenshots — the
   600s `visibleCountdown` fuse decoded from the desktop's `autoResolution` union and
   rendered per-second. The `autoResolutionDeadlineMs` persists in Room
   (`q-rig-1-<gen>` row). Pass-0's static-census question is answered: the phone not only
   decodes the fuse, it RENDERS it live.
2. **G52's two state keys both land (the vc52 Phase-11 fix verified live)**: `modelTransition`
   → the typed notice "The desktop switched the model from glm-5.1 to glm-4.7-air (registry
   fallback) — the session continues on the new model." (dismissable banner, copy built from
   `from`/`to`/`origin`, ConversationViewModel.kt:777); `workspaceHookAdmission` → decoded
   into the hook-review plane (SessionState.kt:46-109, key-presence tracked
   `workspaceHookAdmissionKeyPresent`).
3. **CUA permission observation, typed card**: the pushed `a11y=denied screen=granted`
   observation renders the dark-red banner "Computer Use needs macOS permissions —
   (Accessibility: Denied) — grant them on the Mac" + "Got it" (the G64 typed faces,
   first live drive in the series; the banner persists across screens until dismissed).
4. **Load-earlier window paging**: the `MOCK_ROWS_TOTAL`/window arm produced the strip
   "Load earlier messages · showing latest 320 of 338" (the rowsWindowSchema tail-window
   contract), with `manager.snapshot rows=338` on the wire. (The F-37-D receipt re-verified
   at HEAD; note the total rendered 338, not the armed 2000 — the mock's window base
   computation vs the phone's store floor is worth one look in the next fix round.)
5. **Automation Edit + Delete end-to-end (pass-3's leftover #1, CLOSED)**: Edit opens the
   full authoring sheet (Title, Prompt, 8 schedule chips, HH:MM + human preview "Daily at
   02:00", Ends Never/On date, Max runs, Model: workspace default, Permission mode: task);
   Save → `updateAutomation (id=139)` + `listAutomations (id=140)`; the home row updated
   live; Delete → confirm dialog → `deleteAutomation (id=144)` → row gone.
6. **Usage screen with REAL fixture data (never driven in the series)**: quota panels (5
   hours 64% / Quota reset 05:20 / Weekly 49% Oct 21 / Tool calls 88% Nov 12 / ZCode MCP
   78% Oct 15), the G20 coding-plan charts, stat cards (184.6K total / 51.2K peak / 1h30m
   longest), 7/30/All-time tabs, daily-activity table, Share-by-model section — the
   `MOCK_USAGE` fixture-driven face renders completely.
7. **Memory viewer end-to-end (G75, never driven in the series)**: Settings → Memory →
   the workspace tree (synthetic-ws → MEMORY.md + release-prefs.md) → content viewer
   renders the real bytes ("# Workspace memory - prefers conventional commits…").
8. **Commands viewer**: `/deploy` (user) + `/acme:review` (plugin, argument hint [path])
   with the authoring-lives-on-desktop footnote; the composer slash panel filters to it
   ("Commands · /acme:review — Acme code review pass [path]", screenshot g17).
9. **MCP/Plugins/Skills/Subagents viewers re-verified at HEAD**: typed failure vocabulary
   (connection_failed + mapped copy + raw ECONNREFUSED), plugin diagnostics row
   (plugin.missingDependency), subagent state badges, all with the read-only footnotes.
10. **Theme + font switches, immediate, no restart**: Theme dialog (Light/Dark/Follow/zai
    light/zai dark) → "zai light" applied the whole light palette instantly ("Applies
    immediately, no restart needed" verified true); Text size 1.15× scaled the entire
    settings list; both restored without recreate artifacts (G37's SP-mirror holding).
11. **Context sheet Task tokens row**: "Task tokens · 184320" — the G60 per-task token
    usage plane rendering from the state block (first series receipt).
12. **Hook review summary card**: "2 pending · 3 hooks" with the trust warning copy —
    but see G.A2.1: the item list does NOT render at HEAD.
13. **Draft plane**: unsent "/acme" survived a Leave (typed dialog) and was restored on
    re-entry — saved + recalled.
14. **Widget provider registered** (`PendingApprovalsWidgetReceiver` in the appwidget
    provider list, not zombie); no instance bound on the launcher.

## G.A2 — defects, refutations, and root causes found this pass (order = fix order)

1. **F-P3-A SHARPENED (P2, app) — the pinned-stack starvation hits the HOOK card too:
   the workspace-hook-review card renders header + summary + Trust button but NO item
   rows, making the trust flow unreachable from the conversation.** Receipts: three
   screenshots across 40 minutes (g05/g17/g24) all show "2 pending · 3 hooks" +
   "Trust 0 selected hook(s)" (grey) with zero `hook_review_item_*` rows between them;
   "Warm build"/"Lint gate"/"Watcher" (the seeded items) are absent from every AX dump;
   the body tap does not expand. The decode is healthy (WorkspaceHookReview.kt:56-71 is
   tolerant; `reviewItemId` present in the payload; `actionable` derives true for
   pending_trust) — the item column renders inside `weight(1f, fill=false)` +
   `verticalScroll` in a caller-capped card (HookTrustReviewCard.kt:110-131, the vc52
   Phase-9 "pinned budget" cap) and collapses to zero exactly like F-P3-A's question
   body. F-P3-A is therefore not a question-card bug: it is the pinned pending-stack
   budget starving EVERY interaction card's body (question body, hook items; the
   approval card's options survived only by being shorter). Fix shape unchanged but now
   card-agnostic: raise/scroll the pinned stack budget or render card bodies outside it.
2. **F-P4-A (P2, rig, NEW with code pin) — the fresh-interaction push arm CANNOT deliver
   at HEAD: the patch it sends structurally excludes the pushed interaction, and the
   snapshot that eventually delivers it lands STALE.** Chain (machine-level, all four
   steps observed): (a) the arm consumed at first conversation subscribe (10:25:52),
   +45s fire; (b) at fire, `pushPendingPermission` builds its patch from
   `snapshotPendingInteractions()` BEFORE `interactionPushDelivered` flips — and that
   filter EXCLUDES the push id while undelivered (MockZCode.kt:1332-1340), so the wire
   patch cannot carry it (the RIG-P1 comment "the pushed PATCH always carries it" is
   false at this code state); (c) the same call REPLACED the single `pendingPermission`
   slot, dropping the seeded approval from the patch too; (d) the pushed interaction
   reached the app only via the NEXT snapshot (resubscribe at 10:34:00, ~8 min late) and
   landed `STALE` in Room within the same ingest (DB: `mock-approval-push-1-gmwety5nl |
   PERMISSION | STALE`). No shade notification ever posted. The self-test passes because
   it asserts the state block AFTER delivery (delivered=true → full list). The pass-2
   "push healthy" refutation cannot re-reproduce at HEAD. Fix shape: build the patch
   from `pendingInteractionList()` (unfiltered) inside `pushPendingPermission`, and pin
   the patch CONTENT (not the post-hoc state block). This also un-blocks the
   lock-screen/DIRECT notification arms every later pass wanted.
3. **N-BK-1 (P2, rig, NEW with code pin) — the RIG-P2 git fixtures use the WRONG field
   vocabulary; the Changes tab renders blank paths, zeroed counts, and duplicates every
   row into both sections.** Rendered: "Unstaged (2)" + "Staged (2)" (fixture has 1+1),
   every row shows NO file name and "+0 −0". Root cause (app decode is contract-true):
   the fixture serves `path/status/staged/additions/deletions`; the app's
   GitChangeEntry decode (Phase9Tools.kt:283-290) reads `path`,
   `workspaceRelativePath`/`repoRelativePath` (→ "" → blank row label), `section`
   (absent → falls back to the REQUESTED sourceId → every row lands in both tabs),
   `added`/`removed` (absent → +0 −0). Second instance in the same arm:
   getCommitGraph's fixture serves `oid` but the app requires `hash`
   (`hash = c.strOrNull("hash") ?: return@mapNotNull null` — every commit dropped,
   History renders silent-empty). Also mock-side: the `getChanges` handler ignores the
   `sourceId` arg entirely. Fix shape: rewrite both fixtures in the contract vocabulary
   (`kind/section/added/removed/workspaceRelativePath`, `hash/parents`), filter by
   sourceId in the handler, add a "No commits yet" empty state app-side for the
   silent-empty History face.
4. **N-BJ-2 REFUTED (pass-3's "unpair is UNREACHABLE from the paired UI")**: the Settings
   bottom carries a live "Unpair this device" row (with the honest footnote "After
   unpairing, also press Stop in the desktop's Mobile Remote…") — pass-3 simply never
   scrolled to the settings bottom. The endpoint-row dead-tap and the fakeLink
   post-unpair state (PairingViewModel.kt:50-56) remain as recorded; only the
   "no entry point" claim is withdrawn. The row's code anchor: SettingsScreen.kt
   `showUnpairConfirm`.
5. **N-BK-2 (P4, app, small, NEW) — the automation Edit sheet's schedule chips clip at
   phone width**: "Weekdays" renders "Weekda", "Cron" renders "Cr" (screenshot g10) —
   the chip row overflows its dialog width and truncates trailing chips. Same family
   as vc51's G29 queued-row squeeze; fix = wrap or weight the chip row.
6. **Load-earlier total mismatch (observation)**: armed `MOCK_ROWS_TOTAL=2000`, strip
   rendered "of 338" — the mock's window-base vs the phone's store-floor arithmetic
   disagrees; harmless on this rig but pin it before trusting any paging assertions.

## G.B — the REA 3.18.1 reverse dig, part 5: the method-level census (digest unchanged `ec475e6e…`)

Method receipts: asar sha256 re-verified byte-identical at open (REA session bound);
host index.js sha256 prefix `e31718b8fa8a7d3f`; all greps shell-side on `/tmp/p3-asar`
(same reproducible method as passes 24-31 + this series). Scripts kept at `/tmp/p4/`.

### B1. The remote channel surface is 45, not 38 — and 7 channels were never census'd

The desktop's channel Set (`dXe`, host index.js) enumerates every `be.*` channel:
File, MediaPreview, System, Terminal, Git, GitCheckpoint, Setting, OnboardingRecord,
Credential, **CuaPermission, CuaPipSession, WindowController**, Broadcast, ZCodeTask,
**ZCodeAgent, ZCodeSession, Project, TaskGroups, RemoteProjects, WindowProject,
WindowRemoteProjects, WindowProjectChanges**, ConversationShare, FileWatcher, OAuth,
ProviderSettings, ModelSelection, SourceServerWorkspace, UsageStats,
CodingPlanSubscription, ClientScenes, Skills, McpSync, Plugins, PluginManagement,
Subagents, Commands, Hooks, Memory, OutputStyle, SettingsSync, Bots, **Feedback,
PromptAttachmentTransfer, OffPeakTask**. Pass-2's "remote-38" was the
connectionServices REGISTRATION chain — this Set is the full channel surface, and its
bold members were never gap-assessed: CuaPermission/CuaPipSession (the phone has the
typed permission card — G64 verified this pass — but no pip-session consumer),
WindowController/Window* (window-state sync), Project/TaskGroups/RemoteProjects (the
vc33-era bridge planes), **Feedback** (the phone has a feedback FORM, desktop-side
service never matched), **OffPeakTask**.

### B2. F.B.4 correction: the off-peak-task channel EXISTS and the phone already calls it

Pass-3's "the phone's off-peak rows ride the automation/task planes, not a remote
off-peak service" is WRONG at the transport level: the phone calls
`channelCall("off-peak-task", "list", …)` (V4Plane.kt:1451-1452, the GAP-6 read), and
the desktop serves the channel lazily via `resolveOffPeakTaskService` with a typed
-32601 fallback ("Off-peak task service is unavailable on …") + a
`toProtocolOffPeakTaskSnapshot` protocol shape. The G29 authoring verbs beyond `list`
remain unaccounted — but the channel-level statement is now: read parity exists,
authoring parity unknown.

### B3. Per-service method inventories (the first two, exactly)

- **memoryService** (G82's read-only-mirror spec, now exact): `{loadMemory,
  saveMemory, clearMemory, getUserMemoryDirectory, listProjectMemories,
  readProjectMemoryFile}` — 6 methods; the read-only phone mirror needs exactly
  `listProjectMemories` + `readProjectMemoryFile` (path-validated, workspaceId-scoped,
  MEMORY.md-index shaped). Phone surfaces today: ZERO.
- **fileService** (18 methods): readdir, stat, checkFilesExist, resolvePath,
  createDefaultWorkspace, ensureConversationWorkspace, createScratchWorkspace,
  readTextFile, readFileRange, readMediaPreview, readBinaryPreview,
  searchWorkspaceFiles, listWorkspaceFilesLength, listWorkspaceFilesRange,
  readWorkspaceFileSearchIgnore, readDesignSourceFile, writeDesignSourceFile,
  writeWorkspaceFileSearchIgnore. Phone consumes 6 (all reads). Notables: the channel
  carries WRITE verbs (design-source + search-ignore) the phone deliberately omits
  (read-only posture verified at the protocol level), and workspace-creation verbs
  (createScratchWorkspace) that a future phone "new workspace" face could adopt.
- The remaining 36 inventories are extractable the same way (every factory ends
  `return i(x,"name"),{…methodTable…}`); 60 `create*Service` factory markers exist in
  host/index.js alone. Script: `/tmp/p4/svc_table.py`.

### B4. NEW census surface: the desktop ships a real-time VOICE-INPUT (ASR) plane

`createVoiceInputService` (host index.js): `connect, requestTicket, sendChunk,
readServerMessage, emit, summary, fail, mapAsrErrorCode, release` — a streaming
speech-to-text service with ticketed sessions, chunk upload, error-code mapping and a
summary arm. The phone has NO counterpart (composer is text-only; voice input relies
on the OS IME's dictation, which pass-34 found DEAD on this Android 16 image —
F-34 class). **NEW GAP G87 (P4)**: desktop-only voice-input plane; on mobile the OS
IME usually covers dictation, but the dead-dictation field report makes the in-app
plane worth an inventory row (not necessarily a build).

### B5. Theme catalog: 22 desktop themes vs the phone's 5

Desktop renderer theme names: ayu/everforest/github/min/neo/one/redux/slack/snazzy/
solarized/vitesse (×light+dark), night-owl-light, zai-dark, zai-light (22 total).
The phone's dialog ships exactly System/Light/Dark/zai light/zai dark — the SHIPPED
pair is true parity; the other ~17 editor-style themes are desktop-only. **NEW GAP
G88 (P4, cosmetic)**: theme catalog size; likely intentional (mobile = zai brand pair),
recorded so a future "more themes" ask starts from this inventory.

### B6. The bundled plugin set grew: 18 packages (pass-31 recorded 15)

`/Applications/ZCode.app/Contents/Resources/glm/packages/`: android-emulator-plugin,
browser-use-plugin, bundled-skills, design-plugin, documents-plugin,
image-search-plugin, ios-simulator-plugin, node-repl-host, pdf-plugin,
plugin-creator-plugin, presentations-plugin, restore-legacy-sessions-plugin,
skill-creator-plugin, spreadsheets-plugin, visualize-plugin, zcode-cua-plugin,
zcode-guide-plugin, zcode-site-plugin. Delta vs pass-31's 15: +android-emulator-plugin
(the desktop can now DRIVE Android emulators — relevant to this rig's future),
+restore-legacy-sessions-plugin, +one more. All desktop-side; the phone's plugin plane
stays read-only `listPlugins` (zcodeAgent.ts:608 word) — parity by design.

### B7. V2 push channels re-confirmed; G60 unchanged

`mobile-push:run` + `mobile-push:event` remain the V2 realtime push vocabulary
(broadcast → device-registration webview via postMessage). The G60 claim/rewards
strings live outside the renderer index chunk — pass-0/31's census (claimManualPlan
POST billing/claim + rewards menu badge, phone zero claim surfaces) STANDS (digest
unchanged); not re-derived.

### B8. Registry-chain footnote: clientConfigService rides the same chain

The remote registration chain registers 39 entries — the 38 services PLUS
`clientConfigService` (`Pd`) — resolving pass-2's 38-vs-39 ambiguity: the count was
service rows; clientConfigService was always in the chain.

## G.C — where the phone is BETTER than the desktop (new receipts this pass)

- The theme/font switch applies immediately with zero recreate artifacts (G37's
  SharedPreferences-mirror locale pattern holds; the desktop census has no equivalent
  in-app immediate-apply receipt either way).
- The draft-protection dialog + restore ("You have an unsent draft. It stays saved on
  this device") — the desktop census never matched a leave-guard for composer drafts.
- The automation authoring sheet validates + previews the schedule in natural language
  ("Daily at 02:00") live while editing — the desktop's automation faces were never
  census'd this deep; recorded as a phone-side strength pending a desktop look.

## G.D — fix-order recommendation (supersedes F.D)

1. **F-P3-A (now card-agnostic)** — the pinned pending-stack budget starves every
   interaction card body (question body, hook items). Raise or externalize the budget;
   pins must cover the hook card's item list + Trust-enablement, not just the question.
2. **F-P4-A** — the rig's fresh-push arm: patch from the unfiltered list + a
   patch-content pin; re-run the lock-screen/notification arms after it lands (they
   depend on this delivery).
3. **N-BK-1** — the RIG-P2 git fixtures: contract vocabulary (kind/section/added/
   removed/workspaceRelativePath; hash/parents), sourceId filtering, and an app-side
   "No commits yet" empty state for silent-empty History.
4. **F-P3-B / F-P3-C / F-P2-A / F-P0-C** — unchanged from F.D (openRun hydrate,
   per-session delta retention, the cold-restore relay adoption guard, the file-viewer
   typed error face).
5. **N-BK-2** — automation sheet chip clipping (small).
6. **Parity queue updates**: G82's spec is now exact (B3); G87 voice-input and G88
   themes are inventory rows (decide consume/no-action); the OffPeakTask authoring
   verbs need a desktop-side method inventory before the phone's G29 verbs can claim
   parity (B2).

### Coverage honesty (what pass 4 did NOT drive)

The gen3 arms (expired-fuse face, `MOCK_GIT_REFUSALS` typed codes, the short-fuse
countdown DROP), the lock-screen/DIRECT notification arms (blocked by G.A2.2's
undelivered push), the locale switch (G37 pins + pass-31 receipts stand; the
recreate-loop hazard did not merit the time), the widget add-to-home drag (5th pass
running — provider registration + RIG-4 binding pin stand; the emulator picker flow
is fiddly and `cmd appwidget` has no bind path), the slash-panel row-ACCEPTANCE step
(the panel render + filter receipt stands; acceptance is vc52-pin-covered), and the
Providers/Bots settings rows (same viewer family verified four times this series).
The "of 338 vs armed 2000" window arithmetic (G.A2.6) is recorded, not diagnosed.

## Pass-4 verification stamp

Pass 4 completed 2026-11-13 ~11:40; ONE relay generation (max-armed + 6 new arms) +
the DIRECT mock; 28 screenshots in `/tmp/p4/shots/`; DB pulls in `/tmp/p4/db/`
(interaction machines for the F-P4-A chain + the STALE landing); relay logs
`/tmp/p4/relay-gen{1,2}.log`, `direct-gen1.log`; REA session bound at
`ec475e6e302595fe…`, host index.js sha256 prefix `e31718b8fa8a7d3f`. APK unchanged
(md5 `530ba4ff…`, versionCode 53 / 1.3.2, byte-current for HEAD — docs-only commits
since the build). Rig left: emulator + gen2 relay on :18100 + DIRECT mock on :18200;
app paired to both, theme/font restored to defaults. Nothing pushed.

---

# Part H — pass 5 (APPENDED 2026-11-13, later the same day): the rig-fixed arms + the method-level parity matrix

> Pass 5 of the pass-0 series — Ernest's third "verify everything, dig deeper" ask. Two halves:
> (1) the live rig, this time with the TWO pass-4 rig-side blockers FIXED first (F-P4-A the
> fresh-push patch that structurally excluded its own interaction; N-BK-1 the git fixtures in
> the wrong vocabulary) so the never-drivable arms could actually run — committed as `2b12fc46`
> (rig-only; zero app code touched; pins green with a compile-block RED demonstration), then
> THREE relay generations driving the unblocked legs; (2) the REA dig part 6 — the desktop
> digest re-verified byte-identical (`ec475e6e…`, 3.18.1.10376, host index.js sha256 prefix
> `e31718b8…`, extraction `/tmp/p3-asar`, host dir digest `3d4aa76f…` unchanged), so every
> pass-0..5 census number stands and the deepening came from NEW questions: the per-service
> METHOD tables beyond pass-4's two, the OffPeakTask/automation authoring parity (G.D.6's
> queued item), the hooks authoring + review-reason vocabulary, and the phone-side parity
> matrix at HEAD.
>
> **Environment of record.** HEAD `2b12fc46` (main, clean — docs-only before the pass-5 stamp;
> the one code commit this pass is the rig-side mock fix), debug APK byte-identical to the
> whole series (sha256 prefix `ad449d7d…`, md5 `530ba4ff…`, versionCode 53 / 1.3.2 — rebuilt
> up-to-date in 22 s). Emulator `zcode-a16` warm from pass 4. Relay generations: gen1
> `mock-p5-gen1-e5f6a7` (full 23-arm + AUTOMATION_RUNS/USAGE/MEMORY/TOOLS/TURN_RUNNING/
> ENFORCE_RUN_DELTAS + AUTO_RESOLUTION=countdown 150 s + PUSH 15 s + G52/CUA at 100/105 s) →
> gen2 `mock-p5-gen2-f8e9d0` (push 12 s, NO fuse — the clean shade + contract leg) → gen3
> `mock-p5-gen3-a1c2e3` (GIT_REFUSALS + AUTO_RESOLUTION=expired + push 12 s). Two force-stop
> cycles (the locale legs). Desktop under audit unchanged.
>
> **Verification stamp:** 2026-11-13, verified ~11:40–12:56, GLM-5.3-Flash in ZCode (ZCode
> Companion pass-5 rig), everything local, nothing pushed.

## H.A0 — rig learnings (each will bite the next pass)

1. **THE FUSE MUST OUTLAST THE PUSH WINDOW — the gen1 lesson**: the app sat on Settings until
   +2 m15 s after relay start, so the 150 s countdown fuse FIRED ~2 s after the +15 s-from-
   subscribe push landed — the patch carried a fuse-dead entry and ZERO push rows were created
   (DB-proven). The expiry leg is real and valuable, but schedule it deliberately: fuse > push
   + observe window, or split generations (this pass's gen1/gen2 split is the template).
2. **Empty-string arm passthrough crashes the relay at boot**: `MOCK_AUTO_RESOLUTION="${VAR:-}"`
   hands the mock `MOCK_AUTO_RESOLUTION=""` (set-but-empty), and the enum validator rejects it
   (`must be one of grace|countdown|snoozed|expired`). Pass arms by ENV INHERITANCE (don't
   emit unset arms), never by empty-string defaults.
3. The composer's **Add-attachment row hides behind the IME AND the expanded queue strip** —
   collapse the IME (BACK) and scroll the timeline up; re-dump for bounds every time.
4. The **slash palette's row geometry moves with the IME**: tapping a row from a stale
   screenshot accepted `/logout` while aiming for `/compact` (the acceptance receipt itself
   is the accidental-by-product that PROVES the row→autocomplete contract).
5. The **Language dialog fully localizes** (跟随系统/取消) — reverting needs the localized
   label; searching for "System default" in the zh UI fails silently.
6. **Widget add-to-home is DEFINITIVELY not adb-drivable on this image** (6th attempt):
   `cmd appwidget` has no shell implementation (API 36) and an `input swipe` long-press drag
   from the picker returns to the launcher with NO instance bound (`dumpsys appwidget` under
   the launcher host: `Widgets:` EMPTY). The RIG-4 binding pin stays the binding receipt;
   stop re-attempting in future passes.
7. The system photo picker serves a freshly `adb push`ed file from **Recent on first open** —
   no navigation needed (faster than pass-2's picker walk).

## H.A1 — verified working this pass (receipts; never-driven-in-series in bold)

| Surface | Pass-5 receipt |
| --- | --- |
| F-P4-A rig fix, live on the wire | gen2: subscribe at 11:45:42 → **`manager.deltas :: session=synth-task-1 seq=2..2 ops=1` at 11:45:54** — the pushed patch CARRIED the interaction (the pass-4 structural exclusion is gone); gen1 showed the same deltas shape |
| **Fresh-push shade leg (pass-4-blocked, now UNBLOCKED)** | app backgrounded → shade posted: "ZCode needs your approval" ×2 (grouped, channel=permissions, AUTO_CANCEL, group summary + child id) + "ZCode has a question for you"; tapping the approval notification deep-linked into the target conversation |
| **The expired-fuse FACE (never driven in series)** | gen1 machine-level proof: `q-rig-1-mock-p5-gen1` RESOLVED at deadline+2.8 s (1794570178698 vs deadline …175909) via the state-plane sweep, card left the stack; gen3's `expired` arm rendered the honest face on BOTH cards: **"Approval needed — No answer; continued automatically — Run a synthetic command? `{"command":"echo synthetic"}`"** and **"Agent needs your input — No answer; continued automatically"** — the G77 fuse family is honest end-to-end (countdown ticks (pass-4) → fires → the phone closes the machine with a typed face) |
| **The Git contract-true leg (N-BK-1 rig fix verified live)** | gen2: **Unstaged (2) / Staged (1)** — no duplication; real paths with real counts (src/version.ts +3 −1, scratch/probe.txt +2 −0, src/index.ts +12 −4); **History renders the commit graph** ("abc1234 Ernest 11/13 10:45" + both subjects) — the pass-4 silent-empty History receipt is closed on the rig |
| **Terminal Search + Copy visible output + Detach (never driven)** | Attach (4th series verification: `terminal.create ok` + `terminal.attached :: id=term-1` — F-P0-D stays retracted) → "Search output" field accepts text ("zsh" landed; the mock's stream is empty so the filter is a no-op — mock fidelity note) → "Copy visible output" → **"Copied" toast** → Detach → **`terminal.dispose begin/ok`** → "Not attached." idle |
| **Slash-palette row ACCEPTANCE (pass-4's leftover, CLOSED)** | "/" → palette (screenshot-visible, dump-invisible) → row tap → **composer autocompleted "/logout"** → Send → **`sendConversationCommandV4 (id=23)`** — the full palette→row→send chain |
| **Image attachment upload** | picker → p5blue.png chip → **`attachmentBeginV4 (id=9) → attachmentChunkV4 (id=10, 96 ms) → attachmentCommitV4 (id=11, 36 ms)`** on a real 48×48 PNG; sent with a message (`sendConversationCommandV4 id=12`) |
| **Locale switch live (G37, FIRST live drive in the series)** | Settings → Language → 中文 → UI stays English until relaunch (the no-recreate SP-mirror design holds) → force-stop + relaunch renders the WHOLE UI in Chinese ("+ 新建自动化", "19 分钟前", "3 次"; settings + dialogs fully localized: 跟随系统/取消) → reverted the same way; both directions clean, reconnected after both restarts |
| A11y sweep at HEAD (subtree-aware — the pass-3 merged-nodes recipe) | conversation + settings: **0 subtree-unlabeled clickables** (the N-BC-1 fix holds); home: **2 unlabeled icon-only buttons** (workflow-run Run/Manage chips, `resource-id=workflow_run_*`) — NEW small (N-BL-1) |
| CUA permission observation | the typed banner ("Computer Use needs macOS permissions … Accessibility: Denied") rendered again at HEAD |
| Multi-endpoint persistence | MockBook (relay · Connected) + P3Direct (Direct server · v3.14.3) rows BOTH persisted across launcher-exit, force-stop, and relaunch cycles |
| Robustness | **0 FATAL / 0 ANR** across the whole pass (3 relay generations + 2 force-stops + picker/drag/locale legs); the reconnect ladder converged after every relay restart without user action |

## H.A2 — defects, ledger changes, and root causes (order = fix order)

1. **F-P0-B ISOLATED — the ledger's most important update this pass.** With the rig's
   push-delivery bug fixed (`2b12fc46`), the pushed permission now DELIVERS (row created by
   the patch, receipted above) and is then **STALEd by the rows reconcile sweep exactly as
   pass-2 pinned** (DB: `mock-approval-push-1-mock-p5-gen2-f8e9d0 | PERMISSION | STALE`,
   created 11:45:54.065 by the patch; the seeded approval + question stayed DISPLAYED). The
   mock-side confound is GONE: the one-line `known.permission != null` exemption at
   `InteractionCoordinator.kt:697` is now **provably the only remaining blocker** for the
   card plane. F-P4-A itself CLOSES as rig-fixed this pass (the self-test's patch-content pin
   holds; RED demonstrated compile-block against the pre-fix code per the stash recipe).
2. **F-P5-A (P3, app, NEW) — refused git reads render SILENT-EMPTY sections on the live Git
   screen.** gen3 armed `MOCK_GIT_REFUSALS=getChanges=<custom code>,getCommitGraph` (default
   notAvailable): the Changes and History section HEADERS render with ZERO rows, ZERO error
   text, ZERO retry (screenshots 22/23, also after explicitly tapping both tabs). The pinned
   refusal faces (GitRefusalSurfaceUiTest: generate-commit inline reason + history names the
   reason) do NOT manifest on this path — the live screen loads its sections at open and a
   refusal yields an empty section, not the pinned error row. Same family as F-P0-C (the
   file-viewer silent blank); fix = the typed-refusal face on the section-empty path + a
   pin hosting the LIVE load shape (not a pre-loaded sheet).
3. **F-P3-A re-manifested (4th pass) with the sharpest visual yet**: the question card's
   "Send answers" crushed to a three-line vertical ("Send answe rs") inside the zero-height
   body, and the hook card renders header + body text + "2 pending · 3 hooks" with NO item
   rows. The fix shape is unchanged (card-agnostic pinned-stack budget).
4. **N-BL-1 (P4, app, NEW, a11y)** — the Workflows hub card's per-run Run/Manage icon-only
   buttons are subtree-unlabeled (resource-id only, no text/content-desc): TalkBack silence.
   The ONLY unlabeled clickables found at HEAD (conversation and settings are clean).
5. Small observation: the FIRST attachment pick did not auto-start the upload (the chip
   rendered with Retry; Retry completed the begin/chunk/commit) — the 2nd instance of this
   pattern (pass-2's was a socket-drop retry). The pick→begin handoff while a turn is
   RUNNING deserves one glance in the fix round.
6. Observation: the queue strip re-EXPANDED (both rows + action bars) after the generation
   switch — retained UI state across reconnects; harmless on this rig, recorded.

## H.B — the REA dig, part 6: the METHOD-LEVEL PARITY MATRIX (digest unchanged `ec475e6e…`)

Method receipts: asar sha256 re-verified byte-identical at pass start; all greps shell-side on
`/tmp/p3-asar` (the pass-24/31/4 method); NEW scripts `/tmp/p5/methods3.py` + `methods4.py`
(brace-matching table extractor anchored on `i(x,"createXService")` — the tables sit BEFORE
the marker as `return i(last),{…}}i(x,"createXService")`); phone inventory extracted at HEAD
into `/tmp/p5/pm.txt` (`channelCall` sites + the template-built names).

### B1. Per-service method inventories — exact (new beyond pass-4's two)

| Service (exact table) | Methods | Phone today | Gap row |
| --- | --- | --- | --- |
| memoryService (6) | loadMemory, saveMemory, clearMemory, getUserMemoryDirectory, listProjectMemories, readProjectMemoryFile | 0 | **G82 exact** (read-only mirror = the 2 read verbs) |
| terminalService (8) | create, write, resize, dispose, listTerminalIdsUnderPath, onDynamicData, onDynamicExit, disposeAll | create/dispose/resize/write (the transport HAS write — VIEW-ONLY is app-posture) | **NEW notable: onDynamicData/onDynamicExit streaming events unsubscribed** — a live-stream upgrade path for the attach plane |
| commandsService (6) | list, writeCommandFile, updateCommandFile, deleteCommandFile, setCommandEnabled, getPrimaryUserCommandsDirectory | list | authoring verbs = the protocol-exact spec behind the read-only footnote |
| usageStatsService (9) | getAppUsageSnapshot, getCodingPlanUsageSnapshot, getCodingPlanRegularTps, getCodingPlanResetStatus, **requestCodingPlanResetOpportunity, useCodingPlanReset**, markCodingPlanResetHistoryRead, getSnapshot, getEntitlementSnapshot | snapshot/entitlement reads | **NEW G90: the coding-plan RESET trio** — the phone's Usage screen renders "Quota reset 05:20" read-only; the desktop can request/use a reset remotely |
| gitCheckpointService (4) | createCheckpoint, diffCheckpoints, restoreBetweenCheckpoints, deleteCheckpoint | create + the rewind command plane | checkpoint diff/restore verbs unconsumed (the phone's rewind rides applyFileRewind) |
| outputStyleService (7) | listStyles, addStyle, updateStyle, deleteStyle, getUserStylesDirectory, setActiveStyle, getActiveStyle | 0 | **G84 exact** (read-only mirror = listStyles + getActiveStyle) |
| clientScenesService (1) | getSnapshot | 0 | **G83 exact — ONE verb** (the smallest consume on the board) |
| hooksService (3) | loadHooks, **saveHooks**, grantWorkspaceHookTrust | loadHooks + grantWorkspaceHookTrust | **saveHooks is NEW to the census**: hooks authoring is REMOTE-SERVABLE (saveHooksImpl filters editable≠false, scope user\|project, source zcode) → folds into G89 |
| broadcastService (6) | send, acquireClaim, commitClaim, releaseClaim, tryClaim, onMessage | 0 | **G85 exact** (pass-3's claim protocol, now method-level) |
| subagentsService (3) | createAgent, updateAgent, deleteAgent (+list) | list | authoring verbs exist remotely; phone read-only |
| pluginManagementService (17) | listPlugins, getPluginReferenceCatalog, resolveSuggestedPluginReference, onDynamicPluginOperationProgress, getPluginsOverview, add/remove/update Marketplace, install/cancel/uninstall/update/restoreBuiltin/configure/resetConfig/validate/describe/setEnabled | listPlugins | desktop-side by design (no-action family) |
| OffPeakTask channel (4 verbs + typed failures) | **offPeakList** (phone consumes, V4Plane.kt:1452), **offPeakCreate, offPeakControl, offPeakLifecycle** + failureStage/errorCategory/offpeak_disabled/client_validation | list | **G29 authoring parity SETTLED** (G.D.6's queued item): a dedicated create/control/lifecycle trio exists, unconsumed phone-side; the typed failure vocabulary is undecoded |

### B2. New protocol vocabulary (schema-level)

- The provider model-properties schema carries **`inputFormat:{supportsImage, supportsVideo,
  supportsPdf}`** — TWO bits beyond pass-31's supportsImage record; the phone's modelOptions
  decode ignores all three (view-over-the-registry posture; recorded for the next "image
  attachments to the agent" ask).
- The clientHello plumbing re-verified: the REMOTE schema gates exactly 4 bits
  (workspaceHookReviewUi, workflowRunDeltas, pluginDiscoveryUi, historyFirst), read off the
  hello and stamped onto the trusted connection (`readTrustedZCodeAgentV4Connection`); the
  other 9 of the 13-bit set remain desktop↔agent vocabulary (pass-3 B2 stands, re-derived).

### B3. The hooks i18n census — G71's build spec is now exact

**300 `settings.hooks.*` keys** (zh-locale values in the bundled IntlProvider chunk — the
keys are the spec). Two families matter for the phone:

- **The review-reason enum (11 typed reasons)**: review_superseded, snapshot_mismatch,
  bundle_changed, config_unreadable, config_write_failed, config_rebuild_failed,
  trust_store_corrupt, blocked_by_policy, policy_requires_pretrust, interaction_timeout,
  host_unavailable, rejected — the desktop can say WHY a re-review is required.
- **The authoring faces**: add/edit/delete, matchAll, the session-snapshot semantics ("Hook
  config changes take effect in a NEW session"), configured vs compatibility/importable
  groups.

Phone side: the hook-review decode (`domain/src/main/kotlin/com/zcode/android/domain/model/
WorkspaceHookReview.kt`) carries **NO reason vocabulary** — the phone's card says only
"N pending · M hooks". **NEW GAP G89**: the review-reason enum (decode + typed faces) + the
saveHooks authoring spec (consume/no-action decision belongs to the roadmap; the decode half
is the safe minimum).

### B4. Census-completeness footnotes

- The Resources-tree surfaces (outside the asar, first census'd this pass):
  `cua-helper/ZCode Computer Use.app` (the CUA helper bundle),
  `macos-window-bounds/zcode-window-bounds` (206 KB native helper, mtime matches the 3.18.1
  install), `tools/{bfs, ripgrep, ugrep}` (three search binaries — pass-31 recorded ripgrep
  inside glm/; the Resources-level trio is the fuller record). None are phone-consumable.
- The slash-20 drift check re-delegates to pass-31 (digest unchanged). One trap recorded for
  the next pass: **"Show this slash command help." is the PHONE's OWN builtin-catalog
  fallback** (`features/conversation/port/BuiltinSlashCatalog.kt:22`), not desktop-served
  copy — don't chase it as catalog drift.

## H.C — where the phone is BETTER than the desktop (new receipts this pass)

- **The expired-fuse honesty**: when the desktop's fuse fires, the phone CLOSES the machine
  and renders "No answer; continued automatically" with the full payload — a typed terminal
  face for a remote interaction lifecycle the desktop renders only locally.
- **The locale round-trip** (EN→中文→EN, zero recreates, fully localized dialogs) — the
  G37 SP-mirror pattern verified live in both directions; the desktop's locale plane was
  never census'd to this depth.
- **The attachment Retry affordance** as the recovery path (2nd live instance).
- **0 FATAL / 0 ANR** across 3 generations, 2 force-stops, the picker, the drag attempt, and
  two locale restarts.

## H.D — fix-order recommendation (supersedes G.D)

1. **F-P0-B** — the one-line `known.permission != null` exemption; now provably the only
   remaining blocker (delivery proven by the rig fix; pins from pass-2 stand).
2. **F-P3-A** — the card-agnostic pinned-stack budget (4th re-manifestation).
3. **F-P5-A** — the git-refusal typed faces on the live section-empty path + a live-shape pin.
4. **F-P3-B / F-P3-C / F-P2-A / F-P0-C** — unchanged (openRun hydrate; per-session delta
   retention; the cold-restore adoption guard — rows persisted this pass but the clobber was
   not re-driven; the file-viewer typed error face).
5. **N-BK-1 app-side** — the "No commits yet" History empty state (the rig half is DONE).
6. **N-BL-1** — workflow-run button labels; the attachment pick→begin handoff glance.
7. **Parity queue**: G89 (review-reason decode + saveHooks decision) → G90 (reset trio) →
   G82/G83/G84/G85 (specs now exact) → G86/G87/G88 (unchanged) → the OffPeakTask typed
   failure decode + `inputFormat` bits as opportunistic adds.

### Coverage honesty (what pass 5 did NOT drive)

The notification ACTION buttons (the shade group rendered collapsed on this launcher image;
answering happened via the deep-link + conversation planes — the lock-screen Approve-without-
auth leg from pass-37 remains unexercised at HEAD), the DIRECT-mode generation (the :18200
mock died at the gen3 restart and was not re-armed; the DIRECT receipts stand from passes
2–4), the F-P2-A recreate clobber (rows persisted; the SELECTED endpoint was not asserted —
the relay was the expected active anyway), Providers/Bots settings rows (the same viewer
family verified four times in the series), and the fuse-vs-push race as a deliberate leg
(gen1's version was accidental; a clean "push survives a later fuse" leg belongs to a
future pass).

## Pass-5 verification stamp

Pass 5 completed 2026-11-13 ~12:56; THREE relay generations (gen1 fuse+push — the accidental
expiry receipt; gen2 push+shade+git contract; gen3 git refusals + expired fuse) + 2 force-stop
cycles + the locale round-trip; 26 screenshots in `/tmp/p5/shots/`; relay logs
`/tmp/p5/relay-gen{1,2,3}.log`; logcat `/tmp/p5/logcat-gen1.log` (0 FATAL / 0 ANR); DB pulls
`/tmp/p5/db/`; a11y dumps `/tmp/p5/ax-*.xml` + the reusable audit script `/tmp/p5/ax.py`; REA
table extractor `/tmp/p5/methods{3,4}.py`; phone inventory `/tmp/p5/pm.txt`. APK byte-identical
to the series (sha256 `ad449d7d…`, md5 `530ba4ff…`, versionCode 53 / 1.3.2); ONE rig-side
commit (`2b12fc46` fix(mock): F-P4-A + N-BK-1, 37 mock tests green); desktop digest unchanged
(`ec475e6e…`). Rig left: emulator + gen3 relay on :18100 (the DIRECT mock died at the gen3
restart). Nothing pushed.

---

# Part I — pass 6 (APPENDED 2026-11-13, evening): the contested-rig pass — the fuse-vs-push race completed, the permission ghost-split, and the parity dig

**Concurrency note (first pass run alongside a second agent on the SAME task)**: both
agents drove the shared emulator + relay. Coordination lived in
`/tmp/pass-coord-2026-11-13.md` (claims + live events, append-only). The relay changed
hands FIVE times during this pass (agent A's gen4 → the other agent's gen → agent A's
gen5/gen7 → the other agent's third gen, nonce `gmwfj72ny-14q`), which the legs below
account for explicitly by nonce. Everything here is reproducible from the pulled DBs and
named logs regardless.

## I.1 — live legs (new receipts at HEAD)

### The deliberate fuse-vs-push race — pass-5's "belongs to a future pass" leg, CLOSED

gen5 timeline (t0 = 21:59:08, push armed at t0+15s, countdown fuse at t0+240s, app
BACKGROUNDED before the push — the foreground-cancel face would have withheld it):

1. **21:59:23** — the fresh-interaction patch delivered to the backgrounded app. Shade
   posts 4 records: system autogroup summary (`g:Aggregate_AlertingSection`,
   GROUP_SUMMARY|AUTOGROUP_SUMMARY) + 2× "ZCode needs your approval" + 1× "ZCode has a
   question for you" — each child `channel=permissions`, `AUTO_CANCEL|SILENT`,
   `category=alarm`, and each carrying its **publicVersion** (the F-33-H split verified
   in dumpsys, not just code).
2. The group summary's own title row rendered "**ZCode Companion · in 2m**" — the G77
   fuse chronometer visible AT THE SHADE LEVEL (the countdown ticking toward the fuse
   deadline, from the lock-screen-safe public face's parent group).
3. **22:03:08** — the fuse fired (the mock's `autoResolutionFired` dropped the pending
   permission/userInput entries out of the served list). At **22:03:59**: `dumpsys
   notification` shows **0** active zcode records — ALL FOUR retracted, including the
   two that had been delivered ~4.5 minutes earlier. The retraction path
   (`AndroidNotificationSink.cancel` + the group-count bookkeeping
   `postGroupSummary(remaining)`) works end-to-end on a real group.
4. The app's cards closed with the typed face — "Agent needs your input / No answer;
   continued automatically" (screenshot `s5-postfuse.png`, machine RESOLVED, buttons
   gone) — re-verified on a second generation the same way.

The face itself was already a pass-5 receipt; the NEW half is the **notification
retraction of an already-delivered push when the remote fuse fires** — the shade never
holds a stale ghost after the desktop auto-continued.

### F-P6-A (NEW, P2): the permission ghost-split — store DISPLAYED vs card plane EMPTY

The headline finding of this pass. Evidence chain (all pulled at HEAD, APK `ad449d7d…`):

- gen7 (nonce `mock-p6-gen7-d0e1f2`, push@15s, fuse@900s): the base approval
  `mock-approval-1-mock-p6-gen7-d0e1f2` is **DISPLAYED** in the Room `interactions`
  table (DB pull 22:11, `sessionId=synth-task-1` — the OPEN task), yet the pinned
  plane's FIRST rendered item was the question card. The pinned plane is a LazyColumn
  whose `items(state.pendingPermissions)` render BEFORE the user-input items
  (ConversationScreen.kt:454) — permissions first by construction. First item = the
  question ⇒ **`pendingPermissions` was empty in the live projection** while the store
  said DISPLAYED.
- Mechanism (pinned, not guessed): `ConversationViewModel.pendingPermissions` filters
  `it.kind == PERMISSION && it.permission != null` (ConversationViewModel.kt:71-74).
  Pass-5 isolated the blocker — the reconcile stale sweep needs the
  `known.permission != null` exemption at InteractionCoordinator.kt:697 — and that fix
  is **still not applied at HEAD**, so the sweep nulls the permission payload, the
  projection drops the interaction, and the store keeps it unresolved.
- **Ghost accumulation (the sharper half)**: the store carries unresolved interactions
  from DEAD generations, nothing ever closes them:
  - `mock-approval-1-mock-p2-gen3-c6d8e0` — **pass 2, 2026-11-12** — PERMISSION,
    DISPLAYED, still unresolved ~26 hours later;
  - `q-rig-1-mock-p0-gen4-7f8a9b` — pass 0 — USER_INPUT, REQUESTED (synth-task-2);
  - conversation ROW 42 (`turn-synth-task-3-11` AND `turn-sess-forked-1-11`) — a
    pass-3-era `pendingApproval` toolCall row still in the row store;
  - gen7's own base approval DISPLAYED forever, while gen7's PUSHED approval resolved
    normally — the reconcile resolves the pushed class but not the snapshot-served one
    when the generation dies.
- **Consequence (why P2)**: the notification broker posts from the STORE (2 approval
  notifications were up while ZERO approval cards rendered) — a shade notification for
  an approval with NO in-app answer surface; its deep-link lands on the same
  starved screen (F-P6-B below). The phone's only answer path for such an approval is
  the shade ACTION button — which this pass still could not drive (I.3). Re-manifested
  across THREE generations with different arm sets (gen7 + the other agent's gen, whose
  base approval was still DISPLAYED after its fuse had closed the question and resolved
  the pushed approval).

### F-P6-B (NEW, P2): the timeline body is fully starved with the armed stack

With the question card + hook-review card + queue panel + background-bash strip +
shared-context chip all armed (the standard mock stack), the conversation screen
renders **ZERO timeline rows**: the "Load earlier messages · showing latest 320 of
333" strip sits directly on the queue panel (screenshot `s29-layout.png`). The pending
approval's conversation row (rowId 2003, `createdAtSeq` 2003 < maxSeq 2011) IS inside
the loaded 320-row window and IS in the DB — but is unreachable in the UI; the
timeline strip does not scroll in either direction and the "Jump to latest" affordance
is absent in this state. Also visible: **"Send answe/rs"** crushed to three lines —
F-P3-A's FIFTH re-manifestation. The blocked=True pinned budget (30% +
`PINNED_RESERVE`, ConversationScreen.kt:430-452) plus the expanded queue panel eat the
window. The two-way collapse contract exists for the queue panel but its header toggle
was not hit in this pass (the panel header row sits between the pinned plane and the
timeline; left as an open probe — collapsing it is the expected remedy and MUST be
verified next pass).

### The shade-action tap leg (pass-5 gap) — attempted, blocked at SystemUI

Four records posted (I.1). The action buttons are verified IN CODE (C3
approve-once/deny actions; `setAuthenticationRequired(true)` on the permissions
channel per F-37-B; the chronometer per G77) and the actions exist in the dumpsys
records — but the GROUP never expanded: tapping the badge, the header, and the child
rows all dispatched a contentIntent (deep-link into the app) instead of expanding, and
the swipe gestures went to quick settings. Recipe recorded for pass 7: answer 2 of the
3 pending interactions in-app FIRST so the remaining notification renders UNGROUPED
(single notifications expand in place with visible buttons), or drive the action
PendingIntents directly. The lock-screen leg was set up (PIN 1379 armed) but there was
no live notification at lock time (0 active records); PIN cleared, rig restored.

### The raw `allowOnce`/`allowAlways` button labels = MOCK FIDELITY, not a phone bug

The gen5 deep-link receipt showed approval-card buttons labelled `allowOnce` /
`allowAlways`. Provenance chased to the end: the mock builds
`{optionId, label, kind}` ALL set to the raw id (MockZCode.kt:1286-1291); the card
prefers the runtime's OWN label when semantics are recognized (G76 contract,
WriteComponents.kt:209-210), and the DESKTOP's runtime option builder sets
`label: c.name` (zcode.cjs, the allow_once→allowOnce mapping site) with the wire
schema REQUIRING `label: string` (host zod `o_`). With real desktop payloads the phone
renders human labels and falls back to `consequenceLabel` only for blank ones. Verdict:
phone correct-by-contract; P4 rig-fidelity note — the mock should send realistic
labels (and a defensive "label == optionId ⇒ use the consequence copy" fallback is a
reasonable hardening idea, not a defect).

## I.2 — REA dig (desktop 3.18.1, asar digest `ec475e6e…` unchanged, build-meta
`9f7ab4c3` / 2026-11-11 / electron-builder 26.17.0)

1. **Tool taxonomy drift — one name (P4)**: the phone's `ToolKindTaxonomy` (the G80
   port: 14 kinds × 31 names) covers every tool name found in the shipped
   `zcode.cjs` EXCEPT **`BashOutput`** (present ×1 in the CLI, absent from the phone's
   name map — `KillShell`/`KillBash` are mapped, their output twin is not; it falls to
   the substring heuristic). Fix is one `put("BashOutput", TASK_CONTROL)` line.
2. **Notice/marker plane (P4)**: desktop `timelineMarker` carries `marker.type` ∈
   {`compact`, `goalVerify`, `worktreeCreated`} and `lane` ∈ {`none`, `present`,
   `lightBoundary`, `turnTailBoundary`}. The phone renders `compact` as the typed
   CompactionNotice; `goalVerify`/`worktreeCreated` render as RAW `QuietMetaRow(label
   = markerType)` (ConversationScreen.kt:1571) and `lane` is not decoded at all.
   Honest raw faces — cosmetic gap only.
3. **Slash parity EXACT**: the desktop's workspace-config wire schema
   `Qye = {name, description, inputHint?, source?}` matches the phone's
   `SlashCommandView` field-for-field, including the `inputHint`-driven
   "/cmd " autocomplete behavior (SlashCommandMenu.kt:87-89); the settings
   CommandsViewer additionally maps the desktop's `argumentHint` → inputHint
   (ViewerModels.kt:627). Zero drift at HEAD.
4. **G91 (NEW, P3): the plugin plane is decode-only — no UI consumes it**. The phone
   decodes `PluginsView` (the `listPlugins` STRICT schema: id/name/description/
   version/enabled/source/marketplace/skillRootCount/commandRootCount/mcpServerNames/
   rootPath + diagnostics — ViewerModels.kt:456+) but NO production screen references
   it (grep: one test file only). The desktop ships **18 bundled plugin packages**
   (android-emulator, browser-use, bundled-skills, design, documents, image-search,
   ios-simulator, node-repl-host, pdf, plugin-creator, presentations,
   restore-legacy-sessions, skill-creator, spreadsheets, visualize, zcode-cua,
   zcode-guide, zcode-site) + the pluginManagement 17-method service from pass-5. The
   phone can never SEE its own plugin inventory. The scoped fix: a read-only Plugins
   viewer in the settings viewer family (Usage/Memory/Commands pattern), list +
   enable-state only (the write half is already Deferred scope in the decode comment).
5. **G92 (NEW, P4): `configOptions` is also decode-only**. The desktop's
   `readPresentation` serves `configOptions: {id, name, description?, category?,
   type: select|boolean, currentValue, options[]}`; the phone decodes it into
   `V4WorkspaceConfig.configOptions` (V4WorkspaceConfig.kt:119) — and no settings UI
   consumes it (grep: zero feature consumers). The desktop's settings-OPTIONS plane
   (select/boolean + current values) is one adapter away from a phone render.
6. **Terminal streaming pair — root confirmed (sharpens pass-5's row)**:
   `V4Plane.terminalListenData/terminalListenExit` (V4Plane.kt:1699-1709, with the
   `proxy-channel.ts:127` provenance comment) exist in the transport with **ZERO
   production callers** — the onDynamicData/onDynamicExit streaming pair is
   transport-ready and feature-unwired; the phone terminal cannot stream live output
   until one subscription + render wiring lands in the terminal feature. The G-row's
   fix is smaller than pass-5 implied.
7. **Provider catalog**: host schema confirms `inputFormat.pick({supportsImage,
   supportsVideo, supportsPdf})` + `supportsText` with overlay semantics
   (`ModelInputFormatConfig.overlay`); the phone's modelOptions decode remains
   id/label/badge only (pass-31's note stands — the inputFormat bits are still the
   opportunistic add). Catalog revision marker not locatable in the host chunk (the
   rev lives CLI-side); schema shape is the parity-relevant half and it is confirmed.

## I.3 — where the phone is BETTER (new receipts this pass)

- The **fuse-retraction completeness**: a remotely-expired approval does not leave a
  stale shade ghost — notification, chronometer, machine face and store row all close
  coherently (the store row resolved; only generation-orphaned rows ghost, which is a
  different failure class — F-P6-A).
- The **reconnect endurance**: five relay generations in ~30 minutes, two of them
  hostile (relay killed mid-session), the app re-attached ≤15 s each time with zero
  user action — the pass-3 "11h/15 reconnects" receipt now covers generation churn
  too.

## I.4 — fix-order recommendation (supersedes I.D/§H.D for the new rows; F-P0-B still first)

1. **F-P0-B** (unchanged — and now provably the cause of TWO new rows): the
   `known.permission != null` exemption kills F-P6-A's card half.
2. **F-P6-A store half**: resolve generation-orphaned interactions (a reconcile sweep
   that closes DISPLAYED rows whose nonce/session no longer exists), plus a pin
   against the pulled-DB ghost shape (three generations of ghosts are in
   `/tmp/pass6/db*.db` as fixtures).
3. **F-P6-B**: the queue-panel collapse probe + the pinned/timeline budget re-balance
   (the timeline rendering ZERO rows defeats the whole screen).
4. **F-P3-A** (5th re-manifestation, same fix family).
5. **G91** (Plugins viewer) → **G92** (configOptions adapter) → the BashOutput
   taxonomy line → the terminal streaming wiring → the marker raw-face localization,
   as one parity queue.

## Coverage honesty (what pass 6 did NOT drive)

The shade-action TAP (SystemUI group expansion — recipe recorded), the lock-screen
face (no live notification at lock time; PIN armed then cleared), the DIRECT-mode
generation at HEAD (receipts stand from passes 2–4; the :18200 direct mock was armed
by this agent and left running), F-P2-A's endpoint-persistence assert (same relay-churn
window made the SELECTED-endpoint assertion meaningless), terminal keystroke typing,
row actions, and the cold deep-link leg. The rig was contested the whole pass (five
relay generations across two agents) — every receipt above is anchored to a named
nonce and a pulled DB so the churn cannot have polluted the evidence.

## Pass-6 verification stamp

Pass 6 completed 2026-11-13 ~22:30; FIVE relay generations observed (gen4/gen5/gen7 +
two concurrent-agent gens, nonces in the DB receipts); 32 screenshots
`/tmp/pass6/shots/`; DB pulls `/tmp/pass6/db{1,2,3,4}*.db` (+WALs) — the ghost
fixtures live there; relay logs `/tmp/pass6/relay-gen{4,5,6,7,direct}.log`; logcat
`/tmp/pass6/logcat-p6.log`; coordination file `/tmp/pass-coord-2026-11-13.md`. APK at
HEAD byte-identical to the series (`ad449d7d…`, md5 `530ba4ff`, vc53/1.3.2) — no code
changed this pass. Desktop asar digest unchanged (`ec475e6e…`, build `9f7ab4c3`,
2026-11-11). Rig left: emulator up, the concurrent agent's third gen on :18100 (12
relay java processes counted at 22:24 — likely zombies worth sweeping by a future
pass), this agent's DIRECT mock on :18200 (pid 30908, free to kill), PIN cleared.
Nothing pushed.

# Part J — pass 7 (APPENDED 2026-11-13, night): the lockscreen shade-action route closes the tap leg — plus the DIRECT approvals leg, two stranded-answer rows, and the V2 mobile-push reverse

**Concurrency forensics (completes Part I's note from the other side)**: the "keeper"
loop that killed relays every ~2–3 min was agent A's rig supervision. Detection recipe
pinned for future contested rigs: `ps -E <relay-pid>` dumps the orphan's MOCK_* env (a
foreign arm set — `MOCK_ROWS=40`, no `MOCK_INTERACTION_PUSH` — fingerprints the other
agent immediately); interaction nonce prefixes in the pulled DB (`mock-p6-genN` vs the
default `g<millis36>`) identify which process served each row; stale siblings worth
sweeping: a deleted-file `tap.py` proxy (pid held a Nov-8 process listening :18300),
an orphan `http.server :18101`, and 12 relay java processes. My pass began inside the
contested window and its receipts are likewise nonce-anchored (`gmwfj72ny-14q` mine,
`mock-p6-gen5/gen6` agent A's).

## J.1 — live legs (all at HEAD, APK `ad449d7d…` unchanged, vc53/1.3.2)

### The shade-action TAP leg — CLOSED (Part I's blocked leg; its recorded recipe superseded)

The route that works is the one Part I never tried: **expand the shade from the
LOCKSCREEN** (`keyevent 26` power → `keyevent 224` wake → `cmd statusbar
expand-notifications` while the keyguard holds). On the swipe-keyguard lockscreen the
group children render **with their action buttons exposed as clickable nodes** —
`Button 'Approve' [154,881][364,1013]` + `Button 'Deny' [375,881][528,1013]` in the
uiautomator dump (shot `10-lockshade.png`). Tapping Approve (259,947) dispatched: a
FRESH app process started (uid 2000 launch from the action PendingIntent), and the
app logged the attempt. The foreground-swipe path stays broken (tapping the badge or
rows deep-links instead of expanding — Part I's observation stands for the in-app
shade), but the lockscreen face is the real user path for "phone in another room" and
it works. The F-37-B "lock-screen Approve without auth" leg is now DRIVEN at HEAD:
`setAuthenticationRequired(true)` dispatches on a credential-less swipe keyguard.

### The prerequisite Part I inherited: NotificationContentLevel must be CONTENT

The buttons exist only when the subject survives, and the subject survives only under
**Settings → Notification detail: Task titles** (= `NotificationContentLevel.CONTENT`;
the two-value cycle is Minimal ⇄ Task titles, persisted to
`no_backup/datastore/preferences.pb`). Code pin: `NotificationPlan.decide` drops the
subject under MINIMAL (`event.subject?.takeIf { full && it.isNotBlank() }`) and
`shadeActions = subject != null` — so on DEFAULT prefs a first-run device NEVER gets
shade actions (title-only, by §24 design). The reposted approval after the flip
carried `android.text="Bash: echo synthetic"` + both action PendingIntents
(`[0] "Approve" → PendingIntentRecord … broadcastIntent`). Part I's "actions exist in
the dumpsys records" receipt ran on this flip (set 22:21 by this agent while both
agents shared the app data). A two-line consequence worth a UX look: the DEFAULT
device's shade is answer-less, and nothing in the approval card or settings points at
the toggle.

### F-P7-B (NEW, P2): a shade action answered while the connection is down marks the interaction FAILED — no retry, no queue

The Approve tap at 22:34:57 landed during a reconnect flap (relay-2/relay-3 failed
binds, relay-4 connected 1.5 s later). The app dispatched the answer onto the dead
channel and logged `interaction.rejected :: channel closed: abnormal close 1006`; the
store row went **PERMISSION/FAILED** (DB pull: `resolvedAtMs` set, lifecycle FAILED).
The user believes they approved; the desktop never receives anything; the mock's own
auto-resolution is what eventually closed the sibling interactions. There is no outbox,
no reconnect-flush, no user face beyond the log. With Live-connection OFF (the
default), screen-off kills the socket within seconds — this is the COMMON path for a
lock-screen approve. Fix direction: queue the shade answer behind the reconnect (the
inbox path already has RETRYABLE_KEPT semantics to reuse), or fail the notification
visibly (re-post with a "not delivered — retry" face).

### F-P7-A (NEW, P2): the Approvals screen's inbox answer strands — and its banner remedy can be impossible

First-ever drive of the Approvals screen (on the DIRECT endpoint): the answer flow logs
`approvals.answer :: option=allowOnce` and then shows the honest typed banner **"The
answer was not confirmed — open the task and answer there."** — but the v4 resolve
NEVER leaves the app (zero answer-shaped v4call in the direct mock's log) and the
interaction stays DISPLAYED minutes later (DB: `mock-approval-1-gmwfk0g9x-172` still
DISPLAYED). Mechanism: `ZCodeApp.kt:571` → `ConversationPort.answerFromInbox` →
`coordinator.submitFromStore` → **RETRYABLE_KEPT** (the inbox answer needs a session
machine to carry it; on the Approvals screen none is attached). The RETRY row then
never flushes, and on the DIRECT endpoint the banner's remedy cannot even be executed:
the approval's task (`Synthetic task`/`synth-task-1`) has no row in the direct-ws task
list (foreign workspace), so there is nothing to open. The store inconsistency that
exposes it is mock-fidelity (the direct mock seeds approvals for a session it does not
list), but the app-side truth stands: **the inbox answer path is interaction-scoped in
name only — it silently requires a machine, strands otherwise, and its recovery
instruction can be unactionable.** Fix direction: submit interaction-scoped (the same
shape the shade actions and `NotificationActionReceiver` use), keeping RETRYABLE_KEPT
only for genuine wire failures, and validate the task row before advising "open the
task".

### F-P2-A REFUTED at HEAD (two strengths, both asserted)

The selected endpoint survived (1) a force-stop + relaunch cycle and (2) a FULL
EMULATOR REBOOT (the Dictate crash below killed the emulator mid-pass; cold boot
`-no-snapshot-load`, app relaunch): Settings shows `MockBook — Active endpoint · Relay
(QR pairing) · Connected` with P3Direct still secondary (shot `28-endpoint-after-reboot`).
The cold-restore also reconnected automatically and re-rendered the whole home stack —
the strongest persistence receipt of the series. The pass-3 row can move to
refuted-at-HEAD (the recreate-clobber class predates the current store).

### The viewers leg (pass-5's undriven rows) — all render real decoded content

Providers: Z.AI default + glm-5.1/air/flash (Preview badge) + an OPENAI-COMPATIBLE
provider with glm-4.6 — grouped list, no credentials. Bots: bot-telegram-1
(/synthetic/ws-one, bound to synth-task-1, kind=task) + bot-discord-1 (/synthetic/ws-two).
Subagents: Explore + Plan, built-in, On. Insights (Usage/Plugins/Skills/MCP servers)
render their section rows. All read-only viewer-family screens behave (back stack
correct, no crash, empty/loaded states clean).

### SessionContextSheet — full render, Terminal route, one dead face

The sheet renders everything the arms seed: Task tokens · 184320 (meter), Status ·
4m57s · Iteration 3 · 1 background, goal "Ship it verifying⏸" with the iteration
timeline (✓ iter1 / — iter2 2 criteria unmet), Todos 1/2, Workflow runs (lint-all
Running, release-notes Stopped), Plans (Ship the release 1/3 with the per-step
checkboxes), the git strip (main · clean · Open), and the Files/Git/Terminal/Model
calls/Share chip row (shot `24-sheet.png`). **Terminal** routes
(`nav.terminal :: open`, `nav.screen :: route=terminal`) and renders the VIEW-ONLY
face with Pause/Search — but **Attach is a no-op**: the tap produces no nav log, no
v4call, no error face; the screen stays "Not attached." forever (**N-BM-3**). The
mock DOES implement the terminal channel (54 terminal references incl.
`pushTerminalData`); the app-side attach never fires a request. Small, but the only
silent dead button found this pass.

### Child sessions — the pass-31 P4 small is consumed at HEAD

The subagent timeline row renders **"Subagent · explore · Explored the auth module ·
Open session ›"** and Open session navigates to the child conversation (shot
`31-child-session.png`). Two polish notes (**N-BM-2**): the child view is the generic
"Conversation" title (no task identity), and on an unscripted child the skeleton bars
persist forever with no empty-state face.

### N-BM-1 (NEW, P3): the load-earlier strip's total is a stale subscribe-time snapshot

Two taps on "Load earlier messages · showing latest 320 of 333" produced "370 of
333" then **"420 of 333"** — the DB truth is 420 rows (`conversation_rows` count),
so the loaded side is honest and the "of N" never updates past its subscribe-time
value. At the true end the strip displays an impossible claim instead of an
all-loaded/hidden state.

### The DIRECT generation at HEAD — connected end-to-end, one more stranded-answer receipt

`MOCK_MODE=direct` on :18200 (`MOCKZCODE_READY mode=direct`): the P3Direct endpoint
switched Active and Connected (server-info token + cookie auth), served its own
workspace view (`direct-ws` — automations, workflows, approvals badge), full v4
traffic on the log (initializeConversationV4, listAutomations, listSavedWorkflows/
Runs…). The foreign-workspace Tasks list is correctly EMPTY (the mock's task corpus
belongs to synthetic-ws-one). The Approvals screen rendered the live DIRECT approval
("Permission — Bash · task Synthetic task · echo synthetic · Allow once / Deny") —
whose answer produced F-P7-A above.

### The Dictate leg killed the emulator (N-BM-4, rig receipt; leg UNDRIVEN)

Tapping the composer's **Dictate** entry (the attachment sheet: Mention a file /
Sketch / Photo / Attach a file / Take photo / Dictate) was followed within seconds by
the emulator process DYING (`adb: no devices/emulators found`; qemu gone; boot log
clean). Cause unproven — speech-recognizer crash on the API 36 image vs 8 GB-host OOM
— and NOT re-armed (the leg stayed undriven; the app's Dictate face remains unknown).
The forced cold-boot cycle it caused produced the F-P2-A refutation above, so the
crash had forensic value.

### F-P0-B 5th re-manifestation (receipt, no change to the row)

The fuse card again rendered with "Send answers" greyed while the machine was
answerable (shot `13-failed-face.png`, gen7 stack) — the one-line
`known.permission != null` exemption remains the highest-leverage fix on the board
(Part I's F-P6-A depends on it too).

## J.2 — the REA dig, part 7 (asar digest `ec475e6e…` unchanged, fresh extraction `/tmp/p6-asar`)

1. **G93 (NEW, P3): the desktop's V2 mobile-push plane has NO phone receiver.**
   `createWebRemoteControlV2Http` (out/main/index.js): per-(origin,userId) **device
   registration** — `register {device_mid} → {device_sid}` persisted to deviceStorage
   with old-device cleanup on origin/user change — plus `token {role:"device"}`,
   `delete {device_sid}`, and `POST /api/v1/remote/notification/events
   {device_sid, event}` validated against a receipt
   (`data.event_id` match, `status ∈ accepted|ignored|rejected`). Eligibility is
   probed via `GET /api/v1/remote/notification/status` requiring
   `protocol_version===1 && event_transport==="http"` (+status
   available/unavailable/unknown). The dispatcher (`MobilePushConnection`) observes
   workflow runs (registry cap 4096), stamps `desktop_platform`, respects a
   per-dispatch `{enabled, locale}` user pref (`user_disabled` skip), and skips with
   typed reasons `eligibility_unavailable / run_owner_mismatch / duplicate /
   transport_unavailable`. **The phone has zero Firebase/FCM references** — its
   attention arrives ONLY over the live relay socket (its `device_sid` vocabulary is
   the V1 relay auth plane, unrelated). Consequence: whenever the relay socket is
   down — exactly the shade-action/dead-connection scenario of F-P7-B — the
   desktop's new push arm cannot reach the phone at all. The phone-side fix is
   architectural (a push receiver + the registration face), not a decode; recorded
   as the top item of the push queue.
2. **Plugin inventory re-verified**: 18 bundled packages (matches Part I's G91 list;
   the pass-35 count stands). Nothing new phone-consumable.
3. **The settings-domain census (51 domains)**: modelProvider(3217 keys), plugins,
   source, usage, mcp, skills, subagents, hooks, commands, **shortcuts(318)**,
   browser(299), migration(269), memory, mcpServers, **computerUse(94)**,
   **workspaceFileSearch(90)**, personalization, dynamicWorkflow(45), locale,
   themeMode, appearance, interfaceMode… The phone's settings family covers the
   conversation/notification/privacy/appearance slice; the desktop-only domains are
   legitimately so (shortcuts, browser, migration), but `workspaceFileSearch` and
   `dynamicWorkflow` are decode-vs-UI candidates in the G92 class (the phone already
   renders workspace config rows).
4. **Residual (recorded, not chased)**: the mobile-push event kind/state enum lives
   in a host chunk (the receipt keys `{event_id, session_id, run_id, kind,
   event_state}` are pinned from the dispatcher); the workflow/automation method
   names are RPC-table-encoded in chunks — pass-5's H.B matrix (via the mock's RIG-5
   seams) remains the phone-side contract source.

## J.3 — where the phone is BETTER (new receipts this pass)

- **Full-emulator-reboot cold restore**: paired identity, endpoint selection,
  connection phase and the entire home stack (automations with history counts,
  workflows, task groups with unread/pinned states) restored automatically with zero
  user action — the harshest persistence test of the series, passed.
- **Typed honesty faces everywhere in the failure paths**: "The answer was not
  confirmed — open the task and answer there." (Approvals), "The desktop couldn't
  complete this request." (home, after a relay kill), the "Is your desktop asleep?
  Turn on Keep awake…" hint banner (new face this pass), RECONNECTING → CONNECTED
  phase chips. The failure UX degrades honestly even where the wires behind it
  strand (F-P7-A/B are wire findings, not face findings).

## J.4 — fix-order recommendation (appends to I.4; family grouping)

1. **F-P0-B** (unchanged first — unblocks F-P6-A's card half AND the 5th F-P3-A face).
2. **F-P7-A** — the inbox answer's interaction-scoped submit (one wire-shape change;
   kills the stranded-answer class on the Approvals screen; the shade path already
   proves the interaction-scoped submit works).
3. **F-P7-B** — dead-connection shade answers: reuse the inbox RETRYABLE_KEPT
   semantics behind the reconnect, or surface "not delivered".
4. Part I's order (F-P6-A store half, F-P6-B budget, G91 Plugins viewer, G92
   configOptions, BashOutput taxonomy, terminal streaming wiring) — unchanged.
5. **G93** joins the queue as its architectural item (push receiver + registration
   face; needs a cloud-transport decision before any code).
6. Smalls: N-BM-1 (stale strip total) → N-BM-3 (Attach feedback) → N-BM-2 (child
   view identity/empty state).

## Coverage honesty (what pass 7 did NOT drive)

Terminal keystroke typing (the attach request itself never fires — N-BM-3), row
actions (fork/retry/rewind — the row-affordance hunt scrolled into the queue panel
and was time-boxed out), the fuse-vs-push re-run on my own generations (Part I's
receipt stands; my gens re-manifested the post-fuse card face instead), the
Keep-awake hint banner's navigation target, and the Dictate face (emulator-killing,
N-BM-4). The shade-action tap receipt is ONE dispatch (the success/failure halves
both observed once); a second lockscreen approve over a LIVE connection is still
owed to the series.

## Pass-7 verification stamp

Pass 7 completed 2026-11-13 ~23:05; relay generations under my control: gen1 (broad
arms 21:47), gen2/gen3/gen4/gen5/gen6/gen7 (:18100, INTERACTION_PUSH@12s) + the
direct mock (:18200, `MOCK_MODE=direct`) — interleaved with agent A's gens in the
contested window (21:52–22:10) and clean after it. 30 screenshots `/tmp/p6/shots/`;
DB pulls `/tmp/p6/db/` (interactions ghosts + 420-row truth); relay logs
`/tmp/p6/relay-gen{1..7,direct}.log`; logcat `/tmp/p6/logcat-gen1.log`; the REA
extraction `/tmp/p6-asar/` (464 MB). APK at HEAD byte-identical to the series
(`ad449d7d…`, vc53/1.3.2) — no code changed this pass. Desktop asar digest unchanged
(`ec475e6e…`, build `9f7ab4c3`, 2026-11-11). Rig left: my gen7 relay (pid 32806) on
:18100, the direct mock (pid 36166) on :18200, emulator up (cold-booted 22:31 after
the Dictate death). Nothing pushed.

# Part K — pass 8 (APPENDED 2026-11-14, past midnight): the endpoint-abandonment root cause, the frozen timeline, the never-driven feature legs — and the channel-table dig

> **Pass 8 of the pass-0 series** (Ernest's recurring "verify everything, dig deeper" ask; ran
> solo — the coordination file `/tmp/pass-coord-2026-11-13.md` carries the claim row). Two halves:
> (1) the live rig — six relay generations on :18100 plus the inherited DIRECT mock on :18200,
> driving the legs every earlier pass left open (the Part-I queue-collapse probe, the pass-7
> owed LIVE lockscreen approve, terminal keystrokes, + New task, task Archive, automation
> Pause/Resume, home Search, print completion to an actual PDF file, the mention panel's five
> tabs, the Sketch canvas, Take photo) — which produced TWO new P2-class app defects with
> code-level mechanisms (F-P8-A the silent endpoint abandonment; F-P8-C/F-P8-E the frozen
> timeline + the tail-clamp gate); (2) the REA dig part 8 — desktop digest re-verified
> byte-identical (`ec475e6e…`) so every pass-0..7 census stands, and the deepening came from
> extracting the CHANNEL METHOD TABLES no earlier pass had (Bots 34, Feedback 15, ManagedWorktree
> 14, AppControl 5, the real remote task service 14, ZCodeSession 16, Git 17, the sync ×4,
> DeviceSyncState/ClientConfig/Skills getSnapshot twins) plus the G93 narrowed residual and two
> new gap candidates (G94 worktree plane, G95 feedback plane).
>
> **Environment of record.** HEAD `8f7e93c8` (main, clean — docs-only since), debug APK
> byte-identical to the whole series (md5 `530ba4ff…`, sha256 prefix `ad449d7d…`, versionCode
> 53 / 1.3.2 — installed APK re-pulled and re-hashed at pass start). Emulator `zcode-a16`
> (Android 16, API 36) warm from pass 7 (booted 22:44 the previous evening; never restarted this
> pass). Relay generations: gen1 `…-k1a2b3` (full 23-arm, push 15 s, fuse 900 s) → gen2 `…-k2c3d4`
> (same + off-peak@240 s; its REMOTE_DOWN/MD_FILE arms were LOST to a script bug — appended after
> the `exec` line — recorded in §K.A0) → gen3 `…-k3e4f5` (arms correctly ordered; the
> 3-notification shade leg) → gen4 `…-k4g5h6` (approval-only) → gen5 `…-k5i6j7` (push 12 s) →
> gen6 `…-k6l7m8` (push 25 s) — every receipt nonce-anchored in the pulled DBs. Desktop under
> audit: `/Applications/ZCode.app` 3.18.1.10376, asar sha256 prefix re-verified `ec475e6e302595fe…`
> at pass start; extraction reused from pass 7 at `/tmp/p6-asar/x` (host index.js sha256 prefix
> `e31718b8fa8a7d3f` — identical to pass-4/5's). Screenshots `/tmp/pass8/shots/` (15), DB pulls
> `/tmp/pass8/db/p8-{1,2}.db(+wal)`, relay logs `/tmp/pass8/relay-gen{1..6}.log`, full logcat
> `/tmp/pass8/logcat-gen1.log` (~continuous across all six generations; the midnight-rollover
> awk lesson is in §K.A0).
>
> **Verification stamp:** 2026-11-13 23:35 → 2026-11-14 01:20, GLM-5.3-Flash in ZCode (ZCode
> Companion pass-8 rig), everything local, nothing pushed.

## K.A0 — rig learnings (each will bite the next pass)

1. **Appending env exports AFTER the `exec java` line arms NOTHING** — gen2's
   `MOCK_REMOTE_DOWN_MS`/`MOCK_MD_FILE` were added with `cat >>` and never reached the process
   (verified via `ps -E`). Build generation scripts by EDITING before the exec line.
2. **The midnight rollover breaks string-compared time filters** — `awk '$2>="23:50:21"'`
   silently excludes every post-midnight line (`"00:03:44" < "23:50:21"` lexicographically). A
   13-minute window of log lines "disappeared" this way mid-investigation. Filter by grep on
   connection ids or re-derive windows from the epoch-ms field (field 3 of ZCODE-PAIR lines).
3. **The app's socket dies ~30 s after screen-off with Live-connection OFF (the default)** —
   then the background ladder cycles `connect timed out after 10000ms` against a LIVE relay with
   growing backoff (observed 3+ min without success). Foregrounding via `am start` reconnects in
   ~2 s. Any leg that needs "the app receives something while locked" must either enable
   Settings → **Live connection** (the FGS `Live connection active — tap to disconnect`
   notification receipts, toggle verified live both ways) or foreground-poke first.
4. **The interaction-push arm is CONVERSATION-SUBSCRIBE-anchored** — gen5/gen6 (armed, app
   locked, socket alive) delivered NO push because the conversation screen was not resumed
   (no subscribe → the arm never armed). The arm only fires when the conversation screen's
   subscribe happens N s before the fire time. Recipe for future push legs: reconnect WITH the
   conversation open (foreground), lock INSIDE the arming window (fire−lock ≥ ~10 s).
5. **`rig.py dump` prints only the FIRST 40 labeled nodes** — rows "disappeared" from dumps all
   pass until this was noticed (the "missing" Live connection rows were at dump-index 60+).
   For anything past the first screenful, grep the RAW `uiautomator dump /dev/tty` XML.
6. **`tapc` substring matches bite**: `tapc "Attach"` matched "Not **attach**ed." (tapped the
   wrong node); `tapc "Retry"` matched the banner TEXT (whose full string contains "Retry").
   Anchor taps on exact bounds from the raw XML when labels share substrings.
7. **The persisted ACTIVE endpoint survived a force-stop AND an app-kill across the whole
   pass** (P3Direct selected at cold start twice — F-P2-A stays refuted for the *selection
   persistence* claim), but see F-P8-A: what does NOT survive is the *hub's* notion of the
   active endpoint when a relay pairing also exists.

## K.A1 — verified working this pass (receipts; never-driven-in-series in bold)

| Surface | Pass-8 receipt |
| --- | --- |
| Cold start honors the persisted DIRECT selection | force-stop + relaunch with P3Direct selected: `hub.connect :: cold-start connect begin (kind=DIRECT_SERVER)` → `direct.connected` (twice this pass) — selection persistence holds |
| **The DIRECT degraded face + recovery** | stale long-running direct mock → bootstrap control-request timeouts ×3 (25 s apart) → honest "**First sync failed** / Just paired? …" strip + Retry; after restarting the mock, one Retry → `direct.connected` + `hub.bootstrapApplied tasks=3` — the full degraded→recovery ladder |
| **New task, end-to-end (first series drive)** | + New task sheet (workspace selector/prompt/model) → typed prompt → Create → `tasks.create :: workspace=/synthetic/ws-one model=default/default` → `hub.createTask :: outcome=Accepted` → the row renders **Running** on home — creation→projection round-trip; then **Archive** from the task-row long-press menu → confirm dialog → `zcode-task.archiveTask ok` + re-list → row gone |
| **Task-row long-press menu** | Pin / Mark unread / Move to group… / Rename / Archive / Delete — renders and its Archive leg wires (above); surfaced accidentally, driven deliberately |
| **Automation Pause/Resume verbs (first series drive)** | home row → Pause → `setAutomationEnabled ok in 43ms`, row flips "On · active"→"**Off · active**" and the action becomes **Continue** → Continue → second `setAutomationEnabled` pair — both directions wire-true |
| **Home search (first series drive)** | Search FAB → query field → "task 3" → the list filters to exactly the matching row live |
| **Print → PDF completed (pass-0 only opened the spooler)** | Share → Print (PDF) → spooler renders a real 28-page preview ("Page 1 of 28") → Save as PDF → system saver → **`/sdcard/Download/Synthetic task.pdf` (86,860 bytes)** written and pulled to `/tmp/pass8/receipts-synthetic-task.pdf` — the print plane is end-to-end |
| **The mention panel (first series drive)** | + → Mention a file → composer gets `@` and a **five-tab panel opens (Files / Sessions / Skills / Plugins / Agents)** → typing filters (src/main.rs) → pick → the token lands in the composer → Send → `sendConversationCommandV4 ok in 131ms` |
| **The Sketch canvas (first series drive)** | + → Sketch → "Sketch to send" canvas → two strokes → Done → chip `sketch-1794616828032.png (6.3 KB)` → **`attachmentBeginV4` → `attachmentCommitV4 ok in 331ms`** — and the pick→begin handoff AUTO-STARTED this time (pass-5's no-op pattern did not recur; the staged chip rendered ✓) |
| Terminal attach (5th series verification) + the keystroke posture | Attach → `terminal.create ok` + `terminal.attached :: id=term-1`; keystroke attempts in VIEW-ONLY produce **zero `terminal.write` calls** and no input surface in AX — the write-verb-never-sent posture holds; Copy visible output / Detach → `terminal.dispose begin/ok` |
| **The Approvals inbox ACK over a LIVE relay (2 answers)** | Allow once ×2 → `approvals.answer :: … option=allowOnce` → `sendConversationCommandV4 ok` (747 ms / then instant) → both store rows RESOLVED in the pulled DB — the inbox answers cleanly on the relay (F-P7-A's strand is therefore endpoint/machine-context-specific, not the inbox path per se) |
| The 3-notification shade leg + AUTO_CANCEL | gen3 push while backgrounded → shade group "ZCode needs your approval" ×2 + "ZCode has a question for you" → tapping a child deep-linked into the app over the swipe keyguard (F-37-B posture re-verified) and AUTO_CANCELLED that record |
| **The Live-connection FGS face** | Settings → Live connection ON → **`Live connection active — tap to disconnect`** persistent notification + `Background started FGS: Allowed … com.zcode.android.app` in the system log; toggle verified OFF again at pass end (the setting's own "turn off when done" advice respected) |
| Context sheet full render (re-verified at HEAD) | Task tokens · 184320; Status · 4m58s · Iteration 3 · 1 background; goal + iteration verdicts; Todos 1/2; Workflow runs; Plans 1/3; git strip |
| Git sheet (re-verified at HEAD) | read-only badge; branch block with per-file +42−0/+3−7; Changes Unstaged (2) with real paths; **Checkpoints honest empty state + the Snapshot tab serving `ckpt-1`** with "Snapshots created elsewhere aren't listed here" |
| Queue deltas live-drain | the two seeded queue rows vanished live under `MOCK_RUN_REMOVE_MS=30000` (delta-driven removal round-trip) |
| Robustness | 0 FATAL / 0 ANR across six relay generations, ~15 reconnects (relay kills, foreground/background churn, the wifi toggle), the print/camera/mention/sketch flows; the ladder converged every time a server came back |

## K.A2 — defects found this pass (NEW ledger rows; order = recommended fix order)

- [ ] **F-P8-A (P2, app) — on a cold start with a DIRECT endpoint selected, the pairing edge
  SILENTLY abandons the selection to the relay; "First sync failed" then shows against a
  healthy direct server, and the recovery Retry completes the abandonment.** The full chain,
  receipted line-by-line and pinned in code: (a) cold start seeds the relay (`start()` :494),
  `resolveActiveEndpoint()` (:543-571) flips to the persisted P3Direct and connects —
  `cold-start connect begin (kind=DIRECT_SERVER)`, `direct.connected` ✔; (b) the
  `LaunchedEffect(paired)` pairing edge (ZCodeApp.kt:182-196) runs `onPairingCompleted()` on
  the pairing waypoint; with `current == relay-seed` (the resolve race) it takes the same-id
  branch and launches `refreshRegistry(); persistEndpoint(relayDescriptor)` ASYNC
  (ConnectionHub.kt:672-685) — **that deferred `persistEndpoint` lands AFTER the resolve and
  rewrites `_endpoint.value` back to the RELAY (:1650) with no log at all**; (c) every hub
  decision now uses the wrong active kind: the direct transport's own recovery (a fresh
  `direct-2` `direct.recovered` after I restarted the stale mock) is INVISIBLY DROPPED by the
  `sourceKind != activeKind()` filter in `onActiveState` (:598) — no `hub.phase DIRECT →
  CONNECTED` line exists; the first-sync refresh bootstraps the never-connected RELAY transport
  (`takeInConnectBootstrap` cast fails → `transport.bootstrap()`) whose control requests time
  out 3×25 s → `hub.bootstrapFailed` → the "First sync failed" strip — against a direct server
  that is CONNECTED; (d) the user taps the strip's Retry → `hub.retry()` (:690) sees
  `activeKind()==RELAY_ZAI` and runs `connect()` → `cold-start connect begin (kind=RELAY_ZAI)`
  → MockBook becomes the Active endpoint. Net: the §11.6 multi-endpoint feature silently
  discards the user's selection on every cold start where a relay pairing coexists with a
  DIRECT selection — F-P2-A's pass-2/3 pin named the *switchTo* leg (which logs
  `hub.switched`); **this leg logs NOTHING** (no `hub.switched`, no `hub.unpaired` between
  23:44:13 and 23:46:26 in the pulled log — the silent-flip window is empty by grep). Fix
  shape: `persistEndpoint` must not assign `_endpoint.value` when the resolved selection
  differs (guard `descriptor.id == _endpoint.value?.endpointId?.raw || resolve not yet run`),
  and/or the retry path must reconnect the transport the UI shows. Pins: recreate the race in a
  Robolectric harness (seed relay → persist DIRECT → assert `_endpoint` stays DIRECT after the
  pairing edge settles); assert `hub.switched kind=RELAY_ZAI` never logs without a user
  endpoint tap. (The zombie direct socket is the user-visible battery/resource half: a second
  live ws to a desktop the hub believes inactive.)
- [ ] **F-P8-B (P3, app) — switching to an endpoint whose transport is already CONNECTED
  throws `IllegalStateException: connect() is the first-connection path; use recover()
  (state=CONNECTED)`** (`hub.directConnectFailed` WARN, receipted live when I tapped P3Direct
  while its transport held the recovery connection). The switch converges (the transport stays,
  `hub.switched` logs) but the state machine treats an adopted-connection switch as a
  cold-start; fix = `connectDirect()` should call `recover()` (or no-op) when
  `state==CONNECTED` on the same endpoint.
- [ ] **F-P8-C (P2, app) — the conversation timeline cannot be DRAG-SCROLLED at HEAD, on any
  conversation, portrait included; only taps work.** Receipts: five swipe attempts across two
  conversations (armed stack AND strip-free task 3), byte-identical screencaps at +0.2 s/+1 s/
  +6 s (all three 182,225 B), row bounds `[121,1357]` unchanged across every swipe, AX reports
  `conversation_list` scrollable=true bounds `[0,1357][1080,1705]`; meanwhile the load-earlier
  STRIP tap pages fine (320→370), the find-jump repositions, and the CONTEXT SHEET scrolls
  freely — the list plane is alive, drags never reach it. This retro-explains the series'
  history: every successful older-row leg since pass 3 used the strip or the light rig; pass-7
  "scrolled into the queue panel" was this same death. Mechanism open (fix-round item):
  candidates are an unbounded-height measurement of the LazyColumn in the current
  pinned-budget/StripBudget layout chain (which would disable virtualized scrolling while
  AX keeps the semantic) or an upstream gesture claim; needs a Compose test harness hosting
  the live ConversationScreen shape. Blocks the user-row long-press actions leg (Edit /
  Ask in a side session / Share selection — still undriven in the series).
- [ ] **F-P8-D (P3, app) — the queue panel has NO collapse affordance, and its header is
  half-occluded by the sticky load-earlier strip.** Part I's expected remedy for F-P6-B
  ("collapsing the queue panel MUST be verified next pass") does not exist:
  `QueueStrip` (WriteComponents.kt:676+) renders the header row (`Queued (N) · paused` +
  the Auto-send toggleable) then ALL items always-expanded — there is no collapsed state in
  the composable (the vc49 "all panel headers are two-way toggles" contract covered the
  session-bar sheets, not this strip; the desktop's ConversationQueuePanel HAS a collapse).
  Screenshot receipt: the `Queued (2) · paused` text renders top-clipped under the strip
  band. Fix: add the two-way header toggle (desktop parity) + fix the strip/header overlap.
- [ ] **F-P8-E (P2, app) — the stick-to-bottom follow has NO user-scroll release; every row or
  streaming change re-clamps the viewport to the true bottom.** `followBottom` is cleared only
  by the find-jump (ConversationScreen.kt:1045); `LaunchedEffect(followKey)` (:1061-1072)
  re-runs `scrollToItem(bottom)` whenever `rows.size`/last rowId/last-row streaming length
  changes — with `MOCK_RUN_DELTA_MS=7000` (or any real streaming turn) a reader who swipes up
  is yanked back within ≤7 s, and the jump-to-latest FAB (`visible = !followBottom ||
  !atBottom`) can never latch. The unread counter + `atBottom` pieces exist (:1073-1087); the
  missing piece is `followBottom = false` on a user drag leaving `atBottom`. Fix together with
  F-P8-C (the gestures must first reach the list).
- [ ] **F-P8-F (P3, app+rig) — Take photo fires the correct `ACTION_IMAGE_CAPTURE` (with the
  clip URI; `result code=0`) but the API-36 image's camera2 activity denies/never renders
  (`Permission Denial: starting Intent … CaptureActivity` + `mCurrentFocus` stays
  MainActivity) and the app surfaces NOTHING.** Partially rig (the emulator camera is broken),
  but the app-side half is real: an intent-launch failure has no typed honest face anywhere in
  the attachment flow. Sibling of F-P0-C's silent-blank family; hardening row.
- [ ] **Small (P4, app) — the checkpoint empty state says "create one before risky changes"
  with NO create affordance anywhere.** Git sheet → Checkpoints tab renders the honest empty
  text and the Snapshot tab serves `ckpt-1`; the `gitCheckpointService.createCheckpoint` verb
  is unconsumed (pass-5's B1 wording implied a phone create path — there is none). Either wire
  the create verb behind a button or reword the empty state.
- [ ] **Small (P4, app) — "First sync failed" renders alongside CACHED task rows** (the home
  showed the three seeded tasks while the strip claimed the first fetch never completed —
  `lastRefreshAtMs==null` is true whenever THIS process hasn't refreshed, cached projection
  or not). Wording/state nit in the same strip family.
- [ ] **Small (P4, observation) — a one-off terminal-screen route pop**: one tap in the
  terminal content area popped navigation to the conversation (`nav.screen
  route=conversation/{taskId}` at 00:15:52 with no back log); NOT reproduced on the retry
  (the same tap two minutes later stayed on the terminal). Recorded so the next pass watches
  for it rather than chases it.

## K.A3 — re-manifestations and refutations at HEAD (stamps, no row changes)

- **F-P0-B / F-P3-A family**: the hook-review card again renders "2 pending · 3 hooks" +
  "Trust 0 selected hook(s)" with ZERO item rows (shots 02/03/14); the one-line
  `known.permission != null` exemption remains the card-plane blocker.
- **F-P6-A store half — now 4 ghosts across ~26 h**: the pulled DB (`db/p8-2.db`) holds
  unresolved `q-rig-1-mock-p0-gen4-7f8a9b` (pass 0), `q-rig-1-mock-p2-gen3-c6d8e0` AND
  `mock-approval-1-mock-p2-gen3-c6d8e0` (pass 2 — the same 26-hour ghost Part I recorded),
  and this pass's own `mock-approval-1-mock-p8-gen1-k1a2b3` (its remote fuse fired ~50 min
  before the pull) — all DISPLAYED/REQUESTED with live answer buttons in the inbox. Ghost
  accumulation is monotonic across generations; nothing closes them. 65 total interaction rows.
- **N-BM-1**: the strip's total stayed stale across a page load ("showing latest 370 of 339",
  then the prefetch auto-load produced "**420 of 339**" — the §19.2 prefetch itself WORKS,
  receipted en passant).
- **N-BJ-1**: the seeded 1×1 red attachment renders as an unbounded ~300×220dp red block
  (shot 13).
- **F-P1-D**: the open conversation's ~8 s `conversationWorkflowRunsV4`/`conversationPlansV4`
  polling drum re-measured on gen6 (ids 12→21 continuous while backgrounded-idle).
- **F-P2-A selection persistence**: re-stamped (twice this pass) — but see F-P8-A: persistence
  is real; what breaks is the hub's in-memory adoption, which is worse and was invisible to
  the pass-7 test because that test rebooted into a RELAY selection.
- **The lockscreen-approve-over-LIVE-connection leg (pass-7's owed item) — UNDRIVEN, with the
  blockers now fully diagnosed** (all three recorded in §K.A0.3/4): foreground-withhold,
  subscribe-anchored push arming, and the default-off live window. Not an app defect; the leg
  needs the gen-recipe (Live connection ON + conversation open at reconnect + lock inside the
  arming window) written in §K.A0.

## K.B — the REA dig, part 8: the channel method tables (digest unchanged `ec475e6e…`)

Method receipts: asar sha256 re-verified byte-identical at pass start; all extraction shell-side
on the pass-7 extraction (`/tmp/p6-asar/x`, host index.js sha256 prefix `e31718b8fa8a7d3f` —
identical to pass-4/5's); extractor = the pass-5 walk-back (`return i(...),{…}` before each
`create*Service` marker) hardened with a 80 KB look-back window (`/tmp/pass8` inline scripts).
**Attribution caveat**: the walk-back can grab a NEIGHBOR's table when a service's real table
is built dynamically — below, tables whose method names self-identify are marked ✓; misattributed
grabs are marked ⚠ and the service stays open.

### B1. Tables extracted this pass (new beyond pass-5's twelve)

| Service / factory | Table (exact) | Phone today | Verdict |
| --- | --- | --- | --- |
| **ManagedWorktree** ✓ (14) | startNewTask, forkToWorktree, retryCreation, continueWithoutWorktree, dismissCreation, listCreations, onDidChangeCreations, onDidSettleCreation, listWorktrees, removeWorktree, pruneWorktrees, listStartRefs, isWorktreeAvailable, supportsWorktreeCwd | read-only G53 twin only (`getWorkspaceRepositoryInfo → main-tree\|linked-worktree`) + the raw `worktreeCreated` marker face; zero consume of the creation/fork flow | **NEW GAP G94 (P3)** — the desktop's whole worktree-creation/fork plane (start-a-task-in-a-worktree) has no phone counterpart, not even read-only state (listCreations/onDidSettleCreation would be the minimum face) |
| **Feedback** ✓ (15) | create, cancelCreate, list, get, comment, uploadAttachment, uploadAttachmentWithProgress, cancelUpload, onDynamicUploadProgress, uploadAttachmentData, attachLogsFromExport, getDeviceSnapshot, prepareCompactLogArchive, cleanupPreparedLogArchive, revealLogArchive | no feedback surface found in the settings/About walk (Licenses / updates / What's new only) | **NEW GAP G95 (P4)** — closes pass-4's "desktop-side service never matched" flag: the desktop runs a full support-intake plane INCLUDING device-snapshot + log-archive attach; the phone's diagnostics export could feed it |
| **ZCodeSession** ✓ (16) | initializeWorkspace, getWorkspaceRuntimeIdentity, readWorkspacePresentation, createSession, resumeSession, listSessions, readSession, readSessionMessages, readSessionEvents, promoteDeferredDraftSession, closeSession, closeDeferredDraftSession, setModel, setThoughtLevel, setMode, respondProviderRuntimeHeaders | consumes setModel/setThoughtLevel/setMode + presentation; **`readSessionMessages` is ALREADY consumed** (ConversationPort.kt:318 — find-in-conversation + TrajectorySheet ride it) | two notes: **(a) F-P3-B's fix surface EXISTS** — the openRun transcript hydrate can call the already-wired `readSessionMessages`; (b) `promoteDeferredDraftSession`/`closeDeferredDraftSession` are the desktop-side twin of the phone's local draft plane — consume/no-action decision queued |
| **the real remote task service** ✓ (14, extracted at the `createAttachmentService` marker — the bundler inlined the task controller beneath it) | listTaskList, previewArchivedTaskDeletion, permanentlyDeleteArchivedTask, getTaskMeta, deleteArchivedTasks, deleteArchivedTask, mutateTask, subscribeControllerV4, resyncControllerV4, unsubscribeControllerV4, onDynamicControllerFrame, onDynamicTaskUnreadSignal, onDynamicTasksRemovedSignal, dispose | the phone's task plane rides listGroupedTaskViewStructure + archiveTask; unread tracking is phone-local (pass-2's #6) | the **archived-task DELETE verbs** (preview + permanent) and the **controller V4 subscribe family** are the protocol-exact spec behind the phone's auto-archive plane (G39) and the unread-badge small — inventory row |
| **Bots** ✓ (34) | syncAppRuntimePreferences, getStatus, getConfig, listWorkspaceRefs, getUserConfigOptions, beginFeishuRegistration, pollFeishuRegistration, beginWeixinRegistration, pollWeixinRegistration, saveConfig, listBots, saveBot, removeBotSecret, deleteBot, testBot, createBindCode, getGroupMemberNames, validateTopicResource, readTopicResource, getTopicDetails, retryTopicPreparation, replyToChannel, getBotStates, reconcileGroupResult, resendGroupResult, setGroupEnabled, retryPrivateDelivery, resetBotState, watchAutomationRun, handleInboundMessage, handleProviderCallback, handleProviderCallbackResponse, disposeAll, disposeAllAndWait | read-only listBots viewer (2 rows) | depth note only — the bots plane is one of the desktop's largest (feishu/weixin registration flows, topic resources, delivery retry machinery); phone stays read-only by the ADR boundary |
| **Git** ✓ (17) | invalidate, resolveRepository, getWorkspaceRepositoryInfo, getStatus, getIgnoredPaths, listLocalBranches, getCommitGraph, getDiff, getBranchComparison, switchBranch, createBranchAndSwitch, stage, unstage, discard, commit, push, getIdentity | ~9 read verbs + the vc33 bridge pair | the WRITE half (stage/unstage/discard/commit/push) is remote-servable and phone-absent BY DESIGN (read-only git posture); recorded so a future "commit from the phone" ask starts from this exact surface |
| sync family ✓ ×4 (SettingsSync / McpSync / PluginSync / SkillSync / UserConfigSync — 5 each) | listSyncItems, getSyncContentHashes, checkSyncWriteAccess, exportSyncItems, importSyncItems | 0 | NO-ACTION (desktop-credential/sync machinery; the pass-2 no-port list holds) — now with exact tables |
| **AppControl** (5) | createSession, sendToSession, interruptSession, renameSession, forkSession | 0 | desktop-internal session-control plane (the CUA helper's driver) — NO-ACTION family, recorded so it is never "ported" |
| **CuaPipSession** ✓ (4) | resolveMcpServers, restart, restartAfterPermissionGrant, reconcileRecoveredHelper | 0 (the phone has the typed CUA permission card, G64) | the PiP helper lifecycle — NO-ACTION |
| getSnapshot twins | DeviceSyncState.getSnapshot (1 — the V2 device-sync snapshot getter), ClientConfig.getSnapshot (1), Skills.getSnapshot (1) | 0 / 0 / skills listBots-style viewer | G83's one-verb pattern is a FAMILY; DeviceSyncState.getSnapshot is the read twin of the G93 V2 plane |
| WindowProjectChanges (2): draw, healthy | ⚠ possible neighbor grab | 0 | unresolved — tiny either way |
| FileWatcher / OAuth (4 each): watch, unwatch, disposeAll, onDynamicChange | ✓ shape | 0 | NO-ACTION (file watcher is desktop-internal; OAuth is credential machinery) |
| MediaPreview (5): allow, allows, allowsUrl, accept, readMedia | ⚠ | partial (readMediaPreview per pass-5) | unresolved attribution |
| ModelSelection / ProviderSettings / SourceServerWorkspace / ZCodeTask / ConversationShare / CuaPermission / WindowController | NO TABLE via this anchor | — | remain open (tables built dynamically / channel-name-registered); pass-5's mock-seam matrix stays the contract source for those |

### B2. G93 residual — narrowed

The mobile-push dispatcher keys are re-verified and NARROWED: payload = `{event_id, session_id,
run_id, kind, state}` (+ `workspace_id:session_id` runs-registry key, cap 4096;
`desktop_platform` stamped from `process.platform`; eligibility gates in order
`eligibility_unavailable → run_owner_mismatch → duplicate → transport_unavailable`; the report
endpoint POSTs `/api/v1/remote/notification/events` with a Bearer JWT from the device-token
exchange). The `kind`/`state` ENUM VALUES are produced in the agent process (zcode.cjs), not in
any app bundle — the residual stands but is now scoped to one producer file family.

### B3. Renderer route inventory — closed as a dead angle

The desktop renderer has NO URL-route vocabulary to census: 2,862 lazy chunks, scene/state-driven
navigation (no `path:"/…"` table, no sceneId strings). The phone-vs-desktop surface parity stays
anchored on the service/channel tables (B1) — recorded so no future pass burns time hunting for
a route manifest that does not exist.

### B4. Census corrections this pass makes to earlier rows

- Pass-4's G.B1 "Feedback — desktop-side service never matched" → **matched** (G95 above).
- Pass-5's B1 "gitCheckpointService: create + the rewind command plane" → the phone has **NO
  create surface** (K.A2 small); the verb is unconsumed.
- Pass-2's #9 (zcodeSessionService.createSession "workspace lifecycle") → the full 16-method
  table confirms it and adds the draft-promotion pair.

## K.C — where the phone is BETTER than the desktop (new receipts this pass)

- **The print plane completes** — 28 rendered pages → a real PDF on disk in three taps; the
  desktop census has no print-to-file face to compare (its share plane is link/import-centric,
  G86).
- **The Sketch canvas → uploaded PNG in one flow** (draw → Done → begin/chunk/commit, 6.3 KB
  chip) — the desktop's sketch entry was never census'd as an end-to-end wire flow.
- **The Live-connection FGS** gives the user a visible, tappable socket-lifetime control
  ("Live connection active — tap to disconnect") — the desktop has no equivalent
  connection-lifetime indicator in the census.
- The task-row long-press menu (Pin/unread/Move/Rename/Archive/Delete with a confirm gate) —
  desktop task rows carry no such local management menu in the census.
- 0 FATAL / 0 ANR across six hostile relay generations and ~15 reconnects.

## K.D — fix-order recommendation (supersedes J.4)

1. **F-P8-A** — the silent endpoint abandonment (the deferred relay `persistEndpoint` race);
   this is the true root of the F-P2 class and it poisons every cold start for multi-endpoint
   users. Pins: the Robolectric race harness + the "no hub.switched without a user tap" assert.
2. **F-P0-B** (unchanged — the `known.permission != null` exemption; 6th stamp this pass; also
   F-P6-A's card half).
3. **F-P8-C + F-P8-E together** — the frozen timeline: first restore drag-scroll (mechanism
   hunt in a live-shape Compose harness), then wire the scroll→`followBottom=false` release +
   a latched jump-to-latest. Blocks the user-row actions leg.
4. **F-P6-A store half** — the ghost sweep (4 ghosts, 26 h, monotonic; fixtures in
   `/tmp/pass8/db/p8-2.db`).
5. **F-P8-D** — the queue-panel collapse affordance + the strip/header overlap.
6. **F-P3-B** — the openRun hydrate; the verb (`readSessionMessages`) is already consumed
   elsewhere in the same app (K.B1) — the diff is wiring only.
7. **F-P7-A/B** (unchanged) → **F-P8-B** (the switch-on-connected recover) → **F-P8-F** (the
   intent-launch face) → the three K.A2 smalls.
8. **Parity queue additions**: **G94** (worktree plane — inventory the listCreations/
   onDidSettleCreation shapes first) and **G95** (feedback plane — the phone's diagnostics
   export could ride attachLogsFromExport) join G89/G90/G91/G92; the archived-task delete
   verbs + controller-V4 signals inventory row; the draft-promotion consume/no-action
   decision.

### Coverage honesty (what pass 8 did NOT drive)

The lockscreen-approve-over-LIVE-connection leg (blockers diagnosed, recipe in §K.A0 — third
pass owing it), the user-row long-press ACTIONS (blocked by F-P8-C's frozen timeline), the
question card's options once the F-P3-A body fix lands, MOCK_REMOTE_DOWN_MS and MOCK_MD_FILE
(scripted but never armed — gen2's append-after-exec bug; both arms remain undriven in the
series), the Sessions/Skills/Plugins/Agents tabs of the mention panel (Files only), the Take
photo happy path (impossible on this image), the widget add-to-home (standing stop), and
Themes/locale (receipts stand from passes 4-5).

## Pass-8 verification stamp

Pass 8 completed 2026-11-14 ~01:20; SIX relay generations (gen1-gen6, nonces k1a2b3 → k6l7m8)
+ the inherited DIRECT mock (:18200, restarted once after the stale-server bootstrap timeouts);
15 screenshots `/tmp/pass8/shots/`; DB pulls `/tmp/pass8/db/p8-{1,2}.db(+wal)` (the ghost
fixtures); relay logs `/tmp/pass8/relay-gen{1..6}.log`; full-session logcat
`/tmp/pass8/logcat-gen1.log`; the print PDF receipt `/tmp/pass8/receipts-synthetic-task.pdf`;
REA extraction `/tmp/p6-asar/x` (host `e31718b8…`, desktop asar `ec475e6e…` re-verified).
APK unchanged and re-verified byte-identical at pass start (md5 `530ba4ff…`, vc53/1.3.2). Rig
left: emulator up, gen6 relay on :18100, the DIRECT mock on :18200, Live connection restored
OFF, the app paired with both endpoints and connected to the relay. Nothing pushed.

# Part L — pass 9 (APPENDED 2026-11-14, ~03:00): the silent AUTHENTICATING wedge, the un-rendering typed face, and the runtimeCapabilities/web-remote censuses

> **Pass 9 of the pass-0 series** (solo; ran on the canonical discovery prompt after the
> ledger audit). Two halves again: (1) the live rig — NINE relay generations (gen1 push-45s
> aborted, gen2 push-150s missed, gen3–gen4 lockscreen legs, gen5–gen7 shade-approve
> attempts, gen8 remote-down+md-file, gen9 md-file isolated) plus the PIN'd keyguard —
> which produced TWO new P2 app defects (F-P9-A the permanent silent AUTHENTICATING stall;
> F-P9-B the typed disconnected-remote face that never renders live) and CLOSED the
> 3-pass-owed lockscreen leg at its core; (2) the REA dig part 9 — desktop digest re-verified
> byte-identical at STEP 0 (`ec475e6e…`, extraction `/tmp/p6-asar/x` reused, host
> `e31718b8…`), backlog items 1/4/5 executed: the 13-name runtimeCapabilities vocabulary
> traced bit-by-bit, the G93 mobile-push enum residual CLOSED (producer found, values exact),
> and the desktop's whole web-remote-control companion plane extracted (90 i18n keys).
>
> **Verification stamp:** 2026-11-14 01:45 → 03:00, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed.

## L.A0 — rig learnings (each bites the next pass)

1. **The interaction-push arm is SUBSCRIBE-anchored, not relay-start-anchored** —
   `armInteractionPush` (MockZCode.kt:2061-2080) starts its `delay(ms)` at the FIRST
   conversation subscribe after relay start. Pass-8's §K.A0.4 recipe ("fire−lock ≥ ~10 s",
   computed from relay start) is WRONG on the anchor; compute the fire from the subscribe
   line (`recovery.subscribed` in logcat). Also: a RESUME that re-attaches without a fresh
   `recovery.subscribed` still arms (gen4 fired at resume+60 s with no subscribed line).
2. **The app cancels its own shade notifications on resume** — `app.lifecycle onStart`
   triggers the foreground cleanup; 5 removals receipted at 02:22:21 right after the
   screen wake resumed the activity (gen5's fresh 02:30:31 post was cleaned the same way).
   Any shade-approve leg must NOT let the app resume before the tap.
3. **Notification action buttons are suppressed under the keyguard** (Android 16) and
   **injected taps on lockscreen notification rows never deep-link** (pass-6's
   shade-group-undrivable family, extended). The lockscreen shade-approve route is a
   STANDING RIG STOP — it needs a real device (or UiAutomator-level gestures).
4. **`wm dismiss-keyguard` is the reliable dismiss**; with a PIN set it bounces the PIN
   pad, and the PIN entry must run as ONE atomic adb burst (wake → dismiss → 4 digit
   taps → Enter): the display sleeps between separate tool calls despite
   `screen_off_timeout=600000`. AVD now has PIN **1234** (set this pass — the F-37-B
   no-auth posture is henceforth testable against a real gate).
5. **`svc power stayon true` is ineffective on this API-36 image; use**
   `settings put global stay_on_while_plugged_in 7` **(verified: 1.09 MB captures
   afterwards vs 15 KB black frames before).**
6. **The screen-state ARB list**: `dumpsys power mWakefulness` (Asleep/Dozing/Awake),
   `dumpsys trust deviceLocked`, `dumpsys window mCurrentFocus` — screenshot size is the
   fastest proxy (~15 KB = FLAG_SECURE-black or display-off; ~1 MB = real render).
7. **A home list re-layout shifts task rows between navigations** (the row moved from
   [234,752] to [306,1511] to [234,1127] across three dumps) — re-dump bounds before
   every row tap; the stale tap opened synth-task-2 once (receipted, harmless).
8. **The BACK key from a conversation sometimes lands on the conversation again** (the
   mention panel consumed two backs); verify the screen after each back — the "Settings"
   dumps taken at 02:14 were actually HOME dumps from a missed gear tap.

## L.A1 — verified working this pass (receipts; bold = never-driven-in-series)

| Surface | Pass-9 receipt |
| --- | --- |
| **Push lands WHILE LOCKED over the LIVE connection (the 3-pass-owed leg, CORE)** | gen3: device Asleep 02:18:25→02:22+, heartbeats every 10 s through the lock (FGS `isForeground=true`), patch `rpc.recv bytes=4167` at **02:21:14.459** → `gateway.frame ONLINE` → `manager.deltas ops=1` → **`notify.show :: key=interaction:mock-approva channel=permissions` at 02:21:14.602** — the notification posted to the shade while the device was locked. Repeated gen6: `notify.show` at **02:33:47** while Asleep, 5 active notifications. The lockscreen itself rendered the approval group + question (shot 09) |
| **The Live-connection FGS holds the socket through screen-off** | K.A0.3's dead-socket posture did NOT recur with Live ON: pair_status_query/ack every 10 s across the whole locked window; the FGS receipt "Connected to MockBook · Live connection active — tap to disconnect" visible in the shade (shot 06) |
| **The background recovery ladder works from RECOVERING** | relay restarts at 02:29:16, 02:32:17, 02:41:37 (behind the keyguard, app stopped): `RECONNECTING → RECOVERING → CONNECTED` in ≤0.4 s each, bootstrapApplied after — the ladder is healthy for a transport that reached CONNECTED before the kill |
| **PIN unlock flow (new rig capability)** | `locksettings set-pin 1234` → `wm dismiss-keyguard` bounces the PIN pad → atomic digit burst unlocks (deviceLocked=0, MainActivity focused) — the F-37-B auth-gate posture is now drivable against a REAL keyguard |
| **MOCK_MD_FILE arm (first series drive, isolated gen9)** | env verified on the process (`ps -E`), the demo assistant row served the file VERBATIM: DB row `### Rig markdown probe (pass 9)…` with the full bold/list/table/blockquote payload (1 row in the pulled DB); stored after `recovery.subscribed` → `snapshotReconcile rows=53`. The RENDER half is blocked by F-P6-B (below) |
| **MOCK_REMOTE_DOWN_MS arm fired (see F-P9-B for the defect half)** | `relay.reconnectFailed :: workspace-bridge-error: remote-disconnected (errorText=The remote workspace is not connected. Send /reconnect first, then try again.)` across relay-23/24/25 ladders at 02:45:14–21 and again at 02:49:07 on relay-30 — the typed error travels end-to-end |
| **Mention-panel tabs Sessions/Skills/Plugins/Agents (first series drive — coverage item CLOSED)** | All four render real rows: Sessions → `Synthetic task / synth-task-1`; Skills → `release / Cut a release: bump version, changelog, tag, build artifact.`; Plugins → `prisma-toolkit / Schema-first database workflows and migrations.`; Agents → `Explore / Read-only search agent` (shots 16/17×3). Files was receipted pass 8 — all five tabs now live-verified |
| ConnectionCenter phase legend (first full read) | Not paired / Connecting (validating, proving identity, opening bridge) / Connected / Reconnecting (retrying with backoff, cached labeled) / Synchronizing / Connection failed / Closed — the complete phase vocabulary on one screen (dump d40) |
| Robustness | 0 FATAL / 0 ANR app-side across 9 relay generations, 2 System-UI ANR dialogs (rig-side, dismissed), ~20 reconnects, the PIN/lockscreen/shade churn, and the F-P9-A stall window |

## L.A2 — defects found this pass (NEW ledger rows)

- [ ] **F-P9-A (P2, app) — after the paired relay dies MID-AUTHENTICATION, the transport
  wedges in AUTHENTICATING permanently: zero sockets, zero reconnect attempts, zero logs,
  and no user-visible recovery except switching endpoints.** Receipt: the transport logged
  `recovery.deferredAttach :: transport=AUTHENTICATING` at 02:07:47 against a relay that
  had just been killed; for **8 minutes 6 seconds** (02:07:47→02:15:53) with the app
  FOREGROUND, unlocked, on the conversation screen: no `hub.connect`, no ladder lines, no
  timeouts — `netstat` on the device showed ZERO sockets to :18100, the relay saw ZERO
  connections, the header read "Connecting…" forever, and home rendered NO retry face
  (the cached rows suppress the "First sync failed" strip — K-small-2's wording nit has
  this as its real teeth). The user-visible escapes all failed: resume/unlock (nothing),
  session-bar tap (chip toggle only), active-endpoint row tap (silent no-op). The ONE
  working escape: select the OTHER endpoint (P3Direct → `hub.phase RELAY_ZAI → UNPAIRED`
  + honest `direct.candidateFailed :18200` since the direct mock was down) then switch
  back → fresh `hub.connect` → connected in 2 s. Contrast receipted 4×: a transport that
  was in RECOVERING at kill time ladders back in ≤0.4 s on resume (02:24:50, 02:29:16,
  02:32:17, 02:41:37) — **the wedge is specific to dying while AUTHENTICATING (the
  handshake state has no watchdog and the ladder never arms from it)**. Fix shape: an
  AUTHENTICATING watchdog (timeout → RECOVERING) + a visible face when the ladder is
  stuck; pins: Robolectric harness (kill the server mid-handshake → assert a reconnect
  attempt within N seconds) + "no silent state without a log line" assert. This is the
  F-P8-A family's second mechanism (that row's silent flip; this one's silent stall).
- [ ] **F-P9-B (P2, app) — the typed disconnected-remote face does NOT manifest on the
  live load path: the G67 Reconnect card never renders, so the recovery verb is
  UI-unreachable and the user has no remedy while the remote is down.** Receipt: with
  `MOCK_REMOTE_DOWN_MS=20000` armed (gen8), every bridge-open failed with the typed error
  (verbatim in L.A1) and the HUB logged it (`hub.connectFailed :: SESSION
  workspace-bridge-error: remote-disconnected`, 02:49:07 on relay-30); yet the
  ConnectionCenter opened 40 s later shows the GENERIC "Still offline — showing cached
  state" face — the `connection_remote_reconnect` card (render condition
  `lastError.messageKey == REMOTE_WORKSPACE_DISCONNECTED`, ConnectionCenterScreen.kt:296)
  is ABSENT from a fresh dump. Downstream: the arm only flips on
  `workspace-reconnect-request`, which only the (unrendered) card sends → the phone
  ladders into the same typed error forever; the conversation row snapshot was never
  re-fetched under the arm (the MD_FILE row only landed on gen9, arm absent). The vc52
  receipt + the transport pin (`RelayTransportRemoteWorkspaceReconnectTest`) pass —
  **the mapped messageKey is lost between the transport error object and
  `hub.lastError` on the live path** (the mapping exists: ErrorMapping.kt:334). F-P5-A
  family (pinned faces don't manifest live). Fix shape: trace the hub's
  connectFailed→lastError assignment for the SESSION-scope bridge error; pin: live-shape
  harness asserting the card's testTag renders with the arm armed end-to-end.

## L.A3 — re-manifestations at HEAD (stamps only)

- **F-P0-B (8th) / F-P3-A (8th)**: the hook-review card again "2 pending · 3 hooks" with
  ZERO item rows, and the question card's body crushed to a "Send answe/rs" wrap inside
  its clipped button box (shots 14/15 — both cards in one frame with the armed stack).
- **F-P6-B (2nd)**: with the armed stack the timeline renders ZERO rows (the whole
  viewport is cards+queue+strips); this blocked the MD_FILE render half.
- **F-P8-D (2nd)**: the queue panel again always-expanded (2 rows + Auto-send), no
  collapse affordance; the header clip half was NOT re-receipted (it rendered fully at
  this scroll).
- **F-P1-D (3rd)**: the ~8 s `conversationWorkflowRunsV4` drum continued WHILE ASLEEP
  (02:21:11→02:21:51, ids continuous during the lock) — the poll runs behind the
  lockscreen.
- **F-P6-A (3rd)**: ghost census after gen9: **5 unresolved of 83 rows** — the pass-0
  `q-rig-1-mock-p0-gen4` (REQUESTED, 2026-11-12 19:59), BOTH pass-2 ghosts now present
  (`mock-approval-1-mock-p2-gen3` 22:23 AND `q-rig-1-mock-p2-gen3` 22:14 — the question
  was never counted before), plus gen9's fresh seeded pair (DISPLAYED). Oldest is 2+ days.
  One EXPIRED row proves the auto-resolution machinery exists but rarely fires.
- **F-P1-B (4th)**: gen4's pushed interaction (`mock-approval-push-1-mock-p9-gen4`,
  created 02:25:50) was STALEd by the 02:26:02 snapshot sweep 12 s after arrival — the
  next snapshot legitimately swept it; the push id stayed pending-forever for gen3's
  (RESOLVED only by the resume sweep).
- **K-small-2 (2nd)**: "Connection lost — showing cached tasks / Reconnecting to
  MockBook… / Retry" rendered alongside cached rows during the gen8 arm — and the
  wording nit now has F-P9-A as its structural teeth (no strip at all when the wedge
  suppresses the refresh).

## L.B — the REA dig, part 9 (digest unchanged `ec475e6e…`)

### B1. Backlog item 4 — the runtimeCapabilities/clientHello bits: DONE (all 10 traced)

The vocabulary is a 13-name schema (renderer `validation-BcSOL2CC.js`; host probe helpers
with WeakMap caches; the phone source contains ZERO `runtimeCapabilities` handling and
ZERO `genUiContext` — **the phone never answers the host's probes, so every check falls
to its `.catch(()=>false)` degradation**):

| Bit | Desktop gate (exact) | Phone verdict |
| --- | --- | --- |
| highspeedCardOwner | strips `highspeedMeta.ownerKey` from envelopes relayed to clients that don't declare it (host index.js:692994); renderer builds `kind:"accelerated"` cards → `highspeedMeta{schemaVersion:1,card}` + modelExecution (styles chunk:3030094) | NO-ACTION — accelerated-card plane absent; graceful strip |
| sessionLifecycleWorkspace | **schema-only desktop-wide** (validation copies; zero feature consumers in any bundle) | nothing to consume — DECLARED-RESERVED |
| sessionDirectorySource | host sends `hostCapabilities:{sessionDirectorySource: !!resolveSessionDirectories}` to the agent at read-only-client startup; agent-side session-directory resolution | desktop-agent plane; phone-irrelevant |
| sessionWorkspaceRouting | host→agent probe; unsupported + shared runtime → hard error "This Agent version does not support shared task workspace routing" (host:646076) | phone-irrelevant |
| independentPlanState | host hello declares true; agent check gates planEnabled envelopes → `proto.independentPlanUnsupported` (host:692459) | phone DECODES the bit (V4Wire.kt:36/60) but never gates on it — decode-only note |
| residentRowsRange | host hello declares true; agent must support for `getResidentRowsClient` else `sessionNotResidentError` — "session is not resident: <id> (proto.sessionNotResident)" (host:601104) | ranged-row fetch plane; phone consumes nothing; N-BM-1 adjacency (a ranged fetch would replace the stale-total prefetch) |
| worktreeCwd | agent capability set → `agent.supportsWorktreeCwd` → **ManagedWorktreeService.supports** (host:160374, localhost + no workspaceIdentity only) | G94 adjacency — the worktree plane is additionally agent-gated |
| extensionInputSource | agent set → `supportsExtensionInputSource` — **zero consumers desktop-wide** | reserved |
| conversationQuoteComment | agent set → renderer `[selection-comment] 读取批注能力失败，隐藏批注入口` — the conversation selection→annotation entry, self-hiding when absent (styles chunk:3422272) | **NEW G96 (P4)** — the annotate/批注 plane is desktop-only |
| pluginExtensionSummary | `getPluginsOverview` adds `includeExtensionSummary:true` when the agent declares (host:671837) | depth note under G91 |

### B2. Backlog item 5 — G93's enum residual CLOSED (and pass-8's producer attribution corrected)

The `kind`/`state` values are NOT in zcode.cjs (0 hits in the bundled CLI — pass-8's
"producer: agent process" line was wrong). They live in the HOST bundle:
`chunk-7SAPNXNZ.js` → `collectMobilePushEvents` + the strict event schema:

- **kind ∈ {question, approval, plan, task_completed, task_failed}** — pendingInteraction
  mapping: `kind==="permission" ? "approval" : toolName==="ExitPlanMode" ? "plan" :
  "question"`; phase transitions while running/prewarming: completedSuccess→task_completed,
  error→task_failed.
- **state ∈ {open, resolved}** — emitted only when the pending interaction CHANGED between
  snapshots (previous → resolved, new → open).
- Payload: `{version:1, locale:"zh-CN"|"en-US"?, session_title≤512?, desktop_platform:
  "windows"|"macos"|"linux"?, event_id, kind, workspace_id, session_id, run_id,
  interaction_id?, state, occurred_at}`; event_id formats
  `${interactionId}:${counter}` (interactions) and `${sessionId}:${lastActivityAt}:${kind}`
  (phase transitions); session_title sliced to 256 code points; events skipped with
  reason `run_not_observed` when no run was observed. G93's consume decision now has the
  complete wire contract.

### B3. Backlog item 1 — the desktop Mobile-Remote UI/UX audit: DONE (first full pass)

The plane is **`webRemoteControl.*`** — 90 unique i18n keys (zh-CN+en-US in
IntlProvider-C435ESD3.js) + the `WebRemoteControlDialog` in the styles chunk
(`data-testid: web-remote-control-connection-card` / `-pull-mobile-logs`). Key
structural facts:

- **The companion is a desktop-served BROWSER page, not a deep link**: the QR encodes
  `connectUrl` (a relay URL); "扫码或在手机上打开链接，即可远程控制当前工作区" — a phone
  browser opens the desktop's UI. This is a THIRD mobile-access plane beside the native
  Companion app (V1 relay) and the Bot channels.
- Session lifecycle faces: idle/starting/connecting/running/active/error +
  statusDetail copy per phase; stop (with failed/success toasts); refresh-Qr with a
  CONFIRM dialog (invalidates old links); copyLink fallback ("无法扫码？").
- Failure taxonomy (typed, user-facing): kicked ("relay 已踢出这次配对"), sessionConflict
  (one page per pairing — `singlePageNote`), sessionExpired, sessionNotFound,
  relayUnavailable, desktopDisconnected, invalidMobileConnection, connectionRecoveryTimeout,
  desktopBootstrapTimeout, unsupportedAction, workspaceClosed, unexpectedError.
- Workspace/task switching in the companion (switchWorkspace / noSwitchableWorkspaces /
  noTasks / openTask / taskStatus ×4) + a mobileShell (backHome/chatTitle/reconnecting)
  + a theme menu.
- **Bot channels as long-duration access**: feishu/lark/weixin/telegram entries
  ("适合更长时间的移动端访问") — desktop-side only.
- **Auth gate**: "登录后即可使用手机远程控制。手机和电脑需登录同一账号。"
- `pullMobileLogs` (`web-remote-control-pull-mobile-logs` testid, gated on
  `mobileConnected`) — the desktop pulls the connected mobile PAGE's logs: web-companion
  diagnostics, no phone reach-in.
- `settings.mobilePush` (+Description): the desktop toggle that pushes task
  completion/failure/approvals to the logged-in ZCode App when away — the G93 push
  plane's desktop-side control face.

**NEW G97 (P3) — the phone is blind to this whole plane**: no face for "a browser
session is controlling this workspace" (no single-page conflict guard, no kicked-session
notice, no companion-state mirror), and the bot-channel access alternative is
undocumented phone-side. Consume minimum = honest surfaces, not the plane itself.

### B4. Census corrections this pass makes to earlier rows

- Pass-8 §K.B2's "kind/state enums are produced in the agent process (zcode.cjs)" →
  WRONG; they are in the host bundle (B2 above). The G93 narrowed residual is now CLOSED
  (the ledger row's residual sentence should be read as resolved).
- Pass-8 §K.A0.4's push-arm anchor recipe → corrected in §L.A0.1 (subscribe-anchored).
- The relay.sh launcher in /tmp/p0 reads `MOCK_GEN_ID` (the wrong name) and would pass a
  STALE default nonce — this pass used direct `MOCK_GEN_NONCE` launchers (/tmp/p9/relay-g*.sh);
  any future pass must do the same.

## L.C — where the phone is BETTER (new receipts this pass)

- The push-while-locked pipeline over the user's own FGS toggle (socket held, patch
  applied, shade post — all while Asleep) has no desktop-side equivalent to compare; the
  desktop's companion relies on the browser page being open.
- The typed ladder diversity: the phone's recovery ladder distinguisher (RECOVERING vs
  AUTHENTICATING) recovered 4/4 times when given a recoverable state — the failure is
  only the unreachable wedge (F-P9-A), not the ladder design.

## L.D — fix-order update (amends the consolidated order)

1. F-P8-A → 2. F-P0-B → 3. **F-P9-A** (the wedge strands every multi-endpoint reconnect;
   unblocks the endpoint-switch test legs) → 4. F-P8-C + F-P8-E → 5. **F-P9-B** (the
   un-rendering typed face; unblocks the remote-down recovery leg) → 6. F-P6-A store half
   → 7. F-P8-D → 8. F-P3-A → 9. F-P3-B → … (rest unchanged; smalls and parity queue as
   listed in the ledger).

### Coverage honesty (what pass 9 did NOT drive)

The shade-ACTION approve (standing rig stop — three diagnosed blockers in §L.A0.3/2),
the G67 card's live render (driven — negative; that IS F-P9-B), the MD_FILE render on
screen (blocked by F-P6-B), the user-row long-press ACTIONS (still blocked by F-P8-C),
the question card's options (blocked by F-P3-A), Take-photo happy path and widget
add-to-home (standing stops), backlog item 2's remaining channel tables, backlog item 7
(phone self-audit expansion), and the vc52-era arms (MOCK_PAIR_STATUS_PENDING_MS /
MOCK_CUA_PERMISSION / MOCK_GIT_REFUSALS) which sit outside the pass-0 backlog.

## Pass-9 verification stamp

Pass 9 completed 2026-11-14 ~03:00; NINE relay generations (gen1 `mock-p9-gen1-g1h2i3`
aborted pre-subscribe, gen2 `…g2j3k4` missed window, gen3 `…g3k4l5` lockscreen CORE,
gen4 `…g4l5m6` arm-timing probe, gen5 `…g5m6n7` shade attempt, gen6 `…g6n7o8` locked
fire, gen7 `…g7o8p9` PIN setup, gen8 `…g8p9q0` remote-down+md-file, gen9 `…g9q0r1`
md-file isolated) — every receipt nonce-anchored in the pulled DBs. 21 screenshots
`/tmp/p9/shots/`; DB pulls `/tmp/p9/db/p9-{1..4}.db(+wal)` (ghost fixtures); relay logs
`/tmp/p9/relay-gen{1..9}.log`; full logcat `/tmp/p9/logcat.log`; launchers
`/tmp/p9/relay-g{1..9}.sh` + the md probe `/tmp/p9/md-test.md`; REA extraction reused
`/tmp/p6-asar/x` (desktop asar `ec475e6e…` re-verified at STEP 0, host `e31718b8…`).
APK byte-identical to the whole series (sha256 `ad449d7d…`, md5 `530ba4ff…`, vc53/1.3.2;
re-verified at the pass-9 build). Rig left: emulator zcode-a16 UP (PIN **1234** set,
keyguard ON, stay-on 7 + screen_off_timeout 600000 set, **Live connection left ON** —
the one untoggled cleanup, one tap in Settings→Notifications), ALL relays killed (zero
zombies for the first time in the campaign), the app's connection stale (relay dead — it
will ladder per the healthy path or wedge per F-P9-A). Nothing pushed.

---

# Part M — pass 10 (APPENDED 2026-11-14, morning): the fresh-install pairing chain, the diagnostics plane, the never-swept-screen a11y audit, and the channel-table dig completing backlog items 2/3/7

> **Pass 10 of the pass-0 series** (solo, canonical discovery prompt). Scope: the three
> open backlog items — item 2's remaining channel method tables (REA), item 3's i18n
> namespace diff (REA), item 7's phone self-audit expansion (LIVE) — plus STEP 2
> coverage adjudication. The rig fought back: an uncleanly-shutdown AVD wedged the
> package manager (every launch returned CLASS_NOT_FOUND despite a healthy resolver
> table — recovered only by wipe-data), the fresh install re-paired through the FULL
> onboarding chain (a first-in-series receipt), and the first-boot ANR storm re-manifested
> F-P0-E. Two new rows (F-P10-A the silent commit no-op; N-BN-1 the unlabeled Plugins
> switches), four new G census rows (G98 ssh/wsl/docker source servers, G99 whiteboard,
> G100 model-trajectory, G101 CUA permission status), F-P8-C drag-scroll RE-VERIFIED
> working at HEAD, F-P9-A wedge re-manifested, and the REA dig completed the last two
> open backlog items.
>
> **Verification stamp:** 2026-11-14 03:30 → 05:30, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed.

## M.A0 — rig learnings (each bites the next pass)

1. **An uncleanly-shutdown AVD can wedge the PACKAGE MANAGER: every launch of the
   reinstalled APK returned `result code=-92` (CLASS_NOT_FOUND) while `pm dump` still
   printed a healthy resolver table with both launcher filters.** `adb reboot` did NOT
   clear it; `pm clear` did NOT; uninstall+reinstall did NOT. `dumpsys package` showed
   installed=true/enabled=0/suspended=false and the APK file present at codePath — the
   wedge was invisible to every dump. Recovery = **wipe-data boot** (`-wipe-data`), which
   destroys pairing/settings (all rig-rebuildable). Diagnosis order for "activity does not
   exist" that shouldn't be: compare `dumpsys package`'s resolver table vs
   `cmd package query-activities` — if the table HAS the entries but queries find nothing,
   it is this wedge; go straight to wipe-data, do not chase visibility/appops.
2. **The wipe-data FIRST BOOT is an ANR storm**: the ANR record captured
   `com.google.android.gms` + `com.android.phone` "failed to complete startup" ANRs, a
   `half_watchdog "Blocked in handler on main thread (main) for 15s"`, AND the app's own
   input-dispatch ANR (F-P0-E stamp 2). The app's main thread recovered (debuggerd showed
   Looper idle); the system dialog lagged minutes behind. Budget: dismiss Wait, then
   force-stop + relaunch before any leg.
3. **Wipe-data resets the power settings AND the network posture**: `stay_on_while_plugged_in`
   and `screen_off_timeout` reverted (screen sleeps mid-leg → 15 KB black frames that look
   exactly like FLAG_SECURE black — check `dumpsys SurfaceFlinger` for the `(Secure)` marker
   to tell them apart: display-off black has NO marker), wifi re-enabled (dual-home),
   and later **BOTH eth0 and wlan0 went link-DOWN** (`Network is unreachable` to 10.0.2.2;
   auth ack timeouts that look like the stale-mock trap) — recovery = FULL emulator
   restart (no wipe needed; pairing data survives). Always re-apply
   `stay_on_while_plugged_in 7` + `screen_off_timeout 600000` + `svc wifi disable` after
   any wipe/reboot.
4. **The compositor itself wedged once**: mWakefulness=Awake, no Secure layers, yet every
   screencap was 15 KB black — `HWC missed frame count: 7759`. `input keyevent 26` toggle
   did NOT fix it; an emulator restart did. If screencap is black while SurfaceFlinger
   shows no Secure layers and the display is ON, suspect the compositor, not the app.
5. **Pairing-link typing, definitive recipe**: the field is password-masked — the
   •-bullet count is the length oracle; `.` must go through keyevent 56; `&` MUST be
   escaped (`\&`) in EVERY `input text` chunk — an unescaped `&` terminates the device
   shell command and the rest becomes background junk (one overrun inflated the field to
   534 bullets; ~560 DELs cleared it). Verify the count after EVERY 14-char chunk against
   `expected = pos + len(chunk)`; abort on mismatch (the working driver is
   /tmp/p10/type4.py — unbuffered, no auto-repair). There is NO adb clipboard path for
   the "Paste pairing link" field on this image.
6. **The fresh-install pairing chain (first full receipt)**: onboarding ("Control ZCode
   from your phone") → Paste pairing link → (typed link) → Continue → the E7 stale-link
   consent gate ("This QR code may be be stale" — links older than 10 min) →
   "Continue anyway" → the security-setup gate ("sealed with hardware-backed encryption")
   → Continue anyway → the notifications ask (Turn on notifications → SYSTEM permission
   dialog → Allow) → "Notifications are on" + the Live-connection toggle → Continue → a
   4-page onboarding carousel (Skip) → HOME CONNECTED. FLAG_SECURE is ON from the
   wipe (default `screenshotProtection=true`, CoreModels.kt:214) and CANNOT be turned off
   before pairing (Settings is unreachable on the onboarding screens) — the pairing legs
   run against black screenshots; the AX tree is the oracle until paired.
7. **MOCK_AUTOMATIONS alone is NOT sufficient for the automations plane**: the app calls
   `off-peak-task.list` as part of the plane and the mock faults it
   (`fault.mock.unscripted:offPeakTaskList` → `automationsport.lists TransportFailure`),
   rendering the honest "This feature isn't available on the connected desktop." face.
   With `MOCK_OFFPEAK=1` added the plane renders fully (Nightly digest · Daily at 02:00 ·
   3 runs · Run now/Pause/History/Edit/Delete). This is mock-fidelity debt #8 (below).
8. Navigation hazard: BACK chains from nested sheets (context → git) exit the app
   (G.A0.6), and one stray launcher tap opened the Google sign-in flow mid-leg — anchor
   every navigation on a fresh dump, never chain BACKs blind.

## M.A1 — verified working this pass (receipts; bold = never-driven-in-series)

| Surface | Pass-10 receipt |
| --- | --- |
| **The full fresh-install pairing chain** | M.A0.6 — onboarding → typed link (178/178 count-verified) → E7 stale gate → security-setup gate → notification ask (POST_NOTIFICATIONS granted) → carousel skip → home CONNECTED in ~6 s of the relay being reachable; the persisted endpoint survived two emulator restarts and one app force-stop without re-pairing |
| **Diagnostics plane, end-to-end (first series drive)** | "Add a marker" → `diagnostics.mark :: user marker at 2026-11-14T05:03:09… phase=CONNECTED` in the log; "Copy diagnostics to clipboard" → the floating preview bubble ("=== ZCode Companion diagnostics === / generated: …") with share/copy actions; **"Export diagnostics" → the SYSTEM share sheet with a real file `zcode-diagnostics-1794632646997.txt`** (pulled via run-as from cache/shared/, 1,174 lines) — header/env/connection-snapshot/workspace-resolution/pairing-identity (INV-3, deviceSid truncated)/wire counters/event counters/warnings+errors chronological/connection timeline/per-connection FULL redacted ring. Redaction verified: `auth_challenge` nonce shows `[REDACTED]`, zero passHash/token/hash= matches; `desktopAboveTested (vs MAX_TESTED 3.18.1): false` rides the header |
| **A11y audit of the never-swept screens (item 7)** | subtree-aware unlabeled-clickable census: settings ROOT 0; Usage 0 (honest unavailable face); Skills 0; MCP servers 0; Subagents 0; Providers 0 (Z.AI Default glm-5.1 + glm-5.1-air/flash render); Bots 0 (bot-telegram-1 telegram); Workspace hooks 0 (honest "no hooks seen yet" face); Commands 0 (/deploy); Memory 0 (honest unavailable face); Endpoints 0; Approvals inbox 0 (honest "Nothing needs you" empty state); conversation 0; Context sheet 0 (full render: Status/goal/iter verdicts/Todos/Workflow runs/Plans/git strip + chip row); git sheet 0 (branch main/clean, 2 changed files, Checkpoints/Snapshot tabs). **Plugins: 2 unlabeled** → N-BN-1 |
| **The automations plane fully rendered under the off-peak arm** | Nightly digest · "Daily at 02:00" · Next … · "3 runs" · On · active · Run now/Pause/History/Edit/Delete — the unavailable face was the M.A0.7 rig gap; a11y clean |
| **zai dark theme matrix** | Theme dialog: Light/Dark/Follow the system/**zai light**/**zai dark** (5 options); zai dark applied live (no restart) on settings + home — dark surfaces, blue accents, readable badges/rows; G88's shipped-parity claim re-verified on the newest pair |
| **Font-scale matrix** | Text-size dialog: Small (0.85×)/Default (1.0×)/**Large (1.15×)** — the phone has NO 1.3× tier (the backlog's "font 1.3×" maps to the 1.15× max); Large applied live on settings + home with zero crush/clipping (screenshots s25/s27); reverted to Default (verified in the raw dump: `Text size → Default (1.0×)`) |
| **F-P8-C drag-scroll RE-VERIFIED working at HEAD** | the pass-8 "cannot be DRAG-scrolled" defect NO LONGER REPRODUCES: three drags scrolled the timeline 3 directions (parse row → approval row → YOU row; reverse drag reached the "Applied Parser.kt fixes" list rows) — the frozen-timeline mechanism (whatever it was) is gone at e9a8d206; F-P8-E (follow release) remains undriven (no streaming arm) |
| Home unread badge | the bell renders a red "1" badge after the reconnect (unread plane live) |
| Git sheet Snapshot tab | "No snapshots in this session — create one before risky changes" (K-small-1's empty state, unchanged) + read-only badge + Commit…/Branches… affordances |

## M.A2 — defects found this pass (NEW ledger rows)

- [ ] **F-P10-A (P3, app) — the Commit sheet's Commit action SILENTLY no-ops when the
  hub's active-workspace resolution is null: dialog closes, zero wire verbs, zero log
  lines, zero user face.** Receipt: opened the commit sheet from the conversation's
  context sheet → Git route, typed a non-blank message ("rig commi"), tapped Commit →
  the dialog closed, `git.getChanges` re-fetched twice, but the full logcat has NO
  `git.commit` channel.call, NO `gitport.commit` warn, and the diagnostics export's
  warnings ring shows nothing. Mechanism pinned in code: `GitScreen.kt:464` onCommit →
  `port.commit` → `Phase9Ports.kt:229` → `writeOp("commit")` (:208) whose FIRST line is
  `val wp = workspacePath() ?: return null` — `workspacePath()` is
  `hub.activeWorkspaceTarget()?.first` (:150), and a null target returns null SILENTLY
  (the read path used by the SAME sheet resolves elsewhere — `git.getChanges` worked in
  the same session). The button's enabled gate (`message.isNotBlank() && (identity ==
  null || identity.present)`, GitWriteSheets.kt:125) passed, so the silent return is the
  live path, not a disabled button. The N-BG-2 refusal-face pattern exists for the
  GENERATE verb but the commit path has no failure face at all (`if (hash != null)` …
  else nothing, GitScreen.kt:467-478). Fix shape: resolve the write target the same way
  the reads do (session-scoped), and render a typed refusal face when the target is
  null; pin: a Robolectric commit-sheet harness with a hub whose activeWorkspaceTarget
  is null asserting the face. This is the F-P5-A silent family's write-half twin.
- [ ] **N-BN-1 (P4, a11y) — the Plugins screen's per-plugin enable switches are
  UNLABELED**: `plugin_switch_plugin-prisma` and `plugin_switch_plugin-review`
  (checkable Views at the row trailing edge) carry no content-desc and no text children
  — TalkBack announces nothing for the ONLY interactive element of each plugin row.
  Everything else in the settings-viewer family is clean (M.A1). Fix shape: a
  `Modifier.semantics { contentDescription = "Enable <plugin name>" }` on the switch +
  the a11y pin.

## M.A3 — re-manifestations at HEAD (stamps only)

- **F-P9-A (2nd)**: after the stale gen1 relay dropped the app mid-AUTHENTICATION
  (`relay.connected` never reached; `hub.connectFailed TIMEOUT connect timed out after
  10000ms` at 04:43:13), the transport wedged log-silent with the header stuck
  "Connecting…" — the foreground `am start` poke did NOTHING and `logcat -d` showed zero
  ZCODE-PAIR lines for minutes; the force-stop + relaunch escape connected in ~2 s
  (after the relay was restarted AND wifi was re-disabled — the same-recipe recurrence
  of the pass-9 wedge, not a new mechanism).
- **F-P0-E (2nd)**: the app ANR'd ("Input dispatching timed out … Waited 5006ms for
  MotionEvent") during the wipe-data first-boot storm (the ANR record also contains GMS/
  phone startup ANRs + a half_watchdog 15s block) while the blind pairing-typing burst
  ran. Causality still unproven (first-boot churn + rapid input both present); the rig
  recipe gains "never run the typing burst during a first-boot window" (M.A0.2).
- **K-small-1** (visible): the git Snapshot tab's "create one before risky changes"
  empty state with no create affordance rendered again (unchanged code; no new stamp).
- F-P6-A's store half: the wipe-data RESET the ghost accumulation (the pulled DB holds
  exactly 1 interaction — the current generation's seeded approval, legitimately
  pending). The pass-2/8 ghost fixtures in /tmp/pass8 + /tmp/p9 remain the evidence;
  no stamp change.

## M.B — the REA dig, part 10 (digest unchanged `ec475e6e…`; extraction /tmp/p6-asar/x reused)

### B1. Backlog item 2 — the remaining channel method tables: DONE (all 8)

Walk-back method: channel descriptor `X=Be(be.Y)` → serving site `.register(Y, impl)` →
implementation object. All in host/index.js (attribution: HOST-served, not renderer/CLI).

| Service (channel) | Methods (exact) | Phone verdict |
| --- | --- | --- |
| **ProviderSettings** (`createProviderSettingsService` = oF, channel `id`) | 15: onDidChange, getView, refresh, createPersonalProvider, resolveModelConfig, savePersonalProviderOverlay, deletePersonalProvider, reorderPersonalProviders, reorderPersonalModels, addPersonalModel, renamePersonalModel, deletePersonalModel, savePersonalModelDraft, setPersonalModelEnabled, testModelConnectivity (typed {success:false,error:{code:provider-unavailable\|model-unavailable}}) | personal provider/model AUTHORING is desktop-only; the phone is a read-only catalog consumer (G92-class, no new row) |
| **ModelSelection** (`createModelSelectionService` = iF, channel `Ui`) | 3: onDidChange, getView (REVISION-BASED: getView(config, minRevision, signal) + revision bump on change), dispose | the phone's model options decode id/label/badge only (pass-31 note stands); the revision plane is desktop-internal |
| **SourceServerWorkspace** (`createSourceServerWorkspaceService` = AF, channel `ca`) | 6: resolveWorkspace (canonical workspace-key + migration), getFailedPathCount, getAbandonedPathCount, listMigrationReports, abandonFailedWorkspace, retryWorkspaceMigration | the workspace-identity/migration plane — phone zero surfaces (F-P8-A/G94 adjacency: workspaceIdentity) |
| **ZCodeTask controller family** (`createControllerRoutedTaskService` = Xz + the WindowController channel) | Xz ROUTES {setTaskPinned, archiveTask, unarchiveTask, deleteTask, deleteArchivedTasks, deleteArchivedTask, setTaskUnread} through controller mutateTask + 4 bot-group passthroughs (getBotGroupTaskBlockReason, cancelBotGroupInput, submitBotGroupInput, readBotTopicSummaries) with attachment-scope injection; **WindowController channel (`Pg` → createWindowHostControllerRuntime's per-runtime service) = 14 members EXACT**: listTaskList, previewArchivedTaskDeletion, permanentlyDeleteArchivedTask, getTaskMeta, deleteArchivedTasks, deleteArchivedTask, mutateTask, subscribeControllerV4, resyncControllerV4, unsubscribeControllerV4, onDynamicControllerFrame, onDynamicTaskUnreadSignal, onDynamicTasksRemovedSignal, (dispose) | the pass-8 "controller family partially done" is now protocol-exact — the opportunistic queue's archived-delete verbs + controller-V4 family + setTaskUnread have their full wire contract |
| **ConversationShare** (`EA` class, channel `na`; stubs Dv/Y8e) | 13-verb surface: getCapabilities, preflight, publish, onDynamicPublishProgress, importShare, onDynamicImportProgress, getImportedConversation, getPreview, getContinuation, listManagedShares, revokeManagedShare, restoreManagedShare, deleteManagedShare; remote workspaces + unsupported clients get the typed `feature_disabled` stubs ("Conversation publishing is not available for remote workspaces" / "…not available for this client or remote target"); publish → phase map + shareWebUrl `${origin}/cn/share`; import → `.zcode-share-imports.json` index | G86's "inventory first" is satisfied — the exact wire contract + the typed-refusal shapes |
| **CuaPermission** (`fo`, channel `gm`) | 2: getStatus (typed status: available/reason/grantOwner/grantOwnerDisplayName/accessibility/accessibilityProbeOk/screenRecording/screenCaptureProbeOk/idle — macOS+Windows only, typed reasons "CUA permissions are only available on macOS and Windows." / "not enabled (plugin off or not product mode)") + restartHelper (macOS-only; reason:"permission_granted" variant) | the phone decodes PUSHED observations (the G64-class banner) but has no getStatus/restartHelper surface — G101 |
| **MediaPreview ⚠ CLOSED** (`createMediaPreviewService` = yO, channel `ja`) | 1: prepare({path, expectedKind}) → {kind:"local-url", url} \| {kind:"inline", dataBase64, mediaType, path, size} — inlineMaxBytes default 8,388,608 (8 MiB), typed "Media file is too large for inline preview"; per-remote sessions wire a remoteMediaPreviewFactory | attribution RESOLVED: host-served (host/index.js); the phone's attachment thumbnails decode locally — no consume need |
| **WindowProjectChanges ⚠ CLOSED** (`createWindowProjectChangesService` = tz, channel `rS`) | 2: getCapabilities() → {version:1}, onDidChange(cb) → fires on host-bus `state:projects` messages | attribution RESOLVED: host-served, registered into web-remote-replayable channel servers only when windowServices exist |

### B2. The window-host exposure policy (G97 census addition)

`resolveWindowHostServiceExposurePolicy` (`nme`): the **`web-remote-replayable` client
mode (the browser companion) is restricted to a 49-channel `allowedChannels` Set** —
File, MediaPreview, System, Terminal, Git, GitCheckpoint, Setting, OnboardingRecord,
Credential, CuaPermission, CuaPipSession, Broadcast, ZCodeTask, WindowController,
ZCodeAgent, ZCodeSession, Project, TaskGroups, RemoteProjects, WindowProject,
WindowRemoteProjects, WindowProjectChanges, ConversationShare, FileWatcher, OAuth,
ProviderSettings, ModelSelection, SourceServerWorkspace, UsageStats,
CodingPlanSubscription, ClientScenes, Skills, McpSync, Plugins, PluginManagement,
Subagents, Commands, Hooks, Memory, OutputStyle, SettingsSync, Bots, Feedback,
PromptAttachmentTransfer, OffPeakTask (49 exact). In that mode **McpSync is scoped by
`scopeMcpSyncServiceForMobile` to exactly {loadMcpFromUserDirectory,
saveMcpToUserDirectory, listWorkspaceMcpServerStatuses, setWorkspaceMcpTrust}** — every
other verb rejects typed `feature_disabled` "Remote sync is only available from
Desktop". The NATIVE app's client mode is NOT web-remote-replayable → unrestricted
channels (consistent with the phone's 38-service registry). The companion plane now has
its exact channel allowlist; the desktop's `remote.*` i18n namespace (460 keys) is the
desktop-side device-companion faces ("设备未连接，连接后继续此会话") — G97 deepened.

### B3. Backlog item 3 — the i18n namespace diff: DONE

Census: the desktop renderer carries **84 dotted namespaces / 6,515 keys**
(IntlProvider-C435ESD3.js; zh-CN + en-US) vs the phone's 391 strings.xml keys in ~25
prefixes (most phone UI text is in-code Compose strings, so the diff is a COVERAGE
screen, not a count). Adjudication of the namespaces with NO phone counterpart:

| Desktop namespace | Keys | Verdict |
| --- | --- | --- |
| **ssh** (SSH 远程连接: host/port/username/auth password·privateKey/assetInstallMode local-download-upload…/advancedOptions) | 180 | **NEW G98** — the desktop connects DIRECTLY to SSH servers as workspace hosts; the phone has zero ssh/wsl/docker/source-server surfaces |
| wsl (45) + docker (35) + server (50) + sourceServer (60) | — | fold into G98 |
| **whiteboard** (画板: named boards, pen/eraser/color/strokeWidth/undo/redo/addToChat/missing) | 65 | **NEW G99** — a persisted named-canvas plane vs the phone's send-only Sketch |
| **modelTrajectory** (模型调用轨迹: per-model-call usage input/output) | 165 | **NEW G100** — the desktop's per-call usage trajectory viewer; the phone's Usage screens are aggregate |
| **cuaPermission** (the permission panel copy) | 25 | **NEW G101** — with the getStatus/restartHelper verbs (B1) |
| remote (460) | — | the desktop's device-companion faces — folds into G97 |
| project (432) + projectManagement (205) | — | desktop multi-project CRUD; the phone's workspace switching covers the day-to-day — census note, no row |
| taskList/taskGroup/archives/taskSearch/taskTimeline/taskNav | 287/170/173/45/40/20 | phone parity exists in-app (rows/groups/archive/search); archives delete verbs in the opportunistic queue |
| codeViewer (145) | — | the phone's file viewer covers read-only; parity partial — census note |
| bots (485) | — | Bots authoring = NO-ACTION forever (ADR) |
| imageEditor (90) | — | desktop editor; phone attaches images only — census note |
| browser (90) | — | desktop CUA-browser control UI — AppControl = NO-ACTION forever |
| occupationOnboarding/welcome/login/updateDialog/updateReady/forceUpdate/updateAvailable/postUpdateReleaseNotes/marketingTouch/debugInfo/developerTools/commandCenter/quickPick/v4Pane/sidePane/workspaceSidebar/workspaceFileTree/fileTree/fileManager/directoryBrowser/resourceManager/titleBar/sidebar/treemapping | — | desktop shell — legit |
| genUi (105) / webRemoteControl (130) / conversationShare (77) / rewards (25) / offPeak (20) / plugins+pluginUi+pluginCreator / tokenDebug (20) / bashOutput (20) / terminal (40) / subagentDirectory (30) | — | already rowed (G81/G97/G86/G60/queue items) |

## M.C — where the phone is BETTER (new receipts this pass)

- The diagnostics export is a genuinely complete redacted report (env → connection →
  identity → counters → typed warnings → timeline → full ring) with the desktop's
  MAX_TESTED stamp inline — nothing on the desktop side exposes a comparable one-tap
  artifact for the mobile connection.
- The fresh-install pairing chain is self-guided end-to-end (E7 stale gate, security
  gate, permission ask, carousel) — the desktop's QR flow has no equivalent onboarding
  grammar to compare.

## M.D — STEP 2 coverage adjudication (latest-first)

- Shade-ACTION approve: STANDING RIG STOP (unchanged).
- G67 card live render: F-P9-B's negative receipt stands (code unchanged).
- MD_FILE render: still blocked by F-P6-B (unchanged).
- User-row long-press ACTIONS: UNBLOCKED by the F-P8-C re-verification — the drag
  works, so the next pass can drive the user-row action plane (Copy/Ask in a side
  session/Share/Edit) without an app fix.
- Question-card options: still blocked by F-P3-A (unchanged).
- Take-photo happy path / widget add-to-home: standing stops.
- Re-drive FIXED rows: no fix rounds have run yet.
- Settings-search edges: N/A — this build's Settings screen has NO search field (only
  home has Search tasks, receipted pass 8); the vc51-era "Settings search" gotcha does
  not apply to the pass-0 series' build.

## M.E — ledger arithmetic

Open F: 22 + 1 (F-P10-A) = **23** (21 defects + 2 observations). Open G: 17 + 4 (G98–
G101) = **21 + 3 standing**. Open smalls: 9 + 1 (N-BN-1) = **10** (+1 watch +1 rig).
Backlog: items 1/2/3/4/5/7/8 DONE, item 6 rolling — **7 of 8 fully ticked**.

### Coverage honesty (what pass 10 did NOT drive)

The push/interaction arms (no push generations this pass — the rig went to the pairing/
audit legs after the wedge; the push arm recipe is unchanged), F-P8-E's follow-release
(needs a streaming arm), the terminal view (5× receipted, code unchanged), the
Notifications detail sub-screen and About screen (navigated past, not dumped — low
yield), the automation EDITOR screen (the automations plane now renders — the editor
was swept in vc52-era passes; not re-dumped this pass), the endpoint DETAIL/editor form
(the row tap didn't navigate — the form was last swept in the vc51 series), and
backlog item 6's remaining arms now all stand at their standing stops.

## Pass-10 verification stamp

Pass 10 completed 2026-11-14 ~05:30; relay generations gen1 `mock-p10-gen1-g1a2b3`
(04:20 pairing + first connect), relaunches gen1b/gen1c (stale-session + wifi-out
windows, both receipted), gen1d (05:25, +MOCK_OFFPEAK=1, current) — one launcher
(/tmp/p10/relay-g1.sh), three emulator restarts (wipe-data boot 03:50, warm reboot
03:52×2, full restart 04:52), receipts nonce-anchored. 31 screenshots
`/tmp/p10/shots/s01–s31`; DB pulls `/tmp/p10/db/p10-1.db(+wal)` (ghost census: 1
interaction, clean post-wipe); diagnostics export artifact
`/tmp/p10/diagnostics-export.txt` (1,174 lines, redaction-verified); full logcat
`/tmp/p10/logcat.log` (+ the on-device `-d` windows quoted inline); ANR record
`/tmp/p10/anr2.txt` + `trace01.txt`; launcher + typing drivers `/tmp/p10/relay-g1.sh`,
`/tmp/p10/type4.py` (+ the two failed variants kept as counter-examples); a11y dumps
`/tmp/p10/a-*.xml` (17 screens); i18n census `/tmp/p10/i18n-ns-census.txt` (superseded
by the clean 84-NS count in §B3); REA extraction reused `/tmp/p6-asar/x` (desktop asar
`ec475e6e…` re-verified at STEP 0). APK byte-identical to the whole series (sha256
`ad449d7d…`, md5 `530ba4ff…`, vc53/1.3.2; re-verified at the pass-10 build). Rig left:
emulator zcode-a16 UP (PIN-less, stay-on 7 + screen_off_timeout 600000 set, wifi
disabled, screenshot protection OFF), relay gen1d ALIVE (23-arm light + off-peak), app
paired + CONNECTED on the conversation screen, logcat running to /tmp/p10/logcat.log.
Nothing pushed.

# Part N — pass 11 (APPENDED 2026-11-14, midday): the user-row action plane driven end-to-end, F-P8-E refuted live, the Live-connection window picker's stale projection, and the mock attachment-race fix

> **Pass 11 of the pass-0 series** (solo, canonical discovery prompt). Scope: pass-10's
> UNBLOCKED leftovers first (M.D: the user-row long-press ACTIONS plane; the F-P8-E
> follow-release leg), then the never-swept Settings screens. Three new rows (F-P11-A
> the attachment chip's no-error face, F-P11-B the Live-connection window's stale
> projection, N-BO-1 the changes-viewer raw fault code), ONE refutation (F-P8-E — the
> gesture-direction release + numbered jump FAB both work live; the pass-8 mechanism
> pin was a mis-read), the mock attachment-race fix (commit `1e649eab`, verified live),
> and first-series receipts for the side-session plane, the edit-and-re-ask wire, the
> changes viewer, the Licenses share, the What's-new empty state, and the
> Live-connection window picker vocabulary.
>
> **Verification stamp:** 2026-11-14 10:05 → 11:06, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no DELTA.

## N.A0 — rig learnings (each bites the next pass)

1. **The Read tool renders screenshots SCALED (~920-wide vs the native 1080×2340) —
   eyeballing tap coordinates from a rendered image lands wrong by the 0.852 factor
   three ways at once** (two silent sheet dismissals + one accidental zoom-viewer
   open before this was pinned). Pulled PNGs are NATIVE (`sips -g pixelWidth` settles
   which space you are looking at); uiautomator dump bounds are NATIVE. Rule: derive
   every tap from a dump, never from a rendered screenshot.
2. **DataStore prefs live at `no_backup/datastore/preferences.pb`** — NOT
   `files/datastore/` (only the Glance widget store there) and NOT `shared_prefs/`
   (`zcode_prefs.xml` holds only the locale mirror). `run-as cat` + a raw protobuf
   scan (field 8 varint = liveConnectionTimeoutMinutes) is the settings-leg arbiter.
3. **The FGS toggle reflects `LiveConnectionService.isRunning` (service STATE, not a
   pref)** — force-stop resets it to OFF by design (SettingsScreen.kt:1048-1051's
   "the service lifecycle is the single writer"). A settings leg that reads the toggle
   after a restart is reading the service state, not persistence.
4. **Picker-dialog radio rows expose NO selected/checked state to the AX tree** (all
   five options dump checked=false/selected=false while the visual radio is clearly
   filled) — dialog selection state is a screenshot-only read on this build.
5. The mock's `snapshotRows` seeds IDENTICAL content for any session id — side-session
   children render the full parent transcript (mock fidelity; do not chase as an app
   defect).
6. The second cold boot of the day recovered in ~25 s (pass-10's wipe-data AVD, no
   snapshot); the SystemUI ANR dialog from the boot storm re-appeared once — dismiss
   Wait + force-stop + relaunch (M.A0.2 recipe) and the app converged without
   incident.

## N.A1 — verified working this pass (receipts; bold = never-driven-in-series)

| Surface | Pass-11 receipt |
| --- | --- |
| **The user-row long-press ACTION plane, all four actions (M.D's top unblocked leg)** | Sheet = "Message actions" (Copy / Ask in a side session / Share selection / Edit). **Copy** → the system clipboard preview bubble renders with the row's exact text ("rig scroll p11") + share/clipboard chips. **Share selection** → the SYSTEM share sheet ("Sharing text") carrying the row's exact text + targets. **Edit** → the "Edit message" sheet (prefilled, blank-refused client-side, "Review edit" disabled until changed) → the typed confirm gate ("Re-ask this turn? — Files and later turns may be affected") → **`sendConversationCommandV4 ok` (78 ms)** + the typed notice "Edit sent — the turn is being re-asked." — the full GAP-10 edit path live |
| **Ask in a side session (never driven in-series — the bold pass-1 row is now closed)** | Sheet action → `sendConversationCommandV4` (envelope type `createSelectionSideSession`, firstInput=the row text) ok 192 ms → the mock acks `sess-side-1` + pushes the workspace list → the app **NAVIGATES**: `recovery.attach fresh=[sess-side-1]` → subscribed → snapshot rows=15 → `hub.conversationOpen` — the child session screen renders with the side-session title. The child's rows are the mock's generic re-seed (fidelity note, N.A0.5) |
| **The changes viewer (never driven — accidental open + deliberate happy path)** | The turn header's "view" chip opens "Changes · turn 15" — with `MOCK_FILECHANGES` unarmed it renders the TYPED error face (honest, not silent-blank) "Could not load changes: fault.fileChanges.none" with Close; **with the arm on (gen3) it renders the real unified diff**: "2 file(s) +5 −1 / src/version.ts +3 −1 / @@ -1,3 +1,3 @@ / `+const b = importMetaHot.data.a + 1`" — the read-only diff viewer works end-to-end |
| **F-P8-E's follow-release leg — REFUTED live (see N.A3)** | swipe toward the head → `followBottom=false`; **send while released → NO re-clamp** (the view stayed on the head; the mock appended the user row + "synthetic reply to: rig scroll p11") → the ↓ jump FAB carried a **numbered unread badge "4"** → tap → jumps to the tail, badge clears, follow resumes. The remedy's BOTH halves exist at HEAD (`ConversationScreen.kt:992-1003` gesture-direction release, `:1250-1281` the `conversation_jump_latest` FAB with `UnreadBadge`) — commit `4bb678e7` (phase-7 field round 5) predates pass 8's claim |
| **The G49/G50 queued-send face (incidental)** | with the re-ask in flight, the send QUEUED: the composer shows "Keep typing to queue follow-up changes" + a **Queued chip** + the stop button — the held-queue CAS ids plane renders live |
| MD render half (pass-9's blocked receipt) | the MOCK_MD_FILE demo row renders FULLY formatted under the LIGHT rig: heading, **bold**, inline-code chip, ordered list, a real TABLE (col a/col b, 1/2), the blockquote bar — pass-9's "render half blocked by F-P6-B" was the ARMED-stack variant; on a light timeline the renderer is fine (F-P6-B remains the armed-stack row) |
| Licenses (About family, never driven) | the Licenses row dispatches ACTION_SEND with the real license text (share sheet: "ZCode Companion — open-source components: Kotlin/Jetbrains (Apache-2.0), OkHttp, Tink, ML Kit barcode, CameraX, Protobuf (BSD), DataStore/Room…") + Copy to clipboard — no in-app viewer, the text IS deliverable (receipt, not a defect) |
| What's new (never driven) | honest empty state: "No release notes recorded yet — they appear after you update." + Continue |
| **The Live-connection window picker (never driven)** | Live ON → the row enables → the dialog renders the full vocabulary (15/30/60/240 minutes / "no timeout (until you turn it off)" / Cancel); the selection PERSISTS to disk (`preferences.pb` field 8 = 60, pull-decoded) and renders "60 minutes" after screen re-entry — but the in-session subtitle NEVER recomposes (F-P11-B below) |
| The endpoint row's inert-active tap (pass-10's mystery, now PINNED) | the Settings endpoint row is `clickable=true` but its onClick is `if (id != null && !active) switchTo(id)` (SettingsScreen.kt:467-474) — the ACTIVE row is a **no-op BY DESIGN**; there is no detail route from this row (the detail screen pass-10 dumped came from the ConnectionCenter). Not a defect |
| The notification-detail copy | "Minimal shows only 'ZCode needs your approval' — never tools, paths, or titles… with Approve/Deny in the notification (unlock required)" + the honest push vocabulary "ZCode's relay offers no push service, so with both off you'll see updates the next time you open the app" — the G93-adjacent faces read in full |

## N.A2 — defects found this pass (NEW ledger rows)

- [ ] **F-P11-A (P3, app) — the conversation attachment chip has NO error face: a
  failed `conversationAttachmentReadV4` leaves the image chip at "Loading…" with no
  unavailable state and no retry.** Receipt: the seeded `mock-attachment-1` read
  faulted once (`fault.attachment.unknownRef` at 10:15:46, the mock-race window) and
  the chip rendered "Loading…" for ≥1.3 s; with a PERSISTENT failure the chip would
  never resolve — the `produceState` swallows every failure via
  `runCatching { load(att) }.getOrNull()` → null → the `else` branch renders
  `row_attachment_loading` forever (`RowRenderers.kt:1803-1818`, `:1845-1856`; the
  rewind preview's `previewUnavailable` typed face shows the contrast at `:686-691`).
  The chip recovered here ONLY because the mock self-healed. Fix shape: an
  unavailable/error state on the chip (the F-P0-C family) + a Robolectric harness
  hosting a failing loader. The MOCK half is already fixed: `1e649eab` registers the
  scripted attachment at arm time (cold-start read now `ok in 306ms`, zero
  unknownRef on gen3 — verified live).
- [ ] **F-P11-B (P3, app) — the Live-connection window picker's selection never
  reflects in the row subtitle in-session: the Settings prefs projection doesn't
  re-collect after a write.** Receipt (three cycles): pick "60 minutes" → the
  subtitle stays "Auto-disconnect after 30 minutes" (cycle 1) / "no timeout (until
  you turn it off)" (cycles 2-3, ≥4 min, screenshots s36/s40/s41) while the DIALOG's
  own radio shows 60 selected (s37) and **the write PERSISTS to disk** —
  `no_backup/datastore/preferences.pb` field 8 varint = 60, pull-decoded — and a
  Settings RE-ENTRY renders "60 minutes" correctly (s44). The row reads
  `prefs.liveConnectionTimeoutMinutes` (SettingsScreen.kt:1057-1066) from a snapshot
  that never re-emits after the dialog's `preferencesRepository.update` (:1081).
  Lesser observation: a force-stop ~90 s after cycle 1's selection lost the write
  (disk read 0 at restart — async flush killed). Fix shape: collect prefs via the
  live `dataStore.data` flow on this screen (or key the row on the pref) + a
  Robolectric pin asserting subtitle-after-update; the flush-loss wants a
  dispatch-measure note only.
- [ ] **N-BO-1 (P4, a11y/copy) — the changes viewer's error face interpolates the RAW
  fault code: "Could not load changes: fault.fileChanges.none"**
  (`changes_error` = "Could not load changes: %1$s" with the raw protocol string,
  Phase9Components.kt:379 + strings.xml:153) — the G66 fault→human mapping (commit
  `2af339b0`) doesn't cover this Phase9 surface. Fix shape: route through the same
  typed-message table.

## N.A3 — re-manifestations and refutations at HEAD (stamps/status only)

- **F-P8-E → REFUTED-WORKING (status change, not a stamp)**: the pass-8 mechanism
  claim ("followBottom cleared only by find-jump", pinning :1045) is NOT TRUE at
  HEAD — the gesture-direction release (scroll toward the head → release; scroll to
  the tail → re-follow) AND the latched numbered jump FAB both live-receipted (N.A1).
  The remedy commit `4bb678e7` predates pass 8's pin; pass 8 drafted the row inside
  the frozen-timeline window (the same window F-P8-C's drag claim came from, already
  re-verified working at pass 10). Row stays (keep the release pin for fix rounds).
- **N-BJ-1 (stamp)**: the 1×1 red attachment rendered uncapped at ~300×220dp again
  (s04) — unchanged code, one stamp.
- F-P1-D (stamp): the workflowRuns/plans drum ran every ~8 s through the whole
  conversation session (id=21/22/31/32/48… in the relay log) — unchanged.
- Mock debt #9 (created AND fixed this pass): the scripted attachment's
  registration raced the app's cached-row read — fixed by `1e649eab`, verified on
  gen3 (zero unknownRef, `ok in 306ms`).

## N.D — STEP 2 coverage adjudication (latest-first)

- User-row long-press ACTIONS: **CLOSED** (all four actions dispatched, N.A1).
- F-P8-E follow-release: **CLOSED as refuted-working** (N.A3).
- MD_FILE render half: **CLOSED** on the light rig (the armed-stack variant stays
  F-P6-B-blocked).
- The changes viewer: **CLOSED** (error face + happy path).
- Endpoint detail/editor form: **N/A for the Settings row** (inert by design, pinned;
  the ConnectionCenter-routed detail was a11y-swept at pass 10).
- Question-card options: still blocked by F-P3-A (unchanged). Take-photo happy path /
  widget add-to-home / shade-ACTION approve / TalkBack runtime: standing stops
  (unchanged). Re-drive FIXED rows: no fix rounds have run yet.
- Push/interaction arms: not armed this pass (no push generation — the legs went to
  the action plane; recipe unchanged).
- The Notification detail LEVEL selector (Minimal/Standard dialog): reached, NOT
  driven (the Notifications detail sub-screen's row list is receipted; the selector
  dialog itself remains undriven — low yield).

## N.E — ledger arithmetic

Open F: 23 + 2 (F-P11-A/B) − 1 (F-P8-E refuted) = **24** (22 defects + 2
observations). Open G: **21 + 3 standing** (unchanged — no desktop delta, STEP 0
clean). Open smalls: 10 + 1 (N-BO-1) = **11** (+1 watch +1 rig). Mock debts: #9
created and fixed same-pass (`1e649eab`). Backlog: item 6's rollable list shrinks —
the user-row ACTIONS leg, the F-P8-E leg, and the MD render are all closed this pass.

### Coverage honesty (what pass 11 did NOT drive)

The push/interaction arms (no push generation this pass), the Notification detail
LEVEL selector dialog (Minimal/Standard), the automation editor (unchanged code since
the vc52-era sweep), the context-sheet/Git-sheet deep planes (receipted pass 10,
code unchanged), re-driving FIXED rows (none fixed yet), and all standing stops
(shade-ACTION approve, widget add-to-home, Take-photo happy path, TalkBack runtime).

## Pass-11 verification stamp

Pass 11 completed 2026-11-14 ~11:06; relay generations gen2 `mock-p11-gen2-2b4c6d`
(10:11, light rig + MD_FILE — the MOCKER crashed once on `MOCK_MD_FILE=1` before the
path fix: the arm takes a FILE PATH), gen3 `mock-p11-gen3-3c5d7e` (10:40, +MOCK_
FILECHANGES, the fixed mock jar) — one launcher each (/tmp/p11/relay-g2.sh, renamed
relay-g3.sh), ONE emulator boot (zcode-a16 cold 10:06, boot 25 s), receipts
nonce-anchored. 44 screenshots `/tmp/p11/shots/s00–s44`; DB pulls
`/tmp/p11/db/p11-1.db(+wal)`, `p11-2.db(+wal)`; prefs pull
`/tmp/p11/preferences.pb` (field 8 = 60); full logcat `/tmp/p11/logcat.log`; relay
logs `/tmp/p11/relay-g2.log`, `relay-g3.log`; AX dumps `/tmp/p11/w*.xml` (20+);
launcher `/tmp/p11/relay-g3.sh`. APK byte-identical to the whole series (sha256
`ad449d7d…`, md5 `530ba4ff…`, vc53/1.3.2; re-verified at the pass-11 build). Mock
fix commit `1e649eab` (test suite green, jar rebuilt before gen3). Rig left:
emulator zcode-a16 UP (PIN-less, stay-on 7 + screen_off_timeout 600000, wifi
disabled, screenshot protection OFF), relay gen3 ALIVE (light rig + MD_FILE +
FILECHANGES), app paired + CONNECTED, Live connection ON with the 60-minute window,
logcat running to /tmp/p11/logcat.log. Nothing pushed.

# Part O — pass 12 (APPENDED 2026-11-14, afternoon): the last backlog leg (the notification LEVEL toggle), the never-driven interaction-behavior chain, the background-work plane, the card-plane approval answer's first working receipt — and the saturation adjudication

> **Pass 12 of the pass-0 series** (solo, canonical discovery prompt). Scope: backlog
> item 6's last driveable piece (the Notification detail LEVEL selector, named
> "reached, not driven" at pass 11) + pass 11's skipped push/interaction arms + the
> F-P6-A ghost census. **ZERO new rows opened** — and every remaining backlog
> leftover is now either closed, a standing stop, or blocked-by-open-row, so the
> ledger's stop condition FIRES: the discovery campaign is saturated (see O.E).
>
> **Verification stamp:** 2026-11-14 11:18 → ~11:50, GLM-5.3-Flash in ZCode,
> everything local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…`
> UNCHANGED — no DELTA. APK byte-identical to the whole series (sha256 `ad449d7d…`,
> md5 `530ba4ff…`, vc53/1.3.2 — code unchanged since pass 11's `1e649eab`).

## O.A0 — rig learnings (each bites the next pass)

1. **`svc wifi disable` — the canonical boot recipe's step — BREAKS the emulator's
   NAT on the API 36 image.** With wifi off, `nc 10.0.2.2 18100` = "Network is
   unreachable": every app connect dies at the socket (`hub.connect :: cold-start
   connect begin` → `relay.connect ws://10.0.2.2:18100/ws` → honest 10 s TIMEOUT +
   `hub.connectFailed` WARN). The pass-12 "wedge" (zero relay inbound, force-stop
   escape "failing") was ENTIRELY this rig fault — the app was exonerated by the
   reachability probe + the app-side ladder logs BEFORE any stamp was written. The
   step is API-30-ATD-era; on zcode-a16 do NOT disable wifi (the recipe line is now
   "skip svc wifi disable on the API 36 AVD").
2. **`preferences.pb` stores `notificationContentLevel` AND `interactionBehavior`
   as protobuf STRINGS** (`"CONTENT"`/`"MINIMAL"`, `"queue"`/`"guide"` — see
   `DataStoreRepositories.kt` toProto/toDomain), NOT enum varints. A varint field
   scan cannot see them: this pass's first arbiter read ("field 10 = 1 persists")
   was a wrong-schema guess — the raw-byte string scan settled every question.
   `liveConnectionTimeoutMinutes` (field 8 varint, pass 11) remains the only
   varint-valued pref checked so far.
3. `run-as` must go through `adb shell run-as …` — a bare `adb run-as` is an unknown
   adb subcommand (the canonical prompt's DB-pull shorthand omits the shell).
4. **The armed stack's pinned-card composition is NOT stable across conversation
   re-entries.** First open (s05): question card + hook review card visible with
   ALL bodies starved, both permission cards invisible. Re-open (s09): the approval
   card renders its FULL body ("Run a synthetic command? { "command" : "echo
   synthetic" }") + allowOnce/allowAlways options while the question/hook cards
   vanish. The F-P3-A starvation is composition-count dependent (the pinned budget
   is consumed by the number of competing pinned children, not an unconditional
   clamp) — this narrows the eventual fix shape and any pin harness (host the SAME
   card under varying pinned-child counts).
5. The mock's static session-config echo now fights the optimistic follow-up state
   in TWO places at once: after the Guide tap the queue-strip chips show Guide
   selected while the config chip reads "… · followup · queue" (debt #4's class,
   new visibility — on a real desktop the echo would confirm).

## O.A1 — verified working this pass (receipts; bold = never-driven-in-series)

| Surface | Pass-12 receipt |
| --- | --- |
| **The Notification detail LEVEL toggle (item 6's last driveable piece)** | The row reads "Notification detail: Minimal" + the honest sub_min copy → tap → INSTANT flip to "Notification detail: Task titles" + the sub_rich copy (the live prefs flow re-composes — the exact contrast with F-P11-B's snapshot row one section over) → tap again → back to Minimal. Disk arbiter (preferences.pb raw bytes): `"CONTENT"` after flip 1, `"MINIMAL"` after flip 2 (2 s and 20 s pulls agree). **NO dialog exists** — pass 11's "Minimal/Standard dialog" naming was imprecise; it is a two-value direct toggle row (SettingsScreen.kt:979-1001) |
| **The interaction-behavior chain (never driven in-series — G4/AC6 + I2.6's consume face)** | Settings → Conversation section row "While the agent runs: Follow the desktop" → dialog with the full vocabulary ("Follow the desktop" / "Queue follow-up changes" / "Guide the running turn" + Cancel) → pick Queue → the row flips to "While the agent runs: Queue follow-up changes" AND disk persists `"queue"` → on the armed conversation the **Queue/Guide chips APPEAR on the queue strip** (absent in s05 pre-pref, present in s09 post-pref — the `defaultFollowupMode = prefs.interactionBehavior` seed works live) → tap Guide → `sendConversationCommandV4 ok in 37ms` + the `resyncConversationV4` pair (the effort-chip wire shape) |
| **The Auto-send (auto-drain) toggle — first live drive** (pass 0 only receipted the rendering) | tap → `sendConversationCommandV4` (id=102) → the strip label flips "Queued (2) · paused" → **"Queued (2)"** (the paused suffix drops; the switch renders ON) — the I1 `setAutoDrain` consume path works end-to-end |
| **The card-plane approval ANSWER — first WORKING receipt** (pass 0's allowOnce taps were silent no-ops, absorbed into the F-P0-B/F-P3-A family; never since) | s09's full-bodied approval card → tap allowOnce → `sendConversationCommandV4` (id=113) → **the store lifecycle completes: `mock-approval-push-1-mock-p12-gen2-b2c3d4` DISPLAYED → RESOLVED (resolvedAt 11:44:27, p12-2.db)** → the card clears from the stack. The pushed approval answered from the CARD (the inbox path was pass 0's working receipt) |
| **The background-work plane (never driven in-series)** | the conversation strip "1 background task(s) running · View" → View → the "Background work" bottom sheet ("synthetic background build · bash · running" + Cancel + View output) → View output → **`backgroundBashOutputV4` (id=128)** → the live log panel renders "compiling module 1/3 / compiling module 2/3" + "running · /synthetic/ws-one/.zcode/background/work-bash-1.log" — the whole strip → sheet → output chain works |
| Push arm (45 s post-first-subscribe) | `mock-approval-push-1` created 11:34:57 = 44 s after the conversation's first subscribe (11:34:13 seed), DISPLAYED in the store, visible in the s09 render family while the conversation was open — the arm is healthy at HEAD (mock debt #6's recipe still valid) |

## O.A2 — re-manifestations at HEAD (stamps/status only)

- **F-P3-A (stamp 9 + a shape-narrowing receipt)**: s05 — the question card body
  zero-height (the crushed "Send answe/rs" button), zero hook rows under "2 pending ·
  3 hooks", BOTH permission cards invisible under the armed stack; and O.A0.4's
  variance: the same stack on re-entry renders the approval card's FULL body. The
  starvation tracks the pinned-child count — a pin harness must vary composition.
- **F-P8-D (stamp 3)**: the queue panel again always-expanded with no collapse
  affordance (s05/s09/s12).
- **F-P6-B (stamp 3)**: zero timeline rows under the armed stack (s05/s09).
- **F-P6-A (census stamp 4)**: interactions table at HEAD = 4 rows, ZERO
  generation-orphaned ghosts — the pass-8/9 ghost fixtures died with pass 10's
  wipe-data, and every DISPLAYED row belongs to the live gen2. The orphan-sweep
  code is still absent; a fresh install simply has nothing to orphan yet.
- **F-P1-D (stamp 4)**: the workflowRuns/plans drum ran the whole session — 52
  `conversationWorkflowRunsV4` polls in the gen2 relay log.
- F-P0-B: no re-manifestation EVENT this pass — the pushed row stayed DISPLAYED
  because no post-push snapshot arrived to sweep it (timing, not exoneration).
- Mock debt #4 note (O.A0.5): the config echo now fights the follow-up chips too.

## O.D — STEP 2 coverage adjudication (latest-first)

- The Notification detail LEVEL selector: **CLOSED — driven working** (O.A1; the
  pass-11 "dialog" naming corrected: it is a direct two-value toggle row).
- Pass 11's skipped push/interaction arms: **receipted this pass** (fresh push +
  the armed-stack stamps).
- The ghost census: **receipted** (0 orphans at HEAD).
- Auto-send toggle, interaction-behavior chain, background-work plane,
  card-plane approval answer: **all first-series receipts, WORKING** (O.A1).
- Question-card options: still F-P3-A-blocked (standing until that fix).
- Take-photo happy path / widget add-to-home / shade-ACTION approve / TalkBack
  runtime: standing stops (unchanged). The automation editor + context-sheet deep
  planes: code unchanged since their receipts — skipped per the re-receipt rule.
- The hook card's View + the CUA expand chevron: reachable this pass but left
  undriven (F-P3-A-starved plane; the hook trust surfaces were receipted pass 2/9).

## O.E — ledger arithmetic + the saturation adjudication

Open F: **24** · Open G: **21 + 3 standing** · Open smalls: **11 (+1 watch +1 rig)**
— ALL UNCHANGED (zero new rows, zero status changes beyond stamps).

**Backlog: 8/8.** Item 6's last driveable piece (the LEVEL selector) closed this
pass; every other remaining entry is a standing stop, an F-P3-A-blocked leg, or a
re-drive-after-fix-rounds item. Pass 12 therefore satisfies the ledger's stop
condition verbatim — *a full pass that opens ZERO new rows AND a fully ticked
backlog*: **the discovery campaign is SATURATED.** Every named discovery axis is
worked to rows or classified: the channel method tables (~41 exact), the i18n
namespace diff (exhausted at pass 10), the clientHello/runtimeCapabilities bits,
the renderer route inventory (dead angle), the desktop Mobile-Remote audit (G97),
the phone self-audit expansion (17 screens + diagnostics), the shipped-content
catalogs, and eight passes of coverage leftovers. The next step belongs to Ernest:
build the remediation roadmap from the ledger (24 F + 21 G + 3 standing + 11
smalls) when he chooses — the fix-round prompt is ready in the ledger's template
section.

### Coverage honesty (what pass 12 did NOT drive)

The question card's options (F-P3-A-blocked), the hook card's View/trust actions
and the CUA expand chevron (starved plane; hook trust receipted pass 2/9), "Keep
waiting" on the approval card (receipted pass 3, code unchanged), a fresh
agent-message send (receipted passes 1/5/6 — this pass's wires were all
command-channel verbs), the automation editor, the shade/lockscreen legs and all
other standing stops, and re-driving FIXED rows (none exist).

## Pass-12 verification stamp

Pass 12 completed 2026-11-14 ~11:50; relay generations gen1 `mock-p12-gen1-a1b2c3`
(11:22, light rig + MOCK_AUTOMATIONS/MOCK_OFFPEAK — the automations hub green) and
gen2 `mock-p12-gen2-b2c3d4` (11:32, full armed stack + MOCK_INTERACTION_PUSH_MS=
45000) — one launcher each (/tmp/p12/relay-g1.sh, relay-g2.sh), ONE emulator cold
boot (zcode-a16 11:18, boot <1 min; the SystemUI ANR dialog dismissed per the
M.A0.2 recipe). **Wifi-disable gotcha:** the canonical `svc wifi disable` step was
reverted ~11:26 (NAT-dead on API 36 — O.A0.1); all legs ran with wifi ENABLED.
15 screenshots `/tmp/p12/shots/s00–s14`; DB pulls `/tmp/p12/db/p12-1.db(+wal)`,
`p12-2.db(+wal)`; prefs pulls ×5 (`prefs-after-content.pb`, `prefs-after-minimal.pb`,
`prefs-queue2.pb` are the receipted ones); full logcat `/tmp/p12/logcat.log`; relay
logs `/tmp/p12/relay-g1.log`, `relay-g2.log`; AX dumps `/tmp/p12/w0–w24.xml`.
APK byte-identical to the series (sha256 `ad449d7d…`, md5 `530ba4ff…`, vc53/1.3.2;
rebuilt at HEAD, zero app changes). Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0).
Rig left: emulator zcode-a16 UP (PIN-less, stay-on 7 + screen_off_timeout 600000,
**wifi ENABLED**, screenshot protection OFF), relay gen2 ALIVE (armed stack,
nonce `mock-p12-gen2-b2c3d4`), app paired + CONNECTED on the Synthetic task
conversation (interactionBehavior="queue", autoDrain ON in-session), logcat running
to /tmp/p12/logcat.log. Nothing pushed.

---

# Part P — pass 13 (APPENDED 2026-11-14, ~17:00): the never-driven arm matrix — the delta plane's post-recovery death, the Usage screen's dead touch zone, and the answer-with-error chain's first working receipt

> **Pass 13 of the pass-0 series** (solo, the canonical discovery prompt re-invoked
> by Ernest after pass 12's saturation adjudication). The named backlog is 8/8, so
> this pass worked a NEW axis outside it: **the mock's full env-arm vocabulary
> diffed against everything the series has ever driven.** Eleven arms are
> enumerated in the mock source; five had NEVER been driven in-series
> (MOCK_USAGE, MOCK_TURN_RUNNING, MOCK_ENFORCE_RUN_DELTAS, MOCK_OFFPEAK_COMPLETE_MS,
> MOCK_TOOLS) — they were built for the vc52 campaign's rig legs and inherited
> un-driven. That axis was NOT exhausted in one pass: **3 new rows (F-P13-A P2,
> N-BP-1 P3, N-BP-2 P4), 3 stamps, 5 new mock/rig debts**, and the in-life
> off-peak notification leg + the delta happy path remain drivable. The banner in
> the ledger is amended accordingly: the campaign is saturated on the NAMED axes;
> the arm matrix is a live extension with legs left.
>
> **Rig fix (allowed, committed): mock-fidelity debt #1 CLOSED** — `91ec7ade`
> adds the `MOCK_UNKNOWN_INTERACTION_IDS=1` arm (the typed unknown-id rejection on
> resolveInteraction; self-tests 6/6 green). The app code was touched by nothing:
> the APK rebuilt byte-identical (sha256 `ad449d7d…`, md5 `530ba4ff…`, vc53/1.3.2).
>
> **Verification stamp (pass header):** 2026-11-14 15:45 → ~17:00, GLM-5.3-Flash
> in ZCode, everything local, nothing pushed. STEP 0: desktop 3.18.1 / asar
> `ec475e6e…` UNCHANGED — no DELTA. Generations: gen1 `mock-p13-gen1-c1d2e3`
> (15:46, MOCK_USAGE+MOCK_TOOLS), gen2 `mock-p13-gen2-f4g5h6` (15:59,
> MOCK_TURN_RUNNING), gen3 `…-i7j8k9` + `…-x1y2z3` (30s) + `…-d4e5f6` (60s/90s)
> (16:05–16:13, ENFORCE_RUN_DELTAS), gen4 `…-l0m1n2` (+`-h7i8j9`, `-k1l2m3`,
> `-n4o5p6`) (16:17–16:35, OFFPEAK_COMPLETE + DISCONNECT_AFTER_FRAMES),
> gen5a `…-r4s5t6` / gen5b `…-u7v8w9` (16:38–16:42, armed stack / the unknown-id
> arm). Emulator cold-booted once mid-pass (16:37, the NAT flake — P.A0.4).

## P.A0 — rig learnings (each bites the next pass)

1. **The never-driven-arm audit is the discovery method the named backlog could
   not enumerate**: `grep -oE 'System.getenv\("MOCK[A-Z_0-9]*"\)'` over the mock
   source, diffed against every launcher the series has recorded, yields the
   undriven set in minutes. Pass 13's three rows all came from arms, not from
   UI walking.
2. **`MOCK_OFFPEAK_COMPLETE_MS` and `MOCK_ENFORCE_RUN_DELTAS` count from RELAY
   START, not from the app's first CONNECTED** — the +12s flip landed 5 s BEFORE
   the app connected (gen4), and the one-shot delta pair (update +4 s, remove
   +14 s) raced every reconnect. Debts #11/#13; the legs need
   subscribe-anchored timing.
3. **`MOCK_DISCONNECT_AFTER_FRAMES=2` dies mid-auth** (the first connect never
   completes, so no baseline seeds); `=30` survives the handshake but dies at
   ~2.5 min (every poll frame counts — debt #12). The mid-life-disconnect recipe
   that WOULD work once #12 is fixed: baseline connect → flip → server-side
   drop → reconnect → diff → notify.
4. **The API-36 NAT grew a new tooth**: after the scripted server-side abrupt
   close (the disconnect arm), the emulator's IPv4-mapped-IPv6 path blackholes
   NEW app flows — SYN-SENT hangs with the ladder retrying honestly 6× — while
   pure-IPv4 device flows (`nc -z 10.0.2.2 18100`) still succeed, and a fresh
   PROCESS still hangs. Recovery = full emulator restart (cold-boot <1 min).
   Recorded as mock debt #14 (rig); the app was exonerated by the nc probe +
   the 10+ successful connects earlier in the same session on the same stack.
5. The SystemUI ANR dialog is a COLD-BOOT constant on this AVD (fourth sighting):
   dismiss with "Wait" (M.A0.2 recipe) and move on — it is the system's ANR, not
   the app's.

## P.A1 — gen1 (MOCK_USAGE + MOCK_TOOLS): the Usage plane's first data-bearing receipt, and two new smalls

**WORKING (first in-series — the Usage screen had only ever been receipted on its
honest-unavailable fault face):** with the arm serving the pinned wire fixtures
(`fixtures/protocol/phase{3,4}-wire-fixtures.json` via MOCK_FIXTURES_DIR — the
repo-root path, NOT tools/mockzcode-relative):

- The wire plane: `getAppUsageStats ok in 369ms`, `usage-stats.getCodingPlanUsageSnapshot ok`,
  `usage-stats.getCodingPlanResetStatus ok` — all three calls receipted on one screen open.
- The quota meters render real data: "Usage remaining — 5 hours · 64% · 05:20 +
  Quota reset link + progress bar"; "Weekly 49% Oct 21" with a **Reset** action;
  "Tool calls 88% Nov 12"; "ZCode MCP 78% Oct 15" (s02).
- The dashboard renders: range chips (7 days/30 days/All time), summary cards
  (184.6K total tokens · 51.2K peak day · 1h 30m longest session · 3d/9d streaks),
  Efficiency (42% cache-hit · 210 tool calls · 96 turns), the Activity heatmap
  (2026-09-28 – 2026-10-11), Daily trend + Daily activity bars, the Share-by-model
  PIE (glm-5.3 / glm-5.3-flash + legend), Models rows (114.5K·62% / 70.1K·38%),
  Tools rows (Bash 120·5%, Read 90·0%) — s02/s03/s04.
- **The G20 Plan usage charts RENDER** — the stacked per-day bars + totals line +
  GLM-5.3/GLM-5.1 legend (s04), fed by the concrete port
  (`ViewerPorts.kt:35 → hub.viewerTools().codingPlanUsage`; the interface default
  null at UsageScreen.kt:84-88 is overridden — checked, not assumed).

**The G90 reset verb chain — first live drive, WORKING end-to-end:** "Reset" on
the Weekly row → the typed confirm dialog ("Reset Weekly quota? / This consumes
one reset opportunity… / Resets available: 1 / Type RESET to confirm" with the
Reset button DISABLED until typed, s10) → typed RESET → the button enables (s11)
→ `usage-stats.useCodingPlanReset begin` → the mock answers
`fault.mock.unscripted:useCodingPlanReset` (debt #10) → **the panel renders the
honest typed failure inline: "Reset failed · fault.mock.unscripted:useCodingPlanReset"**
(s12, `CodingPlanQuotaSection.kt:456`). The consume loop works; the mock just
doesn't script the success half.

**NEW N-BP-1 (P3) — the double-inset dead band is ALSO a dead touch zone.** The
screen renders a ~400px empty band under the top bar (s02, persistent across
scroll — s03/s05/s06). Mechanism: `UsageScreen.kt` applies the Scaffold `inner`
padding TWICE — on the PullToRefreshBox (line 141) AND the inner Column (line
146); every one of the 11 sibling viewer screens applies it exactly once. The
band is not just visual: **scroll and pull-to-refresh gestures STARTING inside it
are swallowed** — 8 upward-scroll attempts from y≈500 did nothing (no scroll, no
refresh spinner, zero `getAppUsageStats` re-queries) while the identical drag
from y=1400 scrolled instantly (s09). The vc51 double-inset class, now with an
interaction half.

**NEW N-BP-2 (P4) — every Daily-trend label reads "2026-":** `day.date.take(5)`
(UsageScreen.kt:613) slices the first five chars of the ISO date the decoder
feeds (`2026-10-05` → `2026-`); the slice assumes an MM-DD shape the wire never
serves. Both fixture rows render the truncated label (s03).

**N-BO-1 stamp (2nd surface):** the reset failure face interpolates the raw fault
code — `stringResource(R.string.quota_reset_error) + " · " + err`
(CodingPlanQuotaSection.kt:456) — the same class as the changes viewer's
`changes_error` face. The G66 fault→human mapping misses a second production
surface.

**MOCK_TOOLS receipt (first in-series):** the kernel tool rows render live —
TaskUpdate ("Task control" · Completed · `{"taskId":"synth-task-1",…}`), Bash
("Shell"), Edit ("File write") with kind subtitles and chevrons, and Glob+WebSearch
GROUPED as "Explore · 2 searches" (s14). The seeded markdown row
`Done ~~temporarily~~ permanently.` renders with REAL strikethrough — the
markdown-conformance shape receipted in-series (it is mock copy, not an overlap
defect; checked before rowing).

## P.A2 — gen2 (MOCK_TURN_RUNNING): the stream-watchdog ladder's first in-series receipt

The arm seeds a `TURN_HEADER("running")` row into the snapshot. Live findings:

- **The stream watchdog ENGAGES and self-heals — receipted end-to-end**: after
  90 s of zero frames on the non-terminal turn, `recovery.streamWatchdog :: no
  frames for 90045ms — re-kick session=synth-task-1 strikes=1` →
  `recovery.escalate :: reason=stream watchdog (delivery stalled) attempt=1` →
  `resyncConversationV4 ok in 26ms` → `gateway.replay :: frames=0 (subscribe ack
  landed)` → quiet, heartbeats continue (logcat 16:02:35). The F-37-E-era
  question ("does the watchdog engage") is answered YES at HEAD.
- **The heal is SILENT**: no banner, no notice, no state change — the UI is
  pixel-identical before/after (s15 vs s17). Watch-note, not a row: when nothing
  was lost (frames=0) silence is defensible; the watch is whether a REAL
  frames>0 stall should surface something.
- **The turnHeader row kind is decode-only** — the "running" row renders as the
  bare "── Turn" separator; no running badge, no Stop affordance. NOT a defect:
  the task machine keys on the `control` block (SessionState.kt:199-222,
  phase/canStop/stopState — decoded and consumed), which this arm does not set.
  Recorded in the ledger's parity queue so nobody re-hunts it.

## P.A3 — gen3 (MOCK_ENFORCE_RUN_DELTAS): NEW F-P13-A (P2) — the delta plane is dead on every post-recovery connection

The phone DECLARES the capability at HEAD (`V4Plane.kt:667-675`:
`capabilities = {workflowRunDeltas: true, workspaceHookReviewUi: true}` — the
G48 vc52 round). The gate opens; the mock streams. Then the drop:

- 16:14:21.293 `rpc.recv :: bytes=1262 {"type":"data","payload":{…` — the
  `workflowRun.updated` delta (run patch + actors + nodes, the byte-verified
  shape) ARRIVES at the wire.
- 16:14:21.304 `gateway.drop :: session=synth-task-1 kind=ONLINE seq=1..1 (no
  subscription record)` — **dropped**.
- 16:14:51.307/322 — the `workflowRun.removed` delta (886 B): arrives → **dropped**
  the same way.
- Both while the app was subscribed (`relay.recovered :: subscriptions=2
  snapshotReplace=true` at 16:13:38.107, relay-6) and on the conversation screen.

**Mechanism (pinned, three links):** (1) the detach path drops the local
subscription record when the relay dies (`recovery.detached :: session=…
(unsubscribed)` — the unsubscribe send drops it even though the send itself
fails on the dead socket; V4Plane.kt:88's contract); (2) the recovery path
re-materializes the conversation via `relay.recovered … snapshotReplace=true`
and issues NO `subscribeConversationV4` — the g3d relay log contains zero
subscribe lines and the app log shows only `helloConversationV4` — so the
subscribe-ACK epoch stamp never re-lands (`RelayTransport.rotatePlane` copies
the records, but they were already dropped in (1)); (3) every later
server-initiated delta frame hits `ConversationGateway.kt:261-274`'s null-epoch
branch → `gateway.drop … (no subscription record)`. The `gateway.hold` buffering
for in-flight subscribes never applies (no subscribe is ever in flight). The
UI stays correct ONLY via the ~8s poll drum (F-P1-D's mechanism — this row
sharpens WHY the poll matters); on the real desktop, which pushes deltas
instead of serving polls, updates would silently never arrive.

**Self-heal boundary: process restart only** — a fresh process issues a real
first subscribe (the vc52-era tested path). Receipted: every generation after
the first kill-and-swap dropped its deltas; the fresh-process connects did not
get the chance to prove the happy path because the NAT flake (P.A0.4) ended the
gen4d relay leg — the delta happy path on a fresh process remains drivable and
is the first leg for a fix round's pin.

## P.A4 — gen4 (MOCK_OFFPEAK_COMPLETE_MS): partial — the flip, the completed face, the notifier's wire shape, and the process-scoped baseline

- **The flip WORKS and the completed face renders**: the +12s flip applied
  (no `completeOffPeakRow … not in the seeded list` no-op print), and the
  Idle-time-tasks card shows the flipped row — "Weekend doc migration ·
  <flip-time> ago · Continue" (the Continue verb opens the completed row's
  session; the queued face shows Pause/Cancel as seen on the RUNNING sibling
  row "Nightly refactor sweep · Pause · Cancel task" — s24/s26).
- **The notifier's wire shape receipted**: the app calls `off-peak-task.list`
  per CONNECTED transition (the G63 diff plane) and logs
  `offpeak.notifyFault :: TransportFailure: fault.mock.unscripted:offPeakTaskList`
  (WARN, log-only, no user face) on generations where the arm is absent —
  15:49:04 and 15:59:18 (gen1/gen2).
- **The notifier baseline is PROCESS-SCOPED — receipted**: with a queued
  baseline seeded at 16:24:01, the flip firing, then force-stop + relaunch: the
  fresh process connected, served the completed list, and posted NO
  notification (dumpsys clean). A completion that lands while the app process
  is dead NEVER notifies — the user who missed the moment is never told. Watch-note
  (P4-class); the in-life notification (queued baseline → in-life flip →
  CONNECTED#2 → diff → notify) was NOT driven: gen4c's 2-frame disconnect died
  mid-auth (no baseline) and gen4d's 30-frame disconnect fired at 2.5 min and
  triggered the NAT blackhole (P.A0.4) before the reconnect could land. The
  recipe is ready for the next pass once debt #12 lands.
- The automations hub + idle-tasks rows render green under
  MOCK_AUTOMATIONS+MOCK_OFFPEAK (s24) — re-receipt, unchanged.
- Incidental re-receipt: the draft-guard dialog ("Discard scheduled task
  draft?") fired on a STALE draft left by an earlier pass's session — the guard
  survives process-restarts-through-restore and the Discard path works (s20–s24).

## P.A5 — gen5 (the debt-#1 arm): the answer-with-error chain's first working receipt — store-honest, user-silent

The rig fix (`91ec7ade`) finally makes this leg drivable. Sequence: gen5a armed
stack seeds the interaction cards (store rows receipted via DB pull:
`mock-approval-1-mock-p13-gen5a-r4s5t6`, `q-rig-1-mock-p13-gen5a-r4s5t6`); swap
to gen5b (different nonce, empty slots, the unknown-id arm ON); the approval
card survives the generation swap and stays answerable in the Approvals inbox
("Permission — Bash · task Synthetic task · now · echo synthetic · Allow once /
Deny", s32) — **F-P1-B's stale-answerable shape, live again**; tap Allow once:

- The wire: `sendConversationCommandV4 begin → ok in 37ms` (relay-2, id=16) —
  the RPC succeeds; the REJECTION rides the envelope
  (`status:"rejected"`, `reasonCode:"fault.interaction.unknownId"` — the new
  gate's shape).
- **The store is HONEST (DB arbiter)**: the interaction row → lifecycle
  **FAILED**, resolvedAtMs set; the outbox row →
  `resolveInteraction | FAILED_TERMINAL | ackStatus=rejected | attempts=1`.
  The typed-refusal branch (the pass-1 GOAL_*_REFUSED plumbing) consumed the
  rejection correctly — no fake RESOLVED, no silent no-op.
- **The user face is SILENT**: the card simply clears; the inbox renders the
  empty face ("Nothing needs you", s33); no "answer not delivered" notice, no
  retry. The desktop never received the answer and the user is never told.
  **F-P7-B stamp 2** — the same no-face outcome, now for the typed-rejection
  trigger (the row's remedy — a visible not-delivered/retry face — covers both
  triggers; one row stands).
- **The generation sweep is PARTIAL in both directions — F-P1-B stamp 5**: the
  approval row survived the swap answerable (the sweep missed it), while its
  sibling question row got RESOLVED by the same swap (`q-rig-1…gen5a… |
  RESOLVED | 1` in the same pull). The sweep neither preserves nor expires
  reliably; the row stands with a sharpened mechanism note.

## P.D — STEP 2 coverage adjudication

- The five undriven arms: driven this pass (gen1–gen5); two legs remain — the
  in-life off-peak notification (needs mock debt #12's frame-class filter +
  a stable NAT) and the delta happy path on a fresh process (blocked only by
  the gen4d NAT flake; one clean relay + fresh process answers it).
- The pass-12 coverage list: question-card options still F-P3-A-blocked;
  Take-photo/widget/shade-ACTION/TalkBack standing stops unchanged; the hook
  card's View + CUA chevron still starved-plane (untouched this pass; the
  armed stack was consumed by the gen5 legs).
- The automations editor + context-sheet deep planes: code unchanged since
  their receipts — skipped per the re-receipt rule (the draft-guard dialog
  incidentally re-receipted at gen4, P.A4).

## P.E — ledger arithmetic

Open F: **25** (was 24; +F-P13-A) · Open G: **21 + 3 standing** (unchanged; the
turnHeader note joined the parity queue, not the G table) · Open smalls: **13**
(was 11; +N-BP-1, N-BP-2) · FIXED: 2 (both rig, unchanged) · Mock debts: 14
(was 9; #10–#14 new, #1 CLOSED by `91ec7ade`).

### Coverage honesty (what pass 13 did NOT drive)

The delta happy path on a fresh process (NAT flake; one clean relay answers
it), the in-life off-peak notification (needs debt #12 + stable NAT), the
reset SUCCESS face (mock debt #10 — the mock never scripts the accept), the
30-days/All-time range chips (static fixture; chip-select state only), the
hook card's View/trust + CUA chevron (starved plane, untouched), all standing
stops (Take-photo happy path, widget add-to-home, shade-ACTION approve, TalkBack
runtime, Dictate), and re-driving FIXED/REFUTED rows (none changed).

## Pass-13 verification stamp

Pass 13 completed 2026-11-14 ~17:00; 11 relay generations across 6 arm
configurations (nonces `mock-p13-gen1-c1d2e3` → `mock-p13-gen5b-u7v8w9`,
launchers `/tmp/p13/relay-g*.sh`); ONE emulator cold-boot mid-pass (16:37,
the NAT blackhole recovery — P.A0.4) plus the pass-start reuse of the pass-12
emulator (fresh `adb logcat -T 1` at 15:47 → /tmp/p13/logcat.log and again
post-boot → /tmp/p13/logcat2.log). Receipts: 33 screenshots
(`/tmp/p13/shots/s00–s33`), AX dumps `/tmp/p13/w/w0*.xml`, DB pulls
`/tmp/p13/db/` (p13-g5a.db, g5b-main.db+wal, the sqlite queries receipted in
P.A5), relay logs `/tmp/p13/relay-g*.log`, build log `/tmp/p13/build.log`
(mock self-tests 6/6 in TEST-com.zcode.android.tools.mockzcode.MockZCodeUnknownInteractionIdSelfTest).
APK byte-identical to the series (sha256 `ad449d7d…`, md5 `530ba4ff…`,
vc53/1.3.2; rebuilt at HEAD with zero app changes). Desktop 3.18.1 / `ec475e6e…`
unchanged (STEP 0). Rig left: emulator zcode-a16 UP (cold-booted 16:37,
PIN-less, stay-on, wifi ENABLED, screenshot protection OFF), relay gen5b ALIVE
(`mock-p13-gen5b-u7v8w9`, UNKNOWN_INTERACTION_IDS + TASKMGMT), app paired +
CONNECTED on home, logcat → /tmp/p13/logcat2.log. Commits: `91ec7ade`
fix(mock) + the docs commit. Nothing pushed.

# Part Q — pass 14 (APPENDED 2026-11-14, ~23:30): the arm matrix's last legs — the delta happy path receipted, the reset SUCCESS face, the background off-peak notification, and the mid-life disconnect — plus the four rig debts that unblocked them

> **Pass 14 of the pass-0 series** (solo, the canonical discovery prompt; the arm-matrix
> axis pass 13 opened, now EXHAUSTED). The named backlog stayed 8/8; this pass worked
> the two legs pass 13 recorded as undriven plus the four mock-fidelity debts that
> blocked them: **#10** (the scripted `useCodingPlanReset` accept progression), **#11**
> (the first-list anchor for `MOCK_OFFPEAK_COMPLETE_MS`), **#12** (the DATA-frame-class
> filter for the disconnect storm), **#13** (the subscribe anchor for
> `MOCK_ENFORCE_RUN_DELTAS`) — one `fix(mock)` commit `8361b769`, self-tests
> `MockZCodePass14RigSelfTest` 11/11, full mock suite 146/0, app untouched (the APK
> rebuilt byte-identical `ad449d7d…` / `530ba4ff…`, vc53/1.3.2). Every leg landed a
> WORKING receipt; the pass opened ZERO new rows (one candidate — the foreground-
> consumed off-peak completion — was adjudicated as designed behavior after the
> background half receipted; see Q.A3). With this pass the mock's entire env-arm
> vocabulary has been driven in-series: the discovery campaign is saturated on BOTH
> the named axes (pass 12) and the arm matrix (pass 14).
>
> **Verification stamp:** 2026-11-14 22:28 → ~23:30, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no
> DELTA. Generations: gen1 `mock-p14-gen1-a1b2c3` (22:44, ENFORCE_RUN_DELTAS +
> RUN_DELTA_ANCHOR=subscribe), gen2 `…-d4e5f6` (22:52, USAGE + FIXTURES +
> RESET_SUCCESS), gen3 `…-g7h8i9` (22:55) / gen3b `…-j2k3l4` (23:06) (OFFPEAK +
> OFFPEAK_COMPLETE first-list-anchored — the foreground-consumption reproductions),
> gen3c `…-m4n5o6` (23:15, queued reseed) / gen3d `…-p7q8r9` (23:16, pre-flipped) (the
> background-notify leg, Live-connection FGS), gen4 `…-s1t2u3` (23:19,
> DISCONNECT_AFTER_DATA_FRAMES=2). ONE emulator cold-boot at pass start (22:46 — the
> pass-13 rig's pkill-abrupt-close left the debt-#14 NAT blackhole armed; recovery
> per the recipe, P.Q.A0.1).

## Q.A0 — rig learnings (each bites the next pass)

1. **A plain `pkill -f MockRelayMainKt` against a CONNECTED app can arm the debt-#14
   NAT blackhole for the NEXT process**: pass 14's first fresh launch timed out at
   AUTHENTICATING against a live relay while `nc -z 10.0.2.2 18100` succeeded from
   the device — the exact blackhole signature. Full emulator cold-boot recovered in
   25 s; every later relay swap reconnected normally (the blackhole is probabilistic,
   not deterministic — six abrupt-close swaps later in the pass all recovered).
   Recipe: after ANY pass-start pkill, expect one possible dead connect; probe with
   the app's first connect before blaming anything.
2. **The notify plane logs under tag `ZCODE-NOTIFY`, not `ZCODE-PAIR`** — every
   earlier pass's `grep "ZCODE-PAIR"` + `notify.` sweeps were structurally blind to
   `notify.show`/`notify.suppressed`. The receipt vocabulary: `I ZCODE-NOTIFY:
   notify.show :: key=task:offpeak-rig-1:TASK_ channel=task-status`. Sweep BOTH tags.
3. **An expanded notification shade INTERCEPTS everything**: dumps showed SystemUI
   nodes (`Battery 100 percent`, `Clear all notifications`) and taps went nowhere.
   `adb shell cmd statusbar collapse` before any in-app driving after a shade leg;
   `cmd statusbar expand-notifications` is its (working) inverse.
4. **The GMS sign-in screen hijacks the foreground after cold boots** (first
   sighting this campaign): Settings taps landed on "Sign in with ease / NEXT /
   SKIP". Anchor dumps on the app's `settings_scroll` resource-id before tapping;
   dismiss via SKIP at screenshot coords (dump coords worked here: [22,2109]).
5. The typed-confirm dialog's dump bounds CHANGED after the IME closed
   ([1464]→[1080]) and this time the dump MATCHED the screenshot — the D.A0.2
   ~200px-lie is direction-dependent; the recipe stands (derive dialog taps from the
   screenshot taken at decision time; treat dump bounds as advisory).
6. `keyevent 66` (ENTER) in the composer inserts but does NOT send (F.A0.6
   re-confirmed); `Send message` resolves at [894,2118][1026,2250] after a BACK
   (IME-close). The draft survives a failed send (receipted).

## Q.A1 — gen1: the delta happy path on a FRESH process — F-P13-A's boundary claim receipted

The leg pass 13 could not finish (the gen4d NAT flake). Armed with the debt-#13
subscribe anchor (`MOCK_RUN_DELTA_ANCHOR=subscribe`, update +6 s / remove +22 s from
the FIRST conversation subscribe; the relay had been up 5 min when the leg ran):

- Fresh process (pid 3499) → CONNECTED → conversation open → `subscribeConversationV4
  ok in 161ms` (22:49:56.855) → INITIAL `gateway.frame` (epoch stamped).
- **22:50:02.791 `gateway.frame :: kind=ONLINE seq=2..2`** — subscribe+6.1 s: the
  anchored schedule fired at the SUBSCRIBE, not relay start (relay-start +6 s would
  have been 22:45:05, pre-connect — the anchor is proven by the arithmetic alone).
- **22:50:18.806 `gateway.frame :: kind=ONLINE seq=3..3`** — subscribe+22.1 s: the
  `workflowRun.removed` applied.
- **`gateway.drop` count for the whole pass: 0.** Both deltas reached the decode
  path on the FIRST process's first subscribe. F-P13-A stamps 2: the row's
  "self-heal boundary = process restart only" claim now has its positive receipt —
  the happy path is real; the row stands for the post-recovery half only.
- Workflow-run projections are NOT Room-persisted (the tables carry no wf-run
  state — checked g1-pair.db) — the gateway log IS the arbiter for this plane.

## Q.A2 — gen2: the G90 reset SUCCESS face — first live drive, WORKING end-to-end

`MOCK_RESET_SUCCESS=1` scripts `{used:true}` + stamps the use; every later
`getCodingPlanResetStatus` serves the CONFIRMED progression (consumed opportunity +
usedAt history + unread cursor — exactly `applyResetStatus`'s completion rule).

- The typed-confirm dialog rendered identical to pass 13 (s04/s05: "Reset Weekly
  quota? / Resets available: 1 / Type RESET to confirm", Reset disabled until
  typed, enabled after).
- 22:54:01.596 `useCodingPlanReset begin` → 22:54:02.025 **ok** (the scripted
  success — pass 13's `fault.mock.unscripted` is gone).
- 22:54:02.031 the FIRST reconciliation poll (`useRetryDelaysMs[0]`) → the
  confirmed status → **COMPLETED on the first poll** (no 250/750/1500 retries).
- 22:54:02.133 **`markCodingPlanResetHistoryRead ok`** — the completion
  side-effect that is structurally unreachable on the failure path fired.
- 22:54:02.292 `getEntitlementSnapshot` (the post-completion `load()` refresh) →
  the final render (s07): **"Quota reset" (the COMPLETED note, primary color) under
  the Weekly meter**; the Weekly Reset chip GONE (opportunity consumed); the
  5-hours side intact with its "Reset (2)" count chip.
- Mock-fidelity nits recorded as debts (no app misbehavior): **#15** —
  `markCodingPlanResetHistoryRead` acks but never clears `hasUnreadHistory` (the
  desktop clears the cursor), so every later status stays hasUnreadHistory=true;
  **#16** — the scripted reset doesn't mutate the entitlement meters, so the
  Weekly meter still reads 49% beside the "Quota reset" completion note on the rig.

## Q.A3 — gen3/gen3b + gen3c/gen3d: the in-life off-peak notification — the background half WORKS, the foreground half is by-design (adjudicated, not rowed)

The leg pass 13 designed (queued baseline → in-life flip → CONNECTED#2 → diff →
notify), driven twice and fully understood only on the third shape:

- **gen3 + gen3b (the foreground reproductions — SILENT, 2×):** baseline seeded
  queued per-CONNECTED (relay-3/relay-8 lists 26 ms after CONNECTED), the
  first-list-anchored flip fired mid-life (`MOCK: offpeak offpeak-rig-1 →
  completed` in both relay logs — debt #11's anchor verified: the flip can no
  longer outrun the baseline), screen-off → foreground → CONNECTED#3 (relay-7/
  relay-13) → the notifier re-listed the completed rows → **no notification, no
  notify.* line, no offpeak.postFault** — reproduced cleanly with the outcomes
  gate ON (the Settings switch read checked=true on-device; dump w14) and the
  diff material on the wire. Two systematic blind spots were ruled out first: the
  gate (UI switch true, domain default true), the decode (the transport's
  `{tasks:[…]}` normalization + strict decode both live-verified in code), and a
  background-consumption race (no intermediate CONNECTED existed between flip and
  wake — timeline receipted).
- **The mechanism (pinned, code read):** `NotificationPlan.decide` returns
  `Decision(false, …)` whenever `policy.appForeground` (NotificationPlan.kt:129-130,
  §9.9 "while foregrounded the in-app surfaces own attention"), and the broker has
  ALREADY upserted the informational record with `seen = true` before the show
  check (`seen = !attention`, TASK_FINISHED is not attention) — so a completion
  whose CONNECTED transition lands while the app is foreground is consumed into a
  seen record and never surfaces. My greps also initially missed the plane
  entirely (Q.A0.2's tag blindness).
- **Adjudication — NOT a row:** the foreground case has its in-app surface — the
  home Idle-time-tasks card renders the completed row persistently (pass 13
  receipted the flipped row on exactly that card, P.A4 s24/s26); §9.9's contract
  ("in-app surfaces own attention while foregrounded") is satisfied for this kind.
  The gen3/gen3b silence was my leg design (foregrounding BEFORE the reconnect),
  not app behavior. Recorded here so no future pass re-hunts it; the fix-round
  opportunity (bypass the foreground rule for informational kinds that lack a card)
  stays an OPPORTUNITY, not a defect.
- **gen3c → gen3d (the corrected recipe — WORKING, first in-series):** Live
  connection ON (the FGS notification receipted on-device), the app BACKGROUNDED
  (launcher topResumed), gen3c reseeded the baseline queued (relay-17 list ok
  23:16:12.366), swap to gen3d whose seed is PRE-FLIPPED (the legacy 1 ms
  relay-start anchor — serving completed from boot): **23:17:02.395 RECONNECTING →
  23:17:03.935 CONNECTED (relay-18 — the FGS background ladder recovered in 1.5 s,
  unthrottled as designed) → 23:17:03.962 the notifier's list ok → 23:17:03.978
  `notify.show :: key=task:offpeak-rig-1:TASK_ channel=task-status` (ZCODE-NOTIFY)
  → the shade shows "ZCode Companion · now — Task completed"** (s10, visual
  receipt; dumpsys corroborates). The G63 notifier's complete live path —
  per-CONNECTED diff → gate → plan → sink → shade — is receipted end-to-end.

## Q.A4 — gen4: the mid-life disconnect — the debt-#12 filter works, the recovery is honest

`MOCK_DISCONNECT_AFTER_DATA_FRAMES=2`, fresh process, conversation open:

- The filter's semantics held exactly: the connection survived the handshake and
  ~70 s of poll traffic (under the unfiltered arm with =2 pass 13 died mid-auth),
  then died at the SECOND data push — the conversation subscribe's INITIAL
  snapshot (frame 1, `gateway.frame seq=0..1` 23:20:19.731) + the send's
  rowAppended delta (frame 2, `gateway.frame seq=2..2` 23:21:30.538) → the mock's
  `ws.close(1011)`.
- **The recovery chain (23:21:40.767 → 23:21:42):** `relay.lost :: abnormal close
  1006: Broken pipe` → the in-flight send failed honestly
  (`sendConversationCommandV4 failed: channel closed` + `command.rejected ::
  type=sendText`) → RECONNECTING → auth matched (23:21:41.437) → RECOVERING →
  relay-2 healthy (polls + heartbeats). **~1.2 s total, no wedge** — F-P9-A's
  AUTHENTICATING wedge does NOT apply to mid-life closes with the ladder live.
- **The user face renders (s11):** "The desktop refused the send — see diagnostics
  (fault reason recorded)." beside the composer; the draft ("ping") survives.
- **Watch-note (not a row):** the mock's pushed rows APPLIED (seq=2..2 carried the
  user row + the synthetic reply — both render in the timeline) while the send's
  ACK was lost → the banner says "refused" beside rows that show as delivered. The
  desktop actually processed the send (its push proves it); the phone just never
  learned the ACK. Technically honest on both sides; noted so the apparent
  contradiction isn't re-hunted.
- No NAT recurrence: the abrupt server close reconnected cleanly (the blackhole
  stayed dormant for the rest of the pass).

## Q.D — STEP adjudication: the arm matrix is EXHAUSTED

- Pass 13's two undriven legs: **both driven and receipted this pass** (the delta
  happy path on a fresh process — Q.A1; the in-life off-peak notification — Q.A3).
- The reset SUCCESS face (mock debt #10): driven — Q.A2.
- The mid-life disconnect leg (mock debt #12): driven — Q.A4.
- The mock's env-arm vocabulary is now fully driven in-series across passes 13–14
  (MOCK_USAGE, MOCK_TURN_RUNNING, MOCK_ENFORCE_RUN_DELTAS, MOCK_OFFPEAK_COMPLETE_MS,
  MOCK_TOOLS, MOCK_UNKNOWN_INTERACTION_IDS, plus the driven-before arms). The
  reset-status Queue progression (#10's alternative mechanism) remains for future
  multi-step legs; #15/#16 are the pass's new rig debts.
- Standing stops unchanged: Take-photo happy path, widget add-to-home, shade-ACTION
  approve, TalkBack runtime, Dictate, question-card options (F-P3-A-blocked).

## Q.E — ledger arithmetic

Open F: **25** (unchanged — zero new rows; F-P13-A stamps 2 with the positive
boundary receipt) · Open G: **21 + 3 standing** (unchanged) · Open smalls: **13**
(unchanged) · Mock debts (16 numbered): **6 CLOSED** — #1 (pass 13 `91ec7ade`),
#9 (pass 11 `1e649eab`), #10–#13 (pass 14 `8361b769`) — **10 OPEN** (#2–#8,
#14, plus #15/#16 opened this pass).

### Coverage honesty (what pass 14 did NOT drive)

The 30-days/All-time range chips (static fixture), the hook card's View/trust +
CUA chevron (starved plane, untouched), MOCK_TURN_RUNNING's in-life watchdog
(gen-shaped, unchanged since P.A2), the automations editor deep planes (code
unchanged — skipped per the re-receipt rule), the reset-statusQueue mechanism
(#10's queue variant — the flag path was driven), disconnect limits other than
=2, TalkBack/Dictate/widget/Take-photo standing stops, and re-driving
FIXED/REFUTED rows (none changed).

## Pass-14 verification stamp

Pass 14 completed 2026-11-14 ~23:30; 7 relay generations across 5 arm
configurations (nonces `mock-p14-gen1-a1b2c3` → `mock-p14-gen4-s1t2u3`, launchers
`/tmp/p14/relay-g*.sh`); ONE emulator cold-boot at pass start (22:46, the debt-#14
blackhole recovery — P.Q.A0.1) + the inherited pass-13 emulator found in the
blackholed state (logcat fresh `adb logcat -T 1` at 22:45 → /tmp/p14/logcat.log,
restarted after the boot). Receipts: 12 screenshots (`/tmp/p14/shots/s00–s11`),
AX dumps `/tmp/p14/w/w00–w31.xml`, DB pulls `/tmp/p14/db/` (g1 pair — the
no-workflow-table check, Q.A1), relay logs `/tmp/p14/relay-g*.log`, the full-app
window dump `/tmp/p14/g3b-window.log`, build log `/tmp/p14/build.log` (APK
byte-identical `ad449d7d…` / `530ba4ff…`, vc53/1.3.2; mock self-tests 11/11 +
146/0). Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0). **Rig left:** emulator
zcode-a16 UP (cold-booted 22:46; PIN-less, stay-on 7, wifi ENABLED, screenshot
protection OFF by persistence), relay gen4 ALIVE
(`mock-p14-gen4-s1t2u3`, DISCONNECT_AFTER_DATA_FRAMES consumed — the socket closed
once and reconnected; the arm is spent), app pid 5258 paired + CONNECTED on the
conversation screen, **Live connection ON (left from the gen3c/3d leg — the FGS
30-minute window; toggle it off if unwanted)**, logcat running →
/tmp/p14/logcat.log. Commits: `8361b769` (fix(mock), debts #10–#13) + the docs
commit for this part.

# Part R — pass 15 (APPENDED 2026-11-15, ~01:10): the coverage-honesty leftovers driven — the Usage range chips' first drive, the reset meters/unread fixes live, the reconciliation retry ladder's first receipt, and the F-P13-A boundary (stay-open survives ANY kill; the drop is close-cycle-bound) — plus the two pass-14 mock debts fixed

> **Pass 15 of the pass-0 series** (solo, the canonical discovery prompt; both named
> axes saturated — this pass worked the STEP-2 coverage-honesty list left by Part Q,
> and the open mock-fidelity debts that gated two of its legs). **Mock debts #15
> and #16 CLOSED** (one `fix(mock)` commit `66844fbe`, self-tests
> `MockZCodePass15RigSelfTest` 9/9, full mock suite 155/0, app untouched — the APK
> rebuilt byte-identical `ad449d7d…` / `530ba4ff…`, vc53/1.3.2) **plus the
> reset-statusQueue variant armed** (`MOCK_RESET_QUEUE_PRE`: queue entries now serve
> EXPLICITLY — the pass-14 confirmation fold no longer confirms a queued pre-consume
> entry in place — which is what made the ladder leg drivable). Live legs: the Usage
> range chips (first-ever drive — CLOSED no-defect), the reset re-drive under the
> fixed mock (49%→59% meter + the unread clear, both live), the post-use
> reconciliation RETRY LADDER (first in-series receipt), the disconnect filter at
> limit=1, and the F-P13-A boundary leg (the pass's material finding: the delta-plane
> drop is CLOSE-CYCLE-bound; the stay-open path survives any kill with the deltas
> fully live — zero gateway.drop in the whole pass). ZERO new rows; two stamps
> (F-P9-A 3, F-P13-A 3, the latter with a scope-narrowing code pin).
>
> **Verification stamp:** 2026-11-15 00:44 → ~01:05, GLM-5.3-Flash in ZCode,
> everything local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…`
> UNCHANGED — no DELTA. ONE emulator cold-boot at pass start (00:44 — the rig was
> found DOWN: the pass-14 emulator died overnight; screenshot protection OFF by
> persistence, PIN-less, wifi ENABLED per the O.A0.1 rule — `svc wifi disable` NOT
> run). Generations: gen1 `mock-p15-gen1-a1b2c3` (00:45, USAGE + FIXTURES +
> RESET_SUCCESS — legs A/B), gen2 `…-d4e5f6` (00:54, +RESET_QUEUE_PRE — leg C),
> gen3 `…-g7h8i9` (00:57, DISCONNECT_AFTER_DATA_FRAMES=1 — leg E first half), gen4
> `…-j2k3l4` (00:59, ENFORCE_RUN_DELTAS + RUN_DELTA_ANCHOR=subscribe — leg E second
> half, F-P13-A's boundary).

## R.A0 — rig learnings (each bites the next pass)

1. **The rig does not survive the night**: the pass-14 "left UP" emulator was DOWN
   the next day (no qemu process; the stale gen4 relay still held the port).
   Cold-boot per the recipe; the app's data (pairing, screenshot protection, prefs)
   all persisted across the boot. Recipe unchanged, but every pass should EXPECT a
   boot, not a resume, after any gap.
2. **The SystemUI ANR dialog recurred twice this pass** (under swiftshader, cold
   boot): it BLOCKS uiautomator entirely (12-node dumps: only the dialog) and
   swallows taps aimed beneath it. Dismiss via the dump-derived `Wait` center
   ([71,1276][1009,1408] this boot); it did not recur after the first two dismissals.
3. **F-P9-A's THIRD trigger variant receipted**: relay ABSENT at the cold-start
   connect (the app launched before the relay came up) → `hub.connectFailed ::
   TIMEOUT connect timed out after 10000ms` at 00:44:40 → then TOTAL silence — no
   retry ladder, no logs, no face for 2+ minutes while the device-side NAT was fine
   (`nc -z 10.0.2.2 18100` REACHABLE). Escape = force-stop + relaunch (the known
   one). Same wedge as L.A2's mid-auth kill, different trigger class; stamps 3.
4. Relay-swap recovery stayed honest across three abrupt pkill swaps
   (relay.lost 1006 → RECONNECTING → RECOVERING → CONNECTED in 1.3–2.4 s; the
   debt-#14 blackhole stayed dormant).
5. The off-peak notifier faults honestly on gens without `MOCK_OFFPEAK`
   (`off-peak-task.list refused: fault.mock.unscripted:offPeakTaskList` per
   CONNECTED — debt #8's known shape; harmless, never surfaced a card).

## R.A1 — gen1, leg A: the Usage RANGE CHIPS — first drive, CLOSED no-defect

The Part-Q coverage-honesty item ("the 30-days/All-time range chips — static
fixture") driven to its honest bottom:

- Baseline (s02): Usage open on MOCK_USAGE with real data; "7 days" selected; the
  full stat grid (184.6K total tokens, 51.2K peak day, streaks, efficiency cards),
  the meters (5h 64% / Weekly 49% / Tool calls 88% / MCP 78%), the reset affordances.
- Each chip tap fired a FRESH wire call: `getAppUsageStats` id=13 (initial) → id=17
  ("30 days" tap) → id=19 ("All time" tap) — causality verified by counting around
  the tap. The selection highlight moved 7 days → All time (s02 vs s03 — visual
  receipt); the rendered NUMBERS are identical across ranges (the mock serves the
  static `app-usage-full` fixture regardless of range — the Part-Q note anticipated
  exactly this).
- The app side is correct by contract: `ViewerTools.appUsageStats(range, timeZone)`
  carries the range on the wire, enum `"all"|"7d"|"30d"` (ViewerTools.kt:31-37,
  "verified enum"), and the screen re-queries per tap. **No app defect** — the
  chips drive, the wire carries the range, only the RIG cannot vary the data.
  Coverage item CLOSED.

## R.A2 — gen1, leg B: the reset re-drive under the FIXED mock — debts #15/#16 live

Pre-reset baseline: Weekly meter **49%** (fixture usage 51/remaining 49), the
Weekly "Reset" chip present (s02/s04). Typed-confirm dialog identical to pass 14
(s04: "Reset Weekly quota? / Resets available: 1 / Type RESET to confirm"; Reset
disabled → enabled after typing; dialog taps derived from the decision-time
screenshot per Q.A0.5 — the dump bounds shifted again after the IME opened).

- 00:51:07.971 `useCodingPlanReset begin` → 00:51:08.683 **ok** → 00:51:08.948
  `markCodingPlanResetHistoryRead begin` → ok 00:51:09.456 — **relay line
  `MOCK: reset history read (hasUnreadHistory cleared)`** (debt #15's fold, live:
  every later status read now serves the cleared cursor).
- 00:51:09.461 `getEntitlementSnapshot begin` (the post-completion load()
  refresh) → ok — **relay line `MOCK: entitlement meters mutated
  (WEEK/unit-6 after use at 1794703867628)`** (debt #16's fold, live).
- The visual (s05): **the Weekly meter reads 59%** (was 49% — exactly the
  scripted delta-10: usage 51→41, remaining 49→59), the Weekly Reset chip GONE
  (opportunity consumed), the "Quota reset" COMPLETED note under the Weekly row,
  and the OTHER window untouched: 5 hours still 64% with its "Reset (2)" chip,
  Tool calls 88% / MCP 78% unmoved. **Both debts CLOSED** (`66844fbe`); the
  pass-14 "meter disagrees with the completion note" residual is gone.

## R.A3 — gen2, leg C: the reconciliation RETRY LADDER — first in-series receipt

The mock-side prerequisite (test-first, `MockZCodePass15RigSelfTest` 9/9): the
`resetStatusQueue` entries now serve EXPLICITLY — only the #15 unread fold applies
to them — because the pass-14 confirmed fold otherwise confirmed a queued
pre-consume entry in place (defeating the queue's declared purpose).
`MOCK_RESET_QUEUE_PRE=1` arms the leg: one pre-consume copy of the scripted status
is queued at use time.

- Fresh gen2 baseline: Weekly back to 49%, chip back (the per-relay re-seed, by
  design). Typed confirm → **use ok 00:55:41.597** → relay
  `MOCK: reset ladder armed (one pre-consume status queued at use)`.
- **Read #1** 00:55:41.612 (ok 41.931) — relay `MOCK: reset status served from
  QUEUE`: the PRE-CONSUME shape (week opportunity still intact, no usedAt) → NOT
  confirmed → the app schedules a retry.
- **Read #2** 00:55:42.203 — **+272 ms after read #1's completion** — the
  fallback's confirmed fold → COMPLETED. (Read #1's completion 41.931 + the
  `useRetryDelaysMs[0]` 250 ms + processing ≈ 42.203 — the ladder's delay measures
  from the PREVIOUS READ'S COMPLETION, not from the use; a new app-behavior
  detail.)
- Completion side-effects: `markCodingPlanResetHistoryRead ok` 00:55:42.305 (relay
  "reset history read") → **Read #3** 00:55:42.603 (+364 ms, read #2-completion +
  250 ms again) → the mutated meters ("entitlement meters mutated (WEEK/unit-6)").
- Visual (s06): identical completed face to R.A2 (Weekly 59% + note, chip
  consumed). **Three status reads where pass 14's flag path had ONE** — the
  poll-until-confirmed ladder is receipted live for the first time; gen2's swap
  itself recovered honestly (relay.lost → CONNECTED in 1.3 s).

## R.A4 — gen3/gen4, leg E: the disconnect filter at limit=1 + THE F-P13-A BOUNDARY

**gen3 (DISCONNECT_AFTER_DATA_FRAMES=1):** the filter died ONE FRAME EARLIER than
pass 14's =2 receipt (Q.A4 survived frame 1 and died at frame 2): conversation
open → `subscribeConversationV4 begin` 00:58:17.716 → **`relay.lost :: abnormal
close 1006` at 17.829, +113 ms — the FIRST data push (the subscribe's INITIAL
snapshot)** → the subscribe's ok raced the close (17.961) → RECONNECTING →
RECOVERING → and then the recovery **re-subscribed**: `subscribeConversationV4
begin` on relay-4 at 19.595 → ok 19.751 → `relay.recovered :: subscriptions=2
snapshotReplace=true` → CONNECTED 19.827 → `gateway.frame INITIAL seq=0..1
epoch=epoch-mock-1` (the epoch re-stamped) → `recovery.snapshotReconcile rows=41`.

**gen4 (the boundary leg):** the subscribe stayed ESTABLISHED ~80 s, then the
relay was killed (pkill — out-of-band 1006 Broken pipe) and swapped to a fresh
gen4 carrying the delta arms (ENFORCE_RUN_DELTAS + ANCHOR=subscribe + 6 s/22 s):

- relay.lost 00:59:39.611 → RECOVERING → **subscribe begin on relay-5 at 42.195 →
  ok 42.306 → relay.recovered 42.309** → CONNECTED 42.436 → INITIAL epoch 42.755.
- **00:59:48.376 `gateway.frame :: kind=ONLINE seq=2..2 epoch=epoch-mock-1`** —
  the anchored update landed at subscribe+6.07 s (anchor proven by the arithmetic,
  Q.A1-style) on the POST-RECOVERY connection and **applied** (no drop).
- **01:00:04.407 `gateway.frame :: kind=ONLINE seq=3..3`** — the anchored remove at
  +22.1 s, also applied.
- **`gateway.drop` count for the whole pass: 0** (708 ZCODE-PAIR lines swept).

**THE BOUNDARY (material — F-P13-A narrows):** the SAME app code that dropped
every post-recovery delta at pass 13 (P.A3, receipted 2×) now recovered the delta
plane COMPLETELY across two kills — one with the subscribe in flight, one with it
80 s established. The variable is NOT the build (no app commits between aa933f41
and HEAD) and NOT the screen state (pass 13's repro was also on the conversation
screen). It is the LIFECYCLE CYCLE:

- **Pass 13's shape**: the conversation was CLOSED in the cycle — the
  `recovery.detached :: session=… (unsubscribed)` line proves `detach()` ran.
  Code-pinned THIS pass: `detach()` fires ONLY from `closeConversation()`
  (ConnectionHub.kt:1027-1052 → RecoveryCoordinator.kt:130-149) — it unsubscribes
  on the wire, drops the subscription record, and detaches the manager; the
  recovery's re-materialize (`relay.recovered … snapshotReplace=true`) issues NO
  `subscribeConversationV4` for a detached session — the pass-13 chain, exactly.
- **Pass 15's shape**: the conversation STAYED OPEN through both kills —
  `recovery.detached` NEVER fired this pass (zero occurrences) — so the record and
  manager survived, and the recovery re-subscribed (begin during RECOVERING) with
  the epoch re-stamping and the deltas fully live.
- The kill classes also split clean: pass 13/14 used the mock's in-band
  `close(1011)` storm, pass 15 used pkill (out-of-band 1006) — in the STAY-OPEN
  shape the recovery is resilient to the kill class driven here; the drop is
  CYCLE-bound, not kill-bound (as far as this pass drove).

**Ledger treatment:** F-P13-A stamps 3 with the scope note — the row's mechanism
and fix shape stand UNCHANGED for the close/detach-cycle path (that receipt was
2× at pass 13 and is not re-driven per the rules); the "every post-recovery
connection" universality is withdrawn in favor of the cycle boundary. The stay-open
resilience is a first-series WORKING receipt (the timeline rendered fully after
two kills, s07 — rows, markdown, table, code block, the "ping" draft intact).

## R.A5 — leg D adjudication: the hook-card plane stays F-P3-A-gated

The Part-Q coverage item ("the hook card's View/trust + CUA chevron — starved
plane") is NOT driveable without an app fix: the pinned-pending-stack starvation
(F-P3-A, 9 stamps) is what starves the hook card's rows, pass 9's hook-review
zero-rows receipt stands, and the composition has not changed. Attempting it would
re-narrate an OPEN row. Standing block; the plane opens with F-P3-A's fix phase.

## R.Q — ledger arithmetic + coverage honesty

Open F: **25** (unchanged — zero new rows; F-P9-A stamps 3 with the third trigger
variant; F-P13-A stamps 3 with the cycle-boundary narrowing) · Open G: **21 + 3
standing** (unchanged) · Open smalls: **13 (+1 watch +1 rig)** (unchanged) ·
Mock debts: **#15/#16 CLOSED** (`66844fbe`) — **8 OPEN** (#2–#8, #14) — and the
debt-#10 entry is amended: the queue VARIANT is now driven too (R.A3), so the
flag path (Q.A2) and the ladder path are both receipted.

### Coverage honesty (what pass 15 did NOT drive)

The range DATA variance (standing rig — the fixture is range-invariant by
construction; the chips/wire close at the contract level), the hook-card plane
(F-P3-A-gated, R.A5), MOCK_TURN_RUNNING's in-life watchdog (gen-shaped, unchanged
since P.A2), the automations editor deep planes (code unchanged), disconnect
limits other than =1 and =2, the F-P13-A close-cycle re-receipt (row stands per
the re-receipt rule — the boundary leg above does not re-drive it), the standing
stops (TalkBack/Dictate/widget/Take-photo/shade-ACTION), and re-driving
FIXED/REFUTED rows (none changed).

## Pass-15 verification stamp

Pass 15 completed 2026-11-15 ~01:05; 4 relay generations (nonces
`mock-p15-gen1-a1b2c3` → `mock-p15-gen4-j2k3l4`, launchers `/tmp/p15/relay-g*.sh`);
ONE emulator cold-boot at pass start (00:44 — rig found DOWN after the overnight
gap); logcat fresh `adb logcat -T 1` at 00:44 → /tmp/p15/logcat.log. Receipts: 8
screenshots (`/tmp/p15/shots/s00–s07`), AX dumps `/tmp/p15/w/` (w1–w13 + anr),
relay logs `/tmp/p15/relay-g*.log`, build log `/tmp/p15/build.log` (APK
byte-identical `ad449d7d…` / `530ba4ff…`, vc53/1.3.2; mock self-tests 9/9 + suite
155/0). Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0). **Rig left:** emulator
zcode-a16 UP (cold-booted 00:44; PIN-less, screenshot protection OFF, wifi
ENABLED — O.A0.1 honored), relay gen4 ALIVE (`mock-p15-gen4-j2k3l4`; the delta
arms SPENT — both anchored deltas consumed, the once-per-process guard holds),
app pid 4209 paired + CONNECTED on the Synthetic-task conversation (timeline
fully rendered post-recovery), logcat running → /tmp/p15/logcat.log. Live
connection: the pass-14 FGS window expired overnight; not re-armed this pass.
Commits: `66844fbe` (fix(mock), debts #15–#16 + the queue variant) + the docs
commit for this part.

# Part S — pass 16 (APPENDED 2026-11-15, ~02:00): the mock-fidelity debts #2/#5 fixed and driven, the automations editor's deep planes receipted WORKING, and the REAL frames>0 stall (the P.A2 watch-note closed — the silent heal holds for a genuine backlog stall) — plus debt #4's effort half adjudicated fixed-by-prior-work and a new mock snapshot-fidelity debt

> **Pass 16 of the pass-0 series** (solo, the canonical discovery prompt; both named
> axes saturated since pass 12 — this pass worked the remaining driveable coverage
> surface: the open mock-fidelity debts that gate legs, the automations editor deep
> planes, and the P.A2 stream-watchdog watch-note). **Mock debts #2 and #5 CLOSED**
> (one `fix(mock)` commit `9b6e2292`, self-tests `MockZCodePass16RigSelfTest` 7/7,
> full mock suite 162/0, app untouched — the APK byte-identical `ad449d7d…` /
> `530ba4ff…`, vc53/1.3.2) **plus the MOCK_TURN_STALL arm** (a real frames>0
> delivery stall: live frames, then journal-only rows the resync replays). Live
> legs: the Files content read (debt #2's first working receipt), the effort echo
> (debt #4's effort half does NOT reproduce at HEAD — closed-by-prior-work), the
> automations editor's update+create authoring (both WORKING end-to-end), and the
> stall leg (the watchdog ladder receipted with a genuine backlog; the heal is
> SILENT in both windows — the P.A2 watch-note is answered and closed). **ZERO new
> rows**; N-BK-2 stamps 2 (the schedule-chip clip re-receipted live); mock debt #17
> opened (the snapshot omits delta-pushed effect rows). Zero gateway.drop for the
> whole pass (1,370 pair lines swept).
>
> **Verification stamp:** 2026-11-15 01:13 → ~01:55, GLM-5.3-Flash in ZCode,
> everything local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…`
> UNCHANGED — no DELTA. ONE emulator cold-boot at pass start (01:13 — the pass-15
> emulator died overnight per R.A0.1's expectation; the gen4 relay was still alive
> and pkill'd; screenshot protection OFF by persistence, PIN-less, wifi ENABLED
> per O.A0.1). One SystemUI ANR dialog dismissed (the cold-boot constant, the
> dump-derived Wait center — same bounds as pass 15). Generations: gen1
> `mock-p16-gen1-k1m2n3` (01:22, WORKSPACE_FILES + APPROVAL — legs A/B), gen2
> `…-p4q5r6` (01:31, AUTOMATIONS + OFFPEAK — leg D), gen3 `…-s6t7u8` (01:38,
> TURN_STALL — leg C). All three swaps recovered honestly (1.5–2.7 s); the
> Q.A0.1-expected dead-connect did not materialize.

## S.A0 — rig learnings (each bites the next pass)

1. **The IME auto-period now bites NUMERIC fields**: the first `input text
   "03:30"` landed as `"03:30."` (a trailing dot appended by the keyboard) and the
   editor's live schedule summary FELL BACK to the last-valid value ("Daily at
   02:00") while the field was invalid — the render-the-parsed-value contract,
   noted so nobody reads it as the debt-#4 echo fight. Recipe: after every
   `input text` into a dialog field, re-dump and strip a trailing period
   (MOVE_END + one DEL) before proceeding.
2. **A dialog's bottom buttons are unreachable while the IME is open** — the first
   Save tap silently hit the keyboard (the sheet's Save sits at the dialog
   bottom). Same D.A0.2 class, one more face: close the IME (BACK) FIRST, re-dump
   for the button's post-IME bounds, THEN tap.
3. The `input text` interleave hit the create sheet too ("Rig probe" → "Rig",
   "Say hi from the rig" → "Say"): the E.A0.2 count-verify-repair recipe applies
   per field even inside dialogs (MOVE_END + ≤8-chunk DELs + short retype +
   verify).
4. The home **Approvals icon deep-links straight into the pending interaction's
   conversation** (observed twice): with a pending approval stored, the bell tap
   navigates to the task instead of an approvals list. Not driven further — the
   pending-stack card it scrolls to is the F-P3-A-starved plane.
5. Relay-swap posture: three pkill swaps, zero blackhole, zero gateway.drop. The
   debt-#14 tooth stayed dormant (its known probabilistic shape).

## S.A1 — gen1, leg A: the Files CONTENT READ under fixed seeding — debt #2 CLOSED live

The first working content read on a seeded non-README file in the whole campaign
(the pass-21 recipe seeded content for README.md only; every other seeded path
faulted `fault.file.notFound` on open):

- Files screen: ws-one root renders (docs/, src/, README.md, Cargo.toml) →
  src/ → **main.rs opens**: `file.readTextFile begin` 01:24:37.811 → **ok** 37.880
  (69 ms) → the paged viewer renders the seeded content: line 1
  "# main.rs — rig seed (pass-16 debt-#2 fix)", lines 2–14 the synthetic body, a
  line-number gutter 1–15 (the 15th slot is the trailing-newline line — correct),
  Back/Download affordances (s03).
- The fix shape (self-tested 2 ways): `seedWorkspaceFiles` now seeds default
  content for EVERY path (all seeded paths are files in this vocabulary;
  directories are derived); explicit `fileText` entries keep precedence (the
  pass-21 README fixture unchanged). **Debt #2 CLOSED** (`9b6e2292`).

## S.A2 — gen1, leg B: the effort echo + the approval-card labels

**The effort half of debt #4 does NOT reproduce at HEAD — closed-by-prior-work.**
Baseline chip "yolo · GLM-5.3-Flash · max · followup · queue" → config chip
expand → "Effort · max" → the Reasoning-effort selector (Close/high/max/medium/
xhigh) → "high":

- `setThoughtLevel begin` 01:26:14.489 → **ok in 100 ms**; the config chip
  re-rendered **"yolo · GLM-5.3-Flash · high · followup · queue"** and the
  expanded panel "Effort · high"; **stable 8+ s later** — the pass-1 "the echo
  re-asserts max" shape is gone (the mock's later G3 key-preserving merges +
  `thoughtLevelConfig` mutation fixed it in mock work; no app change involved).
  No mock fix needed this pass; the debt's effort half is adjudicated CLOSED.
- The composer's "Delivery for the next send" picker (Guide/Queue this send/
  Send now): the Guide tap fired **NO wire verb** and left the chip unchanged —
  the routing/followup axis keeps its pass-12 shape (the fight was receipted
  with MOCK_QUEUE's queue strip, which this gen did not arm). **Debt #4 stays
  OPEN with the routing half only**, bound to that composition.

**The approval-card human labels (debt #5): the mock fix is live and
self-tested; the on-device render is F-P3-A-gated.** The pending permission is
stored (lifecycle DISPLAYED, DB-pulled), but the pending-stack card starved in
BOTH compositions (fresh entry + re-entry — the F-P3-A composition-dependence),
and the expanded tool row carries the command but no option buttons. The labels'
visible surface opens with F-P3-A's fix phase (the R.A5 adjudication class).
The self-test pins the wire truth: `options = [{optionId: allowOnce, label:
"Allow once", kind: allowOnce}, …]`, unknown ids verbatim — the desktop's
`label: c.name` shape.

## S.A3 — gen2, leg D: the automations editor's deep planes — WORKING end-to-end

Under MOCK_AUTOMATIONS + MOCK_OFFPEAK (debt #8 honored), the home Automations
section renders the two seeded rows ("Nightly digest · 3 runs · Daily at 02:00",
"Nightly refactor sweep · On · active"). The EDIT sheet renders fully (title/
prompt/schedule chips/time/ends/max-runs/model/permission-mode/Save-Cancel):

- **Update**: the HH:MM field 02:00 → 03:30 (after the S.A0.1 dot-strip), live
  summary "Daily at 03:30", Save → **`updateAutomation` (id=19)** → the sheet
  closes → `listAutomations` (id=20) → **the row re-renders "Daily at 03:30"**
  (s08). First `updateAutomation` receipt of the campaign.
- **Create**: "+ New automation" → Title "RigProbe" / Prompt "SayHi" (the
  S.A0.3 repairs), the default schedule renders "Daily at 09:00" → Create →
  **`createAutomation` (id=24)** → `listAutomations` (id=25) → **the "RigProbe ·
  Daily at 09:00" row renders on the home** (s10). First `createAutomation`
  receipt of the campaign.
- **N-BK-2 stamps 2**: the schedule chips clip at phone width exactly as pass 13
  pinned — "Weekda" and "Cr" truncated in the two chip rows (s06/s07). One-line
  re-receipt, no re-narration.
- The `automationsport.workflows.list :: fault.mock.unscripted` warnings are the
  SAVED-WORKFLOWS channel (a different plane, MOCK_WORKFLOWS-gated), not the
  automations section — the editor legs above are unaffected.

## S.A4 — gen3, leg C: the REAL frames>0 stall — the P.A2 watch-note CLOSED

The new MOCK_TURN_STALL arm (implies the running turn; subscribe-anchored;
`MOCK_TURN_STALL_LIVE=2`, EVERY_MS=1500): after the first conversation subscribe
it emits 2 live row frames, then journals rows WITHOUT emitting — a genuine
mid-turn delivery stall whose backlog only a resync can surface.

- 01:39:02.801 `subscribeConversationV4 ok` (291 ms) → INITIAL seq 0..1 (epoch
  stamped) → **live frames seq=2..2 (02.876) and seq=3..3 (04.333)** — the stream
  was flowing, then the arm went silent.
- 01:39:09.576–.587 **RECOVERY replay seq=3..6 — the frames>0 backlog DELIVERED**
  by the conversation-open attach recovery (the resume replay from the opLog; one
  duplicate seq re-delivered, consumed harmlessly). The app consumed the silent
  backlog with **zero gateway.drop**.
- 01:40:40.514 **`recovery.streamWatchdog :: no frames for 90113ms — re-kick
  strikes=1`** (measured from the LAST frame — the 01:39:09 replay — the
  arithmetic matches) → `recovery.escalate :: attempt=2 force=true
  baseIsCurrent=true` → `resyncConversationV4 ok in 13ms` →
  `gateway.replay :: frames=0 (subscribe ack landed)` → a fresh RECOVERY
  snapshot seq=0..1.
- **The heal is SILENT in both windows**: zero `ZCODE-NOTIFY` lines, zero
  banner/snackbar lines, no state face change (s11 vs s12). The P.A2 watch-note
  ("does a REAL frames>0 stall surface something?") is **answered: NO at HEAD —
  and with the data fully delivered, silence is defensible**; the rows the app
  missed arrive via the replay, the timeline stays correct, no notice fires.
  Watch-note CLOSED (not a row).
- **NEW mock debt #17**: the watchdog's force-resync snapshot does NOT carry the
  delta-pushed effect rows — `pushRowDelta` rows live only in the resync opLog,
  never in the snapshot builder's row source — so after the force-resync the
  stall-leg rows vanish from the timeline (the app renders the authoritative
  snapshot faithfully; the desktop's server would include its own pushed rows).
  Mock-fidelity only; the app behavior is correct-by-contract.

## S.Q — ledger arithmetic + coverage honesty

Open F: **25** (unchanged — zero new rows) · Open G: **21 + 3 standing**
(unchanged) · Open smalls: **13 (+1 watch +1 rig)** (unchanged; N-BK-2 stamps 2) ·
Mock debts: **#2/#5 CLOSED** (`9b6e2292`) — **7 OPEN** (#3, #4, #6, #7, #8, #14,
#17; #4 narrowed to the routing half) — debt #17 opened this pass.

### Coverage honesty (what pass 16 did NOT drive)

The label-render leg (F-P3-A-gated — both compositions starved), debt #4's
routing half (MOCK_QUEUE-strip composition; standing), the automations History
sheet (MOCK_AUTOMATION_RUNS-gated, receipted pass 2's shape), the Usage range
DATA variance (standing rig), the F-P13-A close-cycle re-receipt (row stands per
the re-receipt rule), the standing stops (TalkBack/Dictate/widget/Take-photo/
shade-ACTION), disconnect limits other than =1/=2, and re-driving
FIXED/REFUTED/ABSORBED rows (none changed).

## Pass-16 verification stamp

Pass 16 completed 2026-11-15 ~01:55; 3 relay generations (nonces
`mock-p16-gen1-k1m2n3` → `mock-p16-gen3-s6t7u8`, launchers `/tmp/p16/relay-g*.sh`);
ONE emulator cold-boot at pass start (01:13); logcat fresh `adb logcat -T 1` at
01:21 → /tmp/p16/logcat.log. Receipts: 14 screenshots (`/tmp/p16/shots/s00–s13`),
AX dumps `/tmp/p16/w/` (anr, w1–w36), relay logs `/tmp/p16/relay-g{1,2,3}.log`,
DB pulls `/tmp/p16/db/` (p16-1.db — the DISPLAYED permission row), build log
`/tmp/p16/build.log`, mock test logs (`/tmp/p16/mock-{test,suite}.log`; self-tests
7/7, suite 162/0). APK byte-identical `ad449d7d…` / `530ba4ff…`, vc53/1.3.2
(zero app changes). Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0).
**gateway.drop for the whole pass: 0.** **Rig left:** emulator zcode-a16 UP
(cold-booted 01:13; PIN-less, screenshot protection OFF, wifi ENABLED), relay
gen3 ALIVE (`mock-p16-gen3-s6t7u8`; TURN_STALL — the arm loops journal-only rows
every 1.5 s, bounded by the 512-frame opLog; force-stop or pkill to end), app
pid 4192 paired + CONNECTED on the Synthetic-task conversation, logcat running →
/tmp/p16/logcat.log. Live connection: ON (the pass-14 pref persisted; the
session bar shows "Connected · Running" from the FGS).
Commits: `9b6e2292` (fix(mock), debts #2+#5 + the stall arm) + the docs commit
for this part.

# Part T — pass 17 (APPENDED 2026-11-15, ~03:00): the MOCK_QUEUE composition driven to the wire — the complete G49/G50 send trio, all five queue verbs, and the automations-runs plane's first-series receipts — plus debt #4's routing half adjudicated BY-DESIGN, debt #17 live-closed under the exact pass-16 force shape, and a new arm (MOCK_INPUT_ROUTING) unblocking the held-queue disposition dialog

> **Pass 17 of the pass-0 series** (solo, the canonical discovery prompt; the backlog is
> 8/8 — this pass worked the campaign state's named remainder: the MOCK_QUEUE and
> MOCK_AUTOMATION_RUNS-gated compositions, the only driveable legs left). **ZERO new
> rows.** The pass opened and closed one mock debt (#18 — the mock never served
> `inputRouting.mode "choice"`, so the G50 held-queue disposition dialog was
> structurally undrivable) and closed two more: **debt #4 ENTIRELY** (the routing half
> is the G49 one-shot delivery override — no pick-time verb is BY DESIGN; the wire
> carries it on the next send) and **debt #17** (fix `05ba5c18` + the live force-resync
> receipt T.A3). First-series WORKING receipts: **the complete send trio on ONE wire
> call** (`heldQueueDisposition` + `requestedDelivery` + `expectedHeldQueueItemIds`),
> **all five queue verbs** (send-now / edit / reorder / delete — auto-drain was pass
> 12's), **the automations-runs plane** (History sheet + filter chips + Run-now +
> duplicate face + Delete run), and **Go-to-session's working half** (the
> workspace-session run loads its transcript via the subscribe snapshot).
>
> **Verification stamp:** 2026-11-15 02:00 → 02:37, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no
> DELTA. Rig inherited from pass 16 (emulator zcode-a16 UP, cold-booted 01:13
> pass-16; app relaunched by the `install -r` at 02:00 → pid 5789; PIN-less,
> screenshot protection OFF by persistence, wifi ENABLED per O.A0.1; the pass-16
> gen3 TURN_STALL relay pkill'd at pass start). Generations: gen1
> `mock-p17-gen1-a1b2c3` (02:00, QUEUE + INPUT_ROUTING=choice — leg A), gen2
> `mock-p17-gen2-d4e5f6` (02:19, AUTOMATIONS + OFFPEAK + AUTOMATION_RUNS — leg B),
> gen3 `mock-p17-gen3-e6f7a8` (02:30, TURN_STALL — leg C). All three swaps
> recovered honestly (the app reconnected 1.5–3 s; zero blackhole, zero
> gateway.drop for the whole pass).

## T.A0 — rig learnings (each bites the next pass)

1. **The queue verbs ride `sendConversationCommandV4`, not their own channel methods** —
   the app's command coordinator wraps editQueueItem/reorderQueueItem/sendQueuedNow/
   deleteQueueItem as command types inside the one send call, and the mock logs only the
   generic `MOCK: v4call sendConversationCommandV4 (id=N)`. Greps for the verb NAMES on
   either side produced two false-negative scares ("no wire call" for the edit and the
   reorder) until the queue-state mutations (EDITEDONE rendering, the swapped order)
   proved the verbs had fired. `runAutomationNow`/`deleteAutomationRun` conversely ARE
   their own channel methods. Grep by the wire method, verify by the STATE mutation.
2. **A bottom-anchored ModalBottomSheet SHRINKS UPWARD when its list narrows**: after a
   filter chip reduced the runs list 2→1, the sheet re-laid-out and the chips moved DOWN
   ~208 px — taps aimed at the pre-narrow dump bounds hit the note text instead (the
   "Succeeded filter not applying" scare was this, not an app defect). Re-dump after
   every in-sheet list change; dialog bounds go stale on ANY content change, not just
   IME transitions (the D.A0.2 rule generalized).
3. **The mixed-axis preview scale**: the Read-tool screenshot preview is 897×2000 for the
   1080×2340 real screen — x scale 1.204 but y scale 1.17. Using one scale for both axes
   put the first Save tap ~62 px below the button (it dismissed the sheet via the scrim;
   the editQueueItem call HAD actually fired — see T.A1). Anchor dialog taps on the RAW
   dump bounds when no IME/animation transition intervened; use screenshot coordinates
   only for IME-stale cases (the D.A0.2 trap's exact boundary).
4. **The first screencap after opening a sheet/dialog can RACE the entrance animation**
   (twice this pass: the queue-edit sheet and the History sheet each painted the
   UNDERLYING screen in the first shot while the AX dump already carried the sheet).
   Re-shoot before concluding popup-invisibility.
5. **The mock's `schemaRow` back-dates every row by exactly 300,000 ms**
   (`System.currentTimeMillis() - 300_000 + rowId` — snapshot-tail realism), so a row
   pushed NOW renders a header time 5 minutes old ("YOU 2:02 AM" for a 02:07:11 send).
   App-faithful (it renders the server timestamp); recorded so nobody re-chases it as a
   clock-skew defect. Host/emulator clocks verified within 5 s.
6. The `install -r` at pass start killed the app process; the relaunched process froze
   in the background (ActivityManager freezer) and the monkey launch did NOT foreground
   it — `am start -n com.zcode.android.app/.MainActivity` did. Budget the explicit start
   after every reinstall.

## T.A1 — gen1, leg A: the MOCK_QUEUE composition — the complete send trio + all five queue verbs, wire-true

Arms: MOCK_ROWS=12 (light, D.A0.4) + MOCK_QUEUE=1 (debt #4's named composition: 2
seeded follow-ups, autoDrain=false) + **MOCK_INPUT_ROUTING=choice** (the new debt-#18
arm — the state's inputRouting pins to `choice`, the phone's CHOICE routing).

**The queue strip rendered in full for the first time in the series** (s01): "Queued (2)
· paused" + the Auto-send toggle (OFF=paused, the pass-12 receipt's shape) + the
Queue/Guide followup chips (the pass-12 pref persisted, Queue selected) + both items
with move up/down (item 1's up and item 2's down disabled at the edges — correct) +
Send now / Edit / Delete per item.

**The G50 held-queue disposition dialog — FIRST LIVE RENDER, then the FULL TRIO on ONE
wire call.** The composer's delivery button (content-desc "Delivery for the next send",
G49) opened the three-option menu (Send now (override queueing) / Queue this send /
Guide (wait for me)) → picking **Guide** fired NO wire verb — **and that is BY DESIGN**:
the menu sets the ONE-SHOT `deliveryOverride` (WriteComponents.kt:883, `composer_
delivery_menu`), whose only armed face is the icon TINT going grey → accent (s02-crop
vs s03-crop — the "chip unchanged" pass 16 read was this subtle tint). Tapping Send
with routing=CHOICE opened **"Queue is held / Send now and clear the queue, or keep the
queued messages?"** with Clear queue & send / Keep queue & send (s04) → **Keep queue &
send** → the mock's receipt line:

```
MOCK: sendText trio heldQueueDisposition=keepQueueAndSend requestedDelivery=guide expectedHeldQueueItemIds=[q-rig-1, q-rig-2]
```
app-side `sendConversationCommandV4 ok in 1805ms` (02:07:11). All three G49/G50 keys
(the CHOICE disposition, the one-shot delivery override, the CAS held-ids set) rode ONE
sendText — the payload keys live at CommandCoordinator.kt:128-129. The strip SURVIVED
(keep semantics — desktop-faithful; the mock now also honors clearQueueAndSend
server-side and empties the queue, the T-fix's faithful half). **Debt #4's routing half
adjudicated CLOSED-BY-DESIGN**: the pass-16 "Guide tap fired no wire verb" was the
one-shot contract, not a defect; the pass-12 strip-chips Guide tap (a different surface,
the queue strip's own setFollowupMode) remains the verb-firing path.

**The four never-receipted queue verbs (send-now / edit / reorder / delete —
auto-drain was pass 12's), each wire-true:**

- **editQueueItem** (02:15:33, ok 223ms): the Edit sheet ("Edit queued message" +
  Cancel/Save; the field edit needed the verified-chunk loop — one `q` survivor repaired,
  the dash eaten by adb's input text, settled as EDITEDONE) → the item renders
  EDITEDONE in place. (The first Save tap appeared to miss — the T.A0.3 scale error —
  but the wire proves it fired; the sheet-dismiss scare was MY tap, not the app.)
- **reorderQueueItem** (02:16:22, ok 551ms): Move-down on item 1 → the order swapped —
  the moved row renders in its new place ([two, EDITEDONE]; the I1 contract's exact
  "moved row renders in place").
- **sendQueuedNow** (02:17:37, ok 499ms): Send now on the top item → "Queued (2)" →
  **"Queued (1)"**, the dispatched item gone (mock fidelity note: the mock's handler
  removes the item without appending the dispatched row — the desktop would append;
  nit, not rowed).
- **deleteQueueItem** (02:17:56, ok 285ms): Delete on the last item → the strip GONE
  (empty items → the strip unrenders). G4's "delete stayed modeled-never-sent" comment
  is now receipted live.

## T.A2 — gen2, leg B: the automations-runs plane — first-series receipts across the sheet

Arms: MOCK_AUTOMATIONS + MOCK_OFFPEAK (debt #8 honored) + MOCK_AUTOMATION_RUNS
(one COMPLETED seeded run for auto-rig-1). The relay swap recovered honestly
(reconnect ~2 s, relay-2).

- **The History sheet — FIRST SERIES DRIVE, WORKING end-to-end** (s11b): "Run history /
  Nightly digest" + the awake note ("Scheduled tasks only run while your computer is
  awake.") + **status filter chips All / In progress / Succeeded / Failure** + the
  seeded run row "**1 h ago · Scheduled · 100s**" with the typed outcome badge
  "**Succeeded**" + per-row **Go to session** and **Delete run**. Wire:
  `listAutomationRuns` (a channel method of its own).
- **All four filter chips driven and correct**: Succeeded → the seeded row survives;
  In progress / Failure → the honest empty face (nothing of those statuses). (The
  "Succeeded not applying" mid-leg scare was the T.A0.2 shrink-upward bounds staleness;
  re-dump → clean.)
- **Run now — first series receipt**: `runAutomationNow ok in 677ms` (its OWN channel
  method) → the ledger records the run (trigger=manual, no outcome) → re-opening the
  History sheet renders "**2 min ago · Manual**" with the typed "**In progress**"
  badge — the ledger's live read works.
- **The duplicate face — typed snackbar renders**: a second Run-now → `ok in 114ms`
  carrying `{status:"duplicate"}` (the mock's in-flight guard; the desktop's
  run-in-progress semantics) → the snackbar "**A run is already in progress for this
  automation.**" (`automation_run_duplicate`, AutomationsSection.kt:100/183) — the
  dump raced it twice (T.A0.4's race + the snackbar's bottom anchor), the immediate
  screenshot receipted it (s13).
- **Delete run — first series receipt**: `deleteAutomationRun` + the follow-up
  `listAutomationRuns` → the seeded row vanishes from the sheet (the ledger shrinks).
- **Go to session — the plane's SPLIT receipt**: the MANUAL run's session is the
  workspace session (synth-task-1) → openRun navigates, `subscribeConversationV4 ok`
  → `recovery.subscribed mode=snapshot` → **the transcript LOADS** (real rows — the
  subscribe snapshot path supplies them; a NEW positive receipt for the verb). The
  SEEDED run's session (synth-op-session-1, no live conversation) → openRun navigates
  → **NO subscribe, NO transcript wire call, ZERO gateway lines — the four skeleton
  rows render indefinitely** (s15b) — **F-P3-B re-manifested exactly as pinned**
  (stamp 2), scope NARROWED: the skeleton-forever is the UNSCRIPTED-session shape;
  a run whose session matches a subscribable conversation loads fine. The generic
  "Conversation" title re-manifests **N-BM-2** (stamp 2).
- Mock-fidelity nit (not rowed): the row's "Next **3 runs**" count label is
  snapshot-seeded and does not track the live ledger (Run-now did not bump it).

## T.A3 — gen3, leg C: debt #17 LIVE-CLOSED — the stall rows survive the exact pass-16 force shape

Arms: MOCK_TURN_STALL (implies the running turn; LIVE=2, EVERY_MS=1500 — pass 16's
S.A4 recipe). The conversation opened → `subscribeConversationV4 ok` 02:30:58 →
INITIAL snapshot reconciled rows=14 → 2 live frames → the arm journals silently.

- **02:32:29.761** `recovery.streamWatchdog :: no frames for 90033ms — re-kick
  strikes=1` (measured from the last live frame — the arithmetic matches) →
  `recovery.escalate attempt=1 force=false` → `resyncConversationV4 ok in 91ms` →
  **the timeline RETAINS the stall rows** (backlog frames 56-59 render — pre-fix the
  snapshot omitted them; the fix routes the arm's rows through `appendedRows`, the
  snapshot tail, commit `05ba5c18`).
- **02:34:45.773** the second window: `no frames for 90027ms — re-kick strikes=1` →
  `recovery.escalate attempt=2 force=true` — **THE EXACT PASS-16 SHAPE** →
  `recovery.snapshotReconcile rows=166` (12 synthetic + 2 live + ~152 backlog — the
  snapshot carries EVERYTHING) → the timeline renders backlog frames 147-150. **The
  pass-16 vanish is GONE**: the force-resynced timeline keeps the pushed rows, which is
  what the desktop's server would serve. **Debt #17 CLOSED** (fix + live receipt).
- The heal stays SILENT in both windows (zero `ZCODE-NOTIFY` beyond the startup
  `notify.brokerLoad records=2`, no banner/snackbar) — the P.A2→S.A4 adjudication
  holds under the armed arm too.

## T.Q — ledger arithmetic + coverage honesty

Open F: **25** (unchanged — zero new rows; F-P3-B stamps 2 with the narrowing) ·
Open G: **21 + 3 standing** (unchanged) · Open smalls: **13 (+1 watch +1 rig)**
(unchanged; N-BM-2 stamps 2) · Mock debts: **#4 CLOSED by-adjudication, #17 CLOSED**
(fix `05ba5c18` + T.A3), **#18 OPENED+CLOSED in the same commit** (the
MOCK_INPUT_ROUTING arm; self-tests 5/5, mock suite 167/0/0) — **5 OPEN** (#3, #6, #7,
#8, #14). First-series WORKING receipts: the send trio, 4 queue verbs, the
automations-runs plane (sheet/chips/run-now/duplicate/delete), Go-to-session's
working half.

### Coverage honesty (what pass 17 did NOT drive)

The delivery menu's other two values (Queue this send / Send now — the same one-shot
mechanism, guide receipted on the wire), the queue strip's followup chips and the
Auto-send toggle (pass-12 receipts, code unchanged), deleteRun's confirm-face
semantics (the delete fired directly on the rig), the queue item text's paint-level
ellipsis (the AX carries the full string — cosmetic nit, not rowed), the F-P13-A
close-cycle re-receipt (row stands per the re-receipt rule), the label-render leg and
the card plane (F-P3-A-gated), the standing stops (TalkBack/Dictate/widget/Take-photo/
shade-ACTION), and re-driving FIXED/REFUTED/ABSORBED rows (none changed).

## Pass-17 verification stamp

Pass 17 completed 2026-11-15 ~02:37; 3 relay generations (nonces
`mock-p17-gen1-a1b2c3` → `mock-p17-gen3-e6f7a8`, launchers `/tmp/p17/relay-g*.sh`);
the emulator inherited from pass 16 (no cold boot this pass; app pid 5789 after the
02:00 `install -r` + explicit `am start`); logcat fresh `adb logcat -T 1` at 02:00 →
/tmp/p17/logcat.log. Receipts: 22 screenshots + crops (`/tmp/p17/shots/s01–s16`),
AX dumps `/tmp/p17/w/w1–w46`, relay logs `/tmp/p17/relay-g{1,2,3}.log` (34+48+16
v4calls), build log via gradle (APK **byte-identical** `ad449d7d…` / `530ba4ff…`,
vc53/1.3.2 — zero app changes), mock self-tests `MockZCodePass17RigSelfTest` 5/5 +
suite 167/0/0 (no DB pulls this pass — every leg's arbiter was wire + render state).
Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0). **gateway.drop for the whole pass:
0.** **Rig left:** emulator zcode-a16 UP, relay gen3 ALIVE (`mock-p17-gen3-e6f7a8`;
TURN_STALL journaling ~1 row/1.5 s, bounded by the 512-frame opLog; pkill to end),
app pid 5789 on the synth-task-1 conversation (timeline = stall frames growing),
logcat running → /tmp/p17/logcat.log. Live connection ON (the pass-14 pref
persisted). Commits: `05ba5c18` (fix(mock), debts #17+#18 + the trio log/clear
honoring) + the docs commit for this part.

# Part U — pass 18 (APPENDED 2026-11-15, ~03:20): the G49/G50 one-shot matrix completed live (the delivery menu's two undriven values, the "queue" value riding a NEW server-side hold), the run-row Delete confirm-face adjudicated NO-DEFECT both sides, and the mock-debt list fully adjudicated (#3/#6/#7/#8 closed, #19 opened+closed, #14 reclassified standing)

> **Pass 18 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 empty —
> backlog 8/8 — so STEP 2 worked Part T's coverage-honesty list, and the open
> mock-debt axis fell to the same pass). **ZERO new rows.** First-series WORKING
> receipts: the delivery one-shot **"Queue this send"** — the trio
> `heldQueueDisposition=keepQueueAndSend requestedDelivery=queue
> expectedHeldQueueItemIds=[q-rig-1,q-rig-2]` on ONE sendConversationCommandV4, the
> NEW debt-#19 server-side HOLD appending `q-rig-101`, the strip **Queued (2)→(3)** —
> and the delivery one-shot **"Send now (override queueing)"** — trio
> `clearQueueAndSend requestedDelivery=startNow
> expectedHeldQueueItemIds=[q-rig-1,q-rig-2,q-rig-101]` (the CAS set carries the
> just-queued id), queue EMPTIED + immediate rows. Together with pass 17's `guide`,
> **all three G49 one-shot values are now wire-receipted**. The run-row Delete's
> confirm-face leftover adjudicated **NO-DEFECT from code + i18n on BOTH sides** (no
> live leg needed — pass 17's live delete receipt stands, code unchanged). Mock
> debts: **#3 CLOSED fixed-by-prior-work** (`2b12fc46`), **#6 CLOSED
> by-adjudication**, **#7 CLOSED** (documentation), **#8 CLOSED by construction**
> (`6846727d`), **#19 OPENED+CLOSED** (`6846727d` + the live receipt), **#14
> reclassified STANDING RIG-ENV** — **0 open work-debts.** Stamps: F-P8-D 4.
>
> **Verification stamp:** 2026-11-15 02:52 → 03:13, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no
> DELTA. Rig inherited from pass 17 (emulator zcode-a16 UP; the pass-17 gen3
> TURN_STALL relay pkill'd at pass start; the `install -r` restarted the app →
> pid 7843). ONE generation for the whole pass — gen1 `mock-p18-gen1-c4d5e6`
> (02:56, MOCK_ROWS=12 + MOCK_QUEUE + MOCK_INPUT_ROUTING=choice; **no relay swaps —
> a series first**); two idle-socket drops mid-pass recovered honestly (03:03
> RECOVERING→CONNECTED on relay-4 in ~3 s; 03:11 three 10-s connect timeouts →
> RECOVERING→CONNECTED on relay-8 at 03:12:27, the ladder working as pinned).

## U.A0 — rig learnings (each bites the next pass)

1. **The IME-open dump-bounds artifact cuts BOTH directions.** With the IME up, one
   tap aimed at the dump's composer position (960,1417 — "Send message
   [894,1351][1026,1483]") landed on a queue row's Edit sheet ~600 real px higher;
   minutes later, the SAME geometry honored dump-position taps for the delivery
   menu ((854,1416) opened it; menu items at dump bounds hit true). The safe recipe
   stands and is now mandatory for composer-row taps: force-close the IME with
   **BACK** (keyevent 4 — ESC left `mInputShown=true` once; BACK is deterministic
   per the pass-3 recipe), verify `dumpsys input_method` → `mInputShown=false` AND
   the composer at its bottom bounds ([894,2118][1026,2250] shape), then dump-tap.
2. **Dialog-window dump bounds were TRUE with the IME up beneath** (the G50 dialog's
   "Keep/Clear" dump bounds matched the screenshot within ~20 px in both legs) —
   dialogs render in their own window; the D.A0.2 "~200 px lie" applies to
   IME-transition races on the HOST window, not to dialog windows. Verify by
   screenshot once, then trust the dialog dump.
3. `input text` with `%s` still truncates mid-word ("p18 startNo" from
   "p18%sstartNo%sleg"; "p18 queue ovie" from five words) — the pass-0 flake
   persists at HEAD. Wire legs don't care about the draft text; accept the
   truncation instead of burning minutes on retry loops.
4. **The Edit-sheet Cancel is a CLEAN escape hatch**: after the mis-aimed tap opened
   the queue item's Edit sheet, Cancel dismissed it with ZERO wire verbs (relay log
   empty through the detour) and the draft + the armed delivery override both
   survived. Budget one Cancel tap whenever a queue-row tap mis-fires.
5. `w14`'s mid-transition tree (it showed the Edit sheet while the delivery menu was
   actually opening) is T.A0.4's entrance race again, now with a confusing face:
   the FIRST dump after a tap can show the PREVIOUS overlay's remnant. Re-dump
   before reading (w15 was definitive).

## U.A1 — gen1, leg A: the delivery one-shot matrix completed (both undriven values, wire-true)

Arms: MOCK_ROWS=12 + MOCK_QUEUE=1 (seeds q-rig-1/q-rig-2, autoDrain=false) +
MOCK_INPUT_ROUTING=choice (pass-17's arm). Draft "p18 queue ovie" (the IME-flake
text — irrelevant to the wire).

**A1 — "Queue this send" (the G49 menu's second value, FIRST WIRE RECEIPT).** The
delivery button (content-desc "Delivery for the next send") opened the three-option
menu (dumped in full: `Send now (override queueing)` / `Queue this send` /
`Guide (wait for me)`, s01) → picking **Queue this send** fired no verb (the one-shot
contract, pass-17 adjudication) → Send → the G50 dialog (s06: "Queue is held / Send
now and clear the queue, or keep the queued messages?" — second live render) →
**Keep queue & send** → 03:05:47.916, ok in 396 ms:

```
MOCK: sendText trio heldQueueDisposition=keepQueueAndSend requestedDelivery=queue expectedHeldQueueItemIds=[q-rig-1, q-rig-2]
MOCK: sendText queued delivery -> q-rig-101 appended (heldDisposition=keepQueueAndSend)
```

**The desktop-faithful HOLD, receipted live**: the desktop answers
`sendText{requestedDelivery:"queue"}` with `inputAccepted{delivery:"queue"}` and the
input stays HELD (the bot path marks the task "waiting" with a Queued notification —
asar 0x12fe21b5) — the debt-#19 fix makes the mock do exactly that, and the phone's
strip rendered **"Queued (3) · paused"** (s08). Before the fix the mock appended
user/assistant rows immediately, which would have un-queued the send and hidden the
override's entire point.

**A2 — "Send now (override queueing)" (the third value, FIRST WIRE RECEIPT).** Fresh
draft "p18 startNo" → delivery menu → **Send now (override queueing)** → Send → G50
dialog → **Clear queue & send** → 03:10:42.027:

```
MOCK: sendText trio heldQueueDisposition=clearQueueAndSend requestedDelivery=startNow expectedHeldQueueItemIds=[q-rig-1, q-rig-2, q-rig-101]
```

(No `queued delivery` line — startNow takes the immediate path.) **The CAS set
carries all THREE ids including `q-rig-101`, the item the A1 hold created** — the
phone's expectedHeldQueueItemIds is the ids the user SAW at dialog time, re-read
live (WriteComponents `heldQueueIds()`), desktop-faithful. Render receipt (w19):
the queue strip **GONE** (the mock's clear honoring emptied it) + the user row
"p18 startNo" + "synthetic reply to: p18 startNo" appended (the immediate path).

**The complete G49/G50 matrix now receipted:**

| one-shot value | disposition | wire (all on ONE sendConversationCommandV4) | server effect | render |
| --- | --- | --- | --- | --- |
| `guide` | keep (pass 17, T.A1) | trio with requestedDelivery=guide | immediate rows (pass-17 adjudication) | strip SURVIVES |
| `queue` | keep (U.A1) | trio with requestedDelivery=queue | **HELD as q-rig-101** (debt #19) | strip 2→3 |
| `startNow` | clear (U.A1) | trio with requestedDelivery=startNow | queue emptied + immediate rows | strip GONE + rows |

Cosmetic nit re-observed (NOT rowed, pass-17's adjudication stands): the queue item
text ellipsizes in paint ("queue…" in s08/s09) while the AX carries the full string.

## U.B — leg B: the run-row Delete's confirm-face adjudicated NO-DEFECT (static, both sides)

Part T's coverage-honesty list carried "deleteRun's confirm-face semantics (the
delete fired directly on the rig)". Adjudicated from code + i18n on BOTH sides:

- **Phone**: the RunRow Delete button fires `onDelete` DIRECTLY —
  `AutomationsSection.kt:602` (`port.deleteRun(run.runId)` in the History sheet's
  scope launch), the button at :655-663 has no confirm arms. Contrast: the sibling
  `OffPeakLine` Delete/Cancel arms a SECTION-LEVEL confirm (the G29 comment at
  :636-637) — so the codebase knows the pattern and deliberately does not use it
  for run rows.
- **Desktop**: `automations.delete.title` = "Delete scheduled task" +
  `automations.delete.description` = "Delete "{title}"? This cannot be undone."
  exist ONLY for the AUTOMATION-level delete; the `automations.runs.*` family has
  NO confirm pair (full family extracted — Run history / Delete run / Go to
  session / statuses / pagination, zero title/description keys under runs.delete).
  The desktop deletes a run record directly too.
- **Phone automation-level parity**: the phone HAS the confirm
  (`automations_delete_title/body` = "Delete automation?" / "Delete "%1$s"? The
  schedule stops and the definition is removed.", AutomationsSection.kt:393) —
  desktop-faithful.

A run record is a log entry (non-destructive), and BOTH sides delete it directly →
**NO-DEFECT**; no row opened. No live leg needed: pass 17 already receipted the
live delete wire-true (`deleteAutomationRun` + the ledger shrink) and the code is
unchanged since.

## U.C — the mock-debt axis: #3/#6/#7/#8 closed, #19 opened+closed, #14 reclassified

- **#3 CLOSED fixed-by-prior-work** (`2b12fc46`, pass 5 — pre-dating the ledger's
  snapshotPendingInteractions introduction). At HEAD `snapshotPendingInteractions`
  serves the FULL pending list once the fresh-push delivered
  (`script.interactionPushDelivered`); the only exclusion window is BEFORE its own
  patch lands — the RIG-P1 anti-race, by design (a leg-fidelity guard, not a
  fidelity gap). Desktop evidence closes the faithfulness question: the host pane
  resolves the active permission card FROM `snapshot.pendingInteractions` and the
  notification producer filters that same snapshot list — a delivered interaction
  IS snapshot-listed on the desktop, so the mock's post-delivery inclusion is the
  faithful shape. The F-P0-B stale-sweep receipts stand on their pinned row-plane
  mechanism; this confound is retired.
- **#6 CLOSED by-adjudication**: the push arm's conversation-subscribe anchoring IS
  the desktop shape (the interaction plane rides the conversation subscription —
  pass-6/8 receipts); `155a3c02` armed it that way on purpose. The recipe is the
  drive procedure, not a debt.
- **#7 CLOSED** (documentation): both arms driven pass 9 (Part L) — the ledger's
  own parenthetical already said so.
- **#8 CLOSED by construction** (`6846727d`): `seedOffPeakRigList` extracted and
  served by MOCK_AUTOMATIONS=1 when MOCK_OFFPEAK is unset — the desktop's
  automations ARE scheduled/off-peak tasks, so a session serving listAutomations
  always serves the list; MOCK_OFFPEAK=0 opts out. Removes the pass-10
  "This feature isn't available" face from every future automations launcher.
  Self-tested (the seeded three-status list + workspace key); the plane itself
  stays receipted by passes 10/16/17 (code path unchanged — no live re-drive).
- **#19 OPENED+CLOSED** (`6846727d`): the sendText arm now honors
  `requestedDelivery:"queue"` — enqueue `q-rig-N` + `pushQueuePatch` + the routing
  echo, NO user/assistant rows; composes with the clear disposition (empty →
  exactly the new item) and keep (grows). Self-tests `MockZCodePass18RigSelfTest`
  5/5 (hold-no-rows, keep-composition, clear-composition, immediate-path
  regression guard, the off-peak seed); mock suite **172/0/0**. Live receipt: U.A1.
- **#14 reclassified STANDING RIG-ENV**: the probabilistic API-36 NAT blackhole is
  unfixable in mock code; the recovery (full emulator restart) is the recipe. It
  did NOT manifest this pass — both idle-socket drops recovered honestly without a
  restart. Kept visible in the debt list as environment knowledge, out of the
  open-work count.

**Debt ledger after pass 18: 0 open work-debts; #14 standing-env.** The
`relay-g1.sh` launcher pattern now needs only the arms a leg names — the
automations co-requirement is gone.

## U.Q — ledger arithmetic + coverage honesty

Open F: **25** (unchanged — zero new rows; F-P8-D stamps 4) · Open G: **21 + 3
standing** (unchanged) · Open smalls: **13 (+1 watch +1 rig)** (unchanged) ·
Mock debts: **#3/#6/#7/#8 CLOSED, #19 opened+closed** (fix `6846727d`), **#14
standing-env** — **0 open work-debts**. First-series WORKING receipts: the
delivery one-shot `queue` value with the server-side HOLD, the delivery one-shot
`startNow` value + the clear composition.

### Coverage honesty (what pass 18 did NOT drive)

The delivery menu's `guide` value re-drive (pass-17 receipt, mechanism identical),
the queue strip's followup chips and the Auto-send toggle (pass-12 receipts, code
unchanged), the automations plane live re-drive under the debt-#8 fix (plane code
unchanged; passes 10/16/17 receipts stand), the F-P13-A close-cycle re-receipt
(row stands per the re-receipt rule; no generation swaps occurred), the
label-render leg and the card plane (F-P3-A-gated), the standing stops
(TalkBack/Dictate/widget/Take-photo/shade-ACTION), and re-driving FIXED/REFUTED/
ABSORBED rows (none changed). The G50 dialog's cross-side CAS-mismatch face
(expected ids vs a moved-on queue) remains undriven — the mock has no mismatch arm;
a candidate debt if a fix round ever needs it.

## Pass-18 verification stamp

Pass 18 completed 2026-11-15 ~03:20; ONE relay generation (nonce
`mock-p18-gen1-c4d5e6`, launcher `/tmp/p18/relay-g1.sh` — no swaps, a series
first); the emulator inherited from pass 17 (no cold boot; the 02:56 `install -r`
restarted the app → pid 7843; explicit `am start`); logcat fresh `adb logcat -T 1`
at 02:56 → /tmp/p18/logcat.log. Receipts: 12 screenshots (`/tmp/p18/shots/s01–
s12`), AX dumps `/tmp/p18/w/w0–w19`, relay log `/tmp/p18/relay-g1.log` (29
v4calls), build log /tmp/p18-build.log (APK **byte-identical** `ad449d7d…` /
`530ba4ff…`, vc53/1.3.2 — zero app changes; the 02:56 build was up-to-date),
mock self-tests `MockZCodePass18RigSelfTest` 5/5 + suite 172/0/0 (no DB pulls —
every leg's arbiter was wire + render state, as in pass 17). Desktop 3.18.1 /
`ec475e6e…` unchanged (STEP 0). **gateway.drop for the whole pass: 0**; two
idle-socket drops recovered honestly (relay-4 at 03:03, relay-8 at 03:12:27 —
the reconnect ladder receipted working once more). Commits: `6846727d`
(fix(mock), debts #8+#19) + the docs commit for this part. **Rig left:**
emulator zcode-a16 UP, relay gen1 ALIVE (arms SPENT — the QUEUE composition
consumed by A1/A2, INPUT_ROUTING=choice sticky; pkill to end), app pid 7843
CONNECTED on relay-8 on the synthetic conversation (timeline: "p18 startNo" user
row + synthetic reply; queue strip GONE), logcat running → /tmp/p18/logcat.log.

# Part V — pass 19 (APPENDED 2026-11-15, ~07:00): the G50 cross-side CAS-mismatch face driven live on a NEW desktop-faithful mock arm — the desktop's re-confirmation loop pinned from the host bundle, the phone's generic refusal face receipted end-to-end (wire → notice → outbox FAILED_TERMINAL), and the divergence rowed (F-P19-A)

> **Pass 19 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 still
> empty — backlog 8/8 — so STEP 2 worked Part U's one remaining driveable coverage
> item: "the G50 dialog's cross-side CAS-mismatch face … the mock has no mismatch
> arm"). **ONE new row: F-P19-A (P3).** Mock debt **#20 OPENED+CLOSED in-pass**
> (`0a037a60` — the `MOCK_QUEUE_GHOST_APPEND_MS` arm: the host's held-queue CAS +
> the one-shot SILENT ghost append; self-tests 7/7, suite 179/0/0). First-series
> receipts: the held-queue CAS **ok** leg and the CAS **MISMATCH** leg — the latter
> proving the phone answers a desktop-faithful
> `{status:"failed", reasonCode:"guard.heldQueueConfirmationStale"}` ack with the
> generic refusal ERROR notice while the desktop re-opens the confirmation with
> fresh ids (the divergence).
>
> **Verification stamp:** 2026-11-15 06:20 → 06:52, GLM-5.3-Flash in ZCode,
> everything local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…`
> UNCHANGED — no DELTA. Rig inherited (emulator zcode-a16 UP, no cold boot; the
> pass-18 gen1 relay pkill'd at pass start; the `install -r` restarted the app →
> pid 9546, screenshot protection OFF by persistence, wifi left ENABLED per the
> O.A0.1/NAT lesson). ONE generation — gen1 `mock-p19-gen1-7a9b2c` (MOCK_ROWS=12 +
> MOCK_QUEUE + MOCK_INPUT_ROUTING=choice + MOCK_QUEUE_GHOST_APPEND_MS=4000; no
> relay swaps); logcat fresh `adb logcat -T 1` → /tmp/p19/logcat.log.

## V.A0 — rig learnings

1. **Backtick test names reject `:`** — the Kotlin compiler fails the whole test
   compile with `INVALID_CHARACTERS: Name contains illegal characters: :.` for a
   `fun \`name: with colon\``; parentheses are fine. Cost one 2-minute compile.
2. The home screen's automations card renders the honest "This feature isn't
   available" face under ANY launcher that does not arm `MOCK_AUTOMATIONS` —
   debt #8's by-construction fix serves the plane only when that arm is present;
   not a regression (recorded so nobody re-hunts it).
3. The build was fully up-to-date (zero app changes): the APK digest re-verified
   byte-identical (`ad449d7d…` / `530ba4ff`, vc53/1.3.2) while the mock jar alone
   re-cut (the debt-#20 sources).

## V.B — the desktop side, pinned from the shipped host bundle (all 3.18.1 / `ec475e6e…`)

- **The host guard** (`zcode.cjs` `applyHeldQueueDisposition`, offset 0x00E27xxx):
  when `getInputRoutingMode(sessionId) === "choice"` — the desktop's admission
  matrix serves exactly that mode when `(completedSuccess|completedInterrupted) &&
  queueLength>0 && !autoDrain` (`heldQueueInputRequiresChoice`) — a trio-bearing
  send REQUIRES `heldQueueDisposition` (`heldQueueDispositionRequired`), and a
  non-empty `expectedHeldQueueItemIds` must be **set-equal** to the live queue:
  `set.size === ids.length && getQueueLength() === set.size && ids.every(id =>
  getQueueItem(id) !== null)`; any violation throws
  `V4HeldQueueConfirmationStaleError` ("paused queue changed after send
  confirmation opened"). The CLEAR disposition executes only AFTER the CAS passes.
- **The ack shape**: the dispatcher's execute-catch maps a thrown admission error
  to `{status:"failed", reasonCode:<error.reasonCode>, message:<error.message>}`
  — i.e. **status `"failed"`, NOT `"stale"`/`"rejected"`** — with the verbatim
  reasonCode `guard.heldQueueConfirmationStale`.
- **The renderer's face**: the desktop ignores the fail status when the
  reasonCode matches — `if (T.reasonCode === 'guard.heldQueueConfirmationStale')
  return 'confirmationRequired'` on both the sendText and slash paths; the send
  result feeds `confirmationRequired` → the confirmation state is re-entered with
  the ids **re-read from the current queue snapshot**
  (`Ct.current?.queue.items.map(e => e.queueItemId)`), i.e. the G50 dialog
  RE-OPENS with fresh ids and the send retries through confirmation; the
  send-result settle maps the reasonCode to `null` (no fail settle recorded),
  TTFT records a `confirmationRetry`. `c9e` is the settle mapper
  (`if (e.reasonCode === o9e) return null`).

## V.C — the phone side, static pins (HEAD `e1745ee3`, app unchanged)

- `heldQueueIds()` (`WriteComponents.kt:887`) is read **at ANSWER-tap time** from
  the live session state and rides BOTH dialog buttons (`:1549` clear /
  `:1560` keep) as `expectedHeldQueueItemIds` — the capture is desktop-faithful
  (the ids the user answered with, re-read at answer time).
- The ack mapping (`CommandCoordinator.kt:704-710`): `"accepted"/"duplicate"/
  "noop"` → Accepted; `"stale"` → Stale; **everything else → Failed(ack)** — a
  status-`"failed"` ack lands in `Outcome.Failed` with the reasonCode carried but
  **undifferentiated**.
- The notice (`ConversationViewModel.kt:542-548`): FAILED → the generic ERROR
  SendNotice **"The desktop refused the send — see diagnostics (fault reason
  recorded)."** Draft preserved (only ACCEPTED clears it).
- **Zero `guard.*` / `confirmationRequired` handling exists in the phone** (grep
  receipt over `data/` + `features/conversation/`: one unrelated comment hit) —
  the desktop's re-confirmation loop has no phone counterpart.

## V.D — the mock arm (debt #20, fix `0a037a60`) and the live leg

- **The arm** (`MOCK_QUEUE_GHOST_APPEND_MS=<ms>`; requires `MOCK_QUEUE=1` +
  `MOCK_INPUT_ROUTING=choice`): arms the host-faithful CAS validation on every
  trio-bearing sendText (set-equality, only under `choice`, empty expected
  passes) AND schedules the ONE-SHOT **silent** ghost append (`q-rig-ghost-1`,
  NO queue patch, NO revision bump) `<ms>` after the FIRST trio-bearing sendText
  — the queue moves under the phone's state exactly like a second client
  enqueueing mid-confirmation (the race the guard exists for). On mismatch the
  mock answers the desktop-faithful ack and **mutates nothing** (self-test
  receipts: ack shape, zero rows, zero revision bump, set-equality
  order-insensitivity, duplicates fail, empty passes, choice+disposition
  gating, arm-off regression guard, ghost silence — 7/7; suite **179/0/0**).
- **Leg A — CAS ok (06:49:02, 171 ms):** draft `p19casA` → Send → G50 dialog
  (s01) → Keep queue & send → relay:
  `MOCK: sendText trio heldQueueDisposition=keepQueueAndSend
  requestedDelivery=null expectedHeldQueueItemIds=[q-rig-1, q-rig-2]` →
  `MOCK: sendText heldQueue CAS ok (… live=[q-rig-1, q-rig-2])` → accepted;
  user row + `synthetic reply to: p19casA` appended, strip "Queued (2) · paused"
  unchanged; outbox row `ACKED/accepted`. At +4 s:
  `MOCK: heldQueue ghost appended SILENTLY (no patch) -> live=[q-rig-1, q-rig-2,
  q-rig-ghost-1]` — and the phone's strip STILL renders (2), blind to the ghost.
- **Leg B — CAS MISMATCH (06:50:16, 191 ms):** draft `p19casB` → Send → dialog
  (s02; the phone still shows 2 items) → Keep queue & send → relay:
  `MOCK: sendText heldQueue CAS MISMATCH (disposition=keepQueueAndSend
  expected=[q-rig-1, q-rig-2] live=[q-rig-1, q-rig-2, q-rig-ghost-1]) ->
  guard.heldQueueConfirmationStale (no mutation)` → the ack round-trips as
  `sendConversationCommandV4 ok in 191ms` (a status-failed ack inside a healthy
  transport — logcat receipt), and the phone renders (s03 + w7):
  **"The desktop refused the send — see diagnostics (fault reason
  recorded)."** with the draft `p19casB` PRESERVED, NO user/reply rows appended,
  strip still "Queued (2) · paused".
- **The DB arbiter** (outbox pull, `/tmp/p19/db/`): the leg-B row
  `state=FAILED_TERMINAL, ackStatus=failed,
  ackReasonCode=guard.heldQueueConfirmationStale, ackMessage="paused queue
  changed after send confirmation opened"` — the verbatim host message; the
  leg-A row `ACKED/accepted` in the same table.

## V.E — the row: F-P19-A (P3, NEW)

**The G50 CAS-mismatch face is desktop-divergent.** On
`guard.heldQueueConfirmationStale` the desktop re-opens the held-queue
confirmation with the FRESH queue ids (the send silently retries through
confirmation; no error is surfaced); the phone maps the same ack
(status `failed`) to the generic refusal ERROR notice — the reasonCode is
carried into diagnostics but undifferentiated, and no re-confirmation loop
exists. Recovery on the phone is manual (the draft survives; re-tapping Send
re-opens the dialog), which is why the row is P3 — an honest face on a rare
race path, divergent only in parity. Fix shape: handle the typed reasonCode →
re-open the disposition dialog with re-read ids (desktop parity), or minimally a
distinct notice ("Queue changed on the desktop — confirm again"). Pins: the
`guard.*` grep receipt + an outbox-shaped harness asserting the notice text
divergence (V.C's static pins are the RED shape).

## V.Q — ledger arithmetic + coverage honesty

Open F: **26** (24 defects + 2 obs; F-P19-A new) · Open G: **21 + 3 standing**
(unchanged) · Open smalls: **13 (+1 watch +1 rig)** (unchanged) · Mock debts:
**#20 OPENED+CLOSED** (fix `0a037a60` + the live receipt) — **0 open
work-debts**. First-series receipts: the held-queue CAS ok leg, the CAS
MISMATCH leg (wire → notice → outbox).

### Coverage honesty (what pass 19 did NOT drive)

The desktop's `sendGoalCommand` CAS path (the trio exists there too; the phone's
sendGoal carries no held-queue trio — desktop-only surface), the
`heldQueueDispositionRequired` guard (the phone never sends a disposition-less
send under `choice` — the dialog always answers one), the CAS-mismatch face's
post-fix re-drive (row is OPEN; the fix round will re-drive it), the queue
strip's followup chips (pass-12 receipts), the automations plane (not armed this
gen; its unavailable face noted in V.A0.2), and every standing stop
(TalkBack/Dictate/widget/Take-photo/shade-ACTION).

## Pass-19 verification stamp

Pass 19 completed 2026-11-15 ~07:00; ONE relay generation (nonce
`mock-p19-gen1-7a9b2c`, launcher `/tmp/p19/relay-g1.sh` — no swaps); emulator
inherited from pass 18 (no cold boot; the 06:35 `install -r` restarted the app →
pid 9546; explicit `am start`); logcat fresh at 06:35 → /tmp/p19/logcat.log.
Receipts: 3 screenshots (`/tmp/p19/shots/s01–s03`), AX dumps `/tmp/p19/w/w0–w7`,
relay log `/tmp/p19/relay-g1.log` (the CAS-ok/CAS-MISMATCH/ghost trio lines +
v4calls), DB pull `/tmp/p19/db/` (the outbox arbiter), build log
/tmp/p19-build.log (APK byte-identical `ad449d7d…`/`530ba4ff`, vc53/1.3.2 —
zero app changes; mock jar re-cut). Desktop 3.18.1 / `ec475e6e…` unchanged
(STEP 0). Self-tests `MockZCodePass19RigSelfTest` 7/7 + mock suite **179/0/0**.
gateway.drop for the pass: 0 (no generation swaps, no drops observed). Commits:
`0a037a60` (fix(mock), debt #20) + the docs commit for this part. **Rig left:**
emulator zcode-a16 UP, relay gen1 ALIVE (arms SPENT — both CAS legs consumed,
ghost fired, INPUT_ROUTING=choice + CAS arm sticky; pkill to end), app pid 9546
CONNECTED on relay-1 on the synthetic conversation (timeline: p19casA row +
synthetic reply; draft `p19casB` preserved in the composer; strip "Queued (2) ·
paused"), logcat running → /tmp/p19/logcat.log.

# Part W — pass 20 (APPENDED 2026-11-15, ~08:10): the send-ack TAXONOMY sweep — the goal half of the host's held-queue admission pinned and driven live (NEW F-P20-A: the phone's goal payload structurally lacks the trio the host REQUIRES under choice), the stale face class receipted first-series (typed + honest, NO-DEFECT), and the taxonomy adjudicated closed (guard.* → F-P19-A; heldQueueDispositionRequired → F-P20-A; the stale trio → typed WARNING, no row)

> **Pass 20 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 still
> empty — backlog 8/8 — so the pass opened its own axis: Part V opened F-P19-A on ONE
> typed reasonCode (`guard.heldQueueConfirmationStale`); this pass swept the REST of
> the desktop's send-ack taxonomy from the shipped bundle and drove its driveable
> halves live). **ONE new row: F-P20-A (P3).** Mock arm **#21 OPENED+CLOSED in-pass**
> (`642616ef` — `MOCK_CAS_STALE_TYPES`, the stale-face arm) + the **GOAL half of the
> debt-#20 admission envelope** (`heldQueueGoalAdmissionRejection` — a disposition-less
> sendGoalCommand under choice answers the host-verbatim
> `heldQueueDispositionRequired` failure ack; same `heldQueueCasArm` gate, old rigs'
> shapes unchanged; self-tests `MockZCodePass20RigSelfTest` 8/8, mock suite **187/0/0**).
> First-series receipts: the **goal-refusal leg** (sheet "Set goal" → wire → ERROR
> notice → outbox `FAILED_TERMINAL/heldQueueDispositionRequired` with the payloadJson
> PROVING the missing trio) and the **stale leg** (queue-row Edit → wire → the typed
> "Queue changed on the desktop" WARNING → outbox `FAILED_STALE/proto.staleRevision`).
>
> **Verification stamp:** 2026-11-15 07:05 → 07:57, GLM-5.3-Flash in ZCode,
> everything local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…`
> UNCHANGED — no DELTA. Rig: COLD BOOT (the inherited emulator was down; the pass-19
> gen1 relay pkill'd; one SystemUI ANR on first boot — the rig-side class, dismissed
> Wait). APK byte-identical (`ad449d7d…`/`530ba4ff`, vc53/1.3.2 — zero app changes;
> 237 tasks up-to-date). ONE generation — gen1 `mock-p20-gen1-3f8d1a` (MOCK_ROWS=12 +
> MOCK_QUEUE + MOCK_INPUT_ROUTING=choice + MOCK_QUEUE_GHOST_APPEND_MS=4000 [arms the
> CAS envelope; the ghost itself never fired — no trio send this gen] +
> MOCK_CAS_STALE_TYPES=editQueueItem; no relay swaps); logcat fresh `-T 1` →
> /tmp/p20/logcat.log; wifi left ENABLED per the O.A0.1/NAT lesson.

## W.A0 — rig learnings

1. **The `input text` drop is SLASH-ADJACENT** (new precision on the recorded
   chunk-flake): in five attempts, every chunk-shaped loss sat at the `/`+next-char
   composition boundary (`/goal` → `/oal` twice, `goal` → `go` once, plus tail
   interleaves). Workaround that worked: `/` as its OWN single-char chunk, the
   following letter via KEYEVENT (KEYCODE_G = **35**; 34 is 'F' — cost one attempt),
   the rest as slash-free chunks. New keycode receipts: **keyevent 77 renders `@` on
   this GBoard (not `/`); keyevent 61 is TAB (not DPAD_LEFT — it inserted a tab run
   into the composer).**
2. **Dialog tap truth is NOT uniformly "screenshot-derived"**: the Set-goal DIALOG
   refused both shot-derived taps ((693,890) no-op twice) and accepted the
   DUMP-derived center (811,1041); the queue-row Edit likewise needed dump bounds
   (760,797). The two sources sit ~110–150 px apart whenever an IME/dialog is up —
   and the rendered screenshots read through the tool are DOWNSCALED (2340 → ~2000),
   so pixel coords off them are silently ~17% wrong. Working recipe this pass: dump
   bounds first, verify the effect in the RELAY LOG, treat screenshot coords as
   render-relative only.
3. The goal-refusal ERROR notice PERSISTS (visible 7:53 → 7:55 across screen turns;
   dismissed only by the ✗ or by the next notice replacing it) — the sendNotice slot
   is sticky, not auto-timed.
4. The outbox's stale class has its OWN state: `FAILED_STALE` (vs the refusal class's
   `FAILED_TERMINAL`) — the DB arbiter distinguishes them.

## W.B — the host side (the GOAL half of the admission), pinned from the shipped bundle (all 3.18.1 / `ec475e6e…`)

- **The goal handler routes through the SAME gate as sendText**: `sendGoalCommand`'s
  dispatcher (`SMc`) computes `u = getInputRoutingMode(sessionId)`; under
  `enqueue`/`guide`/active-turn it takes the deferred-queue path (no trio needed),
  but under **`choice`** it calls `n9e(...)` which passes the PAYLOAD's
  `heldQueueDisposition` + `expectedHeldQueueItemIds` straight into
  `applyHeldQueueDisposition` (`Vqe` — the pass-19 body): a missing disposition
  throws **`V4HeldQueueDispositionRequiredError`**
  (`reasonCode="heldQueueDispositionRequired"`, message `"held queue requires
  heldQueueDisposition (clearQueueAndSend | keepQueueAndSend)"`), which the
  dispatcher's execute-catch maps to `{status:"failed", reasonCode, message}` —
  the SAME failed-ack shape as the pass-19 CAS mismatch.
- **The desktop UI pre-gates, so the reasonCode never reaches its renderer**: grep
  receipt over the renderer chunks — `heldQueueDispositionRequired` has **ZERO
  references** (the only heldQueue vocabulary is `heldQueueDisposition` ×7 and
  `heldQueueConfirmationStale` ×4 in `styles-DNV2PY7o.js`); the desktop's goal send,
  like its text send, goes through the confirmation dialog which SUPPLIES the trio.

## W.C — the phone side, static pins (HEAD `642616ef`, app unchanged)

- **The goal payload structurally lacks the trio**: `sendGoalCommand` builds
  `{text, displayText}` ONLY (`CommandCoordinator.kt:220-224`) — and the doc comment
  there says "**not CAS-guarded**", which is true of the CAS set but MISSES the
  admission gate: the host runs `Vqe` on goals too (W.B). The ack mapping
  (`:699-712`) lands the failed ack in `Outcome.Failed` — reasonCode carried,
  **undifferentiated** (same shape as F-P19-A's).
- **Two goal surfaces, neither gated, both generic-faced**: the `/goal` slash
  (dispatch `ConversationViewModel.kt:463-468` → `sendSlash`; refusal face
  "**The goal update was refused by the desktop.**" as WARNING via `sendSlash`'s
  else-branch `:585`, draft preserved — `acceptedClearsDraft` only on accepted) and
  the sheet's "Set goal" (`setGoal` `:601-613`, same copy as **ERROR**,
  `SendNotice.Key.GOAL_REFUSED`; the sheet's affordance rides the same verb per
  `SessionContextSheet.kt:209`). No choice-mode gate, no dialog, no re-confirmation
  loop exists on either — while a paused queue + choice admission stands, a phone
  goal send is STRUCTURALLY unable to succeed.
- **The stale face class is typed and honest** (the sweep's NO-DEFECT half): every
  STALE consumer renders distinct copy — switches/model/effort/row-actions
  "**The session changed on the desktop — try again**" (`:321/:336/:350/:1146`),
  sendText "**Conversation changed on the desktop — please try again.**" (`:537`),
  queue verbs "**Queue changed on the desktop — please try again.**"
  (`:1200-1220`, `queueStaleNotice`).

## W.D — the live legs (gen1 `mock-p20-gen1-3f8d1a`)

- **Leg 1 — the goal-refusal face (07:52:29, 181 ms):** Context sheet → "Set a goal
  for this session" → the goal dialog (s13; field typed clean WITHOUT the slash —
  the drop class is slash-adjacent, W.A0.1) → objective `finish the ledger` → Set
  goal → relay: `MOCK: sendGoalCommand heldQueue admission REFUSED (no
  heldQueueDisposition under choice) -> heldQueueDispositionRequired (no mutation)`
  → logcat `v4.call :: sendConversationCommandV4 ok in 181ms` (a failed ack inside a
  healthy transport) → the conversation renders (s18) the red ERROR banner
  "**The goal update was refused by the desktop.**", strip unchanged "Queued (2) ·
  paused", zero mutations. **The DB arbiter** (outbox pull `/tmp/p20/db/`): the row
  `sendGoalCommand`, payloadJson `{"text":"finish the ledger","displayText":"/goal
  finish the ledger"}` — **the trio's absence verbatim in the wire payload** —
  `state=FAILED_TERMINAL, ackStatus=failed, ackReasonCode=heldQueueDispositionRequired,
  ackMessage="held queue requires heldQueueDisposition (clearQueueAndSend |
  keepQueueAndSend)"`; the pass-19 rows sit directly below for contrast.
- **Leg 2 — the stale face, first series (07:56):** queue-row Edit → "Edit queued
  message" dialog (s21, field pre-filled "queued follow-up one") → Save → relay:
  `MOCK: editQueueItem forced stale (MOCK_CAS_STALE_TYPES) -> proto.staleRevision`
  → the banner (s22) "**Queue changed on the desktop — please try again.**" (the
  typed WARNING replacing the leg-1 notice in the same slot), strip unchanged, no
  mutation. DB: `editQueueItem | FAILED_STALE | stale | proto.staleRevision`.
- **The stale class adjudicated NO-DEFECT**: the desktop treats the stale trio
  (`proto.staleLogEpoch`/`staleRevision`/`staleTarget` — the dispatcher's stale
  productions, zcode.cjs 0xe43a4e-0xe43d14) with a SILENT self-heal — the renderer's
  stale set (`Jfn`/`m$` in `styles-DNV2PY7o.js`) triggers
  `store.recoverFromStaleAuthority()` + `settleCommand` on any stale ack; the phone
  instead shows the honest typed WARNING and the user retries. Different UX,
  truthful copy on both sides — parity-acceptable, recorded so the fix rounds don't
  "fix" it into a row.

## W.E — the row: F-P20-A (P3, NEW)

**The phone's goal sends are structurally refused under the desktop's choice
admission — payload omits the required trio, no gate, generic face.** The host's
`sendGoalCommand` handler passes the payload's `heldQueueDisposition`/
`expectedHeldQueueItemIds` through the SAME `applyHeldQueueDisposition` gate as
sendText (W.B); under `choice` mode a disposition-less goal is refused
(`heldQueueDispositionRequired`, failed-ack). The phone's goal payload carries only
`{text, displayText}` (`CommandCoordinator.kt:220-224`, the comment documenting the
omission as "not CAS-guarded"), and neither goal surface (`/goal` slash, sheet Set
goal) has a choice-mode gate or a distinct face — the generic goal-refusal notice is
all the user gets, with manual recovery (clear/resume the queue via the strip, then
re-send). Desktop parity: its UI pre-gates both sends through the confirmation
dialog and its goal payload carries the trio. P3 for the same reasons as F-P19-A
(rare paused-queue + choice state; honest face; manual recovery). Fix shape: gate
the goal surfaces on the same choice-state computation the composer uses (render
the G50 disposition dialog or a typed "a paused queue is holding sends" notice), or
carry the trio from a goal confirmation; minimally a distinct notice keyed on the
reasonCode. Pins: W.B's handler chain + W.C's payload/surface pins + the leg-1
outbox row (the RED shape — payloadJson without the trio).

## W.Q — ledger arithmetic + coverage honesty

Open F: **27** (25 defects + 2 obs; F-P20-A new) · Open G: **21 + 3 standing**
(unchanged) · Open smalls: 13 (+1 watch +1 rig) (unchanged) · Mock: debt #21
OPENED+CLOSED (`642616ef` — MOCK_CAS_STALE_TYPES + the goal-admission half of the
#20 envelope); **0 open work-debts**. First-series receipts: the goal-refusal leg
(wire → notice → outbox FAILED_TERMINAL + the payloadJson proof), the stale leg
(wire → notice → outbox FAILED_STALE). The send-ack taxonomy sweep is CLOSED:
`guard.heldQueueConfirmationStale` → F-P19-A (open); `heldQueueDispositionRequired`
on goals → F-P20-A (open; zero renderer references = pre-gated desktop); the stale
trio → typed honest WARNING, NO-DEFECT (W.D); everything else in the ack vocabulary
is generic-failed both sides (parity).

### Coverage honesty (what pass 20 did NOT drive)

The composer-slash `/goal` variant of the goal-refusal face (the same verb and port
call as the sheet; its distinct WARNING copy is statically pinned at
`ConversationViewModel.kt:467` — the live drive lost to the slash-adjacent typing
flake, W.A0.1); `sendGoal` WITH a trio (nothing on the phone produces one — the
mock's goal-with-disposition fall-through is untested by any client); the
`heldQueueDispositionRequired` face on sendText (the phone's dialog always answers
a trio under choice — unreachable); the desktop's goal-confirmation dialog live
(desktop-only surface); the stale face on the switch/row-action families (one
family receipted; the copy is enumerated statically); and every standing stop
(TalkBack/Dictate/widget/Take-photo/shade-ACTION).

## Pass-20 verification stamp

Pass 20 completed 2026-11-15 ~08:10; ONE relay generation (nonce
`mock-p20-gen1-3f8d1a`, launcher `/tmp/p20/relay-g1.sh` — no swaps); emulator
COLD-BOOTED this pass (zcode-a16, `-no-snapshot-load`; first boot carried one
rig-side SystemUI ANR, dismissed); `install -r` of the byte-identical APK →
pid 3904; screenshot protection OFF by persistence (captures ~170 KB-class, no
FLAG_SECURE black); logcat fresh at 07:20 → /tmp/p20/logcat.log. Receipts: shots
`/tmp/p20/shots/s00–s22` (s13 goal dialog, s18 goal-refusal notice, s21 edit
dialog, s22 stale notice), AX dumps `/tmp/p20/w/w0–w12`, relay log
`/tmp/p20/relay-g1.log` (the goal-admission + forced-stale lines + v4calls), DB
pulls `/tmp/p20/db/zcode.db{,-wal}` + `zcode2.db{,-wal}` (the outbox arbiter: the
goal row's payloadJson WITHOUT the trio + FAILED_TERMINAL/heldQueueDispositionRequired;
the edit row FAILED_STALE/proto.staleRevision), build log /tmp/p20-mock-build.log +
/tmp/p20-apk-build.log (APK byte-identical `ad449d7d…`/`530ba4ff`, vc53/1.3.2 —
zero app changes; mock jar re-cut at `642616ef`). Desktop 3.18.1 / `ec475e6e…`
unchanged (STEP 0). Self-tests `MockZCodePass20RigSelfTest` 8/8 + mock suite
**187/0/0**. gateway.drop for the pass: 0 (no generation swaps, no drops observed).
Commits: `642616ef` (fix(mock), the goal admission + the stale arm) + the docs
commit for this part. **Rig left:** emulator zcode-a16 UP (PIN-less, wifi ENABLED,
screenshot protection OFF), relay gen1 ALIVE (arms SPENT — the goal leg consumed,
INPUT_ROUTING=choice + CAS arm + CAS_STALE=editQueueItem sticky; pkill to end),
app pid 3904 CONNECTED on relay-1 on the synthetic conversation (the stale notice
face live in the banner slot; strip "Queued (2) · paused"; the composer holds the
typing-flake garbage draft `/goal finish t ledger␉␉␉␉␉␉␉heh` — rig residue, not a
finding), logcat running → /tmp/p20/logcat.log.

# Part X — pass 21 (APPENDED 2026-11-15, ~12:15): the Part-W coverage-honesty leftovers driven — the composer-slash /goal refusal face receipted end-to-end (the G50 dialog FIRES for slash sends and its answer is DISCARDED for goals — F-P20-A stamp 2 with the composer-half mechanism extension), the stale faces on BOTH switch families receipted live, and the host's admission-gate coverage enumerated handler-complete (exactly sendText + sendGoalCommand)

> **Pass 21 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 empty —
> backlog 8/8 — so the pass executed STEP 2 on Part W's coverage-honesty list and
> completed its one open static question). **ZERO new rows.** F-P20-A stamps 2 (the
> composer-slash variant receipted live + a mechanism extension that makes the fix
> SMALLER); the W.Q coverage leftovers "the composer-slash /goal variant" and "the
> stale face on the switch/row-action families" are CLOSED (three stale families now
> live: editQueueItem [pass 20] + switchCollaborationMode + switchModelConfig [this
> pass]; the effort face stays static-only — see X.D). Mock: NO rig changes (debt
> #21's arms covered both legs; the ghost arm was armed and never fired — no
> trio-bearing sendText this pass).
>
> **Verification stamp:** 2026-11-15 11:38 → 12:10, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` / 383,832,025 B
> UNCHANGED — no DELTA. Rig: the inherited emulator was found DOWN (`adb devices`
> empty; it died sometime after pass 20) → COLD BOOT (zcode-a16, `-no-snapshot-load`,
> boot ~35s; one rig-side SystemUI ANR dialog on first drive — dismissed Wait, the
> recorded class). APK byte-identical (`ad449d7d…`/`530ba4ff`, vc53/1.3.2 — 239 tasks
> up-to-date, zero app changes). ONE generation — gen1 `mock-p21-gen1-a7c2e94b`
> (MOCK_ROWS=12 + MOCK_QUEUE + MOCK_INPUT_ROUTING=choice + MOCK_QUEUE_GHOST_APPEND_MS=4000
> [arms the CAS/goal-admission envelope; the ghost itself never fired] +
> MOCK_CAS_STALE_TYPES=switchCollaborationMode,switchModelConfig; no relay swaps).
> Stored endpoints survived the relay swap (constant passHash; no re-pairing — the
> app CONNECTED 16 s after launch). Screenshot protection OFF by persistence
> (captures ~150–215 KB, no FLAG_SECURE black). logcat fresh `-T 1` →
> /tmp/p21/logcat.log. wifi left ENABLED per the O.A0.1 lesson.

## X.A0 — rig learnings

1. **`adb shell input text` SPLITS ITS ARGUMENT ON SPACES** — `input text 'oal rig21
   slash leg'` typed only `oal` (the remote shell word-splits; each word after the
   first is a DROPPED argv). The recorded recipe is `%s` for spaces
   (`oal%srig21%sslash%sleg`); the pass-13-era note recorded it, this pass re-lost two
   typing attempts to forgetting it. Even with `%s`, the TAIL chunk flaked once
   (`leg` landed as a separate append) and one space was eaten at a chunk boundary —
   the objective went out as `rig21 slashleg`; the leg's value is the wire payload,
   so the mangled objective was accepted as-is rather than chased.
2. **Mid-transition uiautomator dumps produce GHOST NODES** — twice this pass a dump
   taken within seconds of a sheet-entrance animation / a post-reconnect resync
   re-render read stale layers: w14 showed a phantom "Edit queued message" dialog
   with `EDITEDONE` (no such dialog ever existed this pass) and w17 showed
   "Queued (3)" + `p18 queue ovie`/`p18 startNo` nodes (pass-18-era strings that
   exist NOWHERE in the live state — drafts table has exactly one row, the strip is
   "Queued (2)" in every settled capture). Rule refined: a dump that shows anything
   surprising is VOID until a settled re-dump confirms it; the screenshot + a fresh
   dump are the pair.
3. Two in-life idle-socket reconnects (11:58:09 RECONNECTING → 11:58:38 CONNECTED,
   ~30 s; 12:00:27 → 12:00:44, ~17 s — the pass-18 honest-recovery class,
   connectionId relay-1 → relay-4 → relay-6) — they bracket BOTH ghost-dump events
   (X.A0.2), which is the mechanism: the dumps raced the resync re-render.

## X.B — the static completion: the admission-gate's handler coverage (all 3.18.1 / `ec475e6e…`)

Part W closed the send-ack taxonomy but enumerated only sendText's and
sendGoalCommand's handlers. This pass enumerated EVERY `getInputRoutingMode` call
site in zcode.cjs (9 hits; zcode.cjs sha256 `666f9c5a…`) and classified each:

- **`Vqe` (the gate body, ~0xe29492)** — under `choice` requires the disposition
  (`e9n` throw → `heldQueueDispositionRequired`); validates the id trio by
  set-equality against the live queue (`t9n` throw → `guard.heldQueueConfirmationStale`).
  Reachable ONLY from the two send handlers.
- **sendText dispatcher (~0xe78d9b)** and **`/goal` handler (~0xe390e1)** — under
  `choice` both fall through to the trio-gated direct send (Parts V.B/W.B).
- **`/compact` handler (~0xe38605)** — under `enqueue`/`guide`/`choice` (or an active
  turn) it takes the DEFERRED path with `requestedDelivery:"queue"` — it NEVER needs
  or carries the trio; a phone `/compact` under choice is structurally QUEUED, not
  refused. Not driven: the mock has no `compact` command handler (it would answer
  `fault.mock.unscripted:compact`, receipting only the known mock face — the
  host-side self-queue stays static-only; recorded here so the fix rounds don't
  re-hunt it).
- **The delivery-defaulting resolver (~0xebaf3c)** and the gateway getter/facades
  (0xe7464e, 0xebc1ea/c210/c22e) — plumbing, not gates.
- **`stop`, the queue verbs, and the switch family never consult routing mode** —
  zero call sites. The pass-20 adjudication ("everything else in the ack vocabulary
  is generic-failed both sides") is therefore HANDLER-COMPLETE: exactly
  {sendText, sendGoalCommand} are admission-gated; nothing else on the phone's
  command inventory (`stop`, `compact`, `editQueueItem`, `reorderQueueItem`,
  `deleteQueueItem`, `sendQueuedNow`, `autoDrain`/`setAutoDrain`, `setFollowupMode`,
  `switchCollaborationMode`, `switchModelConfig`, `pauseGoal`/`resumeGoal`,
  `createSelectionSideSession`, the workflow/hook families) can hit the gate.

## X.C — Leg 1: the composer-slash `/goal` refusal face (11:52–11:54)

Driven with the slash-workaround recipe (`/` own chunk → KEYCODE_G=35 → `%s` chunks;
X.A0.1 documents the two attempts the space-split cost). Composer text settled at
`/goal rig21 slashleg` → Send:

1. **The G50 "Queue is held" dialog FIRED for the slash send** ("Send now and clear
   the queue, or keep the queued messages?" + Clear/Keep buttons, s04) — the
   composer-level choice gate intercepts slash-dispatched sends too. The pass-20
   sheet leg never saw a dialog; THIS is the composer/sheet asymmetry, now receipted.
2. Answered **"Keep queue & send"** → the wire call `sendConversationCommandV4`
   (logcat 11:54:10, ok in 1024 ms) → relay: `MOCK: sendGoalCommand heldQueue
   admission REFUSED (no heldQueueDisposition under choice) ->
   heldQueueDispositionRequired (no mutation)` — **the user's disposition answer was
   DISCARDED**.
3. The code pin for the discard: `send()` (`ConversationViewModel.kt:434-470`)
   receives `heldQueueDisposition`/`requestedDelivery`/`expectedHeldQueueItemIds`,
   but only the `null`-dispatch branch `routeSendPrompt` (:454) forwards them; every
   `sendSlash` branch (:459-470+ — Compact, SendGoal, ResumeGoal, …) calls the port
   WITHOUT any trio parameter. The dialog asks, the user answers, the answer is
   dropped on the slash routing, the payload goes out disposition-less, the host
   refuses.
4. **The face**: the banner "The goal update was refused by the desktop." (s05; the
   `/goal` dispatch's `failureNotice`, Kind WARNING per `sendSlash`'s else-branch
   :585 — the copy is byte-identical to the sheet's ERROR face, so pixels cannot
   distinguish the Kind; the code pin stands). Draft PRESERVED (the composer still
   held the text), strip unchanged "Queued (2) · paused".
5. **The DB arbiter** (`/tmp/p21/db/zcode.db`): `sendGoalCommand | FAILED_TERMINAL |
   failed | heldQueueDispositionRequired`, payloadJson `{"text":"rig21
   slashleg","displayText":"/goal rig21 slashleg"}` — the trio's absence verbatim,
   the slash-variant displayText proving the composer path, directly above the
   pass-19/20 rows.

**F-P20-A consequence (stamp 2, mechanism extension)**: the row's fix shape said
"gate the goal surfaces on the composer's choice-state computation" — the composer
half needs NO new gate: the choice gate ALREADY RUNS for composer slash sends (the
dialog fired) and the fix is pure PLUMBING (carry the answered trio into the goal
branch — `port.sendGoal` needs a trio overload — or at minimum surface the discarded
answer instead of the generic refusal). The SHEET half (`setGoal` :601-613, which
bypasses `send()` entirely) is unchanged — no gate, no dialog, generic ERROR face,
as receipted in Part W.

## X.D — Legs 2/2b: the stale face on BOTH switch families (11:59 / 12:05)

`MOCK_CAS_STALE_TYPES=switchCollaborationMode,switchModelConfig` armed both types
(the arm is command-typed and intercepts before any handler, so it drives even
where the mock has no positive switch behavior — the ack mutates nothing).

- **Leg 2 — `switchCollaborationMode`** (11:59:39): config chips → mode chip
  ("collaboration mode yolo") → the "Switch mode" sheet (Build/Edit/Plan/Full
  access[selected]/Autonomous; s06) → tapped **Edit** → relay:
  `MOCK: switchCollaborationMode forced stale (MOCK_CAS_STALE_TYPES) ->
  proto.staleRevision` → the banner **"The session changed on the desktop — try the
  switch again."** (the typed SESSION_CHANGED WARNING, exact copy per
  `ConversationViewModel.kt:321`), the chip stayed `mode: yolo`. DB: `FAILED_STALE |
  stale | proto.staleRevision | {"mode":"edit"}`.
- **Leg 2b — `switchModelConfig`** (12:05:41): model chip → the "Switch model" sheet
  (Z.AI Default glm-5.1/-air/-flash, Preview; OPENAI-COMPATIBLE glm-4.6; the B1
  flow: tap row → the ATTACHED effort row appears → tap a level to complete) →
  glm-5.1-air + `high` → relay: `MOCK: switchModelConfig forced stale … ->
  proto.staleRevision` → the SAME typed warning banner (s09), the chip stayed
  `account:zai-individual-coding-plan/GLM-5.3-Flash · max`. DB: `FAILED_STALE |
  stale | proto.staleRevision | {"provider":"zai","model":"glm-5.1-air",
  "thought":"high"}`.
- **The stale-face class is now receipted on THREE command families** (queue-row
  edit [pass 20], collaboration-mode, model-config), all with the identical honest
  typed copy — the W.Q "switch/row-action families" leftover is CLOSED. The
  **effort** face (`setThoughtLevel`, `:336`) stays static-only: it rides its OWN
  transport method (not a `sendConversationCommandV4` command type), so the
  command-typed `MOCK_CAS_STALE_TYPES` arm cannot reach it; driving it would need a
  new mock arm on a different wire method — recorded as a rig note, not a debt (the
  copy is enumerated and the class is proven on three families).

## X.E — ledger arithmetic + coverage honesty

Open F: **27** (unchanged — zero new rows; F-P20-A stamps 2) · Open G: **21 + 3
standing** (unchanged) · Open smalls: 13 (+1 watch +1 rig) (unchanged) · Mock debts:
**0 open work-debts** (no new debts; the ghost arm armed-and-unfired is the
envelope's designed one-shot semantics, not a debt). Part W's coverage-honesty list
is now fully adjudicated: composer-slash `/goal` ✓ (X.C); `sendGoal` WITH a trio —
nothing on the phone produces one (unchanged, mock-side fall-through still untested
by any client); `heldQueueDispositionRequired` on sendText — still unreachable (the
dialog always answers a trio); the desktop's goal-confirmation dialog — still
desktop-only; **the stale switch/row-action families ✓** (X.D — three families
live, effort static-only with the reason). The `/compact`-under-choice host path is
statically enumerated (X.B) and mock-blocked from a live drive.

### Coverage honesty (what pass 21 did NOT drive)

The effort-switch stale face (arm-unreachable — the setThoughtLevel transport
method, X.D); `/compact` under choice (mock-unscripted — X.B); `sendGoal` with a
trio (no producer exists phone-side); the row-action stale family beyond the
pass-20 edit (reorder/delete/sendNow share the same `queueStaleNotice` copy
statically); the desktop's own goal-confirmation dialog (desktop-only); and every
standing stop (TalkBack/Dictate/widget/Take-photo/shade-ACTION).

## Pass-21 verification stamp

Pass 21 completed 2026-11-15 ~12:15; ONE relay generation (nonce
`mock-p21-gen1-a7c2e94b`, launcher `/tmp/p21/relay-g1.sh` — no swaps; two in-life
idle-socket reconnects, connectionId relay-1→relay-4→relay-6, honest recovery
~17–30 s each); emulator COLD-BOOTED this pass (zcode-a16, `-no-snapshot-load`; one
rig-side SystemUI ANR on first drive, dismissed); `install -r` of the
byte-identical APK → pid 2888; screenshot protection OFF by persistence; logcat
fresh at 11:43 → /tmp/p21/logcat.log. Receipts: shots `/tmp/p21/shots/s00–s09`
(s04 the G50 dialog on a slash send, s05 the goal-refusal banner, s06 the Switch
mode sheet, s09 the model-stale banner), AX dumps `/tmp/p21/w/w0–w22` (w14/w17 are
the recorded ghost-dump artifacts, X.A0.2), relay log `/tmp/p21/relay-g1.log` (the
goal-admission + both forced-stale lines), DB pulls `/tmp/p21/db/zcode{,2,3}.db{,-wal}`
(the outbox arbiter: the slash-goal row FAILED_TERMINAL with the payloadJson proof,
both switch rows FAILED_STALE with their switch payloads; the drafts table holds
exactly one row — the leg-1 residue). Build log /tmp/p21-apk-build.log (APK
byte-identical `ad449d7d…`/`530ba4ff`, vc53/1.3.2 — zero app changes; mock jar
up-to-date at HEAD). Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0). No mock code
changes this pass (the debt-#21 arms covered both legs as-is). gateway.drop for the
pass: **0**. Zero FATAL/AndroidRuntime lines. **Rig left:** emulator zcode-a16 UP
(PIN-less, wifi ENABLED, screenshot protection OFF), relay gen1 pid 9190 ALIVE
(arms SPENT — the goal-admission leg consumed the envelope; CAS_STALE sticky on
switchCollaborationMode/switchModelConfig; the ghost never fired — no trio-bearing
sendText; pkill to end), app pid 2888 CONNECTED connectionId=relay-6 on the
synthetic conversation (the stale warning live in the banner slot; strip
"Queued (2) · paused"; the composer holds the leg-1 draft `/goal rig21 slashleg `
— rig residue, not a finding), logcat running → /tmp/p21/logcat.log.

# Part Y — pass 22 (APPENDED 2026-11-15, ~17:40): Part X's three driveable coverage-honesty leftovers driven live — the row-action stale family receipted on all three verbs, the effort stale face live-ADJUDICATED as structurally unreachable (NEW N-BQ-1: the protocol method's host error collapses to FAILED, the enumerated SESSION_CHANGED branch is dead code), and /compact under choice live-receipted end-to-end on the NEW mock arm (self-queue → strip 2→3 with the /com… row; duplicate → the typed compactOperationLock refusal) with the G50-on-compact dialog discard stamped onto F-P20-A

> **Pass 22 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 empty —
> backlog 8/8 — so the pass executed STEP 2 on Part X's coverage-honesty list, the last
> part's record). **ZERO app-defect rows opened; ONE new P4 small (N-BQ-1); F-P20-A
> stamps 3 (the compact variant of the composer-half dialog discard).** Two NEW mock
> arms unblocked the static-only faces (committed `8af56472` as `fix(mock)`; self-tests
> 7/7, full mock suite **194/0/0**): `MOCK_SET_THOUGHT_LEVEL_STALE=1` (the host's FQ
> revision-mismatch JSON-RPC error on the effort switch's PROTOCOL method — the
> command-typed stale arm cannot reach that surface) and `MOCK_COMPACT_COMMAND=1`
> (the host command-plane compact handler vMc: under choice the compact SELF-QUEUES a
> kind:"compact" queue item and answers accepted; a second compact answers the typed
> rejected ack compactOperationLock).
>
> **Verification stamp:** 2026-11-15 17:07 → 17:35, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no
> DELTA (the host pins re-verified against the SAME shipped `glm/zcode.cjs`, sha256
> `666f9c5a…` unchanged). Rig: the inherited emulator was found DOWN again (`adb
> devices` empty; the recurring pass-boundary death) → COLD BOOT (zcode-a16,
> `-no-snapshot-load`, boot ~39 s; wifi left ENABLED per the O.A0.1 lesson; screenshot
> protection OFF by persistence — s00 is the 1.37 MB class). APK **byte-identical**
> (`ad449d7d…`/`530ba4ff`, vc53/1.3.2 — zero app changes; the build was up-to-date
> after the mock-only edits). ONE generation — gen1 `mock-p22-gen1-d85ccb3f`
> (MOCK_ROWS=12 + MOCK_QUEUE + MOCK_INPUT_ROUTING=choice +
> MOCK_CAS_STALE_TYPES=reorderQueueItem,deleteQueueItem,sendQueuedNow +
> MOCK_SET_THOUGHT_LEVEL_STALE + MOCK_COMPACT_COMMAND; no relay swaps; ONE in-life
> idle-socket drop 17:20:17 wireReset → honest recovery relay-1 → relay-7, the
> pass-18 class — it bracketed the Chrome-wander accident, Y.A0.3). Stored endpoints
> survived; the app CONNECTED without re-pairing. logcat fresh `-T 1` →
> /tmp/p22/logcat.log.

## Y.A0 — rig learnings

1. **KEYCODE 74 is `;` (semicolon), not `/`** — the slash on the AVD IME is
   **KEYCODE 76** (`input keyevent 76`), and a `'/compact'` chunk with a LEADING
   slash still loses it, so the working recipe is `keyevent 76` then
   `input text 'compact'`. The pass-20/21 recipes ("`/` own chunk + KEYCODE 35")
   never recorded the keycode NUMBER for the slash itself; this pass first typed
   `;compact` (74) before hitting 76.
2. **A system IME onboarding dialog ("Try out your stylus", Reset/Cancel/Next) can
   HIJACK the rig invisibly** — it is a separate IME window: uiautomator dumps the
   app window BEHIND it cleanly (every dump looked normal) while all keyevents and
   text went into the tutorial. Detection = screenshot only (s09). Dismiss via
   screenshot-derived tap on Cancel; the recipe "taps at SCREENSHOT-derived bounds
   for dialogs" now extends to SYSTEM dialogs, not just in-app ones.
3. **A tap into the timeline's "the workspace" hyperlink opens Chrome** (the
   pass-3 link class) — recovery `am start -n …/.MainActivity`; all keyevents typed
   while Chrome was foreground vanished into the browser. The composer FAB bounds
   SHIFT with the IME (field [61,1836][1019,1990] IME-up vs FAB [894,2118][1026,2250]
   IME-down), and a tap 1 px inside a bounds EDGE lands as nothing — tap fresh-dump
   CENTERS only.
4. **uiautomator dumps routinely miss the composer EditText's CURRENT text** while
   the IME commits asynchronously ("field: none" in three dumps whose screenshots
   showed `/compact`) — the composer text arbiter is the SCREENSHOT, not the dump.
   The pass-21 draft residue (`/goal rig21 slashleg `) SURVIVED `install -r` + cold
   boot via the drafts table and re-occupied the composer on first open.

## Y.B — the static pins (host `glm/zcode.cjs` `666f9c5a…` + phone HEAD)

1. **The effort switch rides the PROTOCOL method, whose host layer errors instead of
   acking**: the wire handler `sPi` (`~0xe99b3a`) parses {sessionId, thoughtLevel,
   expectedRevision} and CAS-checks via **`FQ`** (`~0xe25b40`):
   `if(t!==void 0&&t!==e.stateRevision) throw new Dl(-32009,"Session state revision
   mismatch",{actualRevision:e.stateRevision,expectedRevision:t})` — a JSON-RPC
   ERROR, never a `{status:"stale"}` ack (that shape exists ONLY in the
   sendConversationCommandV4 dispatcher, `~0xe3fdff`). The INTERNAL facade
   `setThoughtLevel` (`~0xdd6f06`) has NO CAS at all (desktop-renderer parity: the
   renderer's own effort switch never revision-checks).
2. **The phone chain collapses that error to FAILED**: `V4Plane.setThoughtLevel`
   (V4Plane.kt:1429-1441) returns the raw success object with NO status inspection;
   `ChannelSession.failWith` (ChannelSession.kt:69-76) keeps only the error's
   `message` → `Failure.ChannelError`; `Ports.setThoughtLevel` (Ports.kt:1000-1013)
   catches → `SendOutcome.FAILED`. Therefore the enumerated STALE branch at
   `ConversationViewModel.kt:336` ("The session changed on the desktop — try the
   switch again.", SESSION_CHANGED) is **structurally unreachable** for the real
   transport — only the test fake ever returns STALE (ThoughtLevelRoutingTest's
   scripted port).
3. **The host command-plane compact handler (`vMc`, `~0xe39145`)**: a compact while
   one is running/queued throws `ID("compactOperationLock","Compact is already
   running or queued")` (the dispatcher's typed rejected ack); otherwise under
   `enqueue`/`guide`/`choice` (or an active turn) it SELF-QUEUES —
   `tF(t,{requestedDelivery:"queue",text:"/compact",modelSelection:c})` through
   `steerTurn(...,delivery:"queue")` — and returns (the dispatcher answers
   accepted, NO rows). The compact command is NOT CAS-guarded (the phone's verified
   set; the baseRevision stale arm must never intercept it — the new arm's lock
   check is `queueItems.any { it.kind == "compact" }`, the mock's kind field already
   existing for the pass-18 shapes).

## Y.C — Leg A: the row-action stale family, all three verbs (17:09–17:10)

`MOCK_CAS_STALE_TYPES=reorderQueueItem,deleteQueueItem,sendQueuedNow` (the arm
intercepts any listed command type before its effect handler — zero mock change
needed for this leg). From the open queue panel, per row one:

- **sendQueuedNow** ("Send now", 17:09:39, wire ok 98 ms) → relay
  `MOCK: sendQueuedNow forced stale … -> proto.staleRevision` → banner **"Queue
  changed on the desktop — please try again."** (s02), strip unchanged "Queued (2)
  · paused".
- **reorderQueueItem** ("Move down", wire ok) → same relay line + the SAME banner
  (s03); row order unchanged (one, two).
- **deleteQueueItem** ("Delete", wire ok) → same relay line + the SAME banner (s04);
  both rows still present.
- **DB arbiter** (`/tmp/p22/db/zcode.db`): `sendQueuedNow | FAILED_STALE | stale |
  proto.staleRevision | {"queueItemId":"q-rig-1"}`, `reorderQueueItem | … |
  {"queueItemId":"q-rig-1","beforeQueueItemId":null}`, `deleteQueueItem | … |
  {"queueItemId":"q-rig-1"}` — sitting directly above the pass-20/21 rows (the DB
  carried them through the cold boot).

**The X.C row-action leftover is CLOSED: the stale face class now stands on FOUR
command families** (queue-row edit [pass 20] + send-now/reorder/delete [this pass]),
all with the identical typed copy. No row change — the class was already
adjudicated NO-DEFECT (phone warns honestly vs the desktop's silent
recoverFromStaleAuthority).

## Y.D — Leg B: the effort stale face live → N-BQ-1 (17:13)

Chips expanded (tap the summary chip) → **"Effort · max"** chip → the effort sheet
(medium/high/max radios) → tapped **high** → relay `MOCK: setThoughtLevel forced
revision-mismatch (MOCK_SET_THOUGHT_LEVEL_STALE) -> Dl -32009 (expected=100)` →
logcat `v4.call :: setThoughtLevel channel error: Session state revision mismatch`
+ `port.setThoughtLevel :: TransportFailure: Session state revision mismatch` →
the banner **"The desktop refused the effort switch — nothing changed."** (s07,
the GENERIC EFFORT_REFUSED ERROR) and the chip UNCHANGED ("Effort · max").

**NEW N-BQ-1 (P4): the effort switch's typed stale face is dead code** — the port
maps the protocol error to FAILED (Y.B.2), so `ConversationViewModel.kt:336`'s
SESSION_CHANGED branch can never fire; the effort switch renders the generic
refusal where every switch in its own family (mode/model/queue-verbs) renders the
honest "try again" warning. Fix shape: map the protocol-plane error by its
revision-mismatch identity (or re-shape the host call) so the STALE branch is
reachable; pin: a transport-fake harness asserting `setThoughtLevel` STALE
propagation. Desktop contrast recorded (the renderer's facade has NO CAS at all —
the check exists only for remote clients, i.e. exactly the phone).

## Y.E — Leg C: /compact under choice, both halves (17:22–17:26)

Driven with the Y.A0.1 recipe (keyevent 76 + `compact`):

1. **The G50 "Queue is held" dialog FIRED for the composer /compact send** ("Send
   now and clear the queue, or keep the queued messages?" — s19) — the composer
   choice gate intercepts compact dispatches too. Answered **"Keep queue & send"**
   → wire `sendConversationCommandV4 ok in 473 ms` (17:23:03, relay-7) → relay
   **`MOCK: compact deferred to queue (MOCK_COMPACT_COMMAND, routing=choice) ->
   q-rig-compact-101`** → **the strip grew 2→3 ("Queued (3) · paused") with a
   "/com…" row rendered from the kind:"compact" item, the composer DRAFT CLEARED**
   (acceptedClearsDraft), NO refusal face (s20). DB: `compact | ACKED | accepted |
   {}` — the empty payload verbatim (the compact command carries no trio, exactly
   per the verified shape). **X.B's "/compact mock-blocked" note is now LIVE-CLOSED:
   the pass-21 static conclusion ("structurally QUEUED, not refused") is confirmed
   live, first-series.**
2. **The duplicate compact** (same recipe; the FAB needed the Y.A0.3 center-fix) →
   G50 again → Keep → relay **`MOCK: compact REJECTED compactOperationLock
   (MOCK_COMPACT_COMMAND — already queued)`** → banner **"Compact was refused by
   the desktop (it may already be running or an active turn blocks it)."** (s26,
   the typed COMPACT_REFUSED face), **draft PRESERVED**, strip unchanged at 3. DB:
   `compact | FAILED_TERMINAL | rejected | compactOperationLock | {}`.

**F-P20-A consequence (stamp 3)**: the G50 dialog fired for a compact send and the
answered disposition was DISCARDED (sendSlash's Compact branch calls
`port.compact(taskId)` — `simpleCommand("compact", sessionId, {})` takes no trio)
— the composer-half discard class now covers **compact** too (goal [pass 21],
compact [this pass], text being the only branch that forwards). For compact the
wire OUTCOME is host-faithful either way (vMc consults routing mode, never a trio
— queued either way), but the user's explicit "Clear queue & send" answer is
silently dropped (the desktop would… also queue the compact — vMc has no
disposition path — so the desktop's own compact-under-choice never asks; the
PHONE's dialog is the extra ask). Fix note for the fix rounds: the plumbing shape
is the same as the goal half (carry the answer or stop asking); no new row.

## Y.F — ledger arithmetic + coverage honesty

Open F: **27** (unchanged) · Open G: **21 + 3 standing** (unchanged) · Open
smalls: **14** (+N-BQ-1) (+1 watch +1 rig) · Mock debts: **0 open work-debts**
(the two new arms were opened+closed in the same pass `8af56472`, the established
in-pass pattern). Part X's coverage-honesty list is now FULLY adjudicated: the
row-action stale family ✓ (Y.C, three verbs live); the effort stale face ✓ live —
and ADJUDICATED as dead code (N-BQ-1); `/compact` under choice ✓ live (Y.E, both
halves). What REMAINS not-driven is only the standing set: `sendGoal` with a trio
(no phone producer), `heldQueueDispositionRequired` on sendText (the dialog always
answers a trio), the desktop's own goal-confirmation dialog (desktop-only),
TalkBack/Dictate/widget/Take-photo/shade-ACTION (standing stops), and the N-BQ-1
fix-round re-drive once a fix round touches the effort port.

### Coverage honesty (what pass 22 did NOT drive)

The stale arm on setAutoDrain/setFollowupMode/resumeGoal/pauseGoal (same
command-typed arm, same copy class — statically identical to the receipted four;
not re-driven), the compact `startNow` routing branch (the mock passes through
unscripted — never armed on this rig), the effort sheet's medium/low levels (the
arm answers identically for any level; `high` receipted), and every standing stop
above.

## Pass-22 verification stamp

Pass 22 completed 2026-11-15 ~17:35; ONE relay generation (nonce
`mock-p22-gen1-d85ccb3f`, launcher `/tmp/p22/relay-g1.sh` — no swaps; ONE in-life
idle-socket drop 17:20:17 → honest recovery relay-1 → relay-7); emulator
COLD-BOOTED this pass (zcode-a16, `-no-snapshot-load`; the stylus-dialog +
Chrome-wander accidents are rig-side, both recovered in-session); `install -r` of
the byte-identical APK → pid 4123; screenshot protection OFF by persistence;
logcat fresh at 17:07 → /tmp/p22/logcat.log. Receipts: shots
`/tmp/p22/shots/s00–s26` (s02–s04 the three stale banners, s07 the effort-refusal
banner + unchanged chip, s19 the G50-on-compact dialog, s20 the Queued (3) strip
with the /com… row + cleared composer, s26 the compact-refusal banner), AX dumps
`/tmp/p22/w/w0–w22`, relay log `/tmp/p22/relay-g1.log` (the three forced-stale
lines + the -32009 line + both compact lines), DB pulls
`/tmp/p22/db/zcode{,2}.db{,-wal}` (the outbox arbiter: three FAILED_STALE queue
verbs + `compact|ACKED|accepted|{}` + `compact|FAILED_TERMINAL|rejected|
compactOperationLock|{}` directly above the pass-20/21 rows). Mock changes:
`8af56472` (two arms + MockZCodePass22RigSelfTest 7/7; suite 194/0/0) — committed
BEFORE the legs; APK byte-identical `ad449d7d…`/`530ba4ff` (vc53/1.3.2, zero app
changes). Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0; host pins re-read from
`glm/zcode.cjs` `666f9c5a…`). gateway.drop for the pass: **0**. Zero
FATAL/AndroidRuntime lines. **Rig left:** emulator zcode-a16 UP (PIN-less, wifi
ENABLED, screenshot protection OFF), relay gen1 ALIVE (arms STICKY: the stale
intercepts the three queue verbs, the effort arm answers -32009, the compact arm
is ACTIVE with q-rig-compact-101 IN the queue — the next compact will lock),
app pid 4123 CONNECTED connectionId=relay-7 on the synthetic conversation (the
compact-refusal banner live in the banner slot; strip "Queued (3) · paused"; the
composer holds `/compact` — rig residue, not a finding), logcat running →
/tmp/p22/logcat.log.

# Part Z — pass 23 (APPENDED 2026-11-15, ~18:20): Part Y's three coverage-honesty leftovers driven live — the stale face class extended to SIX typed command families (setAutoDrain + setFollowupMode receipted on the typed queue-changed warning), the goal pause/resume stale faces live-ADJUDICATED as in-family honest (generic-but-retry-anticipating buckets; NO row — the pass-20 standard), the effort sheet's medium level stamped onto N-BQ-1, and the compact startNow/null passthrough receipted END-TO-END on the NEW compact-run marker arm (no G50 dialog → accepted → draft cleared → the manual compaction marker pair rendered from the wire compact — first series)

> **Pass 23 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 empty —
> backlog 8/8 — so the pass executed STEP 2 on Part Y's coverage-honesty list).
> **ZERO new rows in ANY table.** N-BQ-1 stamps 2 (the `medium` level — the arm answers
> identically; the enumerated SESSION_CHANGED copy stays unreachable). The stale-face
> class note widens: the typed warning now stands on SIX command families, and the goal
> verbs' generic buckets are receipted + adjudicated honest. ONE new mock arm unblocked
> the static-only passthrough face (committed `d24a32a7` as `fix(mock)` BEFORE the legs;
> MockZCodePass23RigSelfTest 4/4, full mock suite **198/0/0**): the `MOCK_COMPACT_COMMAND`
> passthrough half now scripts the host-faithful compact RUN — the manual projectMarker
> pair (`running:manual` immediately, `completed:manual` with token counts ~900 ms later,
> both riding the authoritative appendedRows tail) — plus a STRUCTURAL guard (casStaleAck
> hard-excludes "compact" — Y.B.3's "the stale arm must never see it" is no longer
> positional only) and a `MOCK_GOAL_STATUS` env on the MOCK_GOAL arm (default "verifying",
> old rigs unchanged) that made the goal-resume icon leg drivable.
>
> **Verification stamp:** 2026-11-15 17:47 → 18:07, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no DELTA.
> Rig: the inherited emulator was found DOWN again (`adb devices` empty; the recurring
> pass-boundary death, 3rd consecutive pass) and the pass-22 relay was still alive
> (pid 16972, its compact arm would have LOCKED the next compact) → `pkill -f
> MockRelayMainKt` + COLD BOOT (zcode-a16, `-no-snapshot-load`, boot ~30 s; wifi left
> ENABLED per O.A0.1; screenshot protection OFF by persistence — s00 is real content,
> the light home screen at 203 KB). APK **byte-identical** (`ad449d7d…`/`530ba4ff`,
> vc53/1.3.2 — zero app changes; assembleDebug up-to-date). TWO generations: gen1
> `mock-p23-gen1-1505377d` (MOCK_ROWS=12 + MOCK_QUEUE + MOCK_GOAL + **MOCK_GOAL_STATUS=
> paused** + MOCK_CAS_STALE_TYPES=setAutoDrain,setFollowupMode,pauseGoal,resumeGoal +
> MOCK_SET_THOUGHT_LEVEL_STALE + MOCK_COMPACT_COMMAND; NO MOCK_INPUT_ROUTING → null
> routing, the passthrough condition) and gen2 `mock-p23-gen2-eb341a8e` (same minus
> GOAL_STATUS → verifying; stale list narrowed to pauseGoal). Stored endpoints survived
> both relay swaps; no re-pairing. logcat fresh `-T 1` → /tmp/p23/logcat.log.

## Z.A0 — rig learnings

1. **The launcher classpath transcription trap:** hand-copying the `java -cp` line
   silently DROPPED the `org.jetbrains.kotlinx/` group-dir segment on the
   serialization-json jar; the catching check was a full-string `diff` of the two
   launchers' exec lines (an earlier substring grep-diff compared only from the group
   dir onward and MISSED it). Recipe: derive new launchers from the previous pass's
   file with `sed` (as gen2 was), never by re-typing.
2. **BACK pops the whole conversation route when no sheet is open** — the effort sheet
   was auto-dismissed (banner arrival), so the recovery BACK landed on the route and
   returned to Tasks home (s07). Dismiss sheets by drag (`input swipe 540 980 540 2300`)
   or verify a sheet is actually open before BACK.
3. **Ghost dumps at 16 and 64 nodes** (w22, w14) — mid-transition Context-sheet dumps
   produce tiny skeletons with no text nodes; settle ~3 s and re-dump (the pass-21
   lesson, now with two more instances at sheet open/close).
4. **The banner render ignores SendNotice.Kind** — WARNING (queueStaleNotice, s04) and
   ERROR (sheet handlers, s10) render the IDENTICAL red-tinted banner with the ✕
   dismiss affordance; kind divergence is invisible in the UI (adjudication input for
   Z.D).
5. **The composer draft is a real plane:** the pass-22 `/compact` residue SURVIVED
   `install -r` + cold boot and re-occupied the composer (s01), so the compact leg was
   literally "tap Send"; the passthrough ACCEPTED then CLEARED it (acceptedClearsDraft)
   — the residue is consumed, not carried further.

## Z.B — the static pins (phone HEAD `d24a32a7`^)

The four verbs' outcome handling, the recon that shaped the legs:

| verb | wire | CAS | phone handler | stale lands in |
| --- | --- | --- | --- | --- |
| setAutoDrain | command `{autoDrain}` (CommandCoordinator.kt:313) | YES | ConversationViewModel.kt:1247 → `queueStaleNotice` | the TYPED warning "Queue changed on the desktop — please try again." (:1216-1220) |
| setFollowupMode | command `{mode}` (:317-320) | YES | :1252 → `queueStaleNotice` | the TYPED warning |
| resumeGoal | command `{}` (:227-228) | YES | slash :469-474 → `sendSlash`; sheet `resumeGoalFromSheet` :629-640 | sendSlash else → generic WARNING "Goal resume was refused (the conversation may have moved — try again)."; sheet else → the same copy, **Kind.ERROR** |
| pauseGoal | command `{}` (:231-232) | YES | slash :475-480 → `sendSlash`; sheet `pauseGoalFromSheet` :616-628 | sendSlash else → generic WARNING "Goal pause was refused (the goal may not be pausable right now)."; sheet else → the same copy, **Kind.ERROR** |

No STALE branch exists in any goal handler (neither sheet nor slash) — unlike
switchMode/switchModel (:321-322, :350-351) which enumerate it. The desktop's own
stale handling remains silent recoverFromStaleAuthority (Part W).

## Z.C — Leg A: the queue-verb stale faces (17:56, gen1)

From the open queue strip ("Queued (2) · paused", Auto-send OFF, Queue/Guide chips,
two rows):

- **setAutoDrain** (tap the Auto-send row, wire id=24) → relay `MOCK: setAutoDrain
  forced stale (MOCK_CAS_STALE_TYPES) -> proto.staleRevision` → the typed banner
  **"Queue changed on the desktop — please try again."** (s04), strip UNCHANGED at
  "Queued (2) · paused" (the arm mutates nothing).
- **setFollowupMode** (tap Guide, wire id=25) → same relay line → the SAME typed
  banner (s05); the config chip UNCHANGED at "yolo · GLM-5.3-Flash · max · followup ·
  queue".
- **DB arbiter:** `setAutoDrain | FAILED_STALE | stale | proto.staleRevision |
  {"autoDrain":true}` (17:56:31) and `setFollowupMode | … | {"mode":"guide"}`
  (17:56:49) — the payloads verbatim.

**The stale-face class now stands on SIX typed command families** (queue-row edit
[pass 20], sendNow/reorder/delete [pass 22], setAutoDrain + setFollowupMode [this
pass]) — the Part-Y "statically identical, not re-driven" caveat is CLOSED live.

## Z.D — Leg B: the goal-verb stale faces, both entry points (18:01–18:06, gen1+gen2)

gen1 seeded the goal PAUSED (the new `MOCK_GOAL_STATUS=paused`); gen2 the default
verifying — both render the GoalPanel's G26 action icon ("Resume goal" ▶ on paused /
"Pause goal" ⏸ on verifying, `content-desc`-findable at ~[280-260,1146→1278]).

- **Sheet, resume** (gen1, wire id=43) → relay `MOCK: resumeGoal forced stale … ->
  proto.staleRevision` → after the sheet dismissal the banner **"Goal resume was
  refused (the conversation may have moved — try again)."** (s10, the sheet handler's
  `else →` ERROR bucket).
- **Slash, resume** (gen1, wire id=49, typed via keyevent-76 + `goal%sresume`) → relay
  same forced-stale line → the SAME copy from sendSlash's `else →` (WARNING bucket);
  the draft PRESERVED on stale (acceptedClearsDraft is ACCEPTED-only).
- **Slash, pause** (gen1, wire id=50) → relay `MOCK: pauseGoal forced stale …` →
  **"Goal pause was refused (the goal may not be pausable right now)."** (s12).
- **Sheet, pause** (gen2, wire id=9, the ⏸ icon on the verifying goal) → relay same →
  the same pause copy (s13).
- **DB arbiter:** `resumeGoal | FAILED_STALE | stale | proto.staleRevision | {}` ×2
  (18:01:00 sheet, 18:02:55 slash) + `pauseGoal | … | {}` ×2 (18:03:51 slash, 18:06:30
  sheet) — the empty payloads verbatim (the goal verbs carry no fields).

**ADJUDICATED: NO row (the pass-20 standard).** The goal faces are generic-but-honest:
the resume copy NAMES the stale cause ("the conversation may have moved — try again"),
the pause copy anticipates a retry ("right now"), and the banner render is IDENTICAL
to the typed warning (Z.A0.4 — Kind divergence invisible). What the goal verbs lack
versus the switch family is only the enumerated STALE branch and the staleness-naming
copy on pause; recorded here so the fix rounds can decide whether to add the branch
(one-line-per-handler), with the receipts standing as the behavior contract. No
re-hunt.

## Z.E — Leg C: the effort sheet's medium level (17:58, gen1)

Chips expanded (tap the summary chip) → the Effort sheet (medium/high/max radios) →
tapped **medium** (the radio row at c=(540,918)) → relay `MOCK: v4call setThoughtLevel
(id=26)` + `MOCK: setThoughtLevel forced revision-mismatch (MOCK_SET_THOUGHT_LEVEL_
STALE) -> Dl -32009 (expected=100)` → logcat `v4.call :: setThoughtLevel channel
error: Session state revision mismatch` + `port.setThoughtLevel :: TransportFailure`
→ the banner **"The desktop refused the effort switch — nothing changed."** and the
chip UNCHANGED ("Effort · max"). **N-BQ-1 stamps 2** — the dead-branch receipt now
covers two of the three levels (high [pass 22], medium [this pass]; the arm answers
identically for any level, per Part Y's own caveat). NO outbox row (the protocol
method never rides the command outbox — consistent with the N-BQ-1 mechanism).

## Z.F — Leg D: the compact startNow/null passthrough, END-TO-END (17:54, gen1)

The pass-22 rig-left `/compact` draft residue re-occupied the composer (Z.A0.5) — the
leg was a single Send tap. Under NULL routing (no MOCK_INPUT_ROUTING) the phone's
held-queue gate correctly did NOT fire (no G50 dialog — the dialog is the choice-mode
ask), the composer dispatched `sendConversationCommandV4` → the NEW passthrough arm
pushed the host-faithful compact RUN:

- relay `MOCK: compact RAN NOW (MOCK_COMPACT_COMMAND passthrough) -> running:manual
  marker` then `MOCK: compact completed:manual marker pushed …` (~900 ms apart);
- the timeline rendered the manual compaction marker pair LIVE (s03): **`⟳ Compressing
  context`** → **`✓ Context compressed 164K → 38.5K tokens`** (the seeded token counts,
  verbatim) — FIRST-SERIES receipt of a wire-driven manual compact marker pair (the
  seeded compactionDemo rows were the only prior shape);
- the composer DRAFT CLEARED (acceptedClearsDraft), the queue strip UNCHANGED at
  "Queued (2) · paused" (no kind:"compact" item — the compact RAN, it did not queue),
  no rows appended (a command, not a sendText);
- **DB arbiter:** `compact | ACKED | accepted | {}` (17:54:07) — the empty payload
  verbatim, directly above the six stale rows.

**The Part-Y compact-startNow caveat is CLOSED live**: under startNow/null the compact
is neither queued nor refused — it runs, and the mock now shows the host-faithful
shape (the host's vMc runs the turn; the desktop conversation would render the same
projectMarker pair). The pass-22 static conclusion ("structurally QUEUED under
enqueue/guide/choice") stands untouched (the Queued/lock halves were Y.E's receipts).

## Z.G — ledger arithmetic + coverage honesty

Open F: **27** (unchanged) · Open G: **21 + 3 standing** (unchanged) · Open smalls:
**14** (+1 watch +1 rig) (unchanged; N-BQ-1 stamps 2) · Mock debts: **0 open
work-debts** (the passthrough-marker arm + the casStaleAck compact guard + the
MOCK_GOAL_STATUS var were opened+closed in-pass, `d24a32a7` — the established
pattern). Part Y's coverage-honesty list is now FULLY adjudicated: the four stale-arm
verbs ✓ (Z.C/Z.D — six typed families + the goal generic buckets, no row); the
compact startNow branch ✓ (Z.F, with the new arm); the effort medium level ✓ (Z.E).
Second consecutive zero-new-rows pass; the discovery surface stays empty — what
remains is the standing set only.

### Coverage honesty (what pass 23 did NOT drive)

The ACCEPTED halves of all four verbs (the stale arm intercepts before the effects;
setAutoDrain/setFollowupMode accepted were receipted in earlier campaigns — the
toggle's working half pass 12, the followup chips pass 12 — and the goal pause/resume
accepted paths rode the vc51 G26 pins); the effort sheet's `max`→`max` no-op and the
absent `low` level (the sheet renders medium/high/max only); the compact under
enqueue/guide routing (the Queued shape was pass 22's Y.E); sendGoal-with-trio,
heldQueueDispositionRequired-on-sendText, the desktop's own goal-confirmation dialog
(desktop-only), TalkBack/Dictate/widget/Take-photo/shade-ACTION (standing stops); and
the N-BQ-1 fix-round re-drive (gated on a fix round touching the effort port).

## Pass-23 verification stamp

Pass 23 completed 2026-11-15 ~18:07; TWO relay generations (gen1
`mock-p23-gen1-1505377d` launcher `/tmp/p23/relay-g1.sh` 17:47→18:04, gen2
`mock-p23-gen2-eb341a8e` launcher `/tmp/p23/relay-g2.sh` 18:04→end; no idle-socket
drops, no emulator restarts in-life); emulator COLD-BOOTED this pass (the third
consecutive pass-boundary death; the pass-22 relay was found still alive and pkilled
BEFORE the boot); `install -r` of the byte-identical APK → pid 3587; screenshot
protection OFF by persistence; logcat fresh at 17:47 → /tmp/p23/logcat.log. Receipts:
shots `/tmp/p23/shots/s00–s13b` (s01 the residue composer, s03 the compact marker
pair, s04/s05 the typed queue banners, s06 the effort-refusal banner + unchanged chip,
s10 the sheet resume ERROR banner, s12 the slash pause banner, s13b the sheet pause
banner), AX dumps `/tmp/p23/w/w0–w24`, relay logs `/tmp/p23/relay-g{1,2}.log` (two
forced-stale queue lines + four forced-stale goal lines + the -32009 line + both
compact-marker lines), DB pulls `/tmp/p23/db/zcode{,2}.db{,-wal}` (the outbox
arbiter: `compact|ACKED|accepted|{}` + 2×setAutoDrain-shaped, setFollowupMode,
2×resumeGoal, 2×pauseGoal — all FAILED_STALE/proto.staleRevision; setThoughtLevel
correctly absent). Mock changes: `d24a32a7` (passthrough markers + the casStaleAck
compact guard + MOCK_GOAL_STATUS; MockZCodePass23RigSelfTest 4/4; suite 198/0/0) —
committed BEFORE the legs; APK byte-identical `ad449d7d…`/`530ba4ff` (vc53/1.3.2,
zero app changes). Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0). gateway.drop for
the pass: **0**; zero real FATAL/AndroidRuntime lines (the 125 AndroidRuntime matches
are uiautomator's own runtime-init chatter). **Rig left:** emulator zcode-a16 UP
(PIN-less, wifi ENABLED, screenshot protection OFF), relay gen2 ALIVE (arms STICKY:
stale intercepts pauseGoal, the effort arm answers -32009, the compact arm ACTIVE —
the next compact under null/startNow routing runs the marker pair again), app pid
3587 CONNECTED connectionId=relay-5 on the synthetic conversation (the goal-pause
banner live in the banner slot; strip "Queued (2) · paused"; the composer holds
`/goal pause` — rig residue, not a finding), logcat running → /tmp/p23/logcat.log.

# Part AA — pass 24 (APPENDED 2026-11-15, ~18:45): Part Z's coverage-honesty driveables — the effort sheet's max→max no-op receipted WORKING (zero wire traffic on the already-selected level), the level census ADJUDICATED data-driven-complete (the desktop toolbar knows SIX localized levels; the phone renders the echo verbatim; `low`/`ultra` absence is mock-echo fidelity, not an app omission), N-BQ-1 stamped 3 (the xhigh radio's first live observation — third level under the same -32009 collapse), and BOTH admission-reachability residuals pinned unreachable phone-side at HEAD

> **Pass 24 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 empty —
> backlog 8/8 — so the pass executed STEP 2 on Part Z's coverage-honesty list).
> **ZERO new rows in ANY table** (third consecutive). N-BQ-1 stamps 3. The effort-sheet
> plane is now FULLY adjudicated: the no-op guard, the data-driven census, the stale
> collapse (three levels), and both admission residuals. The desktop toolbar's
> effort-level vocabulary is recorded as an inventory note (six localized labels) with
> NO G row — the phone's consumption is data-driven-complete, so there is nothing
> unconsumed to row.
>
> **Verification stamp:** 2026-11-15 18:29 → 18:40, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no DELTA.
> Rig: **the inherited emulator SURVIVED for the first time in four passes** (adb
> emulator-5554 healthy, wifi ENABLED, screenshot protection OFF by persistence) and
> the pass-23 relay gen2 was found ALIVE with its STICKY compact arm (it would have
> LOCKED the next compact) → `pkill -f MockRelayMainKt` by name, emulator REUSED (no
> cold boot). APK byte-identical (`ad449d7d…`/`530ba4ff`, vc53/1.3.2 — zero app
> changes; assembleDebug up-to-date). ONE generation: gen1 `mock-p24-gen1-a41f7c92`
> (launcher `/tmp/p24/relay-g1.sh`, derived from the pass-23 file with `sed` per the
> Z.A0.1 lesson — full-string exec-line diff CLEAN; arms exactly `MOCK_ROWS=12 +
> MOCK_SET_THOUGHT_LEVEL_STALE=1`, the stale arm deliberately ON so any unexpected
> setThoughtLevel is loud). Stored endpoint survived the relay swap; no re-pairing.
> logcat fresh `-T 1` → /tmp/p24/logcat.log.

## AA.A0 — rig learnings

1. **The uiautomator attribute-ORDER trap (new):** a fixed-order regex
   (`checked="…"[^>]*class="…RadioButton"`) silently MISSES nodes whose attribute order
   differs — pass 24's first two greps reported `selected=false`/`checked=false` on ALL
   FOUR effort radios while the truth was `checked=true` on the max row (the per-node
   parse with independent attribute searches settled it). Visual cross-check (the
   screenshot's filled radio) + per-node parsing is the recipe: **never order-depend on
   uiautomator XML attributes; parse each node string and search each attribute
   separately.**
2. **The inherited-rig survival is real but the RELAY is the hazard:** the emulator can
   survive a pass boundary (first observed) while the relay does — pass 23's sticky
   arms (the ACTIVE compact arm, the stale list) would have silently shaped this
   pass's wires. The pkill-before-reuse discipline stands even when the emulator is
   healthy.
3. **The drafts-table residue survived `install -r` a THIRD consecutive time** (the
   pass-23 `/goal pause` composer text re-occupied the composer after this pass's
   `install -r`, Z.A0.5's second → now third observation).
4. The plans-poll drum was VISIBLE this pass (2× `fault.mock.unscripted:
   conversationPlansV4` TransportFailure ~3 s apart in the logcat tail) — F-P1-D's
   known ~8 s poll shape (stamps stand at 4; no re-narration).

## AA.B — the static pins (phone HEAD `9afdbe02`, zero app changes since `d24a32a7`)

1. **The effort sheet is FULLY data-driven:** `for (level in config.thoughtLevels)`
   (`Phase9Components.kt:254`) renders the echo's string array verbatim; the selection
   guard is `if (!selected) onSetThoughtLevel(level)` (:267-268) — tapping the
   already-selected level closes the sheet and sends NOTHING; the chip is
   `"Effort · " + config.thought` (:144, `effort_chip_prefix` strings.xml:244); the
   ViewModel passes the raw string through (`ConversationViewModel.kt:330-332` → the
   port). There is NO app-side level enum anywhere.
2. **The desktop toolbar enumerates SIX localized effort levels** (asar i18n census,
   `chat.toolbar.thoughtLevel.value.*`): `low(低) / medium(中) / high(高) / xhigh(极高) /
   max(最高) / ultra(极致)` — while the wire schema is a FREE string array
   (`thoughtLevels: array(string).default([])`). **ADJUDICATION (the Part-Z "absent
   low" item): NO gap, NO row** — the phone renders whatever the desktop session
   advertises; `low`/`ultra` are absent ONLY because the MOCK's static echo seeds four
   levels (`medium/high/xhigh/max`, `thought:"max"` — the seed predates pass 23,
   `0e4479c4` 2026-10-27, so Part Z's "medium/high/max radios" summary was imprecise;
   the live sheet has shown four radios all along). If a real desktop session
   advertised `low`, the phone would render and send it unchanged (data-driven
   end-to-end). Recorded as an inventory note only.
3. **The send-path enumeration (the Part-Z admission residuals):** EVERY sendText
   funnels through `send()` → `routeSendPrompt` (`ConversationViewModel.kt:454` and
   the PlanShortcut arm :492-498 — the grep finds no other producer), and both thread
   the answered trio into `port.sendPrompt`; the G50 dialog pre-gates under
   choice+held (receipted passes 18/19/21/22). **sendGoal-with-trio: unreachable
   phone-side at HEAD** — `port.sendGoal(taskId, objective, displayText)` (:463-468)
   has no trio parameters at all (that structural omission IS F-P20-A's row); the
   desktop half rides its own confirmation dialog (standing stop).
   **heldQueueDispositionRequired-on-sendText: unreachable phone-side at HEAD** — a
   trio-less sendText requires the dialog to be skipped while the host holds items
   under choice, and every phone path shows the dialog first; the host-side races the
   phone CAN reach answer `guard.heldQueueConfirmationStale` (F-P19-A, receipted) not
   admission; the admission failure stays receipted on the GOAL plane only (pass 20),
   which is exactly the rowed F-P20-A shape. Both residuals CLOSED as one-line
   adjudications — no rows, no re-hunt.

## AA.C — Leg A: the max→max no-op (18:33, gen1)

Chips expanded (the summary chip tap at 445,389) → the Effort chip (`Effort · max`,
679,503) → the "Reasoning effort" dialog with FOUR radios
medium/high/xhigh/max, max `checked=true` (s01, the visual filled radio + the
per-node AX parse — AA.A0.1). Tap the SELECTED max row (540,1380 — the pass-23-proven
dialog coordinates) → the dialog CLOSED, the chip UNCHANGED at "Effort · max", NO
banner, and the wire ARBITER: `setThoughtLevel` count in the relay log **0 before → 0
after** (the single v4call delta between the baselines was a plans-poll, AA.A0.4);
logcat gained no `v4.call :: setThoughtLevel` and no `port.setThoughtLevel` line.
**WORKING receipt — the `:267-268` guard short-circuits the no-op honestly.** No row.

## AA.D — Leg B: the xhigh stale rider (18:36, gen1)

Sheet re-opened → tap **xhigh** (540,1226, first live observation of the fourth
radio) → relay `MOCK: v4call setThoughtLevel (id=21)` + `MOCK: setThoughtLevel forced
revision-mismatch (MOCK_SET_THOUGHT_LEVEL_STALE) -> Dl -32009 (expected=100)` →
logcat `v4.call :: setThoughtLevel channel error: Session state revision mismatch` +
`port.setThoughtLevel :: TransportFailure` → the dialog closed, the banner **"The
desktop refused the effort switch — nothing changed."** (s03), and the chip UNCHANGED
at "Effort · max". **N-BQ-1 stamps 3** — the dead-branch receipt now covers three of
the mock's four levels (high [pass 22], medium [pass 23], xhigh [this pass]; the arm
is level-agnostic by construction, so the medium/high/xhigh triple is the honest
saturation of what the rig can differentiate). **DB arbiter:** the outbox gained ZERO
rows this pass — the newest rows are all pass-23 history
(`pauseGoal`×2/`resumeGoal`×2/`setFollowupMode`/`setAutoDrain`, FAILED_STALE), and
`setThoughtLevel` correctly never rides the command outbox (the protocol-method
collapse, N-BQ-1's mechanism).

## AA.E — ledger arithmetic + coverage honesty

Open F: **27** (unchanged) · Open G: **21 + 3 standing** (unchanged) · Open smalls:
**14** (+1 watch +1 rig) (unchanged; N-BQ-1 stamps 3) · Mock debts: **0 open
work-debts** (no mock changes this pass — the pass-23 arms sufficed; gen1 ran on a
strict subset). Part Z's coverage-honesty list is now FULLY adjudicated: the
max→max no-op ✓ (AA.C, WORKING); the absent-`low` level ✓ (AA.B.2 — data-driven
census, mock-echo fidelity, no gap); the two admission residuals ✓ (AA.B.3 —
unreachable phone-side, code-pinned); the four verbs' ACCEPTED halves / the compact
enqueue-guide shape / the standing stops / the N-BQ-1 fix-round re-drive — skipped
per the rules (receipted in passes 12/16/22/vc51 or gated, zero code changes).
Third consecutive zero-new-rows pass; the discovery surface stays empty — what
remains is the standing set only.

### Coverage honesty (what pass 24 did NOT drive)

The effort sheet's ACCEPTED echo-reconcile half (receipted pass 16 — chip
max→high stable under the mock's config mutation — and the vc51 A3.4 pins; code
unchanged); the followup sheet's identical no-op guard (:238, same pattern, sheet
driven pass 12); `low`/`ultra` on the wire (the mock never advertises them — arming
them would be rig-fiction for a data-driven path the no-op leg already covers);
sendGoal-with-trio and admission-on-sendText (both unreachable, AA.B.3); the
desktop's own goal-confirmation dialog (desktop-only); TalkBack/Dictate/widget/
Take-photo/shade-ACTION (standing stops); the N-BQ-1 fix-round re-drive (gated on a
fix round touching the effort port).

## Pass-24 verification stamp

Pass 24 completed 2026-11-15 ~18:40; ONE relay generation (gen1
`mock-p24-gen1-a41f7c92`, launcher `/tmp/p24/relay-g1.sh`, 18:29→end, pid 20666 —
no idle-socket drops, no emulator restarts in-life); emulator REUSED from pass 23
(the FIRST inherited survival in four passes; the pass-23 relay was pkilled BEFORE
any leg — its sticky compact arm would have locked the next compact); `install -r`
of the byte-identical APK → pid 4881 CONNECTED connectionId=relay-1; screenshot
protection OFF by persistence (every capture real content); logcat fresh at 18:29 →
/tmp/p24/logcat.log. Receipts: shots `/tmp/p24/shots/s01–s03` (s01 the four-radio
sheet with max filled, s02 the post-no-op state, s03 the xhigh stale banner),
AX dumps `/tmp/p24/w/w0–w7`, relay log `/tmp/p24/relay-g1.log` (one forced-stale
setThoughtLevel line; setThoughtLevel count 0 across Leg A), DB pull
`/tmp/p24/db/zcode.db{,-wal}` (the outbox arbiter: zero new rows — all pass-23
history). Mock changes: NONE. APK byte-identical `ad449d7d…`/`530ba4ff` (vc53/1.3.2,
zero app changes). Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0). gateway.drop for
the pass: **0**; zero app-pid FATAL/AndroidRuntime lines (the 37 non-uiautomator
matches are I/D-level lines from transient system pids — none on pid 4881). **Rig
left:** emulator zcode-a16 UP (healthy, wifi ENABLED, screenshot protection OFF),
relay gen1 ALIVE (arm STICKY: setThoughtLevel answers -32009 — the next effort
switch on ANY level will refuse until the relay swap), app pid 4881 CONNECTED
relay-1 on the synthetic conversation (the xhigh stale banner live in the banner
slot; chips expanded; the composer holds `/goal pause` — the third-survival residue,
not a finding), logcat running → /tmp/p24/logcat.log.

# Part AB — pass 25 (APPENDED 2026-11-15, ~19:40): the slash-parser plane driven live — the followup sheet's no-op guard receipted WORKING (Part AA's skipped coverage-honesty item, now wire-arbitered), all THREE local slash-refusal faces receipted first-series (EmptyGoal / UnsupportedGoal / UnsupportedPlanShortcut — verbatim banners, zero wire, draft+attachment preserved each), the /plan dispatch's full wire shape receipted end-to-end (switchCollaborationMode + sendText + the genuine config delta + the chip flip both directions), and the goal SET via slash side-receipted (the third goal entry point rides the same trio-less port — F-P20-A surface census)

> **Pass 25 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 empty —
> backlog 8/8 — so the pass executed STEP 2 on Part AA's coverage-honesty list and then
> extended into the slash-parser plane, the last conversation-input surface family with
> zero live legs). **ZERO new rows in ANY table** (fourth consecutive). ONE rig commit:
> the mock now prints the command type beside every sendConversationCommandV4
> (`9b4a48d9`, suite 198/0/0) — pass 25's gen1 log left ONE unattributed accepted
> command frame (the id=27 note, AB.E.3) and this fix makes every future frame
> attributable at the log line.
>
> **Verification stamp:** 2026-11-15 18:57 → 19:32, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no DELTA.
> Rig: the pass-24 emulator REUSED (second consecutive inherited survival; healthy,
> wifi ENABLED, screenshot protection OFF by persistence — the boot capture 1.37 MB);
> the pass-24 relay gen1 was pkilled BY NAME before any leg (its sticky
> MOCK_SET_THOUGHT_LEVEL_STALE arm would have refused every effort-class wire verb).
> APK byte-identical (`ad449d7d…`/`530ba4ff`, vc53/1.3.2 — assembleDebug up-to-date,
> zero app changes since pass 24). TWO generations: gen1
> `mock-p25-gen1-3d33b1a9` (launcher `/tmp/p25/relay-g1.sh`, 18:59→19:13, arms exactly
> `MOCK_ROWS=12`) and gen2 `mock-p25-gen2-10f1bf` (`/tmp/p25/relay-g2.sh`, 19:14→end,
> same arms + the NEW jar with the type line). Stored endpoint survived BOTH swaps —
> no re-pairing, the app reconnected automatically (hello + subscribe + polls, same pid
> 5305). logcat fresh `-T 1` → /tmp/p25/logcat.log.

## AB.A0 — rig learnings

1. **The AX-radio artifact class is now CONFIRMED BOTH-WAYS (extends AA.A0.1):** the
   followup sheet's radios read `checked=false` on BOTH rows in TWO settled dumps while
   the screenshot showed the queue radio FILLED (blue ring + dot, pixel-probed and
   eyeballed) — and the radio NODE BOUNDS were ~200 px off the rendered positions
   (dump center (540,1028) vs rendered (865,971), the Part-C dialog class). Recipe
   sharpened: **for this app's M3 dialog radios, the AX `checked` attribute and the AX
   bounds are both untrustworthy; the screenshot (pixel probe or eyeball) is the only
   arbiter for state AND tap coordinates.**
2. **The composer input drop is KEYCODE-ADJACENT, not random:** the character typed by
   the FIRST `input text` after a `keyevent 76` (slash) drops reproducibly — `/goal`
   → `/oal` twice (once as one 4-char chunk, once char-by-char) before the fix;
   `/plan` → `/plan` only via `keyevent 44` (P) for the first letter. Recipe: slash via
   keyevent 76, sleep ≥0.6 s, FIRST LETTER via its keycode (35=G, 44=P), remaining
   letters as one word chunk, spaces via keyevent 62.
3. **The photo picker serves a media-scanned push only after a rescan + reopen cycle:**
   the first open after `adb push` still showed the stale grid; the shell
   `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE` scan worked, and the
   SECOND picker open listed the file as the first (newest) tile. The scanner renames
   by MediaStore id — the staged chip read "107.png (178 B)" for the pushed
   rig-p25.png (byte size survives; the pass-5 "Recent on first open" recipe needs the
   scan step when the picker has stale grid state).
4. **The relay swap re-seeds the session config and the chip FOLLOWS the new echo:** at
   the gen1→gen2 swap the phone's locally-set `plan` mode reverted to the fresh
   generation's `yolo` seed (chip re-rendered "yolo · …", expanded chip "mode: yolo")
   — echo-authoritative across reconnects (pass-16's echo-reconcile receipt
   generalizes to relay swaps). No row: the desktop's live session config IS the
   authority and the phone reconciled honestly in both directions (it also followed
   gen2's later `plan` echo at 19:27, AB.E.2).
5. **The banner ✕ and the send FAB share the same screen region and the layout
   shifts between them:** two sends "missed" because the refusal banner's
   dismissal (a mistap) re-flowed the composer — the FAB toggled between real-y
   1285 and 1385 three times this pass. Recipe: screenshot immediately before
   EVERY send tap; dump bounds for the composer row, screenshot for the FAB.
6. The residue draft's `install -r` survival is now FOUR consecutive passes (the
   pass-24 `/goal pause` draft re-occupied the composer at 18:59 before this pass's
   first clear; recorded as rig folklore, not a finding).

## AB.B — the static pins (phone HEAD `15b44eac`, zero app changes)

1. **The followup sheet's no-op guard** (`Phase9Components.kt:237-238`): the row's
   selectable lambda closes the sheet and fires `onSetFollowupMode(mode)` ONLY under
   `if (!selected)` — the identical shape to the effort sheet's `:267-268` guard Part AA
   receipted. Part AA's coverage-honesty list classified this leg "sheet driven pass 12"
   — but pass 12 receipted the PREF chain + the Queue/Guide chips, pass 22/23 receipted
   the CHANGE leg (tap Guide while queue) and its STALE face; the NO-OP wire leg
   (tap the already-selected mode → zero `setFollowupMode`) had never been arbitered.
   This pass drove it (AB.C).
2. **The slash parser's dispatch table** (`SlashCommandParser.kt:40-79`): `/plan` +
   attachments → `UnsupportedPlanShortcut` (:45-53); bare `/goal` → `EmptyGoal` (:62);
   `/goal clear|show` → `UnsupportedGoal` (:65-67); any other `/goal <words>` →
   `SendGoal(objective)` (:68-71) — unknown action words are the OBJECTIVE (desktop
   parity), which is how the accidental `/goal status` leg became a goal SET (AB.D.4).
   `/goal`/`/target` WITH attachments → parser null → plain sendText (the fallthrough
   branch, not driven this pass).
3. **The local refusal face** (`ConversationViewModel.kt:592-594`): `refuseSlash`
   posts a WARNING SendNotice (the banner slot) and touches nothing else — no wire,
   no draft clear, no outbox row. The three refusal copies are inline at :481-487.
4. **The /plan dispatch** (`ConversationViewModel.kt:488-505`): `port.switchMode(taskId,
   "plan")` FIRST; ACCEPTED → `routeSendPrompt(dispatch.task…, skipEmpty=true)`;
   non-accepted → the local WARNING "Could not switch to plan mode — the plan shortcut
   was not sent." The comment pins the desktop contract ("the command only CONFIGURES
   the shortcut (mode + this submission's planEnabled)") and `planEnabled` appears in
   comments only — the phone sends mode + plain text, never a planEnabled field
   (grep receipt; the desktop's planEnabled-on-send is a decode-only divergence,
   inventoried here, NO row — the phone's plan flow works end-to-end as driven).

## AB.C — Leg A: the followup-sheet no-op (19:01, gen1)

Chips expanded → the `followup · queue` chip (415,503) → the "Answer follow-up
delivery" dialog with the queue/guide rows, the queue radio FILLED (screenshot s01 +
pixel probe; BOTH AX radios read checked=false — AB.A0.1) → tap the SELECTED queue row
(screenshot-derived (600,1000)) → the dialog CLOSED, the summary chip AND the expanded
chip UNCHANGED at "followup · queue", and the wire ARBITER: `setFollowupMode` count in
the relay log **0 before → 0 after** (the v4call deltas in the window were the
workflowRuns/plans/resync poll drum, ids 17-19). **WORKING receipt — the :237-238
guard short-circuits the no-op honestly.** The Part-AA coverage-honesty item is CLOSED.

## AB.D — Leg B: the local slash-refusal family, all three faces (19:04-19:08, gen1)

1. **EmptyGoal** — composer cleared, `/goal` typed (the slash palette filtered to the
   catalog row "/goal — Show or set the current session goal. [pause|r…"), Send → the
   red banner **`/goal needs a goal text — e.g. "/goal finish the report".`** (s04)
   with the ✕ affordance; the draft `/goal` INTACT; relay `sendText |
   sendConversationCommandV4 | sendGoalCommand` count **0**.
2. **UnsupportedGoal** — `/goal clear` typed (after one `g`-drop repair, AB.A0.2), Send
   → the banner **`The desktop does not support "/goal clear" here — manage it on the
   desktop.`** (s07c); the draft intact; NO new relay command (count stayed at the
   leg-3 goal-set). Note: an earlier mistap sent `/goal status` first — see (4).
3. **UnsupportedPlanShortcut** — photo staged via the picker (the media-scan recipe,
   AB.A0.3; the chip "✓ 107.png (178 B)" + Remove), `/plan probe` typed, Send → the
   banner **`Plan mode cannot start with attachments attached — remove them and
   retry.`** (s10); attachment chip AND draft preserved; relay count **4 → 4** (zero
   new wire verbs — the refusal is fully local).
4. **The accidental side-receipt — the goal SET via slash:** the first B3 attempt sent
   `/goal status`; the parser's unknown-action fallthrough made it
   `SendGoal(objective="status")` → `sendConversationCommandV4` (relay id=23, 94 ms ok)
   → the outbox row `sendGoalCommand | ACKED/accepted | {"text":"status","displayText":
   "/goal status"}` (DB arbiter), the composer cleared, NO banner (the ACCEPTED side is
   silent by design). **The third goal entry point (slash-set, after the sheet pass-20
   and the queue-verb sheet pass-23) rides the SAME trio-less port** — the payload JSON
   is `{text, displayText}` exactly, F-P20-A's structural omission — **F-P20-A stamps
   4** (surface census; the omission stays harmless outside choice mode, consistent
   with the row).

## AB.E — Leg C: the /plan dispatch end-to-end + the id=27 note (19:10-19:28, gen1+gen2)

1. **gen1, the happy dispatch:** `/plan probe the plan shortcut` typed exactly
   (keyevent-44 recipe) → Send → relay `v4call sendConversationCommandV4 (id=24)` + the
   outbox pair `switchCollaborationMode | ACKED/accepted | {"mode":"plan"}` then
   `sendText | ACKED/accepted | {"text":"probe the plan shortcut"}` + the
   `MOCK: sendText trio … expectedHeldQueueItemIds=[]` line (empty trio — no choice
   gate) → the mock pushed the mode-flip delta (`gateway.frame kind=ONLINE seq=2..2`,
   `manager.deltas ops=1`, "genuine delta — watchdog re-armed") → the config chip
   re-rendered **"plan · GLM-5.3-Flash · max · followup · queue"** + expanded
   "mode: plan" (s09a context) and the timeline gained the YOU/AGENT pair for the plan
   prompt. **WORKING receipt — the two-verb wire shape, the genuine delta apply, and
   the echo-driven chip flip, all first-series.**
2. **gen2, both attribution re-drives:** plain `plain probe` send → exactly ONE
   command (`id=12`, `MOCK: command type=sendText` — the type line's first live
   output) + the YOU/AGENT pair (s13a context); `/plan probe two` re-drive →
   `switchCollaborationMode` (id=16) + `sendText` (id=17) + the chip back to
   "plan · …" (s14) and the outbox rows for both. The gen2 legs also receipted the
   swap-echo re-assert (AB.A0.4).
3. **The id=27 note (NO row):** gen1 logged ONE accepted `sendConversationCommandV4`
   (id=27, ~357 B rpc.send) ~1.1 s after leg C's sendText — NOT in the outbox (the
   CAS-command plane only), no user-visible effect, no state divergence (chip/timeline
   correct; the mode-flip delta and the send acked normally). Neither gen2 re-drive
   reproduced it (2 sends, zero extra frames; the gen1 log pre-dates the type line so
   the frame's `type` is unrecorded). The `9b4a48d9` rig fix makes any recurrence
   self-attributing at the log line; until one recurs this stays a logged curiosity,
   not a defect row.

## AB.F — ledger arithmetic + coverage honesty

Open F: **27** (unchanged; F-P20-A stamps 4 — the slash-set surface) · Open G:
**21 + 3 standing** (unchanged) · Open smalls: **14** (+1 watch +1 rig) (unchanged) ·
Mock debts: **0 open work-debts** (the type line was an observability commit,
opened+closed in-pass, `9b4a48d9`; no new debt). Fourth consecutive zero-new-rows
pass. The Part-AA coverage-honesty list is now FULLY closed (its one mis-filed item —
the followup no-op — receipted AB.C). What remains undriven in this plane: `/goal`/
`/target` WITH attachments (the parser-null plain-sendText fallthrough — low value,
the branch is code-pinned AB.B.2), the "Could not switch to plan mode" non-accepted
arm (reachable only via a stale/refusing switchMode — the pass-21/23 stale arms cover
the family's faces), and `/compact`'s palette row (the dispatch driven passes 22-23).

### Coverage honesty (what pass 25 did NOT drive)

The effort-sheet planes (standing — N-BQ-1's fix-round re-drive gate), the queue
verbs, the automations/runs planes (not armed on gen1/gen2 by design — the home
"isn't available" face re-rendered as receipted), TalkBack/Dictate/widget/Take-photo/
shade-ACTION (standing stops), the desktop's own goal-confirmation dialog
(desktop-only), and the F-P3-A-gated card planes. The `/plan` probe's AGENT reply
rows rendered but the turn-streaming plane was not re-exercised (receipted passes
13/16/17).

## Pass-25 verification stamp

Pass 25 completed 2026-11-15 ~19:32; TWO relay generations (gen1
`mock-p25-gen1-3d33b1a9` 18:59→19:13, gen2 `mock-p25-gen2-10f1bf` 19:14→end, both
`MOCK_ROWS=12` only; launchers `/tmp/p25/relay-g1.sh` + `/tmp/p25/relay-g2.sh`; the
gen2 jar carries the `9b4a48d9` type line); emulator REUSED from pass 24 (second
consecutive inherited survival; no restarts in-life); `install -r` of the
byte-identical APK (`ad449d7d…`/`530ba4ff`, vc53/1.3.2) → pid 5305 CONNECTED across
both swaps (relay-1 gen1, auto-reconnect gen2, no re-pairing); screenshot protection
OFF by persistence (s00 boot capture 1.37 MB). Receipts: shots
`/tmp/p25/shots/s00–s14` (s01 the followup sheet + filled queue radio, s04 the
EmptyGoal banner, s06 the goal-set cleared composer, s07c the UnsupportedGoal banner,
s08a the /plan draft, s09d the picker with the red rig tile, s10 the
UnsupportedPlanShortcut banner, s13a the gen2 plain-probe exchange, s14 the final
plan-mode state); AX dumps `/tmp/p25/w/w0–w14`; relay logs `/tmp/p25/relay-g1.log`
(33 v4call lines) + `/tmp/p25/relay-g2.log` (19 v4call lines + the first
`command type=` outputs); DB pulls `/tmp/p25/db/zcode.db{,-wal}` (gen1: the
sendGoalCommand/switchCollaborationMode/sendText ACKED trio) and
`/tmp/p25/db/zcode2.db{,-wal}` (gen2: the three ACKED rows — plain probe / plan /
probe two). Mock changes: ONE commit `9b4a48d9` (the command-type log line; suite
198/0/0). APK byte-identical (zero app changes). Desktop 3.18.1 / `ec475e6e…`
unchanged (STEP 0). gateway.drop for the pass: **0**; zero app-pid FATAL/
AndroidRuntime lines. **Rig left:** emulator zcode-a16 UP (healthy, wifi ENABLED,
screenshot protection OFF), relay gen2 ALIVE (pid 23162, arms `MOCK_ROWS=12` only —
no sticky refusal arms; the type line live), app pid 5305 on the synthetic
conversation in plan mode (chip "plan · …"; the turn STOP button live from the
probe-two reply; composer empty; the B2 attachment REMOVED before the gen2 legs),
logcat running → /tmp/p25/logcat.log.

---

# Part AC — pass 26 (APPENDED 2026-11-15, ~20:02): the slash-parser plane's REMAINING branches driven live — the /plan dispatch's non-accepted arm receipted first-series (stale switchCollaborationMode → verbatim warning + zero sendText — the branch fires honestly, NO defect), the /goal+attachment parser-null fallthrough receipted (RAW slash text as a plain sendText WITH the attachment — the AB.F leftover closed), the unknown-command raw-sendText contract receipted (/review probe rides verbatim), and the /target alias + /goal replace strip + /goal pause ACCEPTED happy path + /compress alias all receipted first-series — FIVE consecutive zero-new-rows pass

> **Pass 26 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 empty —
> backlog 8/8 — so the pass executed STEP 2 on Part AB's coverage-honesty list and then
> extended into the slash-parser table's remaining undriven branches at HEAD
> `SlashCommandParser.kt:39-72`, which Part AB's record had not fully enumerated:
> the /target alias rides the SAME goal branches (:60), /goal replace strips its
> prefix (:69), /goal resume|pause are CAS-guarded dispatches with only their STALE
> faces ever receipted, /compress is a compact alias (:57), and ANY other /name falls
> through to a RAW plain sendText (:60)). **ZERO new rows in ANY table** (fifth
> consecutive). **ZERO mock changes** — the rig needed nothing new (the generic
> command path answers accepted for pauseGoal/compact; the pass-20 stale arm covered
> leg 1's switchCollaborationMode refusal).
>
> **Verification stamp:** 2026-11-15 19:36 → 20:01, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no DELTA.
> Rig: the pass-25 emulator REUSED (third consecutive inherited survival; healthy, wifi
> ENABLED, screenshot protection OFF by persistence); the pass-25 relay gen2 pkilled BY
> NAME before any leg; `install -r` restarted the app (pid 5305 → 7090) and the stored
> endpoint survived — the app reconnected to gen1 on relaunch WITHOUT re-pairing (hello
> + subscribe + the poll drum). APK byte-identical (`ad449d7d…`/`530ba4ff`, vc53/1.3.2
> — assembleDebug up-to-date, zero app changes). TWO generations: gen1
> `mock-p26-gen1-8d8c23` (launcher `/tmp/p26/relay-g1.sh`, 19:39→19:47, arms exactly
> `MOCK_ROWS=12` + `MOCK_CAS_STALE_TYPES=switchCollaborationMode` for leg 1) and gen2
> `mock-p26-gen2-166cfa` (`/tmp/p26/relay-g2.sh`, 19:47→end, arms `MOCK_ROWS=12` only —
> clean). logcat fresh `-T 1` → /tmp/p26/logcat.log.

## AC.A0 — rig learnings

1. **The banner-✕ / send-FAB region trap (AB.A0.5) re-manifested and cost one send:**
   the leg-1 refusal banner was still up when leg 3's first send tap fired at
   (960,1450) — the tap hit the banner's ✕ (rendered (810,1220) → device ≈(948,1464)),
   the banner dismissed, the draft survived, no wire. Recipe sharpened: **before every
   send tap, CHECK FOR A BANNER in the pre-send screenshot** — its ✕ shares the FAB's
   region and the layout re-flows on dismissal; the leg-3 re-tap one banner-cycle later
   landed cleanly.
2. **The attachment dropdown's Photo row is IME-CLIPPED when the keyboard is open:**
   with the IME up, the + dropdown renders at its anchored height and Photo sits under
   the IME's top edge (partially visible). BACK with the dropdown open closes the
   DROPDOWN (not the IME — the pass-23 BACK-pops-route note generalizes to dropdowns);
   BACK with the dropdown closed dismisses the IME; with NO IME the composer drops to
   full height (Add attachment at (123,2184) per the AX — honest at full-height layout)
   and the dropdown renders ALL rows (Mention a file / Sketch / Photo / Attach a file /
   Take photo / Dictate). Recipe: neutral-tap → BACK (IME) → + → Photo.
3. **The keycode table correction: keyevent 43 is O, not T** (the first-leg attempt
   produced `/oarget`): keycode = 29 + (letter − A); T = 48, R = 46, C = 31, G = 35,
   P = 44 (the pass-24/25 examples stand). Also: tapping (540,1900) while the IME is
   open and the composer sits higher types an IME key (`g`) — tap the composer's
   ACTUAL rendered position, never a remembered coordinate.
4. **The photo picker keeps tile selections across dismiss/reopen:** a tap in the
   Dismiss region while the grid is up can SELECT a tile — this pass's picker session
   staged TWO chips (a stray `86.png (229.1 KB)` + the target `119.png (74 B)`, the
   MediaStore-id rename pattern pass 25 recorded). Remove stray chips before the send
   (the per-chip Remove affordance at the chip row's right edge).
5. **The content query projection is single-column per flag on API 36:**
   `content query --uri … --projection _id,_display_name` fails with "Invalid column";
   query bare and grep the Row output for `_display_name`/`_id` (rig-p26.png = id 119).
6. The residue-draft `install -r` survival folklore continues, but the pass-25 plan
   draft did NOT survive this pass's install (the composer was EMPTY at first open) —
   the draft row survived earlier passes as the same-process restore; a fresh process
   restored nothing because the pass-25 draft was cleared post-leg. No pattern change,
   just the observed variance.

## AC.B — the static pins (phone HEAD `dd8fca48`, zero app changes)

The parser table at `SlashCommandParser.kt:39-72` (read whole this pass — Part AB's
AB.B.2 enumeration was incomplete):
- `/plan` + attachments → UnsupportedPlanShortcut (:47-53, receipted pass 25); bare →
  PlanShortcut (receipted pass 25 accepted; **non-accepted arm receipted THIS pass**,
  AC.C).
- `if (attachmentCount > 0) return null` (:55) — the attachment gate BEFORE the
  compact/goal branches: `/goal|/target` and `/compact|/compress` with attachments all
  fall through to the RAW plain sendText (**receipted THIS pass**, AC.E, on the /goal
  spelling).
- `/compact|/compress` → Compact (:57-59; /compact receipted passes 22-23; **the
  /compress ALIAS receipted THIS pass**, AC.D.4).
- `commandName != "goal" && commandName != "target"` → null (:60) — **the /target
  alias rides the goal branches; receipted THIS pass** (AC.D.2). ANY other /name →
  null → RAW plain sendText (**receipted THIS pass**, AC.D.1 — the "同名 CLI 命令优先"
  contract, first live leg).
- bare `/goal|/target` → EmptyGoal; `clear|show` → UnsupportedGoal (pass 25); `resume`
  /`pause` → ResumeGoal/PauseGoal CAS-guarded dispatches (:64-65 — STALE faces
  receipted pass 23; **the pause ACCEPTED happy path receipted THIS pass**, AC.D.3);
  `replace <x>` strips the prefix (:69, **receipted THIS pass**, AC.D.2b); other words
  → SendGoal (pass 25's accidental leg).

## AC.C — gen1, leg 1: the /plan dispatch's non-accepted arm (19:43)

`MOCK_CAS_STALE_TYPES=switchCollaborationMode` armed; the chip read "yolo · …" (the
gen1 seed echo re-asserted over the pass-25 plan chip — AB.A0.4 again). Composer
cleared → `/plan probe stale arm` typed exactly (keyevent 76 + 44 recipe) → Send →
relay: **exactly ONE command** (`MOCK: command type=switchCollaborationMode` +
`MOCK: switchCollaborationMode forced stale (MOCK_CAS_STALE_TYPES) ->
proto.staleRevision`, id=23) and **ZERO sendText**. UI: the red banner **"Could not
switch to plan mode — the plan shortcut was not sent."** verbatim (s03) with the ✕
affordance; the draft PRESERVED in the composer; the chip UNCHANGED ("yolo · …").
DB arbiter: `switchCollaborationMode | FAILED_STALE | stale | proto.staleRevision |
{"mode":"plan"}` — the pass-21 stale shape, now via the PlanShortcut dispatch entry
point (ConversationViewModel.kt:494-503's else branch; `Ports.kt:669-678` maps the
stale ack to `SendOutcome.STALE` without throwing, so the else fires cleanly).
**WORKING receipt — the branch fires honestly: no sendText after a failed mode
switch, no defect.** (Part AB's AB.F had classified this arm "covered by the pass-21/
23 stale arms" — those receipted the CHIP-switch and queue-verb faces; the dispatch's
own copy was a different branch and was never wire-arbitered until this leg.)

## AC.D — gen2, the parser-table's remaining branch spellings (19:49-19:56)

1. **The unknown-command contract (`/review probe`):** composer → Send → relay
   `command type=sendText` (id=10) ONLY; timeline YOU row **`/review probe` verbatim**
   + AGENT "synthetic reply to: /review probe" (s06); composer cleared; DB
   `sendText | ACKED | {"text":"/review probe"}`. **First-series receipt: any
   non-goal/plan/compact/compress /name is NEVER consumed phone-side — the raw slash
   text rides plain sendText (the desktop's same-name-CLI-wins contract).**
2. **The /target alias (+ the replace strip):** `/target set the objective` → Send →
   `command type=sendGoalCommand`; DB `sendGoalCommand | ACKED |
   {"text":"set the objective","displayText":"/target set the objective"}` — the alias
   dispatches SendGoal identically to /goal (the :60 branch), objective carried,
   displayText verbatim; the composer cleared, no banner (the silent-accepted goal
   side, pass 25's shape). Then `/goal replace write the docs` → DB `sendGoalCommand |
   ACKED | {"text":"write the docs","displayText":"/goal replace write the docs"}` —
   **the replace prefix STRIPPED from the objective (:69), displayText verbatim** —
   the strip branch's first live leg. (The alias rides the SAME sendSlash→port.sendGoal
   path as /goal — no new trio-less surface; F-P20-A's census unchanged.)
3. **The /goal pause ACCEPTED happy path:** `/goal pause` → `command type=pauseGoal`;
   DB `pauseGoal | ACKED | accepted | {}` — **the CAS-guarded goal command's first
   ACCEPTED receipt** (pass 23 only ever drove its stale face). Composer cleared, no
   refusal banner (the GOAL_PAUSE_REFUSED face correctly did not fire). Mock-fidelity
   note: the generic accepted ack carries no goal-state delta, so the goal banner (if
   any) does not flip — the receipt is the dispatch shape (wire + outbox), the same
   arbiter pass 25 used for the goal set.
4. **The /compress alias:** `/compress` → `command type=compact`; DB
   `compact | ACKED | accepted | {}`; composer cleared (acceptedClearsDraft=true).
   Mock-fidelity note: gen2 ran WITHOUT MOCK_COMPACT_COMMAND, so the ack is the
   generic accepted (no self-queue, no ⟳/✓ marker pair) — the pass-22/23 receipts own
   the compact command's host-faithful behavior; this receipt owns the ALIAS dispatch
   (the :57 branch spelling), which was the undriven bit.

## AC.E — gen2, leg 2: the /goal + attachment parser-null fallthrough (19:58-20:00)

The AB.F leftover. rig-p26.png (74 B, 8×8) pushed + MEDIA_SCANNER_SCAN_FILE →
MediaStore id 119 → the picker's second open listed it (the AB.A0.3 stale-grid
recipe; a stray `86.png` selection from the dismiss-tap mishap removed first, chip
`✓ 119.png (74 B)` staged) → `/goal finish the report` typed → Send → relay
**`command type=sendText` ONLY (zero sendGoalCommand)**; timeline YOU row
**`/goal finish the report` RAW as a plain message** + AGENT echo (s24); composer
cleared; DB `sendText | ACKED | {"text":"/goal finish the report","attachments":
[{"ref":"att-up-e2fd9c66-…","fileNam…` — **the attachment gate (:55) forces the
parser null and the RAW slash text rides plain sendText WITH the attachment; the
goal plane correctly does NOT fire.** WORKING receipt — the fallthrough branch is
wire-arbitered; Part AB's AB.F list is fully closed.

## AC.F — ledger arithmetic + coverage honesty

Open F: **27** (unchanged) · Open G: **21 + 3 standing** (unchanged) · Open smalls:
**14** (+1 watch +1 rig) (unchanged) · Mock debts: **0 open work-debts** (no mock
changes this pass). **FIFTH consecutive zero-new-rows pass.** The slash-parser table
at HEAD is now receipted on EVERY dispatch branch and every alias spelling except
`/goal resume`'s ACCEPTED happy path (the pause half receipted AC.D.3; the resume
half is the same port with a mirrored refusal copy — the family's faces are
exhaustively receipted, only that one verb's accepted ack is untyped on the rig) and
`/compact|/compress`-with-attachments (the same :55 gate as /goal — the gate
mechanism is receipted, only the spelling differs). The Part-AB coverage-honesty
list is fully closed (the fallthrough AC.E, the non-accepted arm AC.C).

### Coverage honesty (what pass 26 did NOT drive)

The effort-sheet planes (standing — N-BQ-1's fix-round re-drive gate), the queue
verbs, the automations/runs planes (not armed by design), TalkBack/Dictate/widget/
Take-photo/shade-ACTION (standing stops), the desktop's own goal-confirmation dialog
(desktop-only), the F-P3-A-gated card planes, and the turn-streaming plane (receipted
passes 13/16/17). The attachment-send happy path's THUMBNAIL render was not
re-exercised (the payload carried the attachment; pass 5 owns the render receipt).

## Pass-26 verification stamp

Pass 26 completed 2026-11-15 ~20:01; TWO relay generations (gen1
`mock-p26-gen1-8d8c23` 19:39→19:47 arms `MOCK_ROWS=12` +
`MOCK_CAS_STALE_TYPES=switchCollaborationMode`; gen2 `mock-p26-gen2-166cfa` 19:47→end
arms `MOCK_ROWS=12` only; launchers `/tmp/p26/relay-g1.sh` + `/tmp/p26/relay-g2.sh`);
emulator REUSED from pass 25 (third consecutive inherited survival; no restarts
in-life); `install -r` of the byte-identical APK (`ad449d7d…`/`530ba4ff`, vc53/1.3.2)
→ pid 7090 (install-restart; auto-reconnect to gen1 on relaunch, no re-pairing;
screenshot protection OFF by persistence). Receipts: shots `/tmp/p26/shots/s00-s24`
(s02 the leg-1 draft, s03 the "Could not switch" banner, s06 the /review probe YOU/
AGENT pair, s08 the target leg post-send, s23 the staged-chip + /goal draft, s24 the
fallthrough YOU/AGENT pair); AX dumps `/tmp/p26/w/w0-w25`; relay logs
`/tmp/p26/relay-g1.log` (16 v4call lines) + `/tmp/p26/relay-g2.log` (26 v4call lines,
every command type-attributed by the `9b4a48d9` line); DB pulls `/tmp/p26/db/zcode.db{,-wal}`
— the pass's complete outbox census (7 rows, in order): switchCollaborationMode
FAILED_STALE/stale · sendText `/review probe` ACKED · sendGoalCommand `/target set
the objective` ACKED · sendGoalCommand `write the docs` (replace stripped) ACKED ·
pauseGoal ACKED/{} · compact ACKED/{} · sendText `/goal finish the report`
+attachments ACKED. Mock changes: NONE. APK byte-identical (zero app changes).
Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0). gateway.drop for the pass: **0**
(both generations); zero app-pid FATAL/AndroidRuntime lines. **Rig left:** emulator
zcode-a16 UP (healthy, wifi ENABLED, screenshot protection OFF), relay gen2 ALIVE
(clean arms, no sticky refusals — safe to inherit), app pid 7090 on the synthetic
conversation (chip "yolo · …", composer in the followup-queue state with the
"Queued" badge, timeline bottomed on the leg-2 exchange), logcat running →
/tmp/p26/logcat.log.

---

# Part AD — pass 27 (APPENDED 2026-11-15, ~20:45): the slash-parser table's LAST TWO undriven spellings driven live — `/goal resume`'s ACCEPTED happy path receipted first-series (the parser's final untyped verb: resumeGoal ACKED/{} on the same shape as the pause half) and the `/compact`-with-attachment `:55`-gate fallthrough receipted (RAW sendText WITH the attachment, zero compact on the wire — the gate now receipted on BOTH its spellings) — SIXTH consecutive zero-new-rows pass; the parser table at HEAD is now FULLY saturated on every dispatch branch and every spelling

> **Pass 27 of the pass-0 series** (solo, the canonical discovery prompt; STEP 1 empty —
> backlog 8/8 — so the pass executed STEP 2 on Part AC's coverage-honesty list, which
> named exactly two undriven spellings: "`/goal resume`'s ACCEPTED happy path (the
> pause half receipted AC.D.3; the resume half is the same port with a mirrored refusal
> copy — only that one verb's accepted ack is untyped on the rig)" and
> "`/compact|/compress`-with-attachments (the same :55 gate as /goal — the gate
> mechanism is receipted, only the spelling differs)"). **ZERO new rows in ANY table**
> (sixth consecutive). **ZERO mock changes** — the rig needed nothing new (the mock's
> generic command path answers `accepted` for any non-intercepted type: `resumeGoal`
> has no case in `applyCommandEffect`, no arm needed). **ZERO app changes** (APK
> byte-identical).
>
> **Verification stamp:** 2026-11-15 20:12 → 20:33, GLM-5.3-Flash in ZCode, everything
> local, nothing pushed. STEP 0: desktop 3.18.1 / asar `ec475e6e…` UNCHANGED — no DELTA.
> Rig: the pass-26 emulator REUSED (FOURTH consecutive inherited survival; up since
> 17:43, healthy, wifi ENABLED, screenshot protection OFF by persistence); the pass-26
> relay gen2 pkilled BY NAME before any leg. **One rig event:** the `install -r` restart
> (pid 7090 → 8889) left the transport AUTHENTICATING-wedged — `recovery.deferredAttach
> :: transport=AUTHENTICATING`, `hub.cfgSubscribe :: refused: channel closed` with the
> NAT probe OK (the F-P9-A inherited-wedge shape: no ladder, no face, the relay had come
> up AFTER the app's first connect attempt); a `force-stop` + relaunch recovered it
> instantly (pid 9038, auto-reconnect to gen1, hello + RPCs + heartbeats, NO re-pairing).
> APK byte-identical (`ad449d7d…`/`530ba4ff`, vc53/1.3.2). ONE generation: gen1
> `mock-p27-gen1-7a41e2` (launcher `/tmp/p27/relay-g1.sh`, 20:12→end, arms exactly
> `MOCK_ROWS=12` — clean, no sticky refusals). logcat fresh `-T 1` → /tmp/p27/logcat.log.

## AD.A0 — rig learnings

1. **Per-char `input text` is THE reliable typing recipe on this IME** — every other
   method failed deterministically this pass: keyevent-per-letter garbled
   (`/goal resume` typed as `/obl resume`: the G after keyevent-76 dropped AND the A
   landed as B — a keycode-adjacent shift beyond the pass-25/26 drop note); one-chunk
   `input text 'goal%sresume'` INTERLEAVED (`/goaesumel` — the known interleave); and
   the first chunk following fresh field focus drops its first char
   (`input text '/c'` after keyevent-76 landed only `/`). Per-char single-char
   `input text` calls with ~300 ms sleeps landed 11/11 (`/goal resume`) and 7/8
   (only the fresh-focus first char of `/compact` dropped — repaired by cursor math,
   see 2).
2. **The slash-command menu INTERCEPTS keyevents while open:** with the Commands
   dropdown up, MOVE_END/DEL went nowhere and a full retype no-op'd (the draft
   unchanged across a clear+retype attempt); BACK closes the DROPDOWN (AC.A0.2 again)
   and returns the keyevents to the field. Deterministic repair for a garbled draft:
   single DEL / RIGHT / insert with the cursor — this pass inserted the missing `c`
   after `/` exactly that way (`/ompact` → DEL the stray leading c → RIGHT past the
   slash → `input text 'c'` → `/compact`).
3. **Re-dump before EVERY composer interaction:** the chip row and the Commands menu
   both shift the field (the w5 field [61,1201] vs the no-IME [61,1968]); the
   screenshot-preview scale (~17%, 891×2000 display vs 1080×2340 device) keeps
   costing taps (one home-screen round-trip this pass). The FAB bounds themselves
   were stable ([894,1351][1026,1483] both sends).
4. **The photo picker's tiles render BLACK until thumbnails decode** (swiftshader
   lag) — tile identity comes from the AX desc, not pixels: "Photo taken on Nov 15,
   2026 8:24 PM" = the pushed file's mtime (the content query pairs `_display_name`
   + `_id` + `_size` on one Row line, the AC.A0.5 recipe).
5. **The inherited-rig wedge after a relay pkill is now a TWO-restart pattern:**
   pass 25/26's `install -r` restart reconnected cleanly when the NEW relay was
   already up; this pass the relay came up ~30 s after the app's restart and the
   transport wedged in AUTHENTICATING (the F-P9-A third-variant shape). Recipe:
   bring the relay up FIRST (or force-stop+relaunch the app after it — one extra
   restart beats a multi-minute ladder wait; the wedge had NO face and the strip was
   absent, exactly as Part L/R recorded).

## AD.B — the static pins (phone HEAD `111ae54c`, zero app changes)

- The parser table at `SlashCommandParser.kt:39-72` is UNCHANGED from AC.B's
  enumeration; the two legs target its `:55` gate and the `resume` action arm
  (`if (action == "resume") return SlashDispatch.ResumeGoal(displayText)`).
- The ResumeGoal dispatch rides the SAME `sendSlash` as the pause half
  (`ConversationViewModel.kt:469-474`, `port.resumeGoal(taskId)`, failureKey
  GOAL_RESUME_REFUSED); `acceptedClearsDraft` defaults TRUE (:566-571) so the
  accepted path clears the draft exactly like the compact.
- The mock's generic command path (no resumeGoal/pauseGoal case in
  `applyCommandEffect`, no interception arm) answers
  `{commandId, status:"accepted", revisionAtDecision}` for both verbs — no mock
  change needed; the same arbiter AC.D.3 used.

## AD.C — leg 1: `/goal resume` ACCEPTED happy path (20:21)

Composer cleared → `/goal resume` typed exactly (per-char recipe after one garbled
keyevent attempt, s08/s09 the garble, s11 the exact draft) → Send FAB at the
dump-derived [894,1351][1026,1483] → relay: `sendConversationCommandV4 (id=38)` +
**`command type=resumeGoal`** and nothing else. UI: composer CLEARED
("Message the agent…" placeholder back, s12), **no refusal banner** — the
GOAL_RESUME_REFUSED face correctly did not fire; the chip row unchanged.
DB arbiter: **`resumeGoal | ACKED | accepted | {}`** — exactly the pass-26 pause
shape (AC.D.3), now on the resume verb. **WORKING receipt — the parser's final
untyped verb is wire-arbitered on its accepted path; the family's faces are now
exhaustively receipted (pause accepted AC.D.3 / resume accepted AD.C / both stale
faces pass 23).** Mock-fidelity note (unchanged from AC.D.3): the generic accepted
ack carries no goal-state delta, so no goal banner flips — the receipt is the
dispatch shape (wire + outbox + cleared draft).

## AD.D — leg 2: `/compact` with attachment, the `:55` gate fallthrough (20:30)

rig-p27.png (74 B, 8×8) pushed + MEDIA_SCANNER_SCAN_FILE → MediaStore `_id=121` →
the + dropdown → Photo → the picker's tile identified by its AX desc
("Photo taken on … 8:24 PM", the black-thumbnail recipe AD.A0.4) → chip
**`✓ 121.png (74 B)`** staged (s14; the MediaStore-id rename, pass 25/26's recorded
pattern) → `/compact` typed exact (s18) → Send → relay: **`command type=sendText`
ONLY (id=42) — ZERO `command type=compact`**. UI: the timeline YOU row
**`/compact` RAW as a plain message** (s19) + AGENT "synthetic reply to: /compact";
composer cleared; the chip consumed. DB arbiter: **`sendText | ACKED | accepted |
{"text":"/compact","attachments":[{"ref":"att-up-7d2f417c-fb6f-4334-8f71-cfe6d1b66670",
"fileName":"121.png","mime":"image/png","bytes":74}]}`** — **the attachment gate
(`SlashCommandParser.kt:55`) forces the parser null and the RAW slash text rides
plain sendText WITH the attachment; the compact plane correctly does NOT fire.**
WORKING receipt — the gate is now receipted on BOTH its spellings (/goal AC.E +
/compact AD.D); Part AC's coverage-honesty list is fully closed.

## AD.E — ledger arithmetic + coverage honesty

Open F: **27** (unchanged) · Open G: **21 + 3 standing** (unchanged) · Open smalls:
**14** (+1 watch +1 rig) (unchanged) · Mock debts: **0 open work-debts** (no mock
changes this pass). **SIXTH consecutive zero-new-rows pass.** With AC.D + AC.E +
AD.C + AD.D together, **the slash-parser table at HEAD is FULLY saturated: every
dispatch branch (PlanShortcut accepted AC + non-accepted AC.C, Compact AC.D.4,
SendGoal AB + AC.D.2, ResumeGoal accepted AD.C + stale pass 23, PauseGoal accepted
AC.D.3 + stale pass 23, EmptyGoal AB, UnsupportedGoal AB, UnsupportedPlanShortcut
AB, the `:55` parser-null fallthrough on both spellings AC.E + AD.D) and every
spelling (/goal, /target, replace-strip, /compress, unknown-command,
goal+attachment, compact+attachment) — nothing left to drive on the parser.** The
discovery surface beyond it remains: standing stops + N-BQ-1's fix-round re-drive +
the F-P3-A-gated planes.

### Coverage honesty (what pass 27 did NOT drive)

The effort-sheet planes (standing — N-BQ-1's fix-round re-drive gate), the queue
verbs, the automations/runs planes (not armed by design), TalkBack/Dictate/widget/
Take-photo/shade-ACTION (standing stops), the desktop's own goal-confirmation
dialog (desktop-only), the F-P3-A-gated card planes, the turn-streaming plane
(receipted passes 13/16/17), and the attachment THUMBNAIL render (the payload
carried the attachment; pass 5 owns the render receipt — unchanged code).

## Pass-27 verification stamp

Pass 27 completed 2026-11-15 ~20:33; ONE relay generation (gen1
`mock-p27-gen1-7a41e2`, 20:12→end, arms exactly `MOCK_ROWS=12`; launcher
`/tmp/p27/relay-g1.sh`); emulator REUSED from pass 26 (fourth consecutive inherited
survival; up since 17:43; no restarts in-life); the `install -r` of the
byte-identical APK (`ad449d7d…`/`530ba4ff`, vc53/1.3.2) → pid 8889, then ONE
force-stop+relaunch after the inherited AUTHENTICATING wedge → pid 9038
(auto-reconnect to gen1, no re-pairing; screenshot protection OFF by persistence).
Receipts: shots `/tmp/p27/shots/s00-s19` (s02/s03 the home CONNECTED, s07 the
conversation, s08/s09 the keyevent garble, s11 the exact `/goal resume` draft,
s12 the leg-1 post-send cleared composer, s14 the staged `121.png` chip, s16/s17
the `/ompact` garble + repair, s18 the exact `/compact` draft, s19 the leg-2
YOU/AGENT pair); AX dumps `/tmp/p27/w/w0-w10`; relay log `/tmp/p27/relay-g1.log`
(33 v4call lines — exactly TWO sendConversationCommandV4: id=38 resumeGoal,
id=42 sendText); DB pulls `/tmp/p27/db/zcode.db{,-wal}` — the pass's complete
outbox census (2 rows, in order): resumeGoal ACKED/{} · sendText `/compact`
+attachments ACKED. Mock changes: NONE. App changes: NONE (APK byte-identical).
Desktop 3.18.1 / `ec475e6e…` unchanged (STEP 0). gateway.drop for the pass: **0**;
zero app-pid FATAL/AndroidRuntime lines (the 70 system-level FATAL-grep hits are
other processes' noise). **Rig left:** emulator zcode-a16 UP (healthy, wifi
ENABLED, screenshot protection OFF), relay gen1 ALIVE (clean arms, no sticky
refusals — safe to inherit), app pid 9038 on synth-task-1 (chip "yolo · …",
composer EMPTY, timeline bottomed on the leg-2 exchange), logcat running →
/tmp/p27/logcat.log.
