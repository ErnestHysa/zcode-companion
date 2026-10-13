# ADR-015: Streaming Markdown Renderer — Bounded Custom Implementation (compose-richtext declined)

- **Status:** Accepted
- **Date:** 2026-09-28 (Phase 6)
- **Deciders:** implementation agent (ErnestHysa session), per §41.2 process
- **Supersedes:** none; **refines** the §44 dependency-table entry for `compose-richtext 2.x`

## Context

§44 (Dependencies) planned `compose-richtext 2.x (+codehighlighting)` as the streaming-markdown renderer, with the single-maintainer risk (R15) already noted. §19.2 makes the partial-markdown "stabilized prefix" strategy explicitly OUR component regardless of the renderer chosen.

Pre-implementation verification (2026-09-28, Maven Central) contradicted the §44 entry:

| Claimed (§44) | Measured reality |
|---|---|
| `compose-richtext 2.x`, "active 2.x" | No 2.x exists under `com.halilibo.compose-richtext`. Latest stable: **0.17.0** (2023-era Compose). Latest 1.0.0-**alpha05** (June 2026) — alpha-only. |

Per the implementation rules ("when this document and reality disagree, reality wins — fix the document with evidence"), the dependency decision was re-evaluated:

1. **R15 (single-maintainer bus factor)** is worse than §44 recorded: the stable line is 2+ years stale and the modern line is alpha.
2. **Phase 6's surface needs are bounded**: the conversation timeline is mostly FIRST-CLASS components (YOU blocks, AGENT prose, tool cards, code blocks — custom Composables anyway); markdown proper applies to assistant PROSE only, and the streaming case requires incomplete-construct handling (unclosed fences → literal) that would be custom code on top of any library.
3. The Phase 11 escape hatch remains: if full CommonMark fidelity is ever required, a renderer can be swapped behind `MarkdownText` at that time.

## Decision

Implement a small, bounded, pure-Kotlin markdown subset in `features/conversation/markdown/`:

- **Blocks**: paragraphs, ATX headings (1–3), fenced code (``` + optional lang, incomplete fence → literal pending-code block), bullets, ordered lists, blockquotes.
- **Inline**: `code`, **bold**, *italic*, `[text](https://…)` — links render styled, never auto-open (§26.2-T15).
- **Totality (INV-8/T15)**: the parser never throws on any input (fuzz-tested, incl. 100 KB inputs); unterminated inline markers render literally (no layout jumps).
- **Bounded scope (INV-5 proportionality)**: no tables, no nested lists, no HTML — revisitable at Phase 11 behind this ADR.

## Consequences

- No third-party UI-renderer dependency in the conversation module; no vendored fork to maintain (R15 closed for this surface).
- `MarkdownParser` is pure JVM and unit-tested (block/inline/partial/hostile corpora).
- §44's `compose-richtext` row is OBSOLETE for Phase 6; the dependency-guard ledger should not expect it.
- If Phase 11 needs full CommonMark (tables/deep nesting), revisit: swap behind `MarkdownText` + new ADR.

## References

- §19.2 (render pipeline; partial-markdown ownership), §44 (dependency table), R15, §26.2-T15.
- Implementation: `features/conversation/src/main/kotlin/.../markdown/MarkdownParser.kt`; tests: `ConversationPipelineTest`.
