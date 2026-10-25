// D3/H5/N10 bundle-sensor tests (gaps-and-polish Phase 2H).
// Extraction determinism + the accept gate: a FABRICATED facade delta trips the report.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { diffBundleSensors, extractFacadeMethods, extractI18nNamespaces, extractCssCustomProperties } from "./bundle-lib.mjs";

const HOST = `async listTasks(m){return o.list(m)},async setPluginEnabled(m){return(await xe()).request(_e.plugin,$M,{timeoutMs:Cr})}`;
const ALLOWLIST = `var Du=new Set(["applyFileRewind","setAutoDrain","deleteQueueItem"]);`;

test("extractFacadeMethods reads object-literal members and Set allowlists", () => {
  const names = extractFacadeMethods([HOST, ALLOWLIST]);
  for (const n of ["listTasks", "setPluginEnabled", "applyFileRewind", "setAutoDrain", "deleteQueueItem"]) {
    assert.ok(names.includes(n), `missing ${n} in ${names}`);
  }
});

test("i18n namespaces dedupe with counts, css props keep the first value", () => {
  assert.deepEqual(extractI18nNamespaces([`{"sidebar":{}}`, `x={"sidebar":{},"terminal":{}}`]), { sidebar: 2, terminal: 1 });
  assert.deepEqual(extractCssCustomProperties([`:root{--radius-xs:.125rem;--radius-xs:.5rem}`]), { "radius-xs": ".125rem" });
});

test("a fabricated facade delta trips the report (FACADE_DRIFT)", () => {
  const baseline = { facade: ["listTasks", "setPluginEnabled"], i18nNamespaces: {}, css: {} };
  const current = { facade: ["listTasks", "setPluginEnabled", "brandNewSurface"], i18nNamespaces: {}, css: {} };
  const r = diffBundleSensors(baseline, current);
  assert.equal(r.verdict, "FACADE_DRIFT");
  assert.equal(r.summary.facadeDrift, 1);
  assert.ok(r.reviewRecommended);
  assert.equal(r.items[0].name, "brandNewSurface");
});

test("removing a facade method the app consumes is BREAKING", () => {
  const baseline = { facade: ["listTasks", "getSkillReferenceCatalog"], i18nNamespaces: {}, css: {} };
  const current = { facade: ["listTasks"], i18nNamespaces: {}, css: {} };
  const r = diffBundleSensors(baseline, current, { getSkillReferenceCatalog: true });
  assert.equal(r.verdict, "BREAKING");
  assert.equal(r.items[0].classification, "breaking");
});

test("a new i18n namespace and a css change surface as review notes", () => {
  const baseline = { facade: [], i18nNamespaces: { sidebar: 3 }, css: { "radius-xs": ".125rem" } };
  const current = { facade: [], i18nNamespaces: { sidebar: 3, wizard: 1 }, css: { "radius-xs": ".25rem" } };
  const r = diffBundleSensors(baseline, current);
  assert.equal(r.verdict, "FACADE_DRIFT");
  const layers = r.items.map((i) => `${i.layer}/${i.kind}`);
  assert.ok(layers.includes("i18n/namespace"), layers);
  assert.ok(layers.includes("css/value"), layers);
});

test("no delta → NONE (the committed baseline self-diffs clean)", () => {
  const baseline = JSON.parse(readFileSync(new URL("./bundle-sensors-baseline.json", import.meta.url), "utf8"));
  const r = diffBundleSensors(baseline, baseline, {});
  assert.equal(r.verdict, "NONE");
});

test("the CLI trips with exit 1 on a fabricated delta and prints the sensor notes", () => {
  const dir = mkdtempSync(join(tmpdir(), "bundle-drift-"));
  const current = join(dir, "current.json");
  writeFileSync(
    current,
    JSON.stringify({ facade: ["listTasks", "ghostMethod"], i18nNamespaces: {}, css: {} }),
  );
  let code = 0;
  try {
    execFileSync("node", ["tools/drift/check-drift.mjs", "--kind", "bundle", "--current", current], { encoding: "utf8" });
  } catch (e) {
    code = e.status;
    assert.match(e.stderr, /ghostMethod/);
    assert.match(e.stdout, /builtin-slash-catalog-entries|deep-link-cross-endpoint-stub/);
  }
  assert.equal(code, 3, "the CLI must exit 3 (FACADE_DRIFT) on a facade delta");
});
