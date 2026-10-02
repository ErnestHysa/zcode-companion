# ADR-017 — Phase 8 Background Execution: Live-connection FGS service type, WorkManager adoption, and module/dependency additions

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-02 |
| **Phase** | Phase 8 — Notifications & Background (§43.2-8.3/8.4) |
| **Context invariants** | §25 (Background Execution), §24.5, §29, §10.1 stack, §40.2 module graph |
| **Supersedes** | nothing (the §43.2-8.3 "ADR-013" reference predates ADR-013/014 being consumed by Phases 3/4 — this is the next free number; `[CORRECTED 2026-10-02]`) |

## Decision

1. **The Live-connection foreground service SHIPS as type `specialUse` (API 34+)
   behind user opt-in**, typeless below API 34, per the §25.2 validation chain:
   - `dataSync` remains EXCLUDED (runtime budget → guaranteed kill, §25.1).
   - **`remoteMessaging` evaluated and confirmed NOT APPLICABLE** — the type is
     reserved in practice for system-default messaging/RCS handlers; this app is
     not (and will not become) a system-default messaging handler. §25.1's
     "almost certainly not applicable" is now a recorded evaluation, not an
     assumption.
   - Platform validation executed on the available device class (ATD API 30
     emulator): starts only from the foreground (instrumented Settings-leg +
     spike driver), posts the required low-priority notification, holds the
     process at `fg F/S/FGS` through **forced deep Doze** (service survives;
     network suspension is the OS's §25.1 contract and every reconnect path is
     resync-correct), auto-timeout 30 min enforced, stop is clean
     (`docs/evidence/phase-8/`).
   - Play-policy validation is **deferred with the Play track itself** — the MVP
     distribution is GitHub-Releases/Obtainium (§37), which is not Play-bound.
     Re-evaluation triggers: any Play-track release.
2. **The aggressive-OEM-ROM leg of §25.2 was NOT executed** (no physical/OEM
   hardware in this environment; Q12 stays open). Consequence shipped: the
   conservative defaults — opt-in only, foreground-only start, non-sticky,
   30-min auto-timeout, reconnect-on-wake as the primary path. The service is a
   PRIORITY MECHANISM, not an availability promise (§25.2's normative frame).
3. **WorkManager (`androidx.work:work-runtime-ktx` 2.10.0) adopted** for the
   §25.3 auto-reconnect probe: one unique periodic work ("zcode-reconnect-probe"),
   ≥15 min, `NETWORK_CONNECTED`-constrained, quota-compliant by construction
   (asserted in `ReconnectProbeWorkerTest`), opt-in per preferences
   (`autoReconnectProbe`, additive proto field 5). If the app process is dead,
   the worker no-ops — cold start owns recovery (§15-S2). §29's no-polling rule
   holds: this is the app's ONLY periodic work.
4. **`androidx.core:core-ktx` 1.15.0 adopted** (NotificationCompat surface for
   the new `core:notifications` module).
5. **New module `core:notifications`** (Android library) per the §10.3 module
   map and §40.1 tree: NotificationChannels (§24.2), NotificationPlan +
   NotificationBroker (§24.1 sole decision point, §24.3 dedupe/collapse, §16
   records), AndroidNotificationSink (tray + §24.7 deep-link PendingIntents +
   per-endpoint group summaries), LiveConnectionService (§24.5/§25.2),
   ReconnectProbeWorker (§25.3). Dependencies: `core:common`, `domain`, coroutines,
   androidx-core, work-runtime.
6. **Dependency-graph additions** (both pre-drawn by the architecture):
   - `data → core:notifications` — the §9.2 `REPO --> NOTIFY` edge: the broker's
     §16 NotificationRecord store port is implemented in `data`
     (DataStore-proto, additive schema, `notification_records.proto`).
   - `app → core:notifications` — composition-root wiring (§9.2 `REPO/FEATS →
     NOTIFY` shape; features still never see it — INV-2/6 unchanged: no
     `features → core:notifications` edge exists, notifications reach UI only as
     tray effects).
   - MainActivity becomes the FGS notification's tap target via a
     composition-root-registered class reference (`core:notifications` keeps no
     app types).

## Consequences & notes

- The service holds the PROCESS at foreground priority; the app's socket +
  heartbeat machinery live on process-lifetime threads/scopes (as since Phase 2).
  A service-owned connection remains a Phase 10+ option — no architecture change.
- Battery numbers remain engineering targets (§29); measured evidence and the
  physical-device gap are recorded in `docs/battery-phase8.md`.
- The broker owns ALL notification decisions; UI components never create or
  cancel notifications (§24.1) — enforced by construction (notification APIs
  exist only inside `core:notifications`).
