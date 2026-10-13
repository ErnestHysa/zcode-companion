# Android UI/UX Polish Audit — round 3 (the "Apple/Samsung would ship this" pass)

**Date:** 2026-10-13 · **App:** 1.1.0-vc40 + d3f4425 (everything working, nothing polished)
**Bar stated by Ernest:** native, modern, beautiful — "not a JS app from 2012." This audit finds
what stands between the current UI and that bar. Findings only — nothing implemented.
**Method:** full-file reads of every screen (two independent sweeps + theme/nav/manifest pass),
spot-verified claims. ~170 findings; the systemic causes matter more than any single one.

---

## 0. Executive summary

The bones are good: keyed `LazyColumn`s with contentTypes, edge-to-edge enabled, a designed
light/dark token pair, honest state rendering, predictive back declared. What fails the bar is
**six systemic gaps**, each multiplying across every screen:

1. **Zero motion.** No NavHost transitions, no `AnimatedVisibility`/`animateContentSize`/
   `Crossfade`/`animate*AsState` anywhere, no streaming-caret blink, no haptics (literally zero
   `performHapticFeedback` calls in the app). Everything pops, snaps, or teleports.
2. **Dialog-everywhere interaction model.** AlertDialogs stack on AlertDialogs (create-task →
   model picker; edit → confirm; row actions in a dialog; queue edit in a dialog). M3's native
   pattern — `ModalBottomSheet` — is used **nowhere** in the app. This is the single biggest
   "doesn't feel like a modern Android app" tell.
3. **Hand-rolled chrome instead of M3 structure.** One `Scaffold` in the whole app (pairing).
   Four different header implementations, two badge systems, two divider systems, five loading
   patterns, spacing values of 2/4/6/8/10/12/14/15/16dp with no grid, `Surface+clickable` where
   `ListItem`/`Card(onClick)`/`SegmentedButton`/`BadgedBox`/`stickyHeader` exist.
4. **Theme gaps that read as "grey".** Aggressively compact type scale (18sp titles, 11sp labels),
   no dynamic color (Material You), incomplete M3 scheme (no explicit `surfaceContainer*` roles —
   dialogs get the default flat grey), literal ARGB colors leaking (MCP viewer, Glance widget,
   terminal ANSI white-on-light), and a 2014-era platform launch theme (no SplashScreen API →
   blank flash on cold start).
5. **Real bugs on the most-used surfaces** (§2): editing an automation silently corrupts its
   schedule and drops its model; the voice mic gives zero feedback that it's listening and
   discards text typed during dictation; Approve/Deny buttons render flush together; multi-select
   questions render as toggleable radio buttons; the conversation ViewModel dies on rotation;
   git fetch failures show a false "no changes".
6. **~30 hardcoded English strings** in the newest surfaces (composer strips, automations form,
   turn summaries, approvals errors, one bilingual toast) breaking the otherwise localized app,
   plus sub-48dp touch targets and null contentDescriptions concentrated in Git/Files/rows.

And the named one — **the model picker is fully root-caused in §1**; it's not one bug, it's three
stacked issues (stacked dialog + wrong title + hidden two-step flow), and it became the symbol of
the whole audit.

**Recommended order:** P0 bug fixes (§3) → P1 foundation lift (§4, mechanical, lifts every
screen) → P2 screen-level redesigns (§5, model picker + composer + automations + pairing) →
P3 delight (§6). Details and full findings in §7–§8.

---

## 1. The model picker — root cause (named by Ernest)

**Symptom A ("grey/dark skeleton on the outside and inside"):** from the new-task composer, the
model chip opens `ComposerModelPickerDialog` *on top of* `CreateTaskDialog` — two M3
`AlertDialog`s stacked (`TaskListScreen.kt:339-348`), so the picker floats on a second scrim over
an already-dimmed dialog. While options load, the body is a lone 18dp spinner
(`Phase9Components.kt:87-90`) in an otherwise empty grey box — and the dialog's **title is
literally the string "Close"** (`Phase9Components.kt:83`: `title = Text(R.string.action_close)`
— a copy-paste of the button into the title slot). Grey box, grey box, title "Close", tiny
spinner: reads exactly like a broken skeleton.

**Symptom B ("click doesn't highlight, but the bottom shows the right name"):** the conversation
picker (`ModelPickerDialog`, `Phase9Components.kt:277-371`) is a hidden **two-step** flow. Tapping
a model only sets `thoughtMenuFor` (line 341); the radio's `selected` is computed from `config`
— the session's *current* model (line 321) — so **no radio ever moves on tap**. The only
acknowledgment is a small "Thought level for \<model id\>" row appended at the **bottom** of the
scrollable dialog (lines 348-365) where you must tap an effort chip to finish. On a long list,
that row can even be below the fold. Meanwhile the *composer* picker one-tap-picks and dismisses —
two same-looking pickers with opposite interaction models.

**Fix spec (one shared component):**
- ONE `ModelPickerSheet` (M3 `ModalBottomSheet`, not an AlertDialog): provider-grouped
  `LazyColumn` with sticky section headers, a **check icon** (not a radio) on the active model,
  **immediate pending highlight** driven by local `pending` state — never by the session echo.
- One tap on a model: if the model has >1 thought level, expand that row inline (or dock a bottom
  confirm bar "Use \<model\> · \<effort\>" with the effort as a `SegmentedButton`) so the second
  step is visible and attached to what you tapped — never appended at list bottom. One tap when
  there's a single level.
- A "Current" tag on the active row; a search field (desktop has one); 48dp rows.
- The create-task flow stops stacking dialogs: `CreateTaskDialog` becomes a `ModalBottomSheet`
  and reuses the same sheet (or the picker closes + reopens as a sibling sheet).
- Fix the title ("Choose a model"), delete the copy-paste `action_close` title.

---

## 2. Concrete bugs (fix-first list — data loss and broken controls)

| # | Bug | Where |
|---|---|---|
| 1 | **Editing an automation silently corrupts it:** daily time resets to `"09:00"` (hardcoded init, ignores `scheduleRule.hour/minute`) and the configured model is dropped (`pickedModel` starts null) — saving rewrites the user's schedule with zero warning. | `AutomationsSection.kt:269-273` |
| 2 | **Voice mic is visually dead:** `voice.listening` is a plain getter over a var (not Compose state) — the tint/"Listening…" branch never recomposes; and `onFinal` appends to the draft *captured at click time*, discarding anything typed while listening. | `WriteComponents.kt:626,641,701-708` (+`VoiceInput.kt:47-48`) |
| 3 | **Approve/Deny buttons flush + overflowing:** a dead `Spacer(0.dp)` and a constructed-then-discarded `PaddingValues(4.dp)` sit between them; the row has no weight so narrow screens clip — on the highest-stakes surface in the app. | `ApprovalsScreen.kt:169-205` |
| 4 | **Multi-select questions render as toggle-off RadioButtons** with non-clickable labels — wrong control and ~20dp targets on the blocking "agent needs your answer" card. | `WriteComponents.kt:291-325,365-382` |
| 5 | **ConversationViewModel held in `remember(taskId)`** — activity recreation/rotation rebuilds it and loses scroll/draft state; should be backstack-scoped. | `ZCodeApp.kt:374-378` |
| 6 | **Settings switchMessage auto-dismiss launches in composition** — restarts on every recomposition (may never clear) and vanishes abruptly. | `SettingsScreen.kt:325-329` |
| 7 | **Git fetch failures show false data:** `changes()` errors are swallowed to `emptyList()` → "no changes" is a lie; the Retry button only re-checks `summary()`. | `GitScreen.kt:79-92,115-121` |
| 8 | **Terminal ANSI palette white-on-white in light theme** (`Color(0xFFFFFFFF)` text on light `surfaceVariant`); literal 16-color ARGB table ignores the theme. | `TerminalScreen.kt:229-234` |
| 9 | **Unread badge is a bare 16dp dot** (no count, no semantics) while TaskListScreen has a numbered badge capped at "9" with no "+9" — two wrong badge patterns. | `ConversationScreen.kt:545-553`, `TaskListScreen.kt:380-404` |
| 10 | **Pairing shows two different hardcoded desktop versions** (`"3.14.0"` and `"3.14.3"` in adjacent compat screens) — should come from `CompatibilityGate`. | `PairingScreens.kt:259,280` |
| 11 | **Automations Run Now gives no feedback** (result ignored, no busy, no toast) and double-tap double-fires delete/run (busy doesn't gate rows). | `AutomationsSection.kt:73-81,92-106,227-247` |
| 12 | **Pinned strips stack unbounded** in a non-scrollable column — several pending approvals can squeeze the timeline to zero height. | `ConversationScreen.kt:150-200` |
| 13 | **Stale-state remembers:** approval card `confirmOption`/`refineText`/`selected` not keyed on `interactionId` — a new interaction inherits the old answer state; `EditUserQueryDialog` initial text not keyed. | `WriteComponents.kt:84-85,258-259`, `EditUserQueryDialog.kt:36` |
| 14 | **Attachment bytes read on the main thread** in the picker callback (jank/ANR risk). | `WriteComponents.kt:809-819` |
| 15 | **Markdown re-parse per streaming delta** for the whole message (jank on long replies); relative timestamps computed once and never refreshed. | `RowRenderers.kt:444,583-585` |
| 16 | **Files binary-download dialog keyed on a display string** (`downloadState.startsWith("binary")`) — control flow on UI copy. | `FilesScreen.kt:256` |
| 17 | **Workspace collapse saves to disk synchronously on the UI thread** per tap; first frame pays the read inside composition. | `WorkspaceCollapse.kt:26,35` |
| 18 | **Duplicate/rot:** double dividers back-to-back (Settings `:208-209`), duplicated headers ("Workspace files" above Files' own "Files"), dead `AutomationsSection.save()` + two `workspaceKeyFor()`s, broken indentation blocks, duplicate imports. | various |

---

## 3. Systemic causes of the "2012 feel" (fix once, lift everything)

- **Motion system absent:** no nav transitions (`ZCodeApp.kt:200-204`), no AnimatedContent on
  state swaps (pairing's 15 states, conversation body, loading→content), no `animateContentSize`
  on every expand/collapse (tool cards, reasoning, context panel, git diffs, group collapse), no
  `animateItem` on list insert/remove, FAB/notice/strip pop-in/out, static streaming caret.
- **Zero haptics:** no confirmation tick on send/approve/pick, no long-press tick.
- **No Scaffold/TopAppBar discipline:** hand-rolled header Rows on every screen (4 variants),
  duplicated stacked headers on Files/Git/Terminal routes, no scroll elevation, inconsistent
  title styles (18sp vs 15sp vs in-content), manual `statusBarsPadding` ×12 instead of Scaffold
  insets.
- **Dialog-first flows:** model picker (§1), row-action menu (`RowRenderers.kt:116-146` —
  AlertDialog with buttons crammed into dismissButton), queue edit (`WriteComponents.kt:476-501`),
  edit→confirm double modal (`EditUserQueryDialog.kt:37-57`), create-task + workspace
  DropdownMenu-inside-dialog (`TaskListScreen.kt:215-226`), unpair as a full screen in pairing vs
  a dialog in settings (inconsistent). **`ModalBottomSheet` usage: zero.**
- **Theme:** type scale too compact + `labelSmall=labelMedium=11sp` (below the 12sp readability
  floor); `headline*`/`display*` left at M3 defaults (a trap); no letterSpacing (loses Material
  type character); no dynamic color (documented round-1 decision — the "Phase 11 ADR" is due);
  `surfaceContainer*` roles unmapped (flat grey dialogs/sheets); literal ARGB leaks: MCP viewer
  `:139-141`, widget `ColorProvider(Color(0xFFE6A23C))`, terminal ANSI, toast/notice alphas
  (0.35/0.38/0.4/0.5/0.7/0.8f sprinkled); glyph-as-icon ×10 (`✓ ✗ ↑ ↓ • ⚠ › "  •"` incl. the
  active-endpoint marker appended to a title string); `Icons.Filled.Send` not auto-mirrored.
- **Launch:** theme parent `android:Theme.Material.NoActionBar` (2014), no SplashScreen API, no
  `windowBackground` — cold start flashes a blank platform-themed window.
- **Loading/empty/error states:** five different loading patterns (bare "Loading…" text ×4
  screens, static skeleton+text, mini spinner, state-swapped row text, full-screen uncancellable
  spinner in pairing); text-only empty states without icon/CTA; `Toast` ×5 instead of Snackbar;
  errors without retry (Files) or silently swallowed (Git).
- **Touch targets & a11y:** 22–42dp targets (turn chip ~22dp, copy button 24dp, git rows ~36dp,
  files rows ~42dp, slash items ~40dp, context toggle ~28dp, switch rows toggle-only), null
  contentDescriptions on meaningful chevrons/icons, bare `clickable {}` without roles everywhere,
  TalkBack-invisible states (heatmap, status dots, radio menus in settings `:763-784`).
- **Localization holes:** ~30 hardcoded English strings (pending-sends statuses, queue "paused",
  routing hint, "writing…", turn summary "view", approvals error ×2, schedule lines, terminal
  statuses, git headers, files literals, "read-only" ×2, hardcoded "Back" contentDescriptions,
  bilingual toast) + `MM/dd HH:mm` fixed date pattern + locale-sensitive `String.format`.
- **Spacing anarchy:** no tokens; 2/4/6/8/10/12/14/15/16dp mixed within single screens.

---

## 4. P0 — bug fixes (ship first, mostly mechanical)

§2 items 1–8 at minimum (automation corruption, voice dead state, approvals flush, multi-select
radios, ViewModel scope, switchMessage timer, git false-empty, ANSI palette), plus §2.10 (version
literals), §2.13 (stale remembers), §2.16–17. Each is a small, testable fix; §2.1 and §2.2 need
instrumented pins (automation edit round-trip; voice listening state).

## 5. P1 — foundation lift (mechanical, app-wide)

1. **Theme completion:** restore standard M3 type scale (titleLarge 22sp, labels ≥12sp, M3
   letterSpacing; define `headline*` explicitly); map `surfaceContainer*` roles; complete the
   dynamic-color ADR (apply Material You to chrome roles, keep semantic status tokens fixed —
   the exact reconciliation the round-1 doc deferred); replace glyph markers with icons; migrate
   literal ARGB to roles.
2. **Splash:** `androidx.core:core-splashscreen`, `Theme.SplashScreen` parent + DayNight
   post-splash theme, branded window background.
3. **Navigation:** shared-axis/slide+fade `enterTransition`s with predictive-back-aware pops;
   `launchSingleTop`+`popUpTo` on deep-link fallbacks; AnimatedContent for pairing states.
4. **Structure:** Scaffold+TopAppBar on every screen (kills 4 header variants and the duplicated
   Files/Git/Terminal titles); one `SectionHeader`, one `EmptyState(icon+text+CTA)`, one
   `ErrorState(retry)`, one `LoadingSkeleton` (with shimmer, honoring `rememberReducedMotion`),
   `BadgedBox` badges with counts, `HorizontalDivider` everywhere, `stickyHeader` for workspace
   groups, 4/8/12/16 spacing grid as tokens.
5. **Bottom sheets:** migrate every picker/menu/confirm-list to `ModalBottomSheet` (model picker
   §1, row actions, queue edit, mode picker already a dialog — keep dialogs for text-entry only).
6. **Haptics + micro-feedback:** send tick, approve tick, picker select tick, long-press tick;
   copy → check animation + haptic; animated streaming caret; animated FAB/notice entrances.
7. **A11y + i18n sweep:** 48dp minimums (minimumTouchTargetSize), roles + stateDescriptions on
   every selectable, extract the ~30 hardcoded strings, locale-safe formatting.

## 6. P2 — screen-level redesigns (after P1)

- **Model picker** per §1 spec (unified sheet).
- **Create task** → single bottom sheet (workspace exposed picker, model chip reusing the sheet).
- **Automations section** → collapsible; `SegmentedButton` schedule kinds; `TimePicker` for daily;
  validation with inline errors (no silent 09:00/30-min coercion); per-row overflow menu with
  destructive-styled delete; Switch for enable; Run-Now busy + Snackbar result; `animateItem`.
- **Approvals** → weight(1f) buttons with 12dp gap + busy spinner in the tapped button.
- **Pairing** → full-bleed viewfinder with reticle + torch + cancel on progress states.
- **Usage/Quota** → `SegmentedButton` ranges, `LinearProgressIndicator` (animated) for quota bars
  and update download, heatmap a11y + legend, animated bars.
- **Terminal** → theme-derived ANSI palette (readable in light), real toggles, match navigation.
- **Files** → breadcrumbs as M3 navigation pattern, real empty state, download progress bar.

## 7. P3 — delight

Skeleton shimmer; heatmap legend; conversation search; swipe actions on task rows; expanded
tool-output "show more"; quote bar clipping fix; `LinkAnnotation`-based markdown links.

---

## 8. Full findings appendix

Complete per-file findings from both sweeps (evidence base for the sections above).
Categories: HC=hardcoded style/copy, M3=non-native pattern, INT=interaction/state bug,
A11Y=accessibility, MOTION=motion missing, LOAD=state presentation, i18n, CODE=rot.

### ConversationScreen.kt
- 115-334 [M3] hand-rolled header, no TopAppBar/scroll elevation · 206-221 [M3] "Load earlier" strip ~30dp, no role · 215-216 [i18n] concatenated resources · 150-200 [INT] unbounded pinned strips squeeze timeline · 223 [CODE] broken indentation · 313-334 [HC] mixed 8/12/16dp insets in one header · 331-333 [M3] chips row will overflow again (same class as the vc32 bug, lower row) · 390 [HC] magic `+80` px threshold · 429-455 [INT] heuristic unread counting · 523-556 [MOTION] FAB pops · 545-553 [LOAD/A11Y] dot badge, no count/semantics · 602-607 [HC/i18n] "Artifact"/"Subagent ·"/"Hook ·" + raw markerType · 614-619 [i18n] default-locale `%.1f MB` · 232-242 [MOTION] instant body swap · 351-366 [LOAD] text-only empty state.

### RowRenderers.kt
- 51-53 [CODE] duplicate imports · 102-107 [A11Y] long-press via raw pointerInput, no a11y action (TalkBack can't Copy/Edit) · 116-146 [M3] row-action menu = AlertDialog misuse (→ sheet) · 97-98 [HC] hand-rolled radii · 171-175 [MOTION] static caret · 203-209 [HC] rail 3dp/15dp magic · 200-205 [M3] ripple not clipped to card shape (background before clickable) · 244-255,273-303 [MOTION] expand/collapse snaps · 249,253 [HC] 2000-char truncation, no "show more" · 300 [INT] reasoning capped 12 lines, silent loss · 316,369 [HC] hand-drawn hairlines · 331-336,339 [M3/HC] turn chip ~22dp target, hardcoded English label · 432 [HC] "writing…" · 396-405 [CODE] pointless double code background · 459,467,482 [HC] glyph markers, magic quote bar, alpha · 508-537 [M3] hand-rolled link hit-testing (→ LinkAnnotation) · 529 [HC] bilingual toast · 566-576 [A11Y] 24dp copy button · 557-565 [MOTION] copy feedback silent · 583-585 [INT] stale relative times · 444 [INT] re-parse per delta.

### WriteComponents.kt
- 84-85,258-259 [INT] unkeyed remembers across interactions · 125,163 [HC] take(400), alpha .8 · 132-194 [INT] no busy acknowledgment on approval card · 291-325,302-316,365-382 [INT/A11Y] radio-vs-checkbox, tiny inert labels · 444,536,762-764 [HC/i18n] hardcoded English ("paused", routing hint, Sending…/uncertain/Not sent) · 450-472 [M3] queue rows as TextButton pairs · 476-501 [M3] queue edit in dialog · 534-541 [MOTION] hint pops · 599 [HC] "+" icon labeled attach; two attach buttons · 626,641,701-708 [INT] voice dead state + stale draft · 673-683 [MOTION] stop pops · 684-699 [M3] Send not auto-mirrored · 709-716 [LOAD] bare red error · 788,794 [HC] alpha/10dp · 809-819 [INT] main-thread file read.

### ApprovalsScreen.kt
- 197-198 [CODE] dead Spacer(0)/PaddingValues → flush buttons · 169-205 [INT] unwrapped Row overflows · 151-155 [M3] Card+clickable · 155 [INT] silent no-op card tap · 183,237 [i18n] duplicated hardcoded error · 114-123 [LOAD] bare red text · 174-195 [INT] no busy acknowledgment · 124-140 [LOAD] text-only empty, no PTR · 60 [CODE] scope param anti-pattern · 77-103,146 [HC] header/spacing off-system.

### ContextUsagePanel.kt
- 51-53 [INT] early-return before rememberSaveable (slot-order hazard) · 68-72 [A11Y] 28dp toggle · 71 [M3] bare clickable · 93-114 [MOTION] expand jumps · 97-112 [CODE] dead Row wrapper · 58,67 [HC] off-grid padding.

### EditUserQueryDialog.kt — 37-57 [M3] double-modal confirm · 36 [INT] unkeyed initial text · 69-76 [INT] 6-line cap, no scroll · 82 [INT] inert confirm with no explanation.
### SlashCommandMenu.kt — 41-47 [M3] hand-rolled unbounded menu · 54 [M3] no role/selected/highlight · 67 [HC] fixed 112dp column · 59 [HC] ~40dp rows.

### TaskListScreen.kt
- 167-212,227-232 [INT] bottom notice, 4s auto-delete incl. errors, string-equality tone · 191-201 [INT] FAB not disabled mid-create; text-only busy · 215-226 [M3] DropdownMenu inside dialog · 259-348,339-348 [M3] dialog-over-dialog create+model picker · 120-127 [M3] connection line no affordance · 140-156 [M3] chip tap-resets-to-All asymmetry · 152 [INT] hard clip no ellipsis · 178-186 [CODE] broken indentation · 380-404 [M3] hand-rolled badge vs conversation's dot · 396 [HC] count capped at 9 silently · 423 [HC] copied skeleton rail · 500-527 [M3] no stickyHeader; two divider systems · 518-526 [MOTION] collapse teleports · 599,604 [A11Y] no role; single-blob contentDescription · 593 [INT] raw path fragment leaks to UI · 633 [HC] "  ·  " vs " · " · 25,46,132 [CODE] duplicate/qualified imports.

### AutomationsSection.kt
- 65-77,145-150 [LOAD] bare "Loading…" + gray non-actionable "unavailable" · 83-107,112,251 [CODE] dead save(), duplicate workspaceKeyFor · 73-81,92-106,227-247 [INT] Run-Now silent, busy gates nothing, double-fire deletes · 458-472 [M3] chip-as-Switch + raw lifecycleStatus in label · 427,428 [A11Y] invisible edit affordance · 474-493 [M3] three TextButtons per row · 333-345 [M3] chips reinvent SegmentedButton, no selected semantics · 269-286,354-360,278 [INT] silent corruptions (09:00, model drop, cron→"0 9 * * *", bad int→30) · 381-385 [INT] dead mode field · 497-509,536-538 [HC/i18n] schedule/status literals · 159-181 [MOTION] rows snap · 114-198 [INT] section not collapsible; off-peak list pushes tasks away.

### WorkspaceCollapse.kt — 26,35 [INT] sync disk I/O in composition/click · 32-33 [CODE] cryptic toggle.

### SettingsScreen.kt
- 78-92 [M3] no Scaffold/TopAppBar · 91 [HC] mixed onBackground/onSurface tokens · 96-209 [HC] alpha dividers ×8; double divider 208-209 · 126-138 [LOAD] busy = text swap, no progress/disabled · 143-147,457,467,512 [M3] Toast ×5 · 179-199 [LOAD] update % as text math · 184-194 [INT] unsafe inline casts · 200-207 [MOTION] button pops · 287 [HC] "•" appended to endpoint title (screen readers read it) · 280-309 [INT] dead clickable placeholder row · 295 [HC] raw enum `phase.name` in UI · 310-317 [M3] Remove as stray button · 325-329 [INT/MOTION] composition-launched auto-dismiss timer · 377-410 [INT] no field validation feedback · 397-403 [INT] token field shows secret in cleartext · 381-416 [INT] 4 fields in unscrollable dialog (IME clips) · 565 [i18n] `== "RESET"` hardcoded gate · 682-690 [HC] third section-title variant · 709-732 [INT] tap-to-cycle notification row (the fixed live-window bug's sibling) · 744-754 [INT] live-window enabled-state drifts from FGS reality · 763-784 [M3/A11Y] hand-rolled radio dialog, 40dp rows, no roles · 874-904,914,918,924-941 [M3/A11Y/HC] switch rows toggle-only, alpha-0.38 disabled, no roles, "›" glyph chevron.

### CodingPlanQuotaSection.kt — 266-277,508-513 [LOAD] bare spinner/gray text · 244-246 [ERROR] silent hidden failure · 344 [HC] alpha .7 · 466-481 [MOTION/M3] hand-rolled static progress bar · 457 [ERROR] raw wire error, non-actionable · 502-506 [i18n] hand-built plural.
### UsageScreen.kt — 101-121 [M3] manual header · 122-134 [LOAD] static skeleton + redundant "Loading…" · 122,170 [INT] invisible reload on tab switch · 209-237 [M3] chips where SegmentedButton belongs · 326-344 [A11Y/HC] color-only heatmap, alpha ramps, no legend · 380-394 [MOTION] bars render instantly · 431,467 [HC] alpha dividers · 174-196 [LOAD] bare empty state.

### PluginsViewerScreen.kt — 95-102 [LOAD] bare text · 141 [HC] "⚠ code — message" glyph + raw code · 192-206 [M3/INT] fake-disabled AssistChip as badge · 115,110 [INT] stale error during retry · 134-137 [HC] trailing divider · 119-125 [LOAD] bare empty.
### SkillsViewerScreen.kt — 90-97 [LOAD] bare text · 110 [INT] stale-error-on-retry · 124-133 [M3] search w/o clear/ImeAction · 134-141,143-158 [LOAD] bare empty; message buried.
### McpViewerScreen.kt — 137-144 [HC] literal ARGB status colors (dark-mode break) · 90-97,110,114-120 [LOAD/INT] same trio as above · 179-187 [A11Y] hue-only status.

### ZCodeApp.kt — 200-204 [MOTION] no nav transitions · 202 [INT] cold-start flash of Pairing from live-flow startDestination · 166-168 [INT] duplicate Tasks stack on deep-link fallback · 177-190 [M3] deep-link failures as Toast · 374-378 [INT] ViewModel in remember (state loss) · 427-469 [M3/HC] triple hand-rolled headers, duplicated titles, hardcoded "Back" cD · 94-95 [CODE] remember vs rememberSaveable · 140-196 [INT] silent deep-link hold.

### ConnectionCenterScreen.kt — 67-80 [M3] manual header · 85-89 [LOAD] 14dp spinner as reconnect ornament · 91-96,365-370 [A11Y] color-dot-only states · 130-183 [M3] hand-rolled banner, low-emphasis primary action · 155,224 [LOAD] % as text, no bar · 283-289 [ERROR] dead-end error line · 279-281 [M3] takeover Reconnect as TextButton · 293 vs 302 [HC] inconsistent dividers · 322-324 [i18n] locale format.

### PendingApprovalsWidget.kt — 79 [HC] literal amber ColorProvider · 69,83 [HC] hardcoded sp · 58,91 [INT] no deep-link tap-through; stale after process death (updater guard).
### PairingScreens.kt — 51-57,192-201 [LOAD/INT] uncancellable full-screen spinner, no top bar on progress · 43-64 [MOTION] 15-state instant swap · 90 [A11Y] hardcoded English cD overriding localized text · 259,280 [HC] two different hardcoded versions · 141-158 [M3] no reticle/torch, fixed 360dp preview · 66-70 [M3] no back navigationIcon · 407-425 [M3] unpair full-screen vs dialog elsewhere.
### QrScanner.kt — 62-80 [INT] camera-init failure throws on main thread · 58 [MAGIC/A11Y] fixed band, no guidance semantics.
### FilesScreen.kt — 86 [HC] 8dp gutter · 88-100 [DUP/i18n] double header, "read-only", chip-as-nav · 113-136 [M3] breadcrumbs no roles/ripple, inverted emphasis · 140-145 [LOAD/ERROR] bare error w/o retry; bare loading · 153-177 [A11Y] 42dp rows, no roles · 185-189 [A11Y] null cD chevron · 170-215 [i18n] six hardcoded literals · 207-220 [M3] chips as toolbar; no download progress · 256 [INT] string-keyed dialog · 148-203 [LOAD] blank empty · 60-67 [STATE] rotation loses stack.
### GitScreen.kt — 79-92,115-121 [INT] swallowed errors → false "no changes"; retry partial · 102-103,120,146-192 [i18n/HC] hardcoded headers/Retry/date pattern · 129-136 [HC] ↑↓• glyphs · 255 [HC] uppercase() label · 285-289 [A11Y] 36dp rows · 292-324 [A11Y] null cDs, no expanded state · 326-332 [MOTION] diff snaps · 352-360 [i18n] hardcoded + raw enum in copy · 77 [STATE] rotation refetches all.
### TerminalScreen.kt — 96-100,133,156,177-180 [i18n] hardcoded statuses/counts/ring copy · 104-105 [M3/INT] chips-as-toggles, no selected state · 145-146,149,212 [HC] fixed sp, Color.Transparent, ARGB highlight · 229-234 [BUG] white-on-light ANSI · 150 [A11Y] per-line "terminal line N" noise · 68-70 [BUG] side-effecting remember · 63-67 [STATE] rotation drops pause/search · 108-113 [A11Y] bare switch · 202 [BUG] possible empty-label button · 136-163 [MOTION] search bar pops.

**Count:** ~170 findings. Verified spot-checks: automation corruption (AutomationsSection:269-273),
flush approvals (ApprovalsScreen:197-198), MCP literal colors (McpViewerScreen:139-141).
