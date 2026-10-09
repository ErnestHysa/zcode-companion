#!/usr/bin/env node
// the drift-check/the sensors drift-check CLI (project roadmap Phase 5) — the §39.4 tool.
//
// Open-repo leg:
//   node tools/drift/check-drift.mjs --shared-root <repo>/packages/shared/src \
//        [--inventory tools/drift/surface-inventory.json] [--out drift-report.json]
//
// Relay leg (bundle):
//   node tools/drift/check-drift.mjs --kind relay \
//        --current relay-current.json [--baseline tools/drift/relay-baseline.json]
//
// Exit codes: 0 = ADDITIVE or no drift · 1 = BREAKING · 2 = usage/IO error.
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { extractSurface, diffSurfaces } from "./drift-lib.mjs";
import { diffRelaySurfaces } from "./relay-lib.mjs";

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const kind = arg("kind") ?? "open-repo";
const out = arg("out");
const manifestPath = arg("manifest");
const manifest = manifestPath ? JSON.parse(readFileSync(resolve(manifestPath), "utf8")) : undefined;

function emit(report) {
  const json = JSON.stringify(report, null, 2) + "\n";
  if (out) writeFileSync(resolve(out), json);
  const s = report.summary;
  const review = report.reviewRecommended ? " reviewRecommended=true" : "";
  console.log(
    `verdict=${report.verdict} added=${s.added} changed=${s.changed} removed=${s.removed} breaking=${s.breaking} bodyChanged=${s.bodyChanged}${review}`,
  );
  if (report.verdict === "BREAKING") {
    for (const i of report.items.filter((x) => x.classification === "breaking")) {
      console.error(`BREAKING ${i.layer}/${i.kind} ${i.name}: ${i.detail}`);
    }
  }
  process.exit(report.verdict === "BREAKING" ? 1 : 0);
}

try {
  if (kind === "relay") {
    const currentPath = arg("current");
    if (!currentPath) throw new Error("--kind relay requires --current <relay-surface.json>");
    const current = JSON.parse(readFileSync(resolve(currentPath), "utf8"));
    const baselinePath = arg("baseline") ?? new URL("./relay-baseline.json", import.meta.url).pathname;
    const baseline = JSON.parse(readFileSync(resolve(baselinePath), "utf8"));
    emit(diffRelaySurfaces({ baseline, current, manifest }));
  } else {
    const inventoryPath = arg("inventory") ?? new URL("./surface-inventory.json", import.meta.url).pathname;
    const baseline = JSON.parse(readFileSync(resolve(inventoryPath), "utf8"));
    const current = extractSurface({
      sharedRoot: resolve(arg("shared-root") ?? ""),
      manifest,
      commit: arg("commit") ?? null,
    });
    emit(diffSurfaces({ baseline, current, manifest }));
  }
} catch (e) {
  console.error(`drift-check error: ${e.message}`);
  process.exit(2);
}
