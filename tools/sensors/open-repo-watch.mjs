#!/usr/bin/env node
// the sensors open-repo tag watch (project roadmap Phase 5). Resolves the latest
// release tag of the upstream ZCode open repo via `git ls-remote` (metadata
// only — no relay traffic, no credentials; INV-15-clean by construction) and
// compares it with the recorded state:
//
//   node tools/sensors/open-repo-watch.mjs --repo https://github.com/zai-org/ZCode.git \
//     --state tools/drift/.watch-state/state.json [--out watch.json] [--update-state]
//
// First run (missing state file) is a silent baseline: latestTag is recorded,
// isNew=false — no false alarm on cache eviction in CI.
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const repo = arg("repo");
const statePath = arg("state");
if (!repo || !statePath) {
  console.error("usage: open-repo-watch.mjs --repo <git-url-or-path> --state <state.json> [--out <out.json>] [--update-state]");
  process.exit(2);
}

function latestTag(repoUrl) {
  const out = execFileSync("git", ["ls-remote", "--tags", "--refs", repoUrl], { encoding: "utf8" });
  const versions = [];
  for (const line of out.split("\n")) {
    const tab = line.indexOf("\t");
    if (tab < 0) continue;
    const ref = line.slice(tab + 1);
    const name = ref.replace(/^refs\/tags\//, "");
    const v = name.replace(/^v/, "");
    if (!/^\d+(\.\d+)*$/.test(v)) continue;
    versions.push({ name, segments: v.split(".").map(Number) });
  }
  if (versions.length === 0) return null;
  versions.sort((a, b) => {
    for (let i = 0; i < Math.max(a.segments.length, b.segments.length); i++) {
      const d = (a.segments[i] ?? 0) - (b.segments[i] ?? 0);
      if (d !== 0) return d;
    }
    return 0;
  });
  return versions[versions.length - 1].name;
}

let lastTag = null;
if (existsSync(statePath)) {
  try {
    lastTag = JSON.parse(readFileSync(statePath, "utf8")).lastTag ?? null;
  } catch {
    lastTag = null;
  }
}

const latestTagValue = latestTag(repo);
const result = {
  kind: "open-repo-watch",
  repo,
  latestTag: latestTagValue,
  lastTag,
  isNew: lastTag !== null && latestTagValue !== null && latestTagValue !== lastTag,
  checkedAt: new Date().toISOString(),
};

if (process.argv.includes("--update-state") && latestTagValue !== null) {
  mkdirSync(dirname(resolve(statePath)), { recursive: true });
  writeFileSync(statePath, JSON.stringify({ lastTag: latestTagValue }) + "\n");
}

const json = JSON.stringify(result, null, 2) + "\n";
if (arg("out")) writeFileSync(resolve(arg("out")), json);
process.stdout.write(json);
