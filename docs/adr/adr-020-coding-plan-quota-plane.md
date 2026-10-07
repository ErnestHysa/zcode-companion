# ADR-020: Port the Coding-Plan quota plane as usage-stats R-reads + a gated reset action

| | |
|---|---|
| **Status** | Accepted — 2026-10-07 |
| **Phase** | android-gaps Phase 4 (GAP-3 quota panel read, GAP-4 quota reset flow) |
| **Deciders** | Ernest (ruling, see "Reset-action ruling" below) + implementation session |
| **Governs** | `docs/protocol/v4-wire.md` §13, `architectural.md` §13.3 rows for `usage-stats` / `coding-plan-subscription`, §26 threat-model delta (T19) |
| **Supersedes** | The §13.3 "Never" classification for the coding-plan **quota payload** (not for purchases) |

## Context

The gaps report (§2, §10 rows 3–4, §11) flagged the Coding-Plan quota surface as
class **B ⚠**: porting it overturns a §13.3 "Never" (`coding-plan-subscription`,
"provider-credential adjacency was the concern"), so an ADR + §26 threat-model
delta must exist **before** any code (roadmap hard gate). Two entries depend on
it:

- **GAP-3 (P2)** — quota panel: 5-hour %, tool-calls %, MCP %, next-reset time.
- **GAP-4 (P2)** — quota reset flow (second gate: Ernest's explicit ruling).

## Decision

Port the quota plane in three pieces, none of which is the roadmap's original
mechanistic description (rule-3 re-read found a cleaner, narrower surface —
see "Channel resolution"):

1. **Read (GAP-3):** the phone calls the **`usage-stats`** ProxyChannel method
   `getEntitlementSnapshot` — the exact call the desktop's own UI makes — and
   decodes `UsageEntitlementSnapshot` (usage-stats.ts:73-89) with its
   `quota: UsageQuotaSnapshot` (usage-quota.ts) and `mcpQuota.aggregate`.
   Client-side polling only (one read per Usage-screen load + explicit
   refresh); no background refresh, no push listener.
2. **Reset words (GAP-4):** `getCodingPlanResetStatus` (read),
   `requestCodingPlanResetOpportunity` (server-side grant evaluation,
   idempotency-keyed), `useCodingPlanReset` (**the one destructive-adjacent
   write** — consumes a server-granted reset opportunity), and
   `markCodingPlanResetHistoryRead` (read-marker clear, part of the desktop's
   own completion flow) — all on the same `usage-stats` channel, all gated
   behind a typed-confirm dialog. No other write word from any plan channel is
   ported.
3. **Never ported:** the `coding-plan-subscription` channel itself (the
   purchase facade: Stripe/PayPal/products/orders/agreements). Buying,
   subscribing, or managing payment methods from the phone remains
   desktop-only, matching §2.2 N7's spirit and the §11 trust T-item.

## Channel resolution (rule-3 re-read — what the desktop actually does)

The roadmap cites "`coding-plan-subscription` (`channels.ts:116`) — a push
subscription, not polling". Re-reading the desktop source at `29628c9`:

- `channels.ts:116` names the channel of
  `ICodingPlanSubscriptionService` (codingPlanSubscription.ts:120-121), whose
  method surface is **entirely purchase-shaped** (batchPreview, productInfo,
  Stripe bind/pay, PayPal, enterprise orders). It has **no quota method and no
  event-listen (`on*`) member** — there is nothing on that channel a quota
  viewer could decode.
- The quota payload the desktop renders — `UsageQuotaSnapshot` /
  `UsageQuotaLimit` / `UsageMcpQuotaSnapshot` (usage-quota.ts) — reaches the UI
  inside `UsageEntitlementSnapshot`, fetched by
  `IUsageStatsService.getEntitlementSnapshot` (usageStats.ts:29;
  usageStatsService.ts:122-126 → the BigModelUsageQuotaProvider monitor
  client). The renderer **polls** it with a cache/in-flight-merge/TTL policy
  (useUsageEntitlement.ts:26-80, usageEntitlementRefreshPolicy.ts:20
  `USAGE_ENTITLEMENT_ACCESS_REFRESH_MS = 60_000`) — the desktop comment records
  the motivation verbatim: "生产日志里同一分钟出现 12 个相同 quota RPC，直接拖慢
  renderer" (12 identical quota RPCs in one minute slowed the renderer, so they
  merged in-flight requests). A grep over both channels confirms **zero
  `on*` event members**: no quota push channel exists anywhere in the desktop.
- The reset flow's four words (`getCodingPlanResetStatus`,
  `requestCodingPlanResetOpportunity`, `useCodingPlanReset`,
  `markCodingPlanResetHistoryRead`) are the other members of the same
  `IUsageStatsService` (usageStats.ts:14-26), consumed by
  `useCodingPlanQuotaResetUi` through the same ProxyChannel.

**Resolution:** the §13.3 overturn is *narrower* than the roadmap assumed. The
quota plane rides the **`usage-stats` channel** — already §13.3-classified
P2/R ("sanctioned to port eventually") and already partially ported in Phase 3
(the agent-DB aggregate face, `v4/usage/stats`). What actually changes:

| §13.3 row | Before | After |
|---|---|---|
| `usage-stats` | ported read-only (Phase 3) | **read + ADR-020-gated reset words** (Phase 4); sensitivity Low → **Medium** (a W-class word exists: `useCodingPlanReset` spends a reset opportunity) |
| `coding-plan-subscription` | Never | **stays Never — clarified**: it is the *purchase* facade; the quota payload the report attributed to it rides `usage-stats` (this ADR). Purchases from the phone remain out of scope with no ruling path in this campaign |

## Why the original concern does not apply to this payload

The "Never" was assigned for provider-credential adjacency. Re-reading every
field the phone would send or receive:

- **Responses carry quota metadata only.** `UsageEntitlementSnapshot` is
  generatedAt/serverTime, authenticated flag, an `unavailableReason` enum,
  provider id+label, a remaining-count block, subscription *display* details
  (masked identity — `identityMasked`, never the address), the quota buckets,
  and the MCP aggregate. No API keys, no tokens, no signing material
  (usage-quota.ts header comment: "额度相关的纯类型定义" — pure quota types).
- **Requests carry a plan-scope *descriptor*, not credentials.** The reset
  words take `accountAccess: ZCodeProviderAccountAccess | ZCodeAccountAccess`
  (zcode-protocol/index.ts:810-850). Both union arms are closed enums of
  plan-kind/family literals plus (team arm) organization/project ids —
  `zhipu-account` / `zai|bigmodel` / `start-plan|individual-coding-plan|
  team-coding-plan` / `entitled: boolean`. This is *which plan bucket to
  bill*, not *who is allowed to bill*. The API key itself is resolved
  **host-side** from the desktop's own credential store
  (`accountRequestAuthService` + `credentialService`, usageStatsService.ts:47-58)
  — exactly like every Phase-3 viewer call; the wire request adds no
  secret-bearing field, and the phone cannot learn a credential from any of it.
- **Transport posture is unchanged.** Same relay TLS boundary, same
  connection, same §7 ProxyChannel call convention as the already-ported
  viewer reads; no new trust boundary is created (§26.1 diagram unchanged).

## §26 threat-model delta (new row T19)

| # | Threat | Asset | Attacker | Attack | Impact | Likelihood | Mitigation | Residual |
|---|---|---|---|---|---|---|---|---|
| T19 | Quota-reset misuse — the reset action fires without deliberate user intent (fat-finger, injected UI event, content-driven affordance) | plan quota (a server-granted, finite reset opportunity is consumed; the 5h/week usage window is cleared, raising the user's remaining quota — irreversible for the consumed opportunity) | (a) accidental local user; (b) malware driving the UI (T3 class); (c) content/social-engineering nudging a tap (T14 class) | reset fired from a stale or spoofed affordance; double-tap double-spend | Medium (burns a scarce plan perk; no credential or data exposure) | Low | **typed-confirm dialog** — the word `RESET` must be typed before the confirm button enables; the transport method is reachable ONLY through that confirmed path (UI test pins zero calls without it); server-side idempotency key dedupes retries (`requestCodingPlanResetOpportunity`/`useCodingPlanReset` take `idempotencyKey`; the desktop reuses one key across retries of the same attempt, codingPlanQuotaResetUi.ts); quota metadata displayed is the same class as the Phase-3 usage screens (no new display-sensitivity) | A determined overlay attacker who can also type (T3 residual, disclosed) — unchanged platform limit |

No other T1–T18 row changes: the read adds display-only metadata on an
already-sanctioned channel; §26.6's phase-gate walk is satisfied by this
section for Phase 4.

## Reset-action ruling (the roadmap's second gate)

GAP-4 may not start "until Ernest explicitly rules the reset action in-scope".
**Ruling: IN SCOPE — Ernest, 2026-10-07** (the Phase-4 instruction: "begin
phase 4, and only this phase, verify this phase is 100% fulfilled and
finished" — GAP-4 is one of the phase's two findings, and the roadmap's exit
gate requires it ticked with this ruling recorded). Recorded here and in the
GAP-4 commit message per the exit gate.

## Consequences

- The phone's quota panel is client-polled (Usage-screen load + explicit
  refresh). The desktop's 60 s shared-cache arbitration is a four-surface
  optimization; the phone has one surface and does not reproduce it
  (documented simplification, not a behavior difference the wire can observe).
- The panel **degrades hidden**: absent snapshot, refused channel, or read
  failure ⇒ the section renders nothing (the desktop's own
  `ChatCodingPlanUsageRemainingPanel` returns `null` without state;
  CodingPlanContextUsage.tsx:206-208) — the §13.3 fallback stays
  "Hidden (error face)" at the screen level.
- The reset UI state machine ports the desktop's pure functions verbatim
  (available → processing → completed | failed; sticky processing until the
  server's `usedAt` confirms; idempotency-key reuse across retries;
  opportunity windows validated `expireAt > now`). Celebration confetti and
  the cross-surface auto-play machinery are **not** ported (desktop-visual
  garnish; the phone surfaces the honest completion state inline).
- INV-9 stays intact for the viewer plane: the Phase-3 viewer methods gain no
  write sibling; the reset surface is a separate, ADR-sanctioned method group
  pinned by `V4PlaneViewerSurfaceTest` (exactly one write word,
  `codingPlanResetUse`, exists on the whole plane).
- Fixture discipline (rule 5): the entitlement/reset payload types are
  TypeScript interfaces (not zod), so the Phase-4 generator validates payloads
  by **type-checking them against the verbatim-extracted interfaces**
  (`tsc --noEmit`) instead of zod parse — same "reference-derived or it does
  not exist" guarantee, adapted to the surface's own schema form.
- Live Z.ai field-value capture still rides the Phase-12 canary (the §13
  honest-limits note); fixtures carry the reference-derived shapes meanwhile.

## References

- Desktop: packages/shared/src/usage-quota.ts (whole file); usage-stats.ts:73-89
  (UsageEntitlementSnapshot), :267-283 (CodingPlanUsageSnapshot); coding-plan-reset.ts
  (whole file); packages/services/src/usage-stats/usageStats.ts:12-29;
  usageStatsService.ts:36-126; packages/ui/src/hooks/useUsageEntitlement.ts:26-80;
  hooks/useCodingPlanQuotaResetUi.ts; lib/codingPlanQuotaPresentation.ts (whole);
  lib/codingPlanQuotaResetUi.ts (whole); lib/codingPlanQuotaResetCoordinator.ts;
  chat-input-toolbar/CodingPlanContextUsage.tsx:210-323 (meter construction);
  CodingPlanUsageRemainingPanel.tsx:15-30 (entitlement type).
- Android: docs/protocol/v4-wire.md §13; architectural.md §13.3/§26 (this ADR's
  edits); core/transport V4Plane (Phase-4 methods); domain CodingPlanQuotaModels;
  core:ui CodingPlanQuotaPresentation + CodingPlanQuotaResetState; app settings
  CodingPlanQuotaSection.
