# ADR-022: Terminal input from the phone — armed, double-opted-in, credential-confirmed (A3.18)

| | |
|---|---|
| **Status** | Accepted — 2026-10-20 |
| **Phase** | gaps-and-polish Phase 5D (A3.18 — terminal input; the phone is view-only by gate today) |
| **Deciders** | Ernest (ruling path: the Phase-5 instruction, as ADR-020/021) + implementation session |
| **Governs** | `features/terminal/port/TerminalPort.kt` (INV-9 typed surface), `data/session/TerminalController.kt`, `core/transport` `V4Plane` terminal methods, `architectural.md` §13.3 row `terminal`, §21, §26 row T23 |
| **Supersedes** | TerminalPort's "There is NO write path anywhere in this port" (narrowed to "no write path outside the ADR-022 armed composer") |

## Context

A3 Tier 3 row 18: terminal input is "view-only by gate; same ADR family" as
git mutations. The atlas classifies `terminal` as **Critical (shell access)**,
"R (view-only first) … Disabled; never enabled silently" (§13.3), with three
existing gates: hello `localTerminal === true`, the Settings opt-in (default
OFF), and the attach-authorization probe. J7's read-side copy-out landed in
Phase 2F. The question this ADR settles is whether — and under what gates —
the phone may WRITE to the desktop shell.

## What the shipped desktop exposes (bundle-extracted, 3.14.5.7961)

`createTerminalService` (host/index.js ≈250695) serves `terminal` with
`create`, `write({id, data})`, `resize({id, cols, rows})`, `dispose`,
`onDynamicData`, `onDynamicExit`. There is **no desktop-side input gate**: the
channel is exposed unfiltered to `web-remote-replayable` connections (the
same `exposeOnChannelServer` finding as ADR-021). Whatever the phone writes
reaches the pty. The app's own typed surface is therefore the ONLY gate —
which is exactly why INV-9 exists.

## Decision

**Enable input behind four layered gates, none of which can be skipped, and
make the write word reachable only through the armed composer.**

1. **Capability gate (existing):** hello `localTerminal === true` — otherwise
   the feature is hidden (`CapabilityOff`).
2. **View opt-in (existing):** Settings → Terminal → "Show the desktop
   terminal" (default OFF).
3. **Input opt-in (NEW, separate, default OFF):** Settings → Terminal →
   "Allow typing into the terminal from this phone". Flipping it ON requires a
   **device-credential confirmation** (`BiometricPrompt` with
   `DEVICE_CREDENTIAL` fallback — the §12.7 unpair-flow precedent; injected
   `Authenticator` seam so the gate is unit-pinned). A failed/cancelled prompt
   leaves the setting OFF. Turning it OFF never prompts.
4. **Per-attach arming (NEW):** even with (3) ON, each attached session
   starts **disarmed**. The composer is hidden until the user taps "Arm
   input" on the terminal screen; an **"Input armed"** banner stays visible
   while armed; the session **disarms automatically** on detach, on the
   `Exited` state, when the app leaves the foreground, and after 10 minutes
   without a keystroke. `resize` rides the same arm (it is cosmetic, but it
   is still a write).

The composer itself: a plain single-line field with autocorrect/suggestions
OFF (`KeyboardType.Ascii`, no IME personalization), Enter sends `\r`, a
dedicated **Ctrl-C** button sends `\u0003` (the one control sequence a phone
user needs), and every send is `terminal.write({id, data})` on the attached
id — nothing else. No command history, no macros, no paste-and-run.

**Never ported:** executing anything outside the attached pty, `create` with a
custom `cwd` from the phone (the existing attach keeps the desktop's cwd),
multi-session fan-out.

## §13.3 row delta

| Row | Before | After |
|---|---|---|
| `terminal` | P2 · R (view-only first) · Critical · Disabled; never enabled silently | **R + ADR-022-gated W** (`write`/`resize` only) · Critical (unchanged) · Disabled; never enabled silently — **input additionally needs the second opt-in + device credential + per-session arm** |

INV-9 wording: "no write ops to desktop filesystem/shell … EXCEPT the
ADR-022 armed terminal composer" — pinned by `TerminalPortWriteSurfaceTest`
(exactly `write`/`resize` exist on the port; both throw `IllegalStateException`
when the arm is not held).

## §26 threat-model delta

| # | Threat | Asset | Attacker | Attack | Impact | Likelihood | Mitigation | Residual |
|---|---|---|---|---|---|---|---|---|
| T23 | Shell command injected from the phone without the user's intent | the desktop shell (arbitrary code execution as the desktop user) | (a) lost/unlocked phone; (b) UI-driving malware (T3); (c) shoulder-surf/overlay | type into an armed terminal; re-arm silently | **Critical** | Low (four gates; arm is per-session and auto-expires) | capability gate + view opt-in + **credential-confirmed input opt-in** + **per-attach arm with visible banner and auto-disarm** (detach/exit/background/10 min idle); write reachable only via the armed composer (pinned: zero `write` calls while disarmed); no stored history | a device unlocked in the attacker's hands within the arm window — the same residual as any unlocked-phone remote-control surface (disclosed in Settings copy) |

## Consequences

- `TerminalPort` grows `inputOptIn: StateFlow<Boolean>`, `setInputOptIn(enabled,
  authenticator)`, `armed: StateFlow<Boolean>`, `arm()`, `disarm()`,
  `write(data)`, `resize(cols, rows)`; `TerminalState.Live` gains no shape
  change (arming is orthogonal to attach).
- `V4Plane` grows `terminalWrite(id, data)` / `terminalResize(id, cols, rows)`;
  the Phase-9 "view-only — INV-9" comment cites this ADR.
- The mock terminal gains a `write` handler that echoes the bytes back on
  `onDynamicData` (so the rig proves the round-trip) and a `resize` ack.
- Drift duty: `write`/`resize` join `consumed-surfaces.json#facade`.

## References

- Shipped bundle 3.14.5.7961 `out/host/index.js` `createTerminalService` ≈250695.
- Review: A3 Tier 3 row 18; J7 (copy-out, Phase 2F); architectural.md §21, §13.3,
  §12.7 (biometric precedent).
- ADR-020/021 (gate-pinning pattern).
