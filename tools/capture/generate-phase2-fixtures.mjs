// Dev-time golden fixture generator for the Phase-2 wire surfaces (roadmap rule 5):
// usage state key (v4-wire.md §11.1), workspace-config topic (§11.2), editUserQuery
// payload + churn ops (§11.4). Runs the REFERENCE zod schemas from the ZCode
// reference clone (read-only text extraction, same discipline as
// generate-golden-vectors.mjs) and records VALIDATED payload JSON as golden
// fixtures. Output: fixtures/protocol/phase2-wire-fixtures.json.
// Never shipped, never part of CI deps. zod is bootstrapped into /tmp scratch —
// the reference clone stays untouched (it has no node_modules).
import { writeFileSync, mkdirSync, readFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";

// protocol-verify (T2) (§14.2 T2): the CI verify pipeline regenerates fixtures from an ARBITRARY
// open-repo checkout — the env override points the generator at it. Default keeps
// the pinned reference clone (dev-time behavior unchanged).
const REPO = process.env.ZCODE_REFERENCE_REPO ?? "/Users/ernest/Desktop/DEVPROJECTS/zcode/repo";
const OUT = process.env.ZCODE_FIXTURES_OUT ??
  "/Users/ernest/Desktop/DEVPROJECTS/zcode/android-native/fixtures/protocol/phase2-wire-fixtures.json";
const SCRATCH = "/tmp/fixture-gen";

// ---------------------------------------------------------------- zod bootstrap
if (!existsSync(`${SCRATCH}/node_modules/zod`)) {
  mkdirSync(SCRATCH, { recursive: true });
  execSync(`npm install --no-save --silent zod@4.6.5`, { cwd: SCRATCH, stdio: "inherit" });
}

// ---------------------------------------------------------------- verbatim extraction
// Text is extracted from the pinned reference sources between stable markers —
// never hand-copied — so a clone update that changes a schema CHANGES the fixture
// generator input (regenerate, never patch by hand).
function slice(src, startMarker, endMarker) {
  const start = src.indexOf(startMarker);
  if (start < 0) throw new Error(`marker not found: ${startMarker.slice(0, 60)}`);
  const end = src.indexOf(endMarker, start);
  if (end < 0) throw new Error(`end marker not found: ${endMarker.slice(0, 60)}`);
  return src.slice(start, end + endMarker.length);
}

const legacy = readFileSync(`${REPO}/packages/shared/src/zcode-protocol-legacy-types.ts`, "utf8");
const legacyBlock = [
  `const nonEmptyString = z.string().trim().min(1);`, // legacy-types.ts:11 (verbatim line)
  // legacy-types.ts:462-508 contiguous verbatim: cache usage, breakdown source
  // enum, breakdown item/array, session context usage — up to the next export.
  slice(legacy, "export const zcodeSessionContextCacheUsageSchema", "export const zcodeSessionRuntimeStateSchema = z"),
].join("\n");

const snapshot = readFileSync(`${REPO}/packages/shared/src/zcode-protocol-v4/snapshot.ts`, "utf8");
const usageBlock = slice(
  snapshot,
  "// ── usage。conflation：值未变不下发──",
  "export type SessionUsageState = z.infer<typeof sessionUsageStateSchema>;",
);

const workspaceConfigSrc = readFileSync(`${REPO}/packages/shared/src/zcode-protocol-v4/workspace-config.ts`, "utf8");
const workspaceConfigBlock = workspaceConfigSrc
  .replace(/^import \{ z \} from "zod";$/m, "")
  .replace(/^\/\//gm, "//"); // comment lines stay verbatim

const core = readFileSync(`${REPO}/packages/shared/src/zcode-protocol-v4/core.ts`, "utf8");
// Slice ends at the schema's own `.strict();` — including the trailing
// `export type …` line would leave a dangling type alias after extraction.
const coreBlock = slice(core, "export const conversationRowTargetSchema", "  .strict();");

const attachmentRefSrc = readFileSync(`${REPO}/packages/shared/src/zcode-protocol-v4/attachment-ref.ts`, "utf8");
const attachmentRefBlock = attachmentRefSrc.replace(/^import \{ z \} from "zod";$/m, "");

const command = readFileSync(`${REPO}/packages/shared/src/zcode-protocol-v4/command.ts`, "utf8");
const editUserQuerySlice = slice(command, "  editUserQuery: z.object({", "  }),");
const editUserQueryBlock = `const commandPayloadSchemas = z.object({\n${editUserQuerySlice}\n});\nexport const editUserQuerySchema = commandPayloadSchemas.shape.editUserQuery;`;

const delta = readFileSync(`${REPO}/packages/shared/src/zcode-protocol-v4/delta.ts`, "utf8");
const m = /z\.object\(\{ op: z\.literal\("row\.removed"\), fromRowId: z\.number\(\) \}\)/.exec(delta);
if (!m) throw new Error("row.removed op line not found in delta.ts");
const rowRemovedBlock = `export const rowRemovedOpSchema = ${m[0]};`;

const schemasTs = [
  `import { z } from "zod";`,
  legacyBlock,
  usageBlock,
  workspaceConfigBlock,
  coreBlock,
  attachmentRefBlock,
  editUserQueryBlock,
  rowRemovedBlock,
].join("\n");
mkdirSync(SCRATCH, { recursive: true });
writeFileSync(`${SCRATCH}/phase2-schemas.ts`, schemasTs);

const S = await import(`${SCRATCH}/phase2-schemas.ts`);
const {
  sessionUsageStateSchema,
  workspaceConfigSnapshotSchema,
  workspaceConfigDeltaSchema,
  editUserQuerySchema,
  rowRemovedOpSchema,
} = S;

// ---------------------------------------------------------------- cases
// Every case's `data` is parse-validated by the REFERENCE schema before it is
// written — a golden fixture that its own source-of-truth schema rejects cannot
// exist. Rows inside the edit-churn ops are validated by the Android typed row
// decoder under test (ConversationRowDecoderTest pins those shapes); only the
// row.removed op schema is reference-validated here (extracting rows.ts would
// drag the workflow-row-meta closure into this generator for zero extra pin).
const cases = [];
function add(kind, name, schema, data) {
  schema.parse(data); // generator FAILS if the reference schema rejects the payload
  cases.push({ kind, name, schema: schema.description || String(schema._def?.typeName || name), data });
}

// --- GAP-1/GAP-2: usage state key -------------------------------------------
add("usage-state", "usage-full", sessionUsageStateSchema, {
  contextWindow: {
    usedTokens: 148234,
    maxTokens: 1000000,
    autoCompactThresholdTokens: 800000,
    cache: {
      inputTokens: 142000,
      cacheReadTokens: 118000,
      cacheWriteTokens: 2400,
      latestHitRate: 0.83,
      hitRateRequestCount: 12,
      totalInputTokens: 1704000,
      totalCacheReadTokens: 1416000,
      totalCacheWriteTokens: 28800,
      hitRate: 0.81,
    },
    breakdown: [
      { source: "system_prompt", chars: 12000 },
      { source: "meta_user_context", chars: 3400 },
      { source: "skills", chars: 8100 },
      { source: "tool_prompt", chars: 5400 },
      { source: "system_tool_schemas", chars: 9200 },
      { source: "mcp_tool_schemas", chars: 4500 },
      { source: "messages", chars: 106034 },
    ],
  },
  cumulative: { inputTokens: 1704000, outputTokens: 42300, cacheReadTokens: 1416000, cacheWriteTokens: 28800 },
});
add("usage-state", "usage-null-window", sessionUsageStateSchema, {
  contextWindow: null,
  cumulative: { inputTokens: 4200, outputTokens: 380, cacheReadTokens: 0, cacheWriteTokens: 0 },
});
add("usage-state", "usage-minimal", sessionUsageStateSchema, {
  contextWindow: { usedTokens: 500, maxTokens: 200000, autoCompactThresholdTokens: null },
  cumulative: { inputTokens: 500, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
});

// --- GAP-12: workspace-config topic ------------------------------------------
add("workspace-config", "workspace-config-snapshot", workspaceConfigSnapshotSchema, {
  protocolVersion: 1,
  workspaceId: "synth-ws-identity-key",
  logEpoch: "cfg-epoch-1",
  config: {
    configOptions: [
      {
        id: "model",
        name: "Model",
        description: "Which model answers this workspace",
        category: "model",
        type: "select",
        currentValue: "glm-5.3",
        options: [
          {
            value: "glm-5.3",
            name: "GLM-5.3",
            description: "Flagship reasoning",
            origin: "native",
            modelProviderId: "zai",
            modelProviderName: "Z.ai",
            modelThoughtLevels: ["high", "low"],
            modelDefaultThoughtLevel: "high",
          },
          {
            value: "glm-5.3-flash",
            name: "GLM-5.3 Flash",
            origin: "native",
            modelProviderId: "zai",
            modelProviderName: "Z.ai",
            modelThoughtLevels: [],
          },
        ],
      },
      {
        id: "mode",
        name: "Collaboration mode",
        type: "select",
        currentValue: "build",
        options: [
          { value: "build", name: "Build" },
          { value: "plan", name: "Plan" },
        ],
      },
    ],
    slashCommands: [
      { name: "compact", description: "Compact the current conversation with optional instructions.", inputHint: "[instructions]", source: "builtin" },
      { name: "goal", description: "Show or set the current session goal.", inputHint: "[pause|resume|clear|replace <objective>|<objective>]", source: "builtin" },
      { name: "deploy", description: "Run the workspace deploy script.", inputHint: "<env>", source: "custom" },
      { name: "review-pr", description: "Review the open pull request.", source: "custom" },
      { name: "help", description: "Show this slash command help." },
    ],
  },
});
add("workspace-config", "workspace-config-delta", workspaceConfigDeltaSchema, {
  op: "config.updated",
  config: {
    configOptions: [],
    slashCommands: [
      { name: "compact", description: "Compact the current conversation with optional instructions.", inputHint: "[instructions]", source: "builtin" },
      { name: "goal", description: "Show or set the current session goal.", inputHint: "[pause|resume|clear|replace <objective>|<objective>]", source: "builtin" },
    ],
  },
});

// --- GAP-10: editUserQuery payload + churn ops --------------------------------
add("edituserquery", "edituserquery-payload", editUserQuerySchema, {
  target: { rowId: 42, entityId: "ent-user-row-42" },
  newText: "edited question text",
  workspaceMode: "preserve",
});
{
  const removed = { op: "row.removed", fromRowId: 41 };
  rowRemovedOpSchema.parse(removed); // reference-validated op shape
  // Replacement-turn rows: shapes pinned by the Android typed decoder under test
  // (ConversationRowDecoderTest) — turnHeader + the edited userInput, then the
  // desktop-observed re-run of the assistant turn.
  const turnHeader = {
    rowId: 50,
    turnId: "turn-50",
    kind: "turnHeader",
    entityId: "ent-turn-50",
    createdAt: 1791000000000,
    createdAtSeq: 800,
    origin: "userInput",
    state: "running",
    startedAt: 1791000000000,
  };
  const userInput = {
    rowId: 51,
    turnId: "turn-50",
    kind: "userInput",
    entityId: "ent-user-row-50",
    createdAt: 1791000000100,
    createdAtSeq: 801,
    text: "edited question text",
    origin: "realUser",
    guided: false,
  };
  cases.push({
    kind: "edit-churn",
    name: "edit-churn-ops",
    schema: "rowRemovedOpSchema(op 0 verbatim-validated); rows via Kotlin typed decoder",
    data: [
      removed,
      { op: "row.appended", row: turnHeader },
      { op: "row.appended", row: userInput },
    ],
  });
}

mkdirSync(`${SCRATCH}/out`, { recursive: true });
writeFileSync(
  OUT,
  JSON.stringify(
    {
      metadata: {
        source: "ZCode reference clone packages/shared (v3.14.3, commit 29628c9) — schemas extracted verbatim, payloads parse-validated",
        generator: "dev-time script (tools/capture/generate-phase2-fixtures.mjs; zod@4.6.5 bootstrapped into /tmp scratch)",
        generatedAt: new Date().toISOString(),
        zcodeVersion: "3.14.3",
        sanitization: "synthetic payloads only — no real credentials, content, or traffic (INV-15/16)",
        liveCaptureNote:
          "usage-key live-traffic confirmation rides the Phase-12 canary (fixtures/relay/live-subscribe-16.fixture.json proves the snapshot-frame mechanism; its rpc-frame content was destroyed by sanitization and cannot ground a payload shape)",
      },
      cases,
    },
    null,
    2,
  ) + "\n",
);
console.log(`wrote ${cases.length} phase-2 fixtures -> ${OUT}`);
