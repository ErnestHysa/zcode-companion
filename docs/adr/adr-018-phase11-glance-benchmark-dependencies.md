# ADR-018 — Phase 11 dependency additions: Glance (widget) + androidx.benchmark (Macrobenchmark gate)

| | |
|---|---|
| **Status** | Accepted (Phase 11, 2026-10-03) |
| **Context** | §8.2 mandates an ADR for any new third-party dependency; §44 records both candidates but their adoption lands here |
| **Decides** | `androidx.glance:glance-appwidget:1.2.0` in `:app`; `androidx.benchmark:benchmark-macro-junit4:1.5.0` + the `com.android.test` module in `:baselineprofile` |

## Context

Phase 11 (§43.2-11) has two subphases that require new artifacts:

- **11.4 Glance widget** — "pending-approvals; reads broker state only". §44
  already evaluated Glance ("Glance (Phase 3, widget), 1.2.x, Apache-2.0,
  Google, small size impact, alternative = RemoteViews handroll"); the
  RemoteViews handroll was rejected there (glance = declarative Compose-style
  API over RemoteViews, maintained by Google/AndroidX).
- **11.1 Performance hardening** — "Macrobenchmark gates; budgets or tickets".
  §28.2 names "Macrobenchmark gates in CI (§36) for cold start + streaming
  scroll". No JVM-only substitute can measure cold start or frame timings; the
  macrobenchmark library is the platform-blessed mechanism.

## Decision

1. **Glance 1.2.0** (stable line, `glance-appwidget` only; `glance-material3`
   NOT added — the widget uses plain Glance theme tokens, keeping the surface
   minimal). Placed in `:app` (the widget receiver is an app entry point; no
   `core/*` module grows a UI dependency — INV-2/6 unaffected: the widget reads
   only the NotificationBroker's record flow).
2. **androidx.benchmark 1.5.0** (`benchmark-macro-junit4`) in the new
   `:baselineprofile` module (`com.android.test`, self-instrumenting). The
   module is TEST TOOLING: it never ships in an APK and depends on
   `tools/mockzcode` (INV-15) for its loopback server. The `androidx.benchmark`
   GRADLE plugin is deliberately not applied (the module is configured
   manually); baseline-profile GENERATION stays a Phase-12 item (§43.2-12.1).
3. **App `benchmark` build type** added (non-debuggable, debug-signed) as the
   profiling-faithful measurement target, plus a benchmark-only debug seeding
   hook (`BuildConfig.BENCHMARK_SEEDING` is `false` in every other build type;
   release is unaffected — the flag is a compile-time constant there).

## Consequences

- Both libraries are AndroidX/AOSP (Apache-2.0) — §38 license posture holds.
- APK size: Glance adds ~100–200 KB to download size (§28.1's 12 MiB budget
  re-measured in the phase-11 perf report).
- The benchmark module grows the build graph but is not on the app's release
  path; CI runs it only in the scheduled macrobenchmark workflow (§36).

## Alternatives considered

- RemoteViews widget by hand — rejected per §44 (more code, no Compose
  coherence, testing burden).
- JVM timing tests instead of Macrobenchmark — rejected: cannot measure real
  cold start (no process death / zygote) or real frame deadlines (no renderer).
- Compose Glance alternative "widget glances" third-party libs — rejected
  (platform surface preferred, §2.3).
