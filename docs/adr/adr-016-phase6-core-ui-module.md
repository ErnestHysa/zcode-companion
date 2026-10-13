# ADR-016: `core:ui` Module — Shared Compose Theme and Presentational Components

- **Status:** Accepted
- **Date:** 2026-09-28 (Phase 6)
- **Deciders:** implementation agent (ErnestHysa session), per §41.2/§41.8 process

## Context

§40.1 places theme wiring in `:app` ("MainActivity, navigation host, AppContainer, theme wiring"). Phase 6 introduced the designed M3 token system (ui-ux package: brand indigo/slate palette, compact type scale, status rails, attention strips). Feature modules (`features:tasks`, `features:conversation`) must render the SAME designed tokens, and the module graph forbids `features → :app`.

Without a shared home the token set would be duplicated per feature module (drift risk across the exact colors the design system fixes — status semantics per color-system.md rule 2).

## Decision

Create **`:core:ui`** (Android library, Compose):

- `theme/`: `ZCodeTheme` composable, `ZCodeColors` (extended semantic tokens: status/timeline/code chrome), designed light/dark `ColorScheme` pairs, compact `Typography`.
- `components/`: shared presentational components (ConnectionStatusLine, AttentionStrip, SkeletonBar, task-status label/tone/rail mapping).

Dependencies: `implementation :core:common`, `api :domain` (UI-facing pure types only: `ConnectionPhase`, `TaskStatus` — the UI mirror of the §14 machines created in Phase 6), Compose UI/M3/icons. **No transport, no protocol, no reducers, no repositories** — INV-2/INV-6 hold (pure presentation).

Graph addition (extends §40.2): `features/* → core:ui → {core:common, domain}`; `app → core:ui`.

## Alternatives rejected

- **Theme stays in `:app`** — features cannot see it; forces per-feature duplication of the semantic palette (rejected: single-source-of-truth).
- **Tokens in `core:common`** — would add Compose/Android UI dependencies to a pure-JVM module, breaking its character (result types, AppError, logging sink) and JVM-testability.
- **Per-feature duplication** — three copies of the designed pairs; guaranteed drift.

## Consequences

- One source of truth for the design system; the app-level `ZCodeTheme` is a thin re-export shim for import-path stability.
- New module ⇒ this ADR + the dated §40.1 note (below, per §41.8).
- Dark/light are the DESIGNED pairs from ui-ux/color-system.md; dynamic color is NOT applied (status tokens stay fixed) — revisit at Phase 11 behind an ADR.

## References

- §18.5 (design system), §40.1/§40.2 (tree + graph), ui-ux package (color/typography/component library), INV-2/INV-6.
