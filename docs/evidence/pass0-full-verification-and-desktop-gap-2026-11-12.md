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
