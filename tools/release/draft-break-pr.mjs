#!/usr/bin/env node
// AAP-5 (android-gaps roadmap) — §14.2 T3 BREAKING route: draft the patch PR and
// STOP. This executor exists so the BREAKING path is a recorded, reviewable
// artifact chain — never a release:
//   drift-report.json → PR body (classified findings + patch brief) → branch
//   fix/protocol-drift-<label> carrying the report + PATCH-BRIEF.md →
//   `gh pr create --draft` → HUMAN APPROVAL gates everything after.
// There is deliberately NO code path from here to assembleRelease or any
// release (the absolute Deferred-scope gate, pinned by decision.test.mjs).
import { readFileSync, writeFileSync, copyFileSync, existsSync, mkdirSync } from "node:fs";
import { execSync } from "node:child_process";

function arg(name, fallback) {
  const i = process.argv.indexOf("--" + name);
  return i >= 0 ? process.argv[i + 1] : fallback;
}

const repoRoot = arg("repo-root", process.cwd());
const driftPath = arg("drift-report");
const gh = arg("gh", "gh");
const runLogPath = arg("run-log", `${repoRoot}/build/break-pr-run-log.json`);

const fail = (msg) => { console.error(`draft-break-pr: ${msg}`); process.exit(2); };
if (!driftPath) fail("--drift-report required");
const drift = JSON.parse(readFileSync(driftPath, "utf8"));
if (drift.verdict !== "BREAKING") fail(`drift verdict is ${drift.verdict}, not BREAKING — use publish.mjs for auto-ship`);

const breakingItems = (drift.items ?? []).filter((i) => i.classification === "breaking");
const label = arg("label", "desktop-drift");
const branch = `fix/protocol-drift-${label.replace(/[^a-zA-Z0-9.-]/g, "-")}`;

const body = `## Protocol drift: BREAKING (desktop \`${label}\`)

Auto-drafted by the AAP-5 pipeline (§14.2 T3). **HUMAN APPROVAL REQUIRED before
any of this ships** — the pipeline stops here by design (roadmap Deferred scope:
never auto-ship breaking).

### Classified breaking changes (${breakingItems.length})

| Layer | Kind | Name | Detail |
|---|---|---|---|
${breakingItems.map((i) => `| ${i.layer} | ${i.kind} | \`${i.name}\` | ${i.detail ?? ""} |`).join("\n")}

### Patch brief for the implementing agent

1. Re-read each consumed surface above in the NEW open-repo checkout and port
   the decode changes in the Android client (spec-first per the campaign rules:
   regenerate the affected golden fixtures from the new schemas FIRST —
   \`tools/capture/generate-*.mjs\` with \`ZCODE_REFERENCE_REPO\` pointing at the
   new checkout).
2. Re-run the verify leg (\`tools/ci/verify-protocol.sh --shared-root <new>\`)
   until it is green.
3. Re-run the drift-check and regenerate \`tools/drift/surface-inventory.json\`
   (the post-release ritual, docs/runbooks/drift.md).
4. Only after human review of this PR: bump \`CompatibilityGate.MAX_TESTED_DESKTOP\`,
   then the release matrix applies from the top.

### Full report

The complete drift report is committed on this branch at
\`docs/evidence/drift-report.json\` (also an artifact of the verify leg).

**This PR carries no release. assembleRelease and gh release create must not
run until the patch is reviewed, merged, and the matrix re-decides.**
`;

execSync(`git -C "${repoRoot}" checkout -q -b "${branch}"`, { stdio: "inherit" });
mkdirSync(`${repoRoot}/docs/evidence`, { recursive: true });
copyFileSync(driftPath, `${repoRoot}/docs/evidence/drift-report.json`);
writeFileSync(`${repoRoot}/PATCH-BRIEF.md`, body.split("### Patch brief")[1]?.split("###")[0]?.trim() ?? "");
execSync(`git -C "${repoRoot}" add -A && git -C "${repoRoot}" commit -q -m "chore(protocol-drift): BREAKING drift brief for desktop ${label} (AAP-5 agent-drafted PR, human approval required)"`, { stdio: "inherit" });

const bodyPath = `${repoRoot}/build/break-pr-body.md`;
mkdirSync(`${repoRoot}/build`, { recursive: true });
writeFileSync(bodyPath, body);
const title = `Protocol drift BREAKING (desktop ${label}): agent-drafted patch brief — HUMAN APPROVAL REQUIRED`;
const ghCmd = `"${gh}" pr create --draft --title ${JSON.stringify(title)} --body-file "${bodyPath}" --base main`;
execSync(ghCmd, { stdio: "inherit", shell: "/bin/bash" });

const log = {
  schemaVersion: 1,
  tool: "draft-break-pr.mjs",
  at: new Date().toISOString(),
  driftVerdict: drift.verdict,
  breakingItems: breakingItems.length,
  branch,
  pr: { title, draft: true, bodyPath },
  humanApprovalRequired: true,
  release: "none — never auto-ship breaking (absolute gate)",
};
writeFileSync(runLogPath, JSON.stringify(log, null, 2));
console.log(`[draft-break-pr] STOPPED at the PR step — branch ${branch}; HUMAN APPROVAL REQUIRED`);
console.log(`[draft-break-pr] run log: ${runLogPath}`);
