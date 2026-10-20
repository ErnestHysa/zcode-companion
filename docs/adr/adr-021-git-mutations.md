# ADR-021: Port the desktop's git write vocabulary as a tiered, intent-gated surface (A3.17 + J1.3)

| | |
|---|---|
| **Status** | Accepted — 2026-10-20 |
| **Phase** | gaps-and-polish Phase 5D (A3.17 + J1.3 — git mutations incl. AI commit messages) |
| **Deciders** | Ernest (ruling path: the Phase-5 instruction "begin phase 5 … verify this phase is 100% fulfilled" — the roadmap's exit gate requires this ADR merged before any write code, then the implementation per its ruling) + implementation session |
| **Governs** | `features/git/port/GitPort.kt` (the INV-9 typed surface), `core/transport` `V4Plane` git methods, `architectural.md` §13.3 row `git`, §26 threat-model rows T20–T22, `docs/protocol/phase9-channels.md` §2 |
| **Supersedes** | The GitPort header's "No stage/unstage/commit/push/branch methods EXIST on this port — INV-9 is enforced by the typed surface" (narrowed: the write words exist ONLY as the enumerated ADR-021 group, pinned) |

## Context

A3 Tier 3 row 17 names git mutations the "killer feature" and requires "the
ADR-020 treatment"; J1.3 enumerates the desktop's full write vocabulary the
spec must target: a **commit dialog** (AI generate/regenerate, include-unstaged
toggle, identity-missing state, commit-and-push), a **push dialog** (ahead/
behind, untracked description, error copy), and a **branch switcher with a
typed error taxonomy**. The phone's `GitPort` is a strict read-only subset
(summary / changes / diff / commit-graph) by INV-9 ("no write ops to desktop
filesystem/shell in MVP").

## What the shipped desktop actually exposes (bundle-extracted, 3.14.5.7961)

The `git` service channel object (host/index.js ≈200265–202500) is served to
the phone's `web-remote-replayable` connection UNFILTERED by
`ServiceCollection.exposeOnChannelServer` (`ProxyChannel.fromService` — every
method callable; the only per-client override in the whole host is the
conversation-share stub, see PROBE-L6). Write words and exact shapes:

| Word | Params | Result / failure |
|---|---|---|
| `stagePaths` | `{workspacePath, paths[]}` | void (`git add -- …`); no-op on empty set |
| `unstagePaths` | `{workspacePath, paths[]}` | void (`git restore --staged -- …`) |
| `discardPaths` | `{workspacePath, paths[], staged?}` | void — `staged=true` ⇒ `git restore --source=HEAD --staged --worktree`, else `--worktree` only. **DESTROYS uncommitted work.** |
| `generateCommitMessage` | `{workspacePath, includeUnstaged? (default true), currentSessionFilePaths?}` | `{message…}`; throws `"Commit message generation is not available."` (no generator) / `"There are no changes available to commit."` |
| `commit` | `{workspacePath, message, paths?, stagedOnly?}` | `{commitHash, summary}`; throws `"Commit message cannot be empty"` |
| `push` | `{workspacePath}` | `{branchName, trackingBranchName, …}` (sets upstream when none); throws `"Cannot push while HEAD is detached."` |
| `switchBranch` | `{workspacePath, targetBranchName}` | `{action:"switch", branchName, didChange, created, summary}` or `{…, issues:[{code, message, detail?, paths?}]}` |
| `createBranchAndSwitch` | `{workspacePath, branchName, startPoint?}` | same result family, `action:"create-and-switch"` |
| `getBranchComparison` (read) | `{workspacePath}` | `{baseRef, headRef, comparisonLabel, changes[]}` (upstream…HEAD numstat) |
| `getLocalBranches` (read) | `{workspacePath}` | `{headRefType, currentBranchName, branches:[{name, upstream, objectName, committerDate}]}` |
| `getIdentity` (read) | `{workspacePath}` | `{userName, userEmail, nameSource, emailSource, scopeLabel}` — nulls = identity missing |
| `refresh` (read) | `{workspacePath, includeIdentity?, includeBranchComparison?}` | `{summary, identity?, branchComparison?}` |

**The typed error taxonomy is the host's `parseGitBranchMutationIssues`
(host/index.js ≈167300), wire codes kebab-case** (J1.3 quoted the desktop's
i18n camelCase keys — the phone must map the WIRE codes):
`tracked-changes-would-be-overwritten` (+`paths`),
`untracked-changes-would-be-overwritten` (+`paths`), `branch-already-exists`,
`target-branch-not-found`, `branch-in-other-worktree`, `conflicts-present`,
`operation-in-progress`, `invalid-branch-name`, `unknown`. J1.3's
`trackedOverwrite`/`untrackedOverwrite`/`branchAlreadyExists`/
`branchInOtherWorktree`/`conflictsPresent`/`invalidBranchName`/
`operationInProgress`/`targetBranchNotFound` map 1:1 onto these.

## Decision

Port the vocabulary as **three intent tiers**, each with its own gate; the
transport write words are reachable ONLY through their tier's gate (UI pins
assert zero calls without it — the ADR-020 pattern).

1. **Tier A — reversible index moves, one tap:** `stagePaths` / `unstagePaths`
   on a change row (swipe/overflow). Reversible by the sibling word; no
   confirm. Busy acknowledgment + list refresh after the ack (B2.11 class).
2. **Tier B — history-creating / outward-publishing, dialog-gated:**
   - `commit` behind a **commit sheet**: message field (required — empty is
     refused client-side before the wire, mirroring the host), **Generate /
     Regenerate** (`generateCommitMessage`, include-unstaged Switch mirrors
     the desktop toggle; the "not available" and "no changes" host errors
     render as inline copy), an **identity-missing state** (`getIdentity`
     nulls ⇒ the Commit button is disabled with the desktop's guidance copy —
     the phone cannot and must not set identity), scope = staged-only by
     default (`stagedOnly=true`) with "include unstaged" widening it, and an
     optional **commit-and-push** toggle that chains `push` ONLY after a
     successful commit ack.
   - `push` behind a **push confirm** rendering ahead/behind from the
     summary, the untracked-upstream description when no tracking branch
     exists (the host will `--set-upstream`), and the detached-HEAD refusal;
     host errors surface verbatim with a copy-error button (J1.3).
   - `switchBranch` / `createBranchAndSwitch` behind a **branch sheet**
     (`getLocalBranches`, current marked, upstream shown) with a create field;
     `issues[]` render through the typed map above, `paths[]` listed for the
     two overwrite codes; the raw `detail` is copyable, never the only copy.
3. **Tier C — destructive, typed-confirm:** `discardPaths` destroys uncommitted
   work irreversibly — the single op in this family with that property. It is
   gated by the **ADR-020 typed-confirm** (the word `DISCARD`; locale-stable
   comparison on the literal, not a translated string — the B3 `== "RESET"`
   lesson), the dialog lists every path and the staged/worktree scope, and
   the transport word is reachable only through the confirmed path.

**Never ported by this ADR:** `pull`/`fetch` (absent from the shipped channel),
force-push (no such word exists; `push` never passes `--force`), rebase/merge/
reset/stash (not on the channel), identity mutation (desktop-only by design).

## §13.3 row delta

| Row | Before | After |
|---|---|---|
| `git` | P2 · R · Medium | **R + ADR-021-gated W** (stage/unstage/discard/commit/generateCommitMessage/push/switchBranch/createBranchAndSwitch) · sensitivity Medium → **High** (a destructive worktree write exists) · fallback unchanged (disabled face) |

INV-9 wording becomes: "no write ops … EXCEPT the enumerated ADR-021 git
group" — pinned by `GitPortWriteSurfaceTest` (reflection over the port:
exactly the eight write names exist; a ninth fails the pin).

## §26 threat-model delta

| # | Threat | Asset | Attacker | Attack | Impact | Likelihood | Mitigation | Residual |
|---|---|---|---|---|---|---|---|---|
| T20 | Discard fires without deliberate intent | uncommitted working-tree edits (irreversible loss) | accidental tap; UI-driving malware (T3); content nudging (T14) | discard from a stale/spoofed affordance; double-tap | **High** (data loss) | Low | typed-confirm `DISCARD` + full path list; word reachable only via the confirmed path (pinned); no swipe-to-discard anywhere | overlay attacker who can also type (T3 residual) |
| T21 | Push publishes work outward | remote branch state (secrets/partial work become visible to collaborators) | same classes | push from a stale summary; commit-and-push chaining a wrong commit | Medium | Low | push confirm renders ahead/behind + target; commit-and-push chains only after a successful commit ack; `push` never force-pushes (no such word) | upstream policy (branch protection) stays the remote's |
| T22 | Commit under a wrong identity | repository history attribution | — (no phone path exists) | — | — | — | identity is resolved host-side from the desktop's git config; the phone renders identity-missing and cannot set it | none new |

Branch switching is reversible (switch back) and refused by git itself when
it would overwrite changes — the typed issue map makes those refusals legible;
no new row.

## Consequences

- `GitPort` grows the eight write words + `branches()`/`identity()`/
  `branchComparison()` reads; `V4Plane` grows matching methods; the Phase-9
  "read-only subset" comment is rewritten to cite this ADR.
- All writes refresh via the existing `summary()`/`changes()` path after the
  ack — no optimistic mutation of the change list (the wire is the truth).
- Fixture discipline (rule 5): shapes above are bundle-extracted (no zod
  source for the git channel); the mock gains `stagePaths`/`unstagePaths`/
  `discardPaths`/`commit`/`generateCommitMessage`/`push`/`switchBranch`/
  `createBranchAndSwitch`/`getLocalBranches`/`getIdentity` handlers that
  mutate an in-memory status and can be scripted to return each issue code.
- Drift duty: the ten names join `consumed-surfaces.json#facade`.

## References

- Shipped bundle 3.14.5.7961 `out/host/index.js`: git channel object ≈200265–202500;
  `createGitCliRepo` ≈186000–200000 (`switchBranch` 189019, `createBranchAndSwitch`
  190118, `getBranchComparison` 192876, `stage` 193779, `unstage` 194029, `discard`
  194311, `commit` 194652, `push` 196982, `getIdentity`, `listLocalBranches` 187649);
  `parseGitBranchMutationIssues` ≈167300.
- Review: android-native-gaps-and-polish.md A3 Tier 3 row 17, J1.3, B2.7/B2.11 (git
  false-empty + silent-action classes).
- ADR-020 (typed-confirm precedent).
