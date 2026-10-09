#!/usr/bin/env node
// the drift-check open-repo surface extractor (project roadmap Phase 5).
// Generates the committed golden inventory from a ZCode open-repo checkout:
//
//   node tools/drift/extract-surface.mjs --shared-root <repo>/packages/shared/src \
//        --out tools/drift/surface-inventory.json
//
// The inventory is a GENERATED artifact (roadmap binding rule 5): regenerate,
// never hand-edit. Deterministic per checkout apart from generatedAt.
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { extractSurface } from "./drift-lib.mjs";

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const sharedRoot = resolve(arg("shared-root") ?? "");
if (!sharedRoot) {
  console.error("usage: extract-surface.mjs --shared-root <packages/shared/src> [--out <path>] [--manifest <path>] [--commit <sha>]");
  process.exit(2);
}

let commit = arg("commit") ?? null;
if (!commit) {
  try {
    commit = execFileSync("git", ["-C", sharedRoot, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  } catch {
    commit = null; // not a git checkout — inventory records a null commit
  }
}

const manifestPath = arg("manifest");
const manifest = manifestPath ? JSON.parse(readFileSync(resolve(manifestPath), "utf8")) : undefined;

const inventory = extractSurface({ sharedRoot, manifest, commit });
const json = JSON.stringify(inventory, null, 2) + "\n";
const out = arg("out");
if (out) {
  writeFileSync(resolve(out), json);
  console.error(`surface inventory written: ${out} (${Object.keys(inventory.meta.digests).length} files scanned, commit ${commit ?? "unknown"})`);
} else {
  process.stdout.write(json);
}
