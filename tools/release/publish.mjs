#!/usr/bin/env node
// AAP-5 (android-gaps roadmap) — §14.2 T3 auto-ship executor.
//
// Runs ONLY on an auto-ship decision (re-verified here — defense in depth; a
// non-auto-ship decision makes this script refuse). The pipeline:
//   versionCode/versionName bump → CompatibilityGate.MAX_TESTED_DESKTOP bump →
//   assembleRelease (signed via the off-repo stable keystore) → sha256 →
//   drift-report-generated release notes with the T4 machine-meta block →
//   `gh release create --draft` (ALWAYS a draft: promoting it to public is the
//   human publish click).
//
// Baseline-profile regen is recorded as DELEGATED to the macrobenchmark
// workflow (it needs a device; assembleRelease packages the committed
// profiles) — recorded honestly in the run log, never silently skipped.
//
// Run log (JSON): decision inputs/outputs, every step, artifact paths +
// version marker (roadmap rule 9's artifact statement, machine-readable).
import { readFileSync, writeFileSync, existsSync, copyFileSync } from "node:fs";
import { execSync } from "node:child_process";
import { createHash } from "node:crypto";
import { decideRelease } from "./decision.mjs";

function arg(name, fallback) {
  const i = process.argv.indexOf("--" + name);
  return i >= 0 ? process.argv[i + 1] : fallback;
}
function hasFlag(name) { return process.argv.includes("--" + name); }

const repoRoot = arg("repo-root", process.cwd());
const driftPath = arg("drift-report");
const verifyPath = arg("verify-report");
const canaryPath = arg("canary-report");
const gh = arg("gh", "gh");
const desktopVersion = arg("desktop-version");
const runLogPath = arg("run-log", `${repoRoot}/build/release-run-log.json`);

const fail = (msg) => { console.error(`publish: ${msg}`); process.exit(2); };
if (!driftPath || !verifyPath || !canaryPath) fail("--drift-report, --verify-report, --canary-report required");
if (!desktopVersion) fail("--desktop-version required (the verified desktop version for the MAX_TESTED_DESKTOP bump)");

const drift = JSON.parse(readFileSync(driftPath, "utf8"));
const verify = JSON.parse(readFileSync(verifyPath, "utf8"));
const canary = JSON.parse(readFileSync(canaryPath, "utf8"));

const driftVerdict = drift.verdict;
const suitesGreen = verify.verdict === "ADDITIVE" && verify.gradle === "green";
const canaryGreen = canary.verdict === "green";

const decision = decideRelease({ driftVerdict, suitesGreen, canaryGreen });
const log = {
  schemaVersion: 1,
  tool: "publish.mjs",
  startedAt: new Date().toISOString(),
  inputs: { driftPath, verifyPath, canaryPath, desktopVersion, gh },
  decisionInputs: { driftVerdict, suitesGreen, canaryGreen },
  decision,
  steps: [],
  humanApprovalRequired: false,
};
const step = (name, detail) => {
  log.steps.push({ at: new Date().toISOString(), name, ...detail });
  console.log(`[publish] ${name}${detail?.detail ? ": " + detail.detail : ""}`);
};

if (decision.action !== "auto-ship") {
  // Defense in depth: the matrix already routes; this executor must never act
  // on anything but auto-ship.
  log.refused = `decision=${decision.action} — publish.mjs only executes auto-ship`;
  writeFileSync(runLogPath, JSON.stringify(log, null, 2));
  console.log(`[publish] REFUSED (${decision.action}) — run log: ${runLogPath}`);
  process.exit(3);
}
step("decision", { detail: "auto-ship confirmed by the T3 matrix" });

// ── version bumps ────────────────────────────────────────────────────────────
const gradlePath = `${repoRoot}/app/build.gradle.kts`;
let gradle = readFileSync(gradlePath, "utf8");
const vcMatch = gradle.match(/versionCode = (\d+)/);
const vnMatch = gradle.match(/versionName = "([^"]+)"/);
if (!vcMatch || !vnMatch) fail("cannot read versionCode/versionName from app/build.gradle.kts");
const newVersionCode = Number(arg("version-code", String(Number(vcMatch[1]) + 1)));
const newVersionName = arg("version-name", vnMatch[1].replace(/(\d+)(?!.*\d)/, (m) => String(Number(m) + 1)));
gradle = gradle
  .replace(/versionCode = \d+/, `versionCode = ${newVersionCode}`)
  .replace(/versionName = "[^"]+"/, `versionName = "${newVersionName}"`);
writeFileSync(gradlePath, gradle);
step("version-bump", { detail: `versionName ${vnMatch[1]} → ${newVersionName}, versionCode ${vcMatch[1]} → ${newVersionCode}` });

const gatePath = `${repoRoot}/core/security/src/main/kotlin/com/zcode/android/core/security/pairing/CompatibilityGate.kt`;
let gate = readFileSync(gatePath, "utf8");
if (!gate.includes(`MAX_TESTED_DESKTOP = "${desktopVersion}"`)) {
  gate = gate.replace(/MAX_TESTED_DESKTOP = "[^"]+"/, `MAX_TESTED_DESKTOP = "${desktopVersion}"`);
  writeFileSync(gatePath, gate);
}
step("max-tested-bump", { detail: `CompatibilityGate.MAX_TESTED_DESKTOP → ${desktopVersion}` });

// ── commit the bumps (the --target for the draft release) ────────────────────
execSync(`git -C "${repoRoot}" add -A && git -C "${repoRoot}" commit -q -m "chore(release): vc${newVersionCode} ${newVersionName} — max-tested desktop ${desktopVersion} (AAP-5 auto-ship)"`, { stdio: "inherit" });
const target = execSync(`git -C "${repoRoot}" rev-parse HEAD`).toString().trim();
step("commit-bumps", { detail: target });

// ── baseline profiles: recorded as delegated (device-dependent) ─────────────
step("baseline-profile-regen", {
  detail: "delegated to macrobenchmark.yml (needs a device); assembleRelease packages the committed profiles",
  delegatedTo: ".github/workflows/macrobenchmark.yml",
});

// ── assembleRelease (signed) ─────────────────────────────────────────────────
execSync(`gradle -p "${repoRoot}" :app:assembleRelease --console=plain`, { stdio: "inherit" });
const apk = `${repoRoot}/app/build/outputs/apk/release/app-release.apk`;
if (!existsSync(apk)) {
  fail(`no signed APK at ${apk} (unsigned builds emit app-release-unsigned.apk — the off-repo keystore is missing)`);
}
step("assemble-release", { detail: apk });

// ── sha256 ───────────────────────────────────────────────────────────────────
const digest = createHash("sha256").update(readFileSync(apk)).digest("hex");
const shaPath = `${apk}.sha256`;
writeFileSync(shaPath, `${digest}  app-release.apk\n`);
step("sha256", { detail: `${digest} → ${shaPath}` });

// ── release notes from the drift report + the T4 machine-meta block ─────────
const layerLines = Object.entries(drift.layers ?? {})
  .map(([layer, d]) => {
    const fmt = (arr, kind) => (arr ?? []).map((n) => `  - ${kind}: ${layer}/${n}`);
    return [
      ...fmt(d.added, "added"),
      ...fmt(d.changed, "changed"),
      ...fmt(d.removed, "removed"),
    ].join("\n");
  })
  .filter(Boolean)
  .join("\n");
const notes = `ZCode Companion ${newVersionName} (versionCode ${newVersionCode})

Auto-shipped by the AAP-5 pipeline (§14.2 T3) after an ADDITIVE protocol drift
was verified end-to-end: drift classified, golden fixtures regenerated from the
new open-repo schemas, full unit suite green, Mac live canary green.

**Compatibility:** desktop ${desktopVersion} verified (CompatibilityGate.MAX_TESTED_DESKTOP bumped).
**Drift verdict:** ${driftVerdict} (${drift.summary?.added ?? 0} added / ${drift.summary?.changed ?? 0} changed / ${drift.summary?.removed ?? 0} removed / ${drift.summary?.breaking ?? 0} breaking)

Protocol surface changes (from drift-report.json):
${layerLines || "  - none (no-drift release)"}

---
Install: sideload the APK (SHA-256 published below); the in-app connection
center offers "Update now" when the desktop is newer than this build was
tested against.

<!-- zcode-android-release-meta {"versionName":"${newVersionName}","versionCode":${newVersionCode},"maxTestedDesktop":"${desktopVersion}","apk":"app-release.apk","sha256":"${digest}"} -->`;
const notesPath = `${repoRoot}/build/release-notes.md`;
writeFileSync(notesPath, notes);
step("release-notes", { detail: `${notesPath} (T4 meta block embedded)` });

// ── DRAFT release via gh ─────────────────────────────────────────────────────
const tag = `v${newVersionName}-vc${newVersionCode}`;
const ghCmd = [
  `"${gh}" release create ${tag}`,
  "--draft",
  `--target ${target}`,
  `--notes-file "${notesPath}"`,
  `"${apk}" "${shaPath}"`,
].join(" ");
execSync(ghCmd, { stdio: "inherit", shell: "/bin/bash" });
step("gh-release-create", { detail: `${tag} — DRAFT (promoting to public is the human publish click)`, tag, target });

// ── run log (rule 9 artifact statement, machine-readable) ────────────────────
log.completedAt = new Date().toISOString();
log.artifact = { apk, sha256: digest, shaPath, notesPath, tag, versionName: newVersionName, versionCode: newVersionCode };
writeFileSync(runLogPath, JSON.stringify(log, null, 2));
console.log(`[publish] DONE — signed draft release ${tag}; artifact ${apk}`);
console.log(`[publish] version ${newVersionName} (versionCode ${newVersionCode}); run log ${runLogPath}`);
