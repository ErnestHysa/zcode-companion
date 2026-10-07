#!/usr/bin/env node
// AAP-5 (android-gaps roadmap) — the §14.2 chain orchestrator:
//   seed drift → T1 drift report → T2 verify (tools/ci/verify-protocol.sh)
//   → Mac canary verdict → T3 decision matrix → publish | agent-PR | halt.
// Every tier's inputs/outputs are recorded in one run log (the "matrix
// decisions recorded in the run log" acceptance) and nothing is ever pushed.
//
// --reuse-verify <workdir>: adopt an ALREADY-RUN verify leg's reports (the
// rehearsal reuses the AAP-4 leg instead of paying a second gradle run); the
// reuse is recorded in the run log.
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";
import { decideRelease } from "./decision.mjs";

function arg(name, fallback) {
  const i = process.argv.indexOf("--" + name);
  return i >= 0 ? process.argv[i + 1] : fallback;
}

const repoRoot = arg("repo-root", process.cwd());
const sharedRoot = arg("shared-root");
const label = arg("label", "desktop");
const workDir = arg("work-dir");
const reuseVerify = arg("reuse-verify");
const canaryPath = arg("canary-report");
const gh = arg("gh", "gh");
const skipBaselineRegen = process.argv.includes("--skip-baseline-regen");

const fail = (msg) => { console.error(`pipeline: ${msg}`); process.exit(2); };
if (!sharedRoot && !reuseVerify) fail("--shared-root (or --reuse-verify) required");
if (!canaryPath || !existsSync(canaryPath)) fail("--canary-report (existing file) required");

const log = {
  schemaVersion: 1,
  tool: "pipeline.mjs",
  startedAt: new Date().toISOString(),
  label,
  chain: [],
};

// ── T2 verify (or reuse the AAP-4 leg's outputs) ─────────────────────────────
let verifyDir;
if (reuseVerify) {
  verifyDir = reuseVerify;
  log.chain.push({ tier: "T2-verify", reused: true, from: reuseVerify });
  console.log(`[pipeline] T2 verify: reusing ${reuseVerify}`);
  if (!existsSync(`${verifyDir}/verify-report.json`) || !existsSync(`${verifyDir}/drift-report.json`)) {
    fail(`--reuse-verify dir lacks verify-report.json/drift-report.json: ${verifyDir}`);
  }
} else {
  verifyDir = `${workDir ?? "/tmp"}/pipeline-verify-${Date.now()}`;
  console.log("[pipeline] T2 verify: running tools/ci/verify-protocol.sh");
  let code = 0;
  try {
    execSync(
      `tools/ci/verify-protocol.sh --shared-root "${sharedRoot}" --label "${label}" --work-dir "${verifyDir}"`,
      { stdio: "inherit", cwd: repoRoot },
    );
  } catch (e) {
    code = e.status ?? 1;
  }
  log.chain.push({ tier: "T2-verify", ran: true, exit: code, dir: verifyDir });
  if (code !== 0 && code !== 1) fail(`verify leg infrastructure error (exit ${code})`);
}

const drift = JSON.parse(readFileSync(`${verifyDir}/drift-report.json`, "utf8"));
const verify = JSON.parse(readFileSync(`${verifyDir}/verify-report.json`, "utf8"));
log.chain.push({
  tier: "T1-drift-report",
  verdict: drift.verdict,
  summary: drift.summary,
  report: `${verifyDir}/drift-report.json`,
});

// ── canary ───────────────────────────────────────────────────────────────────
const canary = JSON.parse(readFileSync(canaryPath, "utf8"));
log.chain.push({ tier: "canary", verdict: canary.verdict, report: canaryPath, source: canary.source });

// ── T3 decision ──────────────────────────────────────────────────────────────
const suitesGreen = verify.verdict === "ADDITIVE" && verify.gradle === "green";
const canaryGreen = canary.verdict === "green";
const decision = decideRelease({
  driftVerdict: drift.verdict,
  suitesGreen,
  canaryGreen,
});
log.decision = { inputs: { driftVerdict: drift.verdict, suitesGreen, canaryGreen }, ...decision };
console.log(`[pipeline] T3 decision: ${decision.action}`);
for (const r of decision.reasons) console.log(`[pipeline]   · ${r}`);

// ── dispatch ─────────────────────────────────────────────────────────────────
const runLog = `${workDir ?? "/tmp"}/pipeline-run-log.json`;
let exit = 0;
if (decision.action === "auto-ship") {
  const args = [
    `--repo-root "${arg("ship-repo-root", repoRoot)}"`,
    `--drift-report "${verifyDir}/drift-report.json"`,
    `--verify-report "${verifyDir}/verify-report.json"`,
    `--canary-report "${canaryPath}"`,
    `--desktop-version "${label}"`,
    `--gh "${gh}"`,
    `--run-log "${runLog}"`,
  ];
  const versionName = arg("version-name");
  if (versionName) args.push(`--version-name "${versionName}"`);
  const versionCode = arg("version-code");
  if (versionCode) args.push(`--version-code "${versionCode}"`);
  if (skipBaselineRegen) args.push("--skip-baseline-regen");
  execSync(`node tools/release/publish.mjs ${args.join(" ")}`, { stdio: "inherit", cwd: repoRoot });
} else if (decision.action === "agent-pr") {
  execSync(
    `node tools/release/draft-break-pr.mjs --repo-root "${arg("ship-repo-root", repoRoot)}" --drift-report "${verifyDir}/drift-report.json" --label "${label}" --gh "${gh}" --run-log "${runLog}"`,
    { stdio: "inherit", cwd: repoRoot },
  );
  exit = 1; // BREAKING: the chain stops for a human, deliberately red
} else {
  writeFileSync(runLog, JSON.stringify(log, null, 2));
  console.log(`[pipeline] HALT — run log: ${runLog}`);
  exit = 3;
}

log.completedAt = new Date().toISOString();
log.runLog = runLog;
// The run log is rewritten AFTER the dispatched tool merges its own steps —
// re-read it so the final artifact is the union.
if (existsSync(runLog)) {
  try {
    const dispatched = JSON.parse(readFileSync(runLog, "utf8"));
    dispatched.pipeline = log;
    writeFileSync(runLog, JSON.stringify(dispatched, null, 2));
  } catch { writeFileSync(`${workDir ?? "/tmp"}/pipeline-chain-log.json`, JSON.stringify(log, null, 2)); }
} else {
  writeFileSync(runLog, JSON.stringify(log, null, 2));
}
process.exit(exit);
