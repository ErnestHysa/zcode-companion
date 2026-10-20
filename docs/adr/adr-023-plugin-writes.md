# ADR-023: Plugin writes from the phone — enable/disable ported, install/uninstall/update/configure stay desktop-only (A3.19, PROBE-A1)

| | |
|---|---|
| **Status** | Accepted — 2026-10-20 |
| **Phase** | gaps-and-polish Phase 5D (A3.19 — plugin writes; gated on PROBE-A1) |
| **Deciders** | Ernest (ruling path: the Phase-5 instruction, as ADR-020/021/022) + implementation session |
| **Governs** | `core/transport` `V4Plane` plugin methods, `data/session/ViewerTools.kt` plugins viewer, `architectural.md` §13.3 rows `plugins` / `plugin-management`, §26 row T24 |
| **Supersedes** | §13.3 "sync + ALL write methods (install/uninstall/update/enable — §11 T-item) stay desktop-only" — narrowed: `setPluginEnabled` is ported; install/uninstall/update/configure remain desktop-only |

## PROBE-A1 verdict (bundle-extracted, 3.14.5.7961) — ROUTABLE

A1.1's "retired" verdict and the A3.19 finding are BOTH right, about different
objects:

- The `plugins` channel's own `createPluginsService` (host/index.js ≈1056431)
  implements `installPlugin`/`uninstallPlugin`/`setPluginEnabled` as `Cd()`
  **retired stubs** — the desktop-Settings path round 1 saw.
- The **zcode-agent facade** carries the LIVE words (≈344742–346624):
  `installPlugin {workspace, pluginName, marketplace, scope?, dryRun?, operationId?}`,
  `uninstallPlugin {workspace, pluginId?|pluginName?, marketplace?, removeCache?}`,
  `updatePlugin {workspace, pluginId?, marketplace?}`,
  `setPluginEnabled {workspace, pluginId, enabled, operationId?, scope?}`,
  `configurePlugin {workspace, pluginId, options, clearOptionKeys?, scope?, dryRun?}`,
  `cancelPluginOperation {operationId}` — each forwarded to the CLI
  (`plugins/install|uninstall|update|setEnabled|configure`).
- The phone's `zcode-agent` channel is `createZCodeAgentConnectionScope(service,
  {clientMode:"web-remote-replayable"})` — a Proxy whose `get` returns the v4
  overrides when present, else **`Reflect.get` on the full agent service**. No
  denylist, no clientMode check on these words (the only clientMode-gated
  method in the host is the conversation-share stub, PROBE-L6). The words are
  therefore reachable from the phone exactly as `setThoughtLevel` and
  `createScratchWorkspace` already are.

Recorded as **PROBE-A1 RESOLVED (bundle-extracted): routable**. A live
`v4Call("setPluginEnabled")` against the real desktop would mutate the user's
plugin config and is therefore NOT fired by the autonomous session; the mock
leg proves the call shape (rule 9).

## Decision — tiering per the probe verdict

| Tier | Words | Ruling | Why |
|---|---|---|---|
| **1 — ported** | `setPluginEnabled` | Phone Plugins viewer gains an enable **Switch** per installed plugin; one tap, busy state until the ack, viewer refresh from the wire; failure copy inline. `scope` omitted (the host applies the plugin's own scope); no `operationId` (the call is synchronous for enable/disable). | Toggles an ALREADY-installed, user-chosen plugin; fully reversible by the sibling value; no code enters the desktop. "Enable low-risk" per the finding. |
| **2 — NOT ported (desktop-only, this ADR)** | `installPlugin`, `updatePlugin`, `configurePlugin` | Remain desktop-only. Recorded in §13.3 as "Deferred — needs a marketplace-provenance ADR" | **Install is a supply-chain trust surface**: it pulls executable plugin code from a marketplace onto the desktop. The phone cannot render marketplace provenance, signatures, or the dry-run diff the desktop UI shows; porting it without that display would be a confirm-blind write (the Phase-4 approvals lesson, L3). `update` re-fetches code (same class); `configure` writes arbitrary option maps the phone has no schema for. |
| **2 — NOT ported** | `uninstallPlugin` | Desktop-only | Destroys plugin config (`removeCache`) irreversibly; no phone-side undo. |
| — | `cancelPluginOperation` | Not needed | Only meaningful for the long-running Tier-2 operations. |

## §13.3 row delta

| Row | Before | After |
|---|---|---|
| `plugins` / `plugin-management` / `plugin-sync` | Partially (read-only viewer) · R · Medium | **R + ADR-023-gated W (`setPluginEnabled` ONLY)** · Medium · install/update/configure/uninstall: **Deferred (desktop-only; future ADR with provenance display)** |

## §26 threat-model delta

| # | Threat | Asset | Attacker | Attack | Impact | Likelihood | Mitigation | Residual |
|---|---|---|---|---|---|---|---|---|
| T24 | A plugin is enabled/disabled without intent | plugin runtime state (a disabled security plugin re-enabled, or a needed one disabled) | accidental tap; UI malware (T3) | toggle from a stale list; double-tap | Low–Medium (reversible; no code enters the desktop) | Low | Switch disabled while busy; the ack drives the rendered state (no optimistic flip); `enabled` is the only mutable field; install/update/configure/uninstall have NO phone path (this ADR) | none beyond T3 |

## Consequences

- `V4Plane.setPluginEnabled(workspacePath, workspaceIdentity, pluginId, enabled)`
  (shape above); `ViewerTools` plugins viewer gains the toggle + result decode
  (`zcodePluginsSetEnabledResult` — tolerant: the viewer re-lists after the ack).
- Mock: `setPluginEnabled` flips the scripted plugin's `enabled` flag so the
  rig proves the round-trip.
- Drift duty: `setPluginEnabled` is already in `consumed-surfaces.json#facade`
  (Phase 2H listed the whole plugin word family as consumed-by-ports); the
  install/update/configure/uninstall entries stay listed (their REMOVAL still
  classifies breaking for the desktop-side viewer path) with this ADR noted.
- The PROBE-A1 Phase-0 checkbox stays for its own session; the verdict is
  appended to the report per rule 5.

## References

- Shipped bundle 3.14.5.7961 `out/host/index.js`: facade plugin words ≈344742–346624;
  retired `createPluginsService` ≈1056431; `createZCodeAgentConnectionScope` Proxy
  (host/chunk-LNGNUNBX.js ≈78000); `SAe` channel-server override map ≈1481612.
- Review: A1.1, A3 Tier 3 row 19, PROBE-A1 (Phase 0), L3 (confirm-blind approvals).
