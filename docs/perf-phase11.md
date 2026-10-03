# Phase 11 performance report — §28.1 budgets vs measured

| | |
|---|---|
| **Date** | 2026-10-03 |
| **Environment** | ATD API-30 EMULATOR (x86_64, this Mac) — **no physical reference device exists in this environment** |
| **Evidence** | `docs/evidence/phase-11/benchmark-*.txt/xml` (Macrobenchmark 1.5.0, `benchmark` build type, MockZCode loopback seeding per ADR-018) |

> **Reading these numbers.** §28.1 targets are engineering targets for a
> **mid-range 2023 device**. An ATD emulator is a different machine class; the
> numbers below are EMULATOR-CLASS regression gates (the library's EMULATOR
> error is suppressed with that exact acknowledgment, ADR-018). Every
> reference-device cell is a **TICKET**, not a pass — they are honestly listed
> at the bottom.

## Measured (2026-10-03, 5 startup iterations / 3 scroll iterations)

| §28.1 budget (reference device) | Target | Measured (emulator class) | Verdict |
|---|---|---|---|
| Cold start → task list (warm cache), P50 | ≤ 1.2 s | **1,070.7 ms median** (min 920.7) | under target on emulator; ticket T-P11-1 (device leg) |
| Cold start → task list, P95 | ≤ 2.0 s | **≈ 1,694.6 ms max-of-5** | under target on emulator; same ticket |
| Stream update → pixels / scroll frames | ≤ 16.7 ms P95 | **frameDurationCpuMs P50 2.9 / P95 4.1 / P99 5.3 ms** (400-row conversation, continuous fling, ~298 frames/iter) | order of magnitude under budget on emulator; ticket T-P11-2 (device leg + 3-stream pacing) |
| Cold start → interactive (baseline profile) | ≤ 800 ms P50 | not measured | ticket T-P11-3 (Phase 12.1 generates the profiles; measuring before they exist is meaningless) |
| Conversation first render (tail snapshot) | ≤ 700 ms P95 after connect | covered transitively by the cold-start leg on the same path (connect + tail render inside the measured window); no isolated cell | folded into T-P11-1 |
| Large diff (5k lines) open | ≤ 1 s P95 | **27 ms measured in Phase 9** (regression test retained in `core:ui` suite) — re-run green in this phase's full suite | PASS (JVM-render measurement; carried) |
| Reconnect → recovered | ≤ 3 s P95 | §15 storm matrix asserts correctness, not wall-clock; no isolated wall-clock cell on this hardware | ticket T-P11-4 |
| Max retained conversation memory / app total | ≤ 24 MiB strings; ≤ 220 MiB | not measured (no heap-profiling harness on this environment) | ticket T-P11-5 |
| APK size | ≤ 12 MiB download | debug APK is not the release artifact (§36 release pipeline = Phase 12); measured debug artifact ≈ see evidence below | ticket T-P11-6 (R8 release APK at 12.1) |
| Battery (§29 method) | ≤ 6% / 30 min foreground streaming | requires physical devices (Phase 8 already documented the not-executed legs) | stays with the Phase-8 physical-fleet ticket |

## Technique status (§28.2)

| Technique | Status |
|---|---|
| Macrobenchmark gates in CI (cold start + streaming scroll) | **landed** — `.github/workflows/macrobenchmark.yml` (scheduled nightly + manual, §36 cadence), module `:baselineprofile` |
| Stable keys/contentType in LazyColumn | in place since Phase 6 (row ids + kind content types) |
| Coalesced streaming publication (≤ 30 Hz) | in place since Phase 6 (`StreamCoalescer`) |
| rowsRange windowing (≤ 200) | protocol-mandated since Phase 2 |
| Baseline Profiles | **Phase 12.1** (§43.2-12.1 explicitly owns them) |
| R8 full | Phase 12.1 release pipeline |

## Tickets (acceptance criteria attached)

- **T-P11-1** — re-run `StartupBenchmark` on the §35.6 reference device; accept at ≤ 1.2 s P50 / ≤ 2.0 s P95.
- **T-P11-2** — device leg of the scroll gate + the 3-concurrent-live-streams scenario (needs the reference device and a multi-stream mock pacing script); accept ≥ 55 fps sustained.
- **T-P11-3** — cold-start-to-interactive measurement once Phase 12.1 ships baseline profiles (≤ 800 ms P50 target).
- **T-P11-4** — reconnect wall-clock cell on device (≤ 3 s P95 target).
- **T-P11-5** — memory-budget profiling session (heap dump / per-session retained strings) on the mid-range device.
- **T-P11-6** — release APK size gate at the Phase 12 release build (≤ 12 MiB download with ML Kit bundled).

Each ticket is measurement work on hardware this environment does not have;
none of them hides a regression found on the emulator — the gates here exist
and run nightly in CI.
