// AAP-1 drift-tool tests (android-gaps Phase 5). Run: node --test tools/drift/surface.test.mjs
//
// Hermetic: builds a synthetic shared-root (packages/shared/src shape) in a tmpdir
// whose files carry the same structural markers the extractor greps in the real
// open repo (as-const string maps, z.enum, Schema-named exports, PROTOCOL_V4_LIMITS
// style const objects, BUILTIN_ slash-entry arrays). Golden expectations below are
// hand-pinned — a different-but-plausible extraction must fail them.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { extractSurface, diffSurfaces } from "./drift-lib.mjs";

const MANIFEST = {
  consumed: {
    "schemas/zcode-protocol-v4/*": true,
    "schemas/usage-quota.ts": true,
    "schemas/usage-stats.ts": false,
    "schemas/automation-types.ts": true,
    "schemas/zcode-slash-command-help.ts": true,
    "schemas/zcode-task-mode-schema.ts": true,
    "slash/compact": true,
    "slash/goal": true,
    "modes/*": true,
    "limits/*": true,
    "channels/*": false,
  },
};

function writeFixture(dir, files) {
  for (const [rel, text] of Object.entries(files)) {
    const p = join(dir, rel);
    mkdirSync(join(p, ".."), { recursive: true });
    writeFileSync(p, text);
  }
}

function makeFixture() {
  const root = mkdtempSync(join(tmpdir(), "drift-fixture-"));
  writeFixture(root, {
    "channels.ts": `export const ServiceChannels = {
  /** git 服务 */
  File: "file",
  UsageStats: "usage-stats",
} as const;
export const PlatformChannels = {
  About: "about",
} as const;
`,
    "zcode-task-mode-schema.ts": `import { z } from "zod";
export const zcodeTaskModeSchema = z.enum(["yolo", "plan"]);
`,
    "zcode-slash-command-help.ts": `export type BuiltinZCodeSlashCommandHelpEntry = {
  name: string;
  usage: string;
};
export const BUILTIN_ZCODE_SLASH_COMMAND_HELP_ENTRIES = [
  { name: "help", usage: "/help [command]" },
  {
    name: "compact",
    usage:
      "/compact [instructions]",
  },
];
`,
    "usage-quota.ts": `import { z } from "zod";
export const MCP_USAGE_QUOTA_LIMIT_TYPE = "MCP_USAGE_LIMIT";
export const usageQuotaLimitSchema = z.object({ percentage: z.number() });
export type UsageQuotaLimit = { percentage: number };
`,
    "usage-stats.ts": `import { z } from "zod";
export const appUsageSummarySchema = z.object({ tokens: z.number() });
`,
    "automation-types.ts": `export const AUTOMATION_CREATE_LIMIT = 20;
export type AutomationRecord = { id: string };
`,
    "zcode-protocol-v4/core.ts": `import { z } from "zod";
export const V4_WIRE_PROTOCOL_VERSION = 3;
export const PROTOCOL_V4_LIMITS = {
  maxFrameBytes: 1024 * 1024,
  assemblyTimeoutMs: 30_000,
  transportIdMaxChars: 256,
} as const;
export const coreEnvelopeSchema = z.object({
  type: z.literal("data"),
});
export type CoreEnvelope = {
  type: "data";
};
`,
    "zcode-protocol-v4/snapshot.ts": `import { z } from "zod";
export const MAX_PERMISSION_FEEDBACK_CHARS = 4_096;
export const sessionSnapshotSchema = z.object({ revision: z.number() });
export interface SessionSnapshot { revision: number }
`,
  });
  return root;
}

const GOLDEN_LAYERS = {
  channels: {
    entries: {
      "ServiceChannels.File": "file",
      "ServiceChannels.UsageStats": "usage-stats",
      "PlatformChannels.About": "about",
    },
  },
  enums: {
    entries: { zcodeTaskModeSchema: ["yolo", "plan"] },
  },
  slash: {
    entries: { help: "/help [command]", compact: "/compact [instructions]" },
  },
  schemas: {
    entries: {
      "automation-types.ts": ["AutomationRecord"],
      "usage-quota.ts": ["UsageQuotaLimit", "usageQuotaLimitSchema"],
      "usage-stats.ts": ["appUsageSummarySchema"],
      "zcode-protocol-v4/core.ts": ["CoreEnvelope", "coreEnvelopeSchema"],
      "zcode-protocol-v4/snapshot.ts": ["SessionSnapshot", "sessionSnapshotSchema"],
      "zcode-slash-command-help.ts": ["BuiltinZCodeSlashCommandHelpEntry"],
      "zcode-task-mode-schema.ts": ["zcodeTaskModeSchema"],
    },
  },
  limits: {
    entries: {
      "AUTOMATION_CREATE_LIMIT": "20",
      "MAX_PERMISSION_FEEDBACK_CHARS": "4096",
      "MCP_USAGE_QUOTA_LIMIT_TYPE": "MCP_USAGE_LIMIT",
      "PROTOCOL_V4_LIMITS.assemblyTimeoutMs": "30000",
      "PROTOCOL_V4_LIMITS.maxFrameBytes": "1024*1024",
      "PROTOCOL_V4_LIMITS.transportIdMaxChars": "256",
      "V4_WIRE_PROTOCOL_VERSION": "3",
    },
  },
};

function extractFixture(root) {
  return extractSurface({ sharedRoot: root, manifest: MANIFEST });
}

test("extraction golden: synthetic shared-root extracts the pinned surface", () => {
  const root = makeFixture();
  try {
    const inv = extractFixture(root);
    assert.equal(inv.schemaVersion, 1);
    assert.equal(inv.kind, "surface-inventory");
    assert.deepEqual(inv.layers, GOLDEN_LAYERS);
    // digests cover every scanned file and are sha-256 shaped
    const expectedFiles = Object.keys(GOLDEN_LAYERS.schemas.entries).concat("channels.ts");
    assert.deepEqual(Object.keys(inv.meta.digests).sort(), expectedFiles.sort());
    for (const d of Object.values(inv.meta.digests)) assert.match(d, /^[0-9a-f]{64}$/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("extraction is deterministic (byte-identical, timestamps aside)", () => {
  const root = makeFixture();
  try {
    const a = extractFixture(root);
    const b = extractFixture(root);
    const strip = (x) => JSON.stringify({ layers: x.layers, meta: x.meta });
    assert.equal(strip(a), strip(b));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("no-drift: diff of identical extractions is empty ADDITIVE with zero items", () => {
  const root = makeFixture();
  try {
    const inv = extractFixture(root);
    const report = diffSurfaces({ baseline: inv, current: extractFixture(root), manifest: MANIFEST });
    assert.equal(report.verdict, "ADDITIVE");
    assert.equal(report.summary.added, 0);
    assert.equal(report.summary.changed, 0);
    assert.equal(report.summary.removed, 0);
    assert.equal(report.summary.breaking, 0);
    assert.deepEqual(report.items, []);
    assert.equal(report.reviewRecommended, false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("seeded synthetic change produces the exact added/changed/removed entries (golden)", () => {
  const root = makeFixture();
  try {
    const baseline = extractFixture(root);
    // Seed six drift kinds across five layers:
    writeFixture(root, {
      // (1) added channel entry — additive, unconsumed
      "channels.ts": `export const ServiceChannels = {
  File: "file",
  UsageStats: "usage-stats",
  Bots: "bots",
} as const;
export const PlatformChannels = {
  About: "about",
} as const;
`,
      // (2) changed limit value — BREAKING (limits are consumed geometry)
      "zcode-protocol-v4/core.ts": `import { z } from "zod";
export const V4_WIRE_PROTOCOL_VERSION = 3;
export const PROTOCOL_V4_LIMITS = {
  maxFrameBytes: 2048 * 1024,
  assemblyTimeoutMs: 30_000,
  transportIdMaxChars: 256,
} as const;
export const coreEnvelopeSchema = z.object({
  type: z.literal("data"),
});
export type CoreEnvelope = {
  type: "data";
};
`,
      // (3) removed schema + type from a consumed file — BREAKING ×2
      "zcode-protocol-v4/snapshot.ts": `import { z } from "zod";
export const MAX_PERMISSION_FEEDBACK_CHARS = 4_096;
`,
      // (4) added enum member — additive (unknown-tolerated raw)
      "zcode-task-mode-schema.ts": `import { z } from "zod";
export const zcodeTaskModeSchema = z.enum(["yolo", "plan", "autoEdit"]);
`,
      // (5) removed consumed slash entry — BREAKING
      "zcode-slash-command-help.ts": `export const BUILTIN_ZCODE_SLASH_COMMAND_HELP_ENTRIES = [
  { name: "help", usage: "/help [command]" },
];
`,
      // (6) added schema in an UNCONSUMED file — additive
      "usage-stats.ts": `import { z } from "zod";
export const appUsageSummarySchema = z.object({ tokens: z.number() });
export const appUsageDailySchema = z.object({ day: z.string() });
`,
    });
    const report = diffSurfaces({ baseline, current: extractFixture(root), manifest: MANIFEST });

    assert.equal(report.verdict, "BREAKING");
    const key = (i) => `${i.layer}/${i.kind}/${i.name}`;
    const byKey = Object.fromEntries(report.items.map((i) => [key(i), i]));

    // exact item multiset — no extra items (e.g. no body-digest noise where name drift exists)
    assert.deepEqual(
      Object.keys(byKey).sort(),
      [
        "channels/added/ServiceChannels.Bots",
        "enums/added/zcodeTaskModeSchema.autoEdit",
        "limits/changed/PROTOCOL_V4_LIMITS.maxFrameBytes",
        "schemas/added/usage-stats.ts#appUsageDailySchema",
        "schemas/removed/zcode-protocol-v4/snapshot.ts#SessionSnapshot",
        "schemas/removed/zcode-protocol-v4/snapshot.ts#sessionSnapshotSchema",
        "slash/removed/compact",
      ].sort(),
    );

    const bots = byKey["channels/added/ServiceChannels.Bots"];
    assert.equal(bots.classification, "additive");
    assert.equal(bots.consumed, false);

    const limit = byKey["limits/changed/PROTOCOL_V4_LIMITS.maxFrameBytes"];
    assert.equal(limit.classification, "breaking");
    assert.equal(limit.consumed, true);
    assert.match(limit.detail, /1024\*1024/);
    assert.match(limit.detail, /2048\*1024/);

    for (const name of ["SessionSnapshot", "sessionSnapshotSchema"]) {
      const item = byKey[`schemas/removed/zcode-protocol-v4/snapshot.ts#${name}`];
      assert.equal(item.classification, "breaking");
      assert.equal(item.consumed, true);
    }

    const mode = byKey["enums/added/zcodeTaskModeSchema.autoEdit"];
    assert.equal(mode.classification, "additive");
    assert.equal(mode.consumed, true); // consumed, but additions stay additive (INV-8)

    assert.equal(byKey["slash/removed/compact"].classification, "breaking");

    const daily = byKey["schemas/added/usage-stats.ts#appUsageDailySchema"];
    assert.equal(daily.classification, "additive");
    assert.equal(daily.consumed, false);

    assert.equal(report.summary.added, 3);
    assert.equal(report.summary.changed, 1);
    assert.equal(report.summary.removed, 3);
    assert.equal(report.summary.breaking, 4);
    // per-layer arrays mirror the items
    assert.deepEqual(report.layers.channels.added, ["ServiceChannels.Bots"]);
    assert.deepEqual(report.layers.slash.removed, ["compact"]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("pure-additive drift (additions only) classifies ADDITIVE end-to-end", () => {
  const root = makeFixture();
  try {
    const baseline = extractFixture(root);
    writeFixture(root, {
      "channels.ts": `export const ServiceChannels = {
  File: "file",
  UsageStats: "usage-stats",
  Bots: "bots",
} as const;
export const PlatformChannels = {
  About: "about",
} as const;
`,
      "zcode-task-mode-schema.ts": `import { z } from "zod";
export const zcodeTaskModeSchema = z.enum(["yolo", "plan", "autoEdit"]);
`,
    });
    const report = diffSurfaces({ baseline, current: extractFixture(root), manifest: MANIFEST });
    assert.equal(report.verdict, "ADDITIVE");
    assert.deepEqual(
      report.items.map((i) => `${i.layer}/${i.kind}/${i.name}`).sort(),
      ["channels/added/ServiceChannels.Bots", "enums/added/zcodeTaskModeSchema.autoEdit"].sort(),
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("removal from an UNCONSUMED file stays additive (inventory-only)", () => {
  const root = makeFixture();
  try {
    const baseline = extractFixture(root);
    writeFixture(root, { "usage-stats.ts": `import { z } from "zod";\n` });
    const report = diffSurfaces({ baseline, current: extractFixture(root), manifest: MANIFEST });
    assert.equal(report.verdict, "ADDITIVE");
    const item = report.items.find((i) => i.layer === "schemas" && i.kind === "removed");
    assert.ok(item, "expected a removed schema item");
    assert.equal(item.name, "usage-stats.ts#appUsageSummarySchema");
    assert.equal(item.classification, "additive");
    assert.equal(item.consumed, false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("consumed-file body digest change with no name drift is a review-recommended additive item", () => {
  const root = makeFixture();
  try {
    const baseline = extractFixture(root);
    writeFixture(root, {
      // same surface names, body comment added → digest flips, zero entry drift
      "zcode-protocol-v4/snapshot.ts": `import { z } from "zod";
// tuning note added upstream
export const MAX_PERMISSION_FEEDBACK_CHARS = 4_096;
export const sessionSnapshotSchema = z.object({ revision: z.number() });
export interface SessionSnapshot { revision: number }
`,
    });
    const report = diffSurfaces({ baseline, current: extractFixture(root), manifest: MANIFEST });
    assert.equal(report.verdict, "ADDITIVE");
    assert.equal(report.summary.bodyChanged, 1);
    assert.equal(report.reviewRecommended, true);
    const item = report.items[0];
    assert.equal(item.kind, "body-changed");
    assert.equal(item.layer, "schemas");
    assert.equal(item.name, "zcode-protocol-v4/snapshot.ts");
    assert.equal(item.classification, "additive");
    assert.match(item.detail, /manual review/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("missing curated file extracts empty and diffs as consumed removals", () => {
  const root = makeFixture();
  try {
    const baseline = extractFixture(root);
    rmSync(join(root, "usage-quota.ts"));
    const report = diffSurfaces({ baseline, current: extractFixture(root), manifest: MANIFEST });
    assert.equal(report.verdict, "BREAKING");
    const removed = report.items.filter((i) => i.kind === "removed").map((i) => `${i.layer}/${i.name}`);
    assert.deepEqual(removed.sort(), [
      "limits/MCP_USAGE_QUOTA_LIMIT_TYPE",
      "schemas/usage-quota.ts#UsageQuotaLimit",
      "schemas/usage-quota.ts#usageQuotaLimitSchema",
    ].sort());
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
