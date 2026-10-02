# Phase 8 Battery Report — §29 Method, Measured vs Target

| | |
|---|---|
| **Phase** | Phase 8 — Notifications & Background (§43.2-8.5) |
| **Date** | 2026-10-02 |
| **Verifier** | `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness) |
| **Build** | `0.8.0-phase8 (vc27)`, debug APK, ATD emulator API 30 (arm64) |

## 1. The §29 measurement method (normative protocol)

Every battery claim is measured as `batterystats`/Battery Historian on the named
§35.6 devices (one modern flagship + one mid-range + one older supported device),
fixed scenario, fixed network condition, reported as **target vs measured with the
device class**. The §28.1 budget rows that bind this phase:

| Metric | Target (§28.1) | Class |
|---|---|---|
| Battery, foreground streaming 30 min | ≤ 6% on the referenced mid-range 2023 device | engineering target |
| Battery, 8 h background with FGS on vs off | §29 measurement row (no numeric budget; relative delta reported) | engineering target |

Design-level battery rules already enforced by construction (§29): **no polling**
(the WorkManager probe in 8.4 is the app's ONLY periodic work — ≥15 min,
network-constrained, opt-in), event-driven notifications, render coalescing, a
single socket per endpoint, debounced DataStore writes, and the Live-connection
FGS is **user opt-in with a 30-minute auto-timeout** (§24.5 `[PROPOSED]` default,
implemented).

## 2. What was measured (emulator-class, ATD API 30)

The ATD emulator cannot model the §28.1 reference-device battery, and its
`batterystats` has **no per-UID accounting** (`Total cpu time reads: 0` in both
collection windows — see `docs/evidence/phase-8/batterystats-fgs-on.txt` /
`-off.txt`). The emulator legs below are therefore **mechanism evidence, not
battery numbers**.

| Leg | Command / scenario | Result | Evidence |
|---|---|---|---|
| FGS process priority | `holdFgsForSpikeWindow` driver: FGS started, `dumpsys activity processes` at t+30 s | process held at `Proc # 2: fg F/S/FGS` for the whole window — the OS will NOT freeze/kill it as cached state (the §24.5 mechanism: the FGS holds the process so the app's own socket + heartbeats keep running screen-off) | `fgs-spike-service-dump.txt` |
| FGS service record | `dumpsys activity services` | `ServiceRecord{...LiveConnectionService}` alive, low-priority notification channel `live-connection` (IMPORTANCE_LOW, no badge) | `fgs-spike-service-dump.txt` |
| Deep-Doze survival | `dumpsys deviceidle enable all` + `force-idle` with the FGS running, 20 s in IDLE | process **still `fg F/S/FGS`** in deep idle — the service survives; network is suspended by the OS per §25.1 (documented, not fought: every reconnect path is resync-correct, §15) | `fgs-spike-doze.txt` |
| FGS-off comparison | same window, no FGS | service absent; process eligible for cached-freeze (mechanism contrast) | `batterystats-fgs-off.txt` |
| Per-UID battery delta (FGS on vs off) | `dumpsys batterystats` after each 3-min window | **NOT MEASURABLE on this emulator** — `Total cpu time reads: 0`; no UID rows emitted | both `batterystats-*.txt` files |

A real (minor) product defect was found and fixed during this leg: the FGS
notification's tap `PendingIntent` crashed the service start when no launcher
activity class had been registered yet (service-first start). Fixed with a
null-guard; the required FGS notification posts regardless.

## 3. What was NOT measured (environmental limitation — honest status)

| §29 requirement | Status |
|---|---|
| Foreground streaming 30 min → ≤ 6% on the referenced mid-range device | **NOT EXECUTED** — no physical device is attached to this environment (only the ATD emulator). Reported as not verified; never claim it as passing. |
| 8 h background FGS-on vs FGS-off delta | **NOT EXECUTED** — same limitation, plus the ATD provides no per-UID accounting at any window length. |
| OEM ROM matrix (§35.6: aggressive OEM kills, §25.2 Q12) | **NOT EXECUTED** — no OEM hardware. Q12 stays open; the design already ships the documented fallback (shorter auto-timeout knob, `LiveConnectionService.autoTimeoutMs`; reconnect-on-wake unaffected). |

## 4. Conservative posture shipped regardless of measurement

Because the physical matrix could not run, Phase 8 ships the CONSERVATIVE end of
every battery-affecting choice (R12 containment):

1. FGS is **opt-in only, started from the foreground only**, never sticky — after
   process death it stays off and the app falls back to reconnect-on-wake (§25.3).
2. **Auto-timeout 30 min** (default; adjustable constant) — bounded worst-case
   exposure independent of measurement.
3. The §24.4 honest copy ships in Settings ("Notifications are delivered while
   the app is in use or when the Live connection is enabled…") — the product
   never promises background attention it cannot measure.
4. No polling anywhere; the periodic probe is ≥15 min, network-constrained,
   opt-in (quota-compliant by construction — asserted in `ReconnectProbeWorkerTest`).

## 5. Verdict

**Within §43.2-8.5's acceptance ("within §28.1 targets or documented
deviation")**: the deviation is DOCUMENTED — physical-device measurement is
pending hardware access and is a release-adjacent ritual (the §36/§43-1.4
pattern: manual, never CI). The mechanism evidence (FGS priority hold, deep-Doze
survival, bounded exposure) supports the design; the numeric §28.1 battery
targets remain engineering targets awaiting the named-device sweep pre-release.
