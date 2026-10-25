// D3/H5/N10 bundle-sensor extraction library (gaps-and-polish Phase 2H).
//
// Three sensors over a SHIPPED desktop bundle (the asar's out/ tree):
//   facade   — remote-callable method names. The desktop facade's wire shape is
//              `async <name>(args){return(await x()).request(...)` — verified live in
//              3.14.5 (e.g. `async setPluginEnabled(m){return(await xe()).request(...)`).
//   i18n     — IntlProvider namespace keys (`"<ns>": {`), deduped with occurrence
//              counts (H5/J10: a new namespace = a new UI surface).
//   css      — custom properties from the shipped stylesheet (N10: radius/font/
//              elevation/brand — a THEME drift class the i18n sensor cannot see).
//
// Extraction is a deterministic text fingerprint, not a JS parse (same policy as
// drift-lib.mjs): sorted, byte-stable, same bytes in → same JSON out.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

// Facade members are OBJECT-LITERAL async methods (verified in 3.14.5: they run in
// comma-separated runs like `...,$M,{timeoutMs:Cr})},async listAutomations(m){...` —
// the anchor is an object-literal member position, not a bare `async fn` (532 of
// those exist; only ~361 sit in literal-member positions).
const FACADE_RE = /(?:^|[\{,]\s*)async\s+([a-zA-Z][A-Za-z0-9]{2,60})\s*\(/g;
const NS_RE = /[`"']([a-z][a-zA-Z0-9_]{2,40})[`"']\s*:\s*\{/g;
const CSS_PROP_RE = /--([a-z][a-z0-9-]{1,60})\s*:\s*([^;{}]{1,120})[;}]/g;

function readAll(dir, ext, acc = []) {
  let entries = [];
  try {
    entries = readdirSync(dir);
  } catch {
    return acc;
  }
  for (const e of entries.sort()) {
    const p = join(dir, e);
    const st = statSync(p);
    if (st.isDirectory()) readAll(p, ext, acc);
    else if (p.endsWith(ext)) acc.push(p);
  }
  return acc;
}

/** Remote-facade method names from the host chunk texts. */
export function extractFacadeMethods(texts) {
  const set = new Set();
  for (const t of texts) {
    // (1) object-literal async members (the remote service object)
    for (const m of t.matchAll(FACADE_RE)) set.add(m[1]);
    // (2) the wire command allowlists — quoted camelCase names inside Set([...])
    //     literals (verified in 3.14.5: `new Set(["applyFileRewind",...,"setAutoDrain"])`).
    for (const arr of t.matchAll(/new Set\(\[([^\]]{10,4000})\]\)/g)) {
      for (const n of arr[1].matchAll(/"([a-z][A-Za-z0-9]{3,60})"/g)) set.add(n[1]);
    }
  }
  return [...set].sort();
}

/** i18n namespace keys → occurrence counts (deterministic order). */
export function extractI18nNamespaces(texts) {
  const counts = new Map();
  for (const t of texts) {
    for (const m of t.matchAll(NS_RE)) counts.set(m[1], (counts.get(m[1]) ?? 0) + 1);
  }
  return Object.fromEntries([...counts.entries()].sort((a, b) => a[0].localeCompare(b[0])));
}

/** Shipped CSS custom properties → values (deduped; first value wins per property). */
export function extractCssCustomProperties(cssTexts) {
  const props = new Map();
  for (const t of cssTexts) {
    for (const m of t.matchAll(CSS_PROP_RE)) {
      const k = m[1];
      if (!props.has(k)) props.set(k, m[2].trim());
    }
  }
  return Object.fromEntries([...props.entries()].sort((a, b) => a[0].localeCompare(b[0])));
}

/** Walk an extracted asar `out/` tree and produce the full sensor payload. */
export function extractBundleSensors(outDir) {
  const hostJs = readAll(join(outDir, "host"), ".js").map((p) => readFileSync(p, "utf8"));
  const rendererJs = readAll(join(outDir, "renderer"), ".js").map((p) => readFileSync(p, "utf8"));
  const css = readAll(join(outDir, "renderer"), ".css").map((p) => readFileSync(p, "utf8"));
  return {
    facade: extractFacadeMethods(hostJs),
    i18nNamespaces: extractI18nNamespaces([...hostJs, ...rendererJs]),
    css: extractCssCustomProperties(css),
  };
}

/**
 * Diff the facade/i18n/css sensors against the committed baseline.
 * Facade removals of APP-CONSUMED methods are BREAKING; any other facade delta is
 * FACADE_DRIFT (a desktop release shipped a changed remote surface — go look).
 * New i18n namespaces and CSS property changes are REVIEW notes.
 */
export function diffBundleSensors(baseline, current, consumedFacade = {}) {
  const items = [];
  const base = new Set(baseline.facade ?? []);
  const cur = new Set(current.facade ?? []);
  for (const m of [...cur].sort()) {
    if (!base.has(m)) {
      const consumed = consumedFacade[m] === true;
      items.push({
        layer: "facade",
        kind: "method",
        name: m,
        classification: consumed ? "breaking" : "facade-drift",
        detail: consumed
          ? "a facade method the app consumes was ADDED (unexpected: update consumed-surfaces.json)"
          : "new remote facade method in the shipped desktop (review: new phone-reachable surface)",
      });
    }
  }
  for (const m of [...base].sort()) {
    if (!cur.has(m)) {
      const consumed = consumedFacade[m] === true;
      items.push({
        layer: "facade",
        kind: "method",
        name: m,
        classification: consumed ? "breaking" : "facade-drift",
        detail: consumed
          ? "a facade method the app consumes was REMOVED — the app's call path breaks"
          : "facade method no longer present in the shipped desktop (removed or renamed)",
      });
    }
  }
  const review = [];
  const baseNs = new Set(Object.keys(baseline.i18nNamespaces ?? {}));
  for (const ns of Object.keys(current.i18nNamespaces ?? {}).sort()) {
    if (!baseNs.has(ns)) review.push({ layer: "i18n", kind: "namespace", name: ns, detail: "new i18n namespace (new UI surface)" });
  }
  const baseCss = baseline.css ?? {};
  for (const [k, v] of Object.entries(current.css ?? {}).sort()) {
    if (!(k in baseCss)) review.push({ layer: "css", kind: "property", name: `--${k}`, detail: `new custom property (${v})` });
    else if (baseCss[k] !== v) review.push({ layer: "css", kind: "value", name: `--${k}`, detail: `${baseCss[k]} → ${v}` });
  }
  const breaking = items.filter((i) => i.classification === "breaking");
  const drift = items.filter((i) => i.classification === "facade-drift");
  const verdict = breaking.length > 0 ? "BREAKING" : drift.length > 0 || review.length > 0 ? "FACADE_DRIFT" : "NONE";
  return {
    kind: "bundle",
    verdict,
    reviewRecommended: drift.length > 0 || review.length > 0,
    summary: { breaking: breaking.length, facadeDrift: drift.length, review: review.length },
    items: [...breaking, ...drift, ...review],
  };
}
