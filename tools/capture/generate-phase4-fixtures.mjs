// Dev-time golden fixture generator for the Phase-4 coding-plan quota plane
// (roadmap rule 5; ADR-020; docs/protocol/v4-wire.md §13): the
// `usage-stats` channel surfaces getEntitlementSnapshot (GAP-3) and the reset
// words getCodingPlanResetStatus / requestCodingPlanResetOpportunity /
// useCodingPlanReset / markCodingPlanResetHistoryRead (GAP-4).
//
// The entitlement/reset payload types are TYPESCRIPT INTERFACES (usage-quota.ts,
// usage-stats.ts, coding-plan-reset.ts) — no zod schemas exist for them — so the
// reference validation is TYPE-CHECKING: the desktop's interface/type text is
// extracted VERBATIM and each payload is assigned to its interface in a scratch
// .ts checked by `tsc --noEmit --strict`. A golden fixture its own source-of-truth
// type rejects cannot exist. Output: fixtures/protocol/phase4-wire-fixtures.json.
// Never shipped, never part of CI deps. typescript+zod are bootstrapped into
// /tmp scratch — the reference clone stays untouched (it has no node_modules).
import { writeFileSync, mkdirSync, readFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";

const REPO = "/Users/ernest/Desktop/DEVPROJECTS/zcode/repo";
const OUT = process.env.PHASE4_OUT ||
  "/Users/ernest/Desktop/DEVPROJECTS/zcode/android-native/fixtures/protocol/phase4-wire-fixtures.json";
const SCRATCH = "/tmp/fixture-gen-p4";

// ---------------------------------------------------------------- tool bootstrap
if (!existsSync(`${SCRATCH}/node_modules/typescript`)) {
  mkdirSync(SCRATCH, { recursive: true });
  execSync(`npm install --no-save --silent typescript@5.5.4 zod@4.6.5`, {
    cwd: SCRATCH, stdio: "inherit",
  });
}

// ---------------------------------------------------------------- verbatim extraction
// Text is extracted from the pinned reference sources between stable markers —
// never hand-copied — so a clone update that changes a type CHANGES the fixture
// generator input (regenerate, never patch by hand).
function slice(src, startMarker, endMarker) {
  const start = src.indexOf(startMarker);
  if (start < 0) throw new Error(`marker not found: ${startMarker.slice(0, 60)}`);
  const end = src.indexOf(endMarker, start);
  if (end < 0) throw new Error(`end marker not found: ${endMarker.slice(0, 60)}`);
  return src.slice(start, end + endMarker.length);
}
/** slice() variant that EXCLUDES the end marker (for blocks that end at the
 *  opening line of the NEXT declaration — a dangling `export interface X` line
 *  would not type-check). */
function sliceBefore(src, startMarker, endMarker) {
  const start = src.indexOf(startMarker);
  if (start < 0) throw new Error(`marker not found: ${startMarker.slice(0, 60)}`);
  const end = src.indexOf(endMarker, start);
  if (end < 0) throw new Error(`end marker not found: ${endMarker.slice(0, 60)}`);
  return src.slice(start, end);
}
function stripImports(src) {
  return src
    .split("\n")
    .filter((line) => !/^\s*import\s/.test(line) && !/^\s*export\s+\*\s+from\s/.test(line))
    .join("\n");
}

// usage-quota.ts: pure quota types — whole file minus imports/re-export.
const usageQuotaTypes = stripImports(
  readFileSync(`${REPO}/packages/shared/src/usage-quota.ts`, "utf8"),
);

// coding-plan-reset.ts: the whole reset type surface minus imports (its
// ZCodeAccountAccess import is re-declared below from the verbatim schemas).
const resetTypes = stripImports(
  readFileSync(`${REPO}/packages/shared/src/coding-plan-reset.ts`, "utf8"),
);

// usage-stats.ts: the entitlement snapshot + its nested display types — ONE
// slice from the snapshot through the subscription details (everything between
// is self-contained; the REQUEST type needs protocol imports the result plane
// never references, so the block starts at the snapshot itself — request-side
// shapes validate through coding-plan-reset.ts + the access-type module).
const usageStatsSrc = readFileSync(`${REPO}/packages/shared/src/usage-stats.ts`, "utf8");
const entitlementBlock = sliceBefore(
  usageStatsSrc,
  "export interface UsageEntitlementSnapshot",
  "export interface UsageStatsSnapshot",
);

// zcode-protocol/index.ts: the account-access descriptor schemas (verbatim zod)
// + the nonEmptyString helper they reference.
const proto = readFileSync(`${REPO}/packages/shared/src/zcode-protocol/index.ts`, "utf8");
const accountAccessBlock = slice(
  proto,
  "export const zcodeAccountAccessSchema",
  "export type ZCodeProviderAccountAccess = z.infer<typeof zcodeProviderAccountAccessSchema>;",
);

mkdirSync(SCRATCH, { recursive: true });

// ---------------------------------------------------------------- payload cases
// Timestamps are FIXED instants (2026-10-13T23:20Z base) so UI tests pin labels
// deterministically under a fixed zone; synthetic values only (INV-15/16).
const BASE = 1791933600000;
const T = {
  fiveHourReset: BASE + 5 * 3600 * 1000,
  weekReset: BASE + 7 * 24 * 3600 * 1000,
  toolReset: BASE + 30 * 24 * 3600 * 1000,
  mcpReset: BASE + 24 * 3600 * 1000,
  expires1h: BASE + 3600 * 1000,
  expires2h: BASE + 2 * 3600 * 1000,
  usedAt3hAgo: BASE - 3 * 3600 * 1000,
  nextTry10m: BASE + 10 * 60 * 1000,
};

const entitlementFull = {
  generatedAt: BASE,
  serverTime: BASE + 123,
  authenticated: true,
  context: { scope: "personal", displayName: "synthetic", productId: "prod_synth" },
  provider: { id: "account:zai-individual-coding-plan", name: "Z.ai Coding Plan" },
  remaining: { count: 320, isShow: true, percentage: 64, nextResetTime: T.fiveHourReset },
  subscription: null,
  quota: {
    level: "glm-coding-pro",
    limits: [
      {
        type: "TOKENS_LIMIT", unit: 3, number: 5,
        usage: 36, remaining: 64, percentage: 36,
        nextResetTime: T.fiveHourReset,
        usageDetails: [
          { modelCode: "glm-5.3", displayName: "GLM-5.3", usage: 30 },
          { modelCode: "glm-5.1", usage: 6 },
        ],
      },
      {
        type: "TOKENS_LIMIT", unit: 6,
        usage: 51, remaining: 49, percentage: 51,
        nextResetTime: T.weekReset,
        usageDetails: [{ modelCode: "glm-5.3", usage: 48 }],
      },
      {
        type: "TIME_LIMIT", unit: 5, number: 1,
        usage: 12, remaining: 88, percentage: 12,
        nextResetTime: T.toolReset,
        usageDetails: [],
      },
      {
        // deliberately unmatched by any meter lookup (unit 9): the panel must
        // ignore limits outside the desktop's exact unit/number queries.
        type: "TOKENS_LIMIT", unit: 9,
        usage: 1, remaining: 99, percentage: 1,
        nextResetTime: T.weekReset,
        usageDetails: [],
      },
    ],
  },
  mcpQuota: {
    serverTime: BASE,
    level: null,
    scope: { providerFamily: "zai", targetType: "PERSONAL" },
    aggregate: {
      type: "MCP_USAGE_LIMIT",
      usage: 22, remaining: 78, percentage: 22,
      nextResetTime: T.mcpReset,
      usageDetails: [{ modelCode: "web-reader", displayName: "Web Reader", usage: 12 }],
    },
  },
};

// zai-team-style payload: CREDIT_LIMIT is the SAME category as TOKENS_LIMIT
// (codingPlanQuotaPresentation.ts:16-33); its 5h face at percentage 100 means
// FULLY CONSUMED (remaining 0% — desktop quota FULL ⇔ remaining 100 = pct 0).
const entitlementZaiCredit = {
  generatedAt: BASE,
  authenticated: true,
  provider: { id: "account:zai-team-coding-plan", name: "Z.ai Team Plan" },
  remaining: null,
  subscription: null,
  quota: {
    level: "glm-coding-team",
    limits: [
      {
        type: "CREDIT_LIMIT", unit: 3, number: 5,
        percentage: 100,
        nextResetTime: T.fiveHourReset,
        usageDetails: [],
      },
      {
        type: "TIME_LIMIT", unit: 5, number: 1,
        percentage: 45,
        nextResetTime: T.toolReset,
        usageDetails: [{ modelCode: "glm-5.3", usage: 9 }],
      },
    ],
  },
};

// The honest unavailable face (desktop CodingPlanUsageNotice with the
// unavailableReason verbatim).
const entitlementNoPlan = {
  generatedAt: BASE,
  authenticated: true,
  unavailableReason: "no_plan",
  provider: null,
  remaining: null,
  subscription: null,
  quota: null,
};

const resetStatusFull = {
  availableFiveHourResets: [{ expireAt: T.expires1h }, { expireAt: T.expires2h }],
  availableWeekResets: [{ expireAt: T.weekReset }],
  latestFiveHourResetHistory: { usedAt: T.usedAt3hAgo },
  latestWeekResetHistory: null,
  hasUnreadHistory: true,
};

// An AVAILABLE-flow snapshot: live opportunities + NO unread completion (the
// desktop machine enters completed whenever hasUnreadHistory owns the newest
// usedAt — an available chip requires the unread cursor cleared).
const resetStatusAvailable = {
  availableFiveHourResets: [{ expireAt: T.expires1h }, { expireAt: T.expires2h }],
  availableWeekResets: [],
  latestFiveHourResetHistory: null,
  latestWeekResetHistory: null,
  hasUnreadHistory: false,
};

const resetStatusEmpty = {
  availableFiveHourResets: [],
  availableWeekResets: [],
  latestFiveHourResetHistory: null,
  latestWeekResetHistory: null,
  hasUnreadHistory: false,
};

const resetOpportunityGranted = { granted: true, nextTryAt: null };
const resetOpportunityDenied = { granted: false, nextTryAt: T.nextTry10m };
const resetUseResult = { used: true };

// The REQUEST descriptors the phone derives/derives-and-sends (ADR-020): the
// accountAccess descriptor is derived from the builtin provider-id grammar and
// validated here against the desktop's own zcodeProviderAccountAccessSchema.
const accountAccessZaiIndividual = {
  type: "zhipu-account",
  accountType: "zai",
  mode: "individual-coding-plan",
  entitled: true,
};
const resetUseRequest = {
  preferredProviderId: "account:zai-individual-coding-plan",
  accountAccess: accountAccessZaiIndividual,
  idempotencyKey: "synthetic-idempotency-key-0",
  resetType: "FIVE_HOUR",
};

// ---------------------------------------------------------------- type check
// Each payload is exported AS its reference interface — tsc --noEmit --strict
// rejects any shape drift (missing required field, wrong literal, added @ts
// noise). This replaces phase-2/3's zod parse as the reference validation.
const casesTs = `import type {
  UsageEntitlementSnapshot,
} from "./phase4-usage-stats-types.js";
import type {
  CodingPlanResetStatusSnapshot,
  CodingPlanResetOpportunityResult,
  CodingPlanResetUseRequest,
  CodingPlanResetUseResult,
} from "./phase4-reset-types.js";
import type { ZCodeProviderAccountAccess } from "./phase4-access-types.js";

export const entitlementFull: UsageEntitlementSnapshot = ${JSON.stringify(entitlementFull, null, 2)};

export const entitlementZaiCredit: UsageEntitlementSnapshot = ${JSON.stringify(entitlementZaiCredit, null, 2)};

export const entitlementNoPlan: UsageEntitlementSnapshot = ${JSON.stringify(entitlementNoPlan, null, 2)};

export const resetStatusFull: CodingPlanResetStatusSnapshot = ${JSON.stringify(resetStatusFull, null, 2)};

export const resetStatusAvailable: CodingPlanResetStatusSnapshot = ${JSON.stringify(resetStatusAvailable, null, 2)};

export const resetStatusEmpty: CodingPlanResetStatusSnapshot = ${JSON.stringify(resetStatusEmpty, null, 2)};

export const resetOpportunityGranted: CodingPlanResetOpportunityResult = ${JSON.stringify(resetOpportunityGranted, null, 2)};

export const resetOpportunityDenied: CodingPlanResetOpportunityResult = ${JSON.stringify(resetOpportunityDenied, null, 2)};

export const resetUseResult: CodingPlanResetUseResult = ${JSON.stringify(resetUseResult, null, 2)};

export const accountAccessZaiIndividual: ZCodeProviderAccountAccess = ${JSON.stringify(accountAccessZaiIndividual, null, 2)};

export const resetUseRequest: CodingPlanResetUseRequest = ${JSON.stringify(resetUseRequest, null, 2)};
`;

// Split the verbatim text into per-file modules so relative import names resolve
// inside the scratch dir and each type surface stays reference-verbatim.
writeFileSync(
  `${SCRATCH}/phase4-usage-stats-types.ts`,
  [
    `import type { UsageQuotaSnapshot, UsageMcpQuotaSnapshot } from "./phase4-quota-types.js";`,
    stripImports(entitlementBlock),
  ].join("\n"),
);
writeFileSync(`${SCRATCH}/phase4-quota-types.ts`, usageQuotaTypes);
writeFileSync(
  `${SCRATCH}/phase4-reset-types.ts`,
  [
    `import type { ZCodeAccountAccess, ZCodeProviderAccountAccess } from "./phase4-access-types.js";`,
    resetTypes,
  ].join("\n"),
);
writeFileSync(
  `${SCRATCH}/phase4-access-types.ts`,
  [
    `import { z } from "zod";`,
    `const nonEmptyString = z.string().trim().min(1);`,
    accountAccessBlock,
  ].join("\n"),
);
writeFileSync(`${SCRATCH}/phase4-cases.ts`, casesTs);
writeFileSync(
  `${SCRATCH}/tsconfig.json`,
  JSON.stringify({
    compilerOptions: {
      strict: true,
      noEmit: true,
      target: "es2022",
      module: "esnext",
      moduleResolution: "bundler",
      skipLibCheck: true,
      noUnusedLocals: false,
    },
    include: ["phase4-*.ts"],
  }, null, 2),
);
execSync(`./node_modules/.bin/tsc -p ${SCRATCH}/tsconfig.json`, { cwd: SCRATCH, stdio: "inherit" });
console.log("tsc type-check OK — all payloads satisfy the reference interfaces");

// ---------------------------------------------------------------- emit fixtures
const fixture = {
  metadata: {
    source:
      "ZCode reference clone packages/shared (v3.14.3, commit 29628c9) — TypeScript interfaces extracted verbatim, payloads TYPE-CHECK validated (tsc --noEmit --strict); accountAccess descriptor validated against zcodeProviderAccountAccessSchema",
    generator:
      "dev-time script (tools/capture/generate-phase4-fixtures.mjs; typescript@5.5.4 + zod@4.6.5 bootstrapped into /tmp scratch)",
    generatedAt: new Date().toISOString(),
    zcodeVersion: "3.14.3",
    sanitization: "synthetic payloads only — no real credentials, content, or traffic (INV-15/16)",
    liveCaptureNote:
      "exact Z.ai field values still ride the Phase-12 canary (the §13 honest-limits note; report §2/§13); these fixtures carry the reference-derived interface shapes meanwhile (the GAP-3/GAP-4 precedent: reference schema grounds the payload, live capture confirms)",
    adrNote:
      "ADR-020 (docs/adr/adr-020-coding-plan-quota-plane.md): the plane rides the usage-stats channel (getEntitlementSnapshot poll + reset words); coding-plan-subscription is the purchase facade and stays Never; useCodingPlanReset is the ONE destructive-adjacent word, typed-confirm gated (T19)",
    timestamps: { baseIso: "2026-10-13T23:20:00Z", baseMs: BASE },
  },
  cases: [
    { kind: "usage-stats-channel", name: "entitlement-full", schema: "UsageEntitlementSnapshot", data: entitlementFull },
    { kind: "usage-stats-channel", name: "entitlement-zai-credit", schema: "UsageEntitlementSnapshot", data: entitlementZaiCredit },
    { kind: "usage-stats-channel", name: "entitlement-no-plan", schema: "UsageEntitlementSnapshot", data: entitlementNoPlan },
    { kind: "coding-plan-reset", name: "reset-status-full", schema: "CodingPlanResetStatusSnapshot", data: resetStatusFull },
    { kind: "coding-plan-reset", name: "reset-status-available", schema: "CodingPlanResetStatusSnapshot", data: resetStatusAvailable },
    { kind: "coding-plan-reset", name: "reset-status-empty", schema: "CodingPlanResetStatusSnapshot", data: resetStatusEmpty },
    { kind: "coding-plan-reset", name: "reset-opportunity-granted", schema: "CodingPlanResetOpportunityResult", data: resetOpportunityGranted },
    { kind: "coding-plan-reset", name: "reset-opportunity-denied", schema: "CodingPlanResetOpportunityResult", data: resetOpportunityDenied },
    { kind: "coding-plan-reset", name: "reset-use-result", schema: "CodingPlanResetUseResult", data: resetUseResult },
    { kind: "request-descriptor", name: "account-access-zai-individual", schema: "ZCodeProviderAccountAccess", data: accountAccessZaiIndividual },
    { kind: "request-descriptor", name: "reset-use-request", schema: "CodingPlanResetUseRequest", data: resetUseRequest },
  ],
};
writeFileSync(OUT, JSON.stringify(fixture, null, 2) + "\n");
console.log(`wrote ${OUT} (${fixture.cases.length} cases)`);
