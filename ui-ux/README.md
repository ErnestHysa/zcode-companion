> **ARCHIVED 2026-10-21 (M1.1, gaps-and-polish Phase 6D) — HISTORICAL, not the source of truth.**
> The "folder structure" this README promises (18 docs + 15 mockups) NEVER existed in this
> repository (verified against the full git history — `ui-ux/` has exactly one touching
> commit, which added only this README). The design authority is now IN CODE and ENFORCED:
> `core/ui/src/main/kotlin/com/zcode/android/core/ui/theme/` (the ZCodeRadius 5-step scale,
> the surface roles, the ZCodeElevation tokens) plus the shape/i18n/a11y gate tests
> (ThemeShapesTest, I18nLiteralGateTest, AccessibilityAuditTest). The conformance residue
> that did hold up is recorded in the audit report's Part M1; the one claim with zero code
> ("small Z mark on pairing/connection") is resolved by `ZMark` (M1.2, in code). Do not
> cite this README as a spec.

# ZCode Native Android — UI/UX Design Package

**Version:** 1.0.0  
**Date:** 2026-09-28  
**Scope:** Visual design, interaction design, and Phase 6 screen specifications for the ZCode Native Android client.

---

## What this package is

This package is the **visual and interaction source of truth** for the ZCode Native Android application — a native Android companion/control client for ZCode (Z.ai’s agentic coding harness).

It is **not**:

- A redesign of ZCode Desktop
- A mobile port of the desktop UI
- A WebView or browser shell
- A full IDE or local agent runtime
- An implementation package (no production Kotlin/Compose code)

It **is**:

- A complete design system (color, type, space, icons, components, motion)
- Phase 6 primary UI specifications and mockups
- Clearly labeled future-phase visual direction
- Implementation guidance for coding agents

---

## Product relationship

```
Android Phone
    ↓
Native Android UI (this package)
    ↓
Domain / data / transport layers
    ↓
ZCode remote protocol
    ↓
ZCode Desktop (authoritative runtime)
    ↓
Agent / workspace / tools
```

**Desktop remains authoritative.** The phone is a presentation and control surface: observe tasks, stream conversation, later approve permissions and send prompts. State ownership, protocol, recovery, and security are defined in `architectural.md` — not here.

---

## Architecture relationship

| Document | Domain | Wins when conflict |
|----------|--------|--------------------|
| `architectural.md` | Protocol, transport, state ownership, phases, security, recovery, capabilities | Technical capability & scope |
| This UI/UX package | Visual design, interaction, navigation, components, states presentation | Visual hierarchy & interaction patterns |

**Rule for implementers:** If a mockup implies a capability that architecture does not support in the current phase, **architecture wins**. Document the discrepancy; do not implement unsupported behavior.

Place this package beside the project architecture (e.g. `ui-ux/` next to `architectural.md`). Paths in this package are relative to `ui-ux/` itself so the ZIP remains portable.

---

## Phase 6 vs later phases

| Scope | Content |
|-------|---------|
| **Phase 6 (primary)** | Pairing/connection presentation, task list, task detail, conversation read/stream, tool activity inspection, connection/recovery/error/empty states, basic settings |
| **Phase 7+ (documented, not MVP)** | Send prompt, permission approve/deny, notifications/FGS, terminal, diffs, files, git, model/mode, multi-endpoint, polish |

Do **not** implement Phase 7+ UI as if it were Phase 6. Future direction lives in `future-phase-ui.md` and is marked **FUTURE / NOT PHASE 6**.

---

## Folder structure

```
ui-ux/
├── README.md                      ← you are here
├── ui-ux.md                       ← package overview & product framing
├── design-principles.md
├── design-system.md
├── color-system.md
├── typography.md
├── spacing-layout.md
├── iconography.md
├── navigation.md
├── interaction-design.md
├── motion.md
├── accessibility.md
├── responsive-design.md
├── component-library.md
├── screen-specifications.md
├── state-specifications.md
├── phase-6-ui.md
├── future-phase-ui.md
├── design-decisions.md
├── implementation-guidance.md
└── mockups/
    ├── 01-pairing.png
    ├── 02-connecting.png
    ├── 03-task-list.png
    ├── 04-task-list-empty.png
    ├── 05-task-detail.png
    ├── 06-conversation.png
    ├── 07-streaming-response.png
    ├── 08-tool-activity.png
    ├── 09-attention-required.png
    ├── 10-reconnecting.png
    ├── 11-connection-error.png
    ├── 12-conversation-error.png
    ├── 13-settings.png
    ├── 14-dark-task-list.png
    └── 15-dark-conversation.png
```

---

## How to use the mockups

1. Open `phase-6-ui.md` for the Phase 6 experience map.
2. Open `screen-specifications.md` for per-screen purpose, layout, states, and embedded mockups.
3. Open `state-specifications.md` for the visual state matrix (connection, task, conversation).
4. Open `component-library.md` before building reusable Compose components.
5. Treat mockups as **visual references**, not pixel-perfect contracts. Match hierarchy, density, status treatment, and interaction intent.
6. Prefer the design tokens in `color-system.md`, `typography.md`, and `spacing-layout.md` over hardcoding screenshot values.

Image references use **relative paths** from the Markdown file location, e.g.:

```markdown
![Task List](./mockups/03-task-list.png)
```

---

## Design system overview (v2 visual redesign)

- **Language:** Premium native Android + **agent timeline** grammar (not ChatGPT, not GitHub-dashboard clone).
- **Themes:** **Light is primary in mockups 01–13**; **14–15 are explicit dark**. Both are designed pairs.
- **Density:** Compact task rows with status rails; controlled title sizes; less empty vertical waste.
- **Status:** Structural (left rails) + label + color — never color alone.
- **Connection:** Dot + label under app bar; strip only when recovering/error.
- **Conversation:** YOU / AGENT overlines, tool blocks with rails, code blocks — timeline of agent work.
- **Brand:** Indigo accent on cool slate neutrals; small **Z** mark on pairing/connection.

---

## Implementation-agent instructions (summary)

1. Read `architectural.md` first (technical source of truth).
2. Read this package before implementing UI.
3. Implement only Phase 6 surfaces unless authorized for a later phase.
4. Do not invent protocol, transport, or backend behavior.
5. Do not move future-phase features into Phase 6 for visual completeness.
6. Preserve accessibility, touch targets, and Android-native patterns (predictive back, edge-to-edge, system bars).
7. Prefer reusable components from `component-library.md`.
8. If mockup conflicts with architecture on capability/scope → architecture wins; document the gap.

Full rules: `implementation-guidance.md`.

---

## Important constraints

- No claims of E2E encryption, zero-knowledge, or relay blindness unless architecture establishes them.
- Pairing is security-sensitive; never surface credentials, hashes, or proofs in normal UI.
- Synthetic content only in mockups (no real secrets, paths, or personal data).
- Long content (code, tool output, histories) must remain usable — virtualize, paginate, truncate with expand where appropriate.
- Recovery UX must not imply data loss the architecture does not indicate.

---

## Quality bar

A senior Android/UX engineer should be able to hand this package to an implementation team and know:

- What Phase 6 looks and behaves like  
- How screens connect and recover  
- What is deferred  
- How tokens and components map to Compose  

---

*End of README. Companion to architectural.md (technical). This package: visual & interaction only.*
