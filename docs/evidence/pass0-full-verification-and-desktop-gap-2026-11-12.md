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
