#!/usr/bin/env node
// AAP-1/AAP-2 drift-check CLI (android-gaps roadmap Phase 5) — the §39.4 tool.
//
// Open-repo leg:
//   node tools/drift/check-drift.mjs --shared-root <repo>/packages/shared/src \
//        [--inventory tools/drift/surface-inventory.json] [--out drift-report.json]
//
// Relay leg (bundle):
//   node tools/drift/check-drift.mjs --kind relay \
//        --current relay-current.json [--baseline tools/drift/relay-baseline.json]
//
// Exit codes: 0 = ADDITIVE or no drift · 1 = BREAKING · 2 = usage/IO error ·
//              3 = FACADE_DRIFT (bundle sensor: a desktop release changed the remote surface — go look).
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { extractSurface, diffSurfaces } from "./drift-lib.mjs";
import { diffRelaySurfaces } from "./relay-lib.mjs";
import { diffBundleSensors } from "./bundle-lib.mjs";

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const kind = arg("kind") ?? "open-repo";
const out = arg("out");
const manifestPath = arg("manifest");
const manifest = manifestPath ? JSON.parse(readFileSync(resolve(manifestPath), "utf8")) : undefined;

// A4 sensor notes (Phase 2H): the app-side facts the next desktop-release review
// must see alongside the bundle diff — the embedded builtin slash-catalog size and
// the §11.6 deep-link cross-endpoint stub (ZCodeApp.kt, `existsOnOtherEndpoint = false`).
function bundleSensorNotes() {
  const notes = [];
  try {
    const root = new URL("../../", import.meta.url).pathname;
    const app = readFileSync(`${root}app/src/main/kotlin/com/zcode/android/app/connect/ConnectionHubSlashCatalog.kt`, "utf8");
    const count = (app.match(/BuiltinSlash\(/g) ?? []).length;
    notes.push({ note: "builtin-slash-catalog-entries", value: count, action: "re-extract on desktop release (extract-builtin-slash-catalog.mjs)" });
  } catch {
    notes.push({ note: "builtin-slash-catalog-entries", value: "unavailable", action: "locate the embedded catalog source" });
  }
  try {
    const root = new URL("../../", import.meta.url).pathname;
    const app = readFileSync(`${root}app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt`, "utf8");
    const stubbed = app.includes("existsOnOtherEndpoint = false");
    notes.push({ note: "deep-link-cross-endpoint-stub", value: stubbed, action: stubbed ? "still a stub — desktop multi-endpoint drift is unresolvable app-side" : "resolved" });
  } catch {
    notes.push({ note: "deep-link-cross-endpoint-stub", value: "unavailable" });
  }
  return notes;
}

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
  if (report.verdict === "FACADE_DRIFT") {
    for (const i of report.items) {
      console.error(`FACADE_DRIFT ${i.layer}/${i.kind} ${i.name}: ${i.detail}`);
    }
  }
  for (const n of report.notes ?? []) {
    console.log(`note ${n.note}=${n.value} — ${n.action ?? ""}`);
  }
  process.exit(report.verdict === "BREAKING" ? 1 : report.verdict === "FACADE_DRIFT" ? 3 : 0);
}

try {
  if (kind === "bundle") {
    // D3/H5/N10 shipped-bundle sensor leg (gaps-and-polish Phase 2H):
    //   node tools/drift/check-drift.mjs --kind bundle --current bundle-current.json \
    //        [--baseline tools/drift/bundle-sensors-baseline.json]
    const currentPath = arg("current");
    if (!currentPath) throw new Error("--kind bundle requires --current <bundle-sensors.json>");
    const current = JSON.parse(readFileSync(resolve(currentPath), "utf8"));
    const baselinePath = arg("baseline") ?? new URL("./bundle-sensors-baseline.json", import.meta.url).pathname;
    const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
    const manifestBundle = manifest?.facade ?? {};
    const report = diffBundleSensors(baseline, current, manifestBundle);
    report.notes = bundleSensorNotes();
    emit(report);
  } else if (kind === "relay") {
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
