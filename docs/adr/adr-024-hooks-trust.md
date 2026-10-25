# ADR-024: Workspace hook trust from the phone — the blocking review card + grant (A3.20)

| | |
|---|---|
| **Status** | Accepted — 2026-10-20 |
| **Phase** | gaps-and-polish Phase 5D (A3.20 — hooks trust grant; "a session can block on hook trust with NO phone remedy today") |
| **Deciders** | Ernest (ruling path: the Phase-5 instruction, as ADR-020–023) + implementation session |
| **Governs** | `core/protocol/v4/V4Command.kt` hook commands (already modeled, never sent), `data/session/InteractionCoordinator.kt`, `features/conversation` interaction cards, `core/transport` `V4Plane.grantWorkspaceHookTrust`, `architectural.md` §13.3 row `hooks`, §26 row T25 |
| **Supersedes** | §13.3 `hooks` "P3 · R · Inspectors · Hidden" (narrowed: the review interaction is answered from the phone; the Settings-side grant word is ported for the same digest pair) |

## Context

Workspace hooks are **user-configured shell commands the agent runs at
lifecycle events** (SessionStart, UserPromptSubmit, PreToolUse,
PermissionRequest, PostToolUse, PostToolUseFailure, Stop). Because they
execute code, the desktop gates them behind a per-workspace **trust review**:
when a session reaches hooks whose declaration digest is not yet trusted, the
runtime raises a `workspaceHookReview` interaction and **blocks until it is
answered**. The phone decodes none of it today — a session can sit blocked
with no phone remedy. `V4Command.kt` already models the four review commands
(`respondWorkspaceHookReview`, `toggleWorkspaceHookReviewItem`,
`revokeWorkspaceHookTrust`, `requestWorkspaceHookReview`) with zero senders
(I1 class).

## What the shipped desktop exposes (bundle-extracted, 3.14.5.7961)

Interaction payload `workspaceHookReview` (host/chunk-4RHDQOPX.js ≈486560,
zod `yS`, `.strict()`):

```
{ kind:"workspaceHookReview", reviewFlowId, generation, interactionId, sessionId, taskId,
  runId, workspaceIdentity, workspaceLabel, remoteSessionId?, bundleDigest (sha256 hex),
  createdAt, deadlineAt, sourceFiles:[{path, displayPath, editable}],
  summary:{eventCount, hookCount, pendingCount},
  items:[{reviewItemId, event, matcher?, type:"command"|"process", displayName,
          displayCommand, sourcePath, resolvedTimeoutMs, resolvedMaxOutputBytes,
          executionMode:"foreground"|"background", configuredEnabled, editable,
          trustState: not_applicable|pending_trust|trusted_persistent|blocked_untrusted|
                      blocked_policy|revoked|stale_digest }],
  warningCode:"workspace_hooks_execute_code" }
```

Commands (v4 envelope, NOT CAS-guarded — none of the four is in the
baseRevision set): base `{sessionId, taskId, runId, remoteSessionId?,
workspaceIdentity, bundleDigest, reviewFlowId, generation, interactionId}` +
`respondWorkspaceHookReview {decision:{action:"trust_selected", reviewItemIds[≥1]}}`,
`toggleWorkspaceHookReviewItem {reviewItemId, enabled}`,
`revokeWorkspaceHookTrust {reviewItemIds[≥1]} | {sessionId, workspaceIdentity, bundleDigest, hookDeclarationDigests[≥1]}`,
`requestWorkspaceHookReview {sessionId, workspaceIdentity, bundleDigest}`.
Facade: `grantWorkspaceHookTrust {workspacePath, workspaceIdentity, bundleDigest,
hookDeclarationDigest}` (host/index.js ≈340201 → `workspaceHookTrustGrant`).

## Decision

1. **Port the review interaction as a BLOCKING card** in the conversation
   (the approvals-card class, Phase 1C/3B): header "This workspace's hooks
   run code" (`warningCode`), the workspace label + source files, and one
   row per item showing **event · displayName · the FULL displayCommand**
   (monospace, selectable — never truncated to a one-liner: the command IS
   the risk), execution mode/timeout, and its trust state as a chip.
2. **Per-item selection → `trust_selected`.** Items in a pending state
   (`pending_trust`, `revoked`, `stale_digest`) carry a checkbox (default
   UNCHECKED — trust is opt-in per hook); `toggleWorkspaceHookReviewItem`
   mirrors the desktop's enable toggle for `editable` items. The single
   primary action **"Trust N selected hooks"** is disabled at N=0 and fires
   `respondWorkspaceHookReview {action:"trust_selected", reviewItemIds}`
   behind a confirm sheet that repeats the N commands and the workspace. No
   "trust all" shortcut exists on the phone (the desktop decision union has
   only `trust_selected`; the phone adds no broader word).
3. **Revoke is a secondary action** on trusted items (`revokeWorkspaceHookTrust
   {reviewItemIds}`) with a plain confirm (revoking is the safe direction).
4. **`grantWorkspaceHookTrust` (facade) is ported** as the Settings-side
   grant for the same digest pair, used when the card's resolution needs the
   persistent grant the desktop issues from its Settings → Hooks page; the
   phone exposes it ONLY from the review card's confirmed path (no free-form
   digest entry UI). `requestWorkspaceHookReview` re-raises the review when
   the user dismissed the card (the "review again" affordance).
5. **Deadline honesty:** `deadlineAt` renders as a countdown; a past deadline
   renders the card as expired (the runtime will have auto-resolved).

## §13.3 row delta

| Row | Before | After |
|---|---|---|
| `hooks` (+ the v4 hook-review commands on `zcode-agent`) | P3 · R · Inspectors · Hidden | **R + ADR-024 W** (`respondWorkspaceHookReview` / `toggleWorkspaceHookReviewItem` / `revokeWorkspaceHookTrust` / `requestWorkspaceHookReview` / facade `grantWorkspaceHookTrust`) · sensitivity **High** (trust = permission for shell code to run) · fallback: the card renders read-only when a send is refused |

## §26 threat-model delta

| # | Threat | Asset | Attacker | Attack | Impact | Likelihood | Mitigation | Residual |
|---|---|---|---|---|---|---|---|---|
| T25 | Hooks trusted without the user reading them | the desktop shell (hook commands execute on every matching lifecycle event, persistently) | accidental tap; UI malware (T3); a malicious repo whose hooks look benign (supply-chain social engineering) | trust granted from a collapsed/truncated view; "trust all" reflex | **High** (persistent code execution) | Low–Medium | full `displayCommand` rendered monospace (never truncated), per-item opt-in checkboxes (default off), confirm sheet repeating the exact commands + digest, no trust-all word, `stale_digest` shown as its own state (a changed hook never inherits trust) | a user who trusts a hook they did not understand — the same residual as the desktop's own review dialog |

## Consequences

- Domain gains `WorkspaceHookReview` (+items, trust-state enum, tolerant
  decode: unknown `event`/`trustState` → UNKNOWN, never actionable);
  `InteractionCoordinator` learns the `workspaceHookReview` kind (lifecycle
  machine reused; the submit is `respondWorkspaceHookReview`, not
  `resolveInteraction`); `CommandCoordinator` gains the four senders;
  `ConversationPort` gains `respondHookReview` / `toggleHookReviewItem` /
  `revokeHookTrust` / `requestHookReview` / `grantHookTrust`.
- `V4Plane.grantWorkspaceHookTrust(workspacePath, workspaceIdentity,
  bundleDigest, hookDeclarationDigest)`.
- Mock: `MOCK_HOOKS=1` scripts a `workspaceHookReview` interaction with two
  pending items and one trusted; `respondWorkspaceHookReview` resolves it and
  flips trust states so the rig proves the loop.
- Drift duty: `grantWorkspaceHookTrust` + the four command names join
  `consumed-surfaces.json#facade`.

## References

- Shipped bundle 3.14.5.7961: `out/host/chunk-4RHDQOPX.js` ≈486560 (yS/hS/dV/pV/mV/gV
  schemas, `_T` trust-state enum); `out/host/chunk-LNGNUNBX.js` ≈46060 (`$r` command
  payload schemas); `out/host/index.js` ≈340201 (`grantWorkspaceHookTrust`).
- Review: A3 Tier 3 row 20, C1 (`hooks` channel row), I1 (decoded-never-sent class).
