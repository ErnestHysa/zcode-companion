// Dev-time golden fixture generator for the Phase-3 read-only viewer wire
// surfaces (roadmap rule 5): app usage snapshot (v4-wire.md §12.1), automations
// + off-peak list (§12.2), plugins list (§12.3), skills reference catalog
// (§12.4), mcp server statuses (§12.5). Runs the REFERENCE zod schemas from the
// ZCode reference clone (read-only verbatim text extraction, same discipline as
// generate-phase2-fixtures.mjs) and records VALIDATED payload JSON as golden
// fixtures. Output: fixtures/protocol/phase3-wire-fixtures.json.
// Never shipped, never part of CI deps. zod is bootstrapped into /tmp scratch —
// the reference clone stays untouched (it has no node_modules).
import { writeFileSync, mkdirSync, readFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";

const REPO = "/Users/ernest/Desktop/DEVPROJECTS/zcode/repo";
const OUT = "/Users/ernest/Desktop/DEVPROJECTS/zcode/android-native/fixtures/protocol/phase3-wire-fixtures.json";
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

const proto = readFileSync(`${REPO}/packages/shared/src/zcode-protocol/index.ts`, "utf8");

// Helper schemas the extracted blocks reference — each line/block verbatim from
// its source file (index.ts:84/86, model-selection.ts:4-17,
// zcode-task-mode-schema.ts:6, zcode-protocol-legacy-types.ts:34-39).
const helperBlock = [
  `const nonEmptyString = z.string().trim().min(1);`,
  `const timestampMsSchema = z.number().int().nonnegative();`,
  slice(readFileSync(`${REPO}/packages/shared/src/model-selection.ts`, "utf8"),
    "export const modelSelectionSchema", "  .strict();"),
  slice(readFileSync(`${REPO}/packages/shared/src/zcode-task-mode-schema.ts`, "utf8"),
    "export const zcodeTaskModeSchema", ";"),
  slice(readFileSync(`${REPO}/packages/shared/src/zcode-protocol-legacy-types.ts`, "utf8"),
    "export const zcodeWorkspaceRefSchema", "  .strict();"),
].join("\n");

// GAP-5: app usage snapshot — usage-stats.ts from APP_USAGE_RANGES through the
// AppUsageSnapshot type (self-contained z basics only).
const usageStatsSrc = readFileSync(`${REPO}/packages/shared/src/usage-stats.ts`, "utf8");
const appUsageBlock = slice(usageStatsSrc,
  "export const APP_USAGE_RANGES",
  "export type AppUsageSnapshot = z.infer<typeof appUsageSnapshotSchema>;");

// GAP-6: automations — index.ts scheduleRule + intervalUnit carrier enum, the
// automation protocol schema, and the list params/result (the create/update
// slices are skipped: they drag in bots.ts's botDeliveryTarget schema, which
// the list plane never references).
const automationBlock = [
  slice(proto,
    "export const zcodeAutomationScheduleRuleSchema",
    "export type ZCodeAutomationScheduleRuleProtocol = z.infer<typeof zcodeAutomationScheduleRuleSchema>;"),
  slice(proto,
    "export const zcodeAutomationIntervalUnitSchema",
    "]);"),
  slice(proto,
    "export const zcodeAutomationProtocolSchema",
    "export type ZCodeAutomationProtocol = z.infer<typeof zcodeAutomationProtocolSchema>;"),
  slice(proto,
    "export const zcodeAutomationListParamsSchema",
    "export type ZCodeAutomationListProtocolResult = z.infer<typeof zcodeAutomationListResultSchema>;"),
].join("\n");

// GAP-6: off-peak — permissionMode + create params + task snapshot + create
// result + list params/result (contiguous block).
const offPeakBlock = slice(proto,
  "export const zcodeOffPeakPermissionModeSchema",
  "export type ZCodeOffPeakListProtocolResult = z.infer<typeof zcodeOffPeakListResultSchema>;");

// GAP-7: plugins — the whole "Plugin management" block (option value, scope,
// hook detail, user-config option, component kind/item/group, plugin info,
// diagnostic, list params/result).
const pluginBlock = slice(proto,
  "// ── Plugin management (list + enable/disable) ──",
  "export type ZCodePluginsListResult = z.infer<typeof zcodePluginsListResultSchema>;");

// GAP-8: skills reference catalog — entry + params + result.
const skillsBlock = slice(proto,
  "// ── Skill 对话引用 catalog──",
  "export type ZCodeSkillsReferenceCatalogResult = z.infer<\n  typeof zcodeSkillsReferenceCatalogResultSchema\n>;");

// GAP-9: mcp — failure kinds + status kind enum + status snapshot, then the
// list mode + result schemas (params skipped: it references
// zcodeProtocolMcpServerSchema, a large union irrelevant to the RESULT plane).
const mcpStatusBlock = slice(proto,
  "export const zcodeMcpServerStatusKindSchema",
  "export type ZCodeMcpServerStatusSnapshot = z.infer<typeof zcodeMcpServerStatusSnapshotSchema>;");
const mcpListBlock = [
  slice(proto, "export const zcodeMcpListModeSchema", "export type ZCodeMcpListMode = z.infer<typeof zcodeMcpListModeSchema>;"),
  slice(proto, "export const zcodeMcpListResultSchema", "export type ZCodeMcpListResult = z.infer<typeof zcodeMcpListResultSchema>;"),
].join("\n");

const schemasTs = [
  `import { z } from "zod";`,
  helperBlock,
  appUsageBlock,
  automationBlock,
  offPeakBlock,
  pluginBlock,
  skillsBlock,
  mcpStatusBlock,
  mcpListBlock,
  `export { z };`, // re-exported so the channel-type extensions below use the SAME zod instance
].join("\n");
mkdirSync(SCRATCH, { recursive: true });
writeFileSync(`${SCRATCH}/phase3-schemas.ts`, schemasTs);

const S = await import(`${SCRATCH}/phase3-schemas.ts`);
const {
  z,
  appUsageSnapshotSchema,
  zcodeAutomationProtocolSchema,
  zcodeAutomationListResultSchema,
  zcodeOffPeakTaskSnapshotSchema,
  zcodeOffPeakListResultSchema,
  zcodePluginsListResultSchema,
  zcodeSkillsReferenceCatalogResultSchema,
  zcodeMcpListResultSchema,
} = S;

// Channel-payload extensions: the phone calls the zcode-agent / off-peak-task
// CHANNEL methods, whose result items are the desktop types (automation-types.ts:65-113,
// off-peak-types.ts:102-165) — the protocol schemas above plus the channel-only
// fields cited there. Extending the VERBATIM schema keeps every protocol field's
// validation reference-derived; the extension fields mirror the desktop TS
// interfaces field-for-field. serverTicketId is deliberately NOT modeled
// (zcodeAgentService.ts toProtocolOffPeakTaskSnapshot: "不暴露 serverTicketId").
const automationChannelItemSchema = zcodeAutomationProtocolSchema.extend({
  workspaceKey: z.string(),
  workspacePath: z.string(),
  workspaceIdentity: z.string().optional(),
  locationKind: z.enum(["local", "remote"]),
  endAt: z.number().optional(),
  scheduleEditedByUser: z.boolean().optional(),
  dispatchStatus: z.enum(["claimed", "dispatched", "failed_to_dispatch", "skipped"]),
  dispatchAttempts: z.number().int().nonnegative(),
  retryAt: z.number().optional(),
  lastError: z.string().optional(),
  createdAt: z.number(),
  updatedAt: z.number(),
});
const automationListChannelSchema = z.object({ automations: z.array(automationChannelItemSchema) }).strict();

const offPeakChannelItemSchema = zcodeOffPeakTaskSnapshotSchema.extend({
  workspaceKey: z.string(),
  workspacePath: z.string(),
  workspaceIdentity: z.string().optional(),
  prompt: z.string(),
  permissionMode: z.enum(["build", "edit", "plan", "yolo"]),
  queuedAt: z.number().int().nonnegative(),
  startedAt: z.number().int().nonnegative().optional(),
  endedAt: z.number().int().nonnegative().optional(),
  failureReason: z.string().optional(),
  filesChanged: z.number().int().nonnegative().optional(),
  schedulable: z.boolean().optional(),
  queuePosition: z.number().int().positive().optional(),
});
const offPeakListChannelSchema = z.object({ tasks: z.array(offPeakChannelItemSchema) }).strict();

// ---------------------------------------------------------------- cases
// Every case's `data` is parse-validated by the REFERENCE(-extended) schema
// before it is written — a golden fixture that its own source-of-truth schema
// rejects cannot exist.
const cases = [];
function add(kind, name, schema, data) {
  schema.parse(data); // generator FAILS if the reference schema rejects the payload
  cases.push({ kind, name, schema: schema.description || name, data });
}

// --- GAP-5: app usage snapshot (v4/usage/stats result) ------------------------
add("usage-stats", "app-usage-full", appUsageSnapshotSchema, {
  range: "7d",
  generatedAt: 1791440000000,
  timeZone: "Europe/London",
  source: "agent-db",
  summary: {
    totalTokens: 184600,
    inputTokens: 120000,
    outputTokens: 42000,
    reasoningTokens: 8600,
    cacheCreationTokens: 3200,
    cacheReadTokens: 15600,
    cacheHitRate: 0.42,
    totalSessions: 14,
    totalTurns: 96,
    toolCallCount: 210,
    toolErrorRate: 0.05,
    modelErrorRate: 0.02,
    avgTimeToFirstTokenMs: 1420,
    avgTurnDurationMs: 41000,
    activeDays: 5,
    currentStreakDays: 3,
    longestSessionMs: 5400000,
    longestStreakDays: 9,
    peakDayTokens: 51200,
    favoriteModel: { modelId: "glm-5.3", totalTokens: 114452, share: 0.62 },
  },
  heatmap: {
    startDate: "2026-09-28",
    endDate: "2026-10-11",
    maxTokens: 51200,
    weeks: [
      {
        weekIndex: 0,
        days: [
          { date: "2026-09-28", level: 1, totalTokens: 8200, turnCount: 6, toolCallCount: 12 },
          { date: "2026-09-29", level: 3, totalTokens: 30100, turnCount: 14, toolCallCount: 48 },
          null, null, null, null, null,
        ],
      },
      {
        weekIndex: 1,
        days: [
          { date: "2026-10-05", level: 4, totalTokens: 51200, turnCount: 22, toolCallCount: 77 },
          { date: "2026-10-06", level: 2, totalTokens: 15400, turnCount: 9, toolCallCount: 30 },
          null, null, null, null, null,
        ],
      },
    ],
  },
  dailyModelUsage: [
    {
      date: "2026-10-05",
      models: [
        { modelId: "glm-5.3", totalTokens: 38400 },
        { modelId: "glm-5.3-flash", totalTokens: 12800 },
      ],
    },
    {
      date: "2026-10-06",
      models: [
        { modelId: "glm-5.3", totalTokens: 11500 },
        { modelId: null, totalTokens: 3900 },
      ],
    },
  ],
  models: [
    { modelId: "glm-5.3", totalTokens: 114452, inputTokens: 74000, outputTokens: 32000, requestCount: 420, share: 0.62 },
    { modelId: "glm-5.3-flash", totalTokens: 70148, inputTokens: 46000, outputTokens: 10000, requestCount: 260, share: 0.38 },
  ],
  tools: [
    { toolName: "Bash", callCount: 120, errorCount: 6, errorRate: 0.05, avgDurationMs: 3200 },
    { toolName: "Read", callCount: 90, errorCount: 0, errorRate: 0, avgDurationMs: 210 },
  ],
});
add("usage-stats", "app-usage-zero", appUsageSnapshotSchema, {
  range: "all",
  generatedAt: 1791440000000,
  timeZone: "Europe/London",
  source: "agent-db",
  summary: {
    totalTokens: 0,
    inputTokens: 0,
    outputTokens: 0,
    reasoningTokens: 0,
    cacheCreationTokens: 0,
    cacheReadTokens: 0,
    cacheHitRate: 0,
    totalSessions: 0,
    totalTurns: 0,
    toolCallCount: 0,
    toolErrorRate: 0,
    modelErrorRate: 0,
    avgTimeToFirstTokenMs: null,
    avgTurnDurationMs: null,
    activeDays: 0,
    currentStreakDays: 0,
    longestSessionMs: 0,
    longestStreakDays: 0,
    peakDayTokens: 0,
    favoriteModel: null,
  },
  heatmap: { startDate: null, endDate: null, maxTokens: 0, weeks: [] },
  dailyModelUsage: [],
  models: [],
  tools: [],
});

// --- GAP-6: automations (zcode-agent listAutomations channel payload) ---------
{
  const automations = [
    {
      automationId: "auto-1",
      title: "Nightly dependency review",
      cronExpr: "30 2 * * *",
      prompt: "Review yesterday's dependency changes and summarize risks.",
      modelSelection: { providerId: "zai", modelId: "glm-5.3", options: { reasoningLevel: "high" } },
      mode: "build",
      targetTaskId: "task-77",
      enabled: true,
      lifecycleStatus: "active",
      nextRunAt: 1791500000000,
      lastRunAt: 1791410000000,
      runCount: 12,
      recurring: true,
      scheduleRule: { unit: "daily", interval: 1, hour: 2, minute: 30, anchorAt: 1791000000000 },
      workspaceKey: "/synthetic/ws",
      workspacePath: "/synthetic/ws",
      workspaceIdentity: "synth-ws-identity-key",
      locationKind: "local",
      dispatchStatus: "dispatched",
      dispatchAttempts: 1,
      createdAt: 1790000000000,
      updatedAt: 1791410000000,
    },
    {
      automationId: "auto-2",
      title: "Weekly report",
      cronExpr: "0 9 * * 1",
      prompt: "Draft the weekly summary.",
      enabled: false,
      lifecycleStatus: "paused",
      runCount: 3,
      recurring: true,
      maxRuns: 20,
      scheduleRule: { unit: "weekly", interval: 1, hour: 9, minute: 0, anchorAt: 1790000000000, weekdays: [1] },
      workspaceKey: "/synthetic/ws",
      workspacePath: "/synthetic/ws",
      locationKind: "remote",
      scheduleEditedByUser: true,
      dispatchStatus: "skipped",
      dispatchAttempts: 0,
      lastError: "disabled by user",
      createdAt: 1789000000000,
      updatedAt: 1791300000000,
    },
    {
      automationId: "auto-3",
      title: "One-shot backup",
      cronExpr: "0 12 1 1 *",
      prompt: "Run the backup script.",
      enabled: true,
      lifecycleStatus: "completed",
      runCount: 1,
      recurring: false,
      maxRuns: 1,
      workspaceKey: "/synthetic/ws",
      workspacePath: "/synthetic/ws",
      locationKind: "local",
      dispatchStatus: "dispatched",
      dispatchAttempts: 1,
      retryAt: 1791400000000,
      createdAt: 1788000000000,
      updatedAt: 1791400000000,
    },
  ];
  add("automation-list", "automation-list-mixed", automationListChannelSchema, { automations });
  // The CLI-plane word automation/list responds with the PROTOCOL face (toProtocolAutomation)
  // — pin the protocol schema against the SAME items with channel-only fields stripped.
  zcodeAutomationListResultSchema.parse({
    automations: automations.map(({ workspaceKey, workspacePath, workspaceIdentity, locationKind,
      endAt, scheduleEditedByUser, dispatchStatus, dispatchAttempts, retryAt, lastError, createdAt, updatedAt, ...protocolFace }) => protocolFace),
  });
  add("automation-list", "automation-list-empty", automationListChannelSchema, { automations: [] });
}

// --- GAP-6: off-peak tasks (off-peak-task channel list payload) ----------------
{
  const tasks = [
    {
      offPeakTaskId: "opt-1",
      title: "Idle-time refactor",
      status: "queued",
      queuePosition: 3,
      createdAt: 1791440000000,
      prompt: "Refactor the utils package.",
      permissionMode: "build",
      workspaceKey: "/synthetic/ws",
      workspacePath: "/synthetic/ws",
      workspaceIdentity: "synth-ws-identity-key",
      queuedAt: 1791440000000,
    },
    {
      offPeakTaskId: "opt-2",
      title: "Nightly docs",
      status: "running",
      sessionId: "sess-9",
      createdAt: 1791400000000,
      prompt: "Regenerate API docs.",
      permissionMode: "yolo",
      workspaceKey: "/synthetic/ws",
      workspacePath: "/synthetic/ws",
      queuedAt: 1791400000000,
      startedAt: 1791440100000,
      schedulable: true,
    },
    {
      offPeakTaskId: "opt-4",
      title: "Broken sweep",
      status: "failed",
      createdAt: 1791200000000,
      prompt: "Sweep TODOs and file issues.",
      permissionMode: "plan",
      workspaceKey: "/synthetic/ws",
      workspacePath: "/synthetic/ws",
      queuedAt: 1791200000000,
      endedAt: 1791200600000,
      failureReason: "workspace unavailable",
    },
    {
      // Different workspace — the phone-side filter (zcodeAgentService.ts:2666-2671
      // mirror) must drop this row for the /synthetic/ws view.
      offPeakTaskId: "opt-3",
      title: "Other-workspace sweep",
      status: "completed",
      sessionId: "sess-5",
      createdAt: 1791000000000,
      prompt: "Sweep TODOs.",
      permissionMode: "plan",
      workspaceKey: "/other/ws",
      workspacePath: "/other/ws",
      queuedAt: 1791000000000,
      startedAt: 1791000100000,
      endedAt: 1791003600000,
      filesChanged: 7,
    },
  ];
  add("offpeak-list", "offpeak-list-mixed", offPeakListChannelSchema, { tasks });
  // Protocol minimal face (offPeak/list word) validated against the same items
  // projected down (toProtocolOffPeakTaskSnapshot shape).
  zcodeOffPeakListResultSchema.parse({
    tasks: tasks.map((t) => ({
      offPeakTaskId: t.offPeakTaskId,
      title: t.title,
      status: t.status,
      ...(typeof t.queuePosition === "number" && t.queuePosition > 0 ? { queuePosition: t.queuePosition } : {}),
      ...(t.sessionId ? { sessionId: t.sessionId } : {}),
      createdAt: t.createdAt,
    })),
  });
  add("offpeak-list", "offpeak-list-empty", offPeakListChannelSchema, { tasks: [] });
}

// --- GAP-7: plugins list (plugins/list result) ---------------------------------
add("plugins-list", "plugins-list-mixed", zcodePluginsListResultSchema, {
  plugins: [
    {
      id: "com.example.commit-aid",
      name: "commit-aid",
      description: "Conventional-commit helper",
      version: "1.4.0",
      enabled: true,
      source: "marketplace",
      marketplace: "example",
      author: "Example Labs",
      homepage: "https://example.com/commit-aid",
      skillCount: 2,
      skillRootCount: 1,
      commandRootCount: 3,
      components: [
        { kind: "command", items: [{ name: "commit", description: "Draft a conventional commit" }] },
        { kind: "skill", items: [{ name: "commit-style" }, { name: "release-notes", description: "Release note templates" }] },
      ],
      declaredMcpServerNames: ["git-extra"],
      hostMcpServerNames: ["git-extra"],
      mcpServerNames: ["git-extra"],
      rootPath: "/plugins/commit-aid",
      userConfig: { style: { type: "string", default: "conventional", description: "Commit style" } },
      configuredOptions: { style: "conventional" },
      rootSource: "user",
      enabledSource: "user",
    },
    {
      id: "builtin.core",
      name: "core",
      version: "3.14.3",
      enabled: false,
      source: "builtin",
      marketplace: "builtin",
      skillRootCount: 0,
      commandRootCount: 0,
      mcpServerNames: [],
      rootPath: "/builtin/core",
    },
  ],
  diagnostics: [
    { code: "manifest.missing_description", message: "Plugin 'core' declares no description.", severity: "warning", pluginId: "builtin.core" },
  ],
});
add("plugins-list", "plugins-list-empty", zcodePluginsListResultSchema, { plugins: [], diagnostics: [] });

// --- GAP-8: skills reference catalog (skills/referenceCatalog result) ----------
add("skills-catalog", "skills-catalog-mixed", zcodeSkillsReferenceCatalogResultSchema, {
  authority: "workspace",
  skills: [
    { id: "skill-1", name: "commit-helper", description: "Drafts conventional commits.", path: "/synthetic/ws/.zcode/skills/commit-helper/SKILL.md", scope: "workspace", enabled: true },
    { id: "skill-2", name: "release-notes", description: "Release note scaffolding.", path: "/home/u/.zcode/skills/release-notes/SKILL.md", scope: "user", enabled: true },
    { id: "skill-3", name: "git-extra", description: "Advanced git flows.", path: "/plugins/commit-aid/skills/git-extra/SKILL.md", scope: "plugin", enabled: true, pluginName: "commit-aid" },
  ],
});
add("skills-catalog", "skills-catalog-empty", zcodeSkillsReferenceCatalogResultSchema, {
  authority: "workspace",
  skills: [],
});

// --- GAP-9: mcp server statuses (mcp/list result) -------------------------------
add("mcp-status", "mcp-status-mixed", zcodeMcpListResultSchema, {
  statuses: {
    "context7": { status: "connected", transport: "stdio", toolCount: 8, updatedAt: "2026-10-07T09:12:00.000Z", protocolEra: "modern" },
    "search": { status: "failed", transport: "http", toolCount: 0, updatedAt: "2026-10-07T09:00:00.000Z", error: "connect timeout after 10s", failureKind: "connection_timeout" },
    "docs": { status: "disabled", transport: "sse", toolCount: 0, updatedAt: "2026-10-06T18:40:00.000Z" },
    "internal-api": { status: "untrusted", transport: "stdio", toolCount: 0, updatedAt: "2026-10-07T08:00:00.000Z", failureKind: "official_origin_untrusted" },
  },
});
add("mcp-status", "mcp-status-empty", zcodeMcpListResultSchema, { statuses: {} });

mkdirSync(`${SCRATCH}/out`, { recursive: true });
writeFileSync(
  OUT,
  JSON.stringify(
    {
      metadata: {
        source: "ZCode reference clone packages/shared (v3.14.3, commit 29628c9) — schemas extracted verbatim, payloads parse-validated",
        generator: "dev-time script (tools/capture/generate-phase3-fixtures.mjs; zod@4.6.5 bootstrapped into /tmp scratch)",
        generatedAt: new Date().toISOString(),
        zcodeVersion: "3.14.3",
        sanitization: "synthetic payloads only — no real credentials, content, or traffic (INV-15/16)",
        liveCaptureNote:
          "all five surfaces are one-shot R reads; live-channel authorization over the production relay rides the Phase-12 manual canary (phase9-channels.md terminal precedent) — MockZCode implements the verified surface with scriptable refusal scenarios",
        channelTypeExtensionNote:
          "automation/off-peak channel payloads are the desktop types (automation-types.ts:65-113, off-peak-types.ts:102-165) = the verbatim protocol schemas extended with the cited channel-only fields; serverTicketId deliberately never modeled (toProtocolOffPeakTaskSnapshot: 不暴露 serverTicketId)",
      },
      cases,
    },
    null,
    2,
  ) + "\n",
);
console.log(`wrote ${cases.length} phase-3 fixtures -> ${OUT}`);
