// AAP-1 drift library (android-gaps roadmap Phase 5) — the §39.4 drift-check,
// this campaign's implementation of it. Shared by:
//   tools/drift/extract-surface.mjs      (open-repo extractor CLI)
//   tools/drift/check-drift.mjs          (diff CLI: open-repo + relay legs)
//   tools/drift/surface.test.mjs         (golden tests)
//   tools/drift/extract-relay-surface.mjs (bundle leg, AAP-2)
//
// Extraction is a text fingerprint, not a TS parse: it greps the structural
// markers the upstream sources actually use (documented in docs/runbooks/drift.md).
// Deterministic by construction: every layer is sorted; same bytes in, same JSON out.
import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

export const SURFACE_SCAN_DIR = "zcode-protocol-v4";
export const CURATED_ROOT_FILES = [
  "channels.ts",
  "zcode-task-mode-schema.ts",
  "zcode-slash-command-help.ts",
  "usage-quota.ts",
  "usage-stats.ts",
  "automation-types.ts",
];

// Names matching this are fingerprinted as protocol limits/version scalars.
const LIMIT_NAME_RE = /(VERSION|LIMIT|MAX|TTL|TIMEOUT|BUDGET)/;
// Arrays whose const name matches this are fingerprinted as slash-catalog entries.
const SLASH_ARRAY_NAME_RE = /(SLASH|COMMAND|ENTRIES)/;

// Consumption defaults (overridable per-entry via the committed manifest).
// "Consumed" = the Android app strictly decodes this surface, so its removal or
// change is BREAKING; unconsumed drift is recorded but cannot break the app.
const SCHEMA_FILE_DEFAULTS = {
  "usage-quota.ts": true,
  "usage-stats.ts": false, // monitor plane — ADR-020 kept purchases/monitor desktop-only
  "automation-types.ts": true,
  "zcode-slash-command-help.ts": true,
  "zcode-task-mode-schema.ts": true,
};
const SLASH_DEFAULTS = new Set(["compact", "goal"]); // the two builtin names mapped to v4 commands

// ---------------------------------------------------------------------------
// text helpers
// ---------------------------------------------------------------------------

function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/(^|[^:"'`/])\/\/[^\n]*/g, "$1");
}

function findMatching(text, openIdx) {
  const open = text[openIdx];
  const close = open === "{" ? "}" : open === "[" ? "]" : ")";
  let depth = 0;
  let inStr = null;
  for (let i = openIdx; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      if (c === "\\") i++;
      else if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'" || c === "`") inStr = c;
    else if (c === open) depth++;
    else if (c === close) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

function splitTopLevel(block) {
  const segs = [];
  let depth = 0;
  let inStr = null;
  let start = 0;
  for (let i = 0; i < block.length; i++) {
    const c = block[i];
    if (inStr) {
      if (c === "\\") i++;
      else if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'" || c === "`") inStr = c;
    else if (c === "{" || c === "(" || c === "[") depth++;
    else if (c === "}" || c === ")" || c === "]") depth--;
    else if (c === "," && depth === 0) {
      segs.push(block.slice(start, i));
      start = i + 1;
    }
  }
  segs.push(block.slice(start));
  return segs;
}

function topLevelPairs(block) {
  const pairs = [];
  for (const seg of splitTopLevel(block)) {
    const m = seg.match(/^\s*(?:"([^"]+)"|'([^']+)'|([A-Za-z_$][\w$]*))\s*:\s*([\s\S]+)$/);
    if (m) pairs.push({ key: m[1] ?? m[2] ?? m[3], value: m[4].trim().replace(/,\s*$/, "") });
  }
  return pairs;
}

function normExpr(v) {
  return v
    .replace(/\s+/g, "")
    .replace(/([\d_])_(?=[\d_])/g, "$1");
}

const NUMERIC_EXPR_RE = /^[0-9eE_.\s+*/()-]+$/;

function sha256(text) {
  return createHash("sha256").update(text, "utf8").digest("hex");
}

function sortedEntries(obj) {
  const out = {};
  for (const k of Object.keys(obj).sort()) out[k] = obj[k];
  return out;
}

// ---------------------------------------------------------------------------
// per-structure extractors (all operate on comment-stripped text)
// ---------------------------------------------------------------------------

function extractStringConstMaps(text) {
  const maps = [];
  const re = /export\s+const\s+(\w+)\s*=\s*\{/g;
  let m;
  while ((m = re.exec(text))) {
    const open = m.index + m[0].length - 1;
    const close = findMatching(text, open);
    if (close < 0) break;
    re.lastIndex = close + 1;
    if (!/^\s*as\s+const/.test(text.slice(close + 1, close + 40))) continue;
    const entries = {};
    for (const p of topLevelPairs(text.slice(open + 1, close))) {
      const vm = p.value.match(/^"((?:[^"\\]|\\.)*)"$/);
      if (vm) entries[p.key] = JSON.parse(`"${vm[1]}"`);
    }
    if (Object.keys(entries).length > 0) maps.push({ name: m[1], entries });
  }
  return maps;
}

function extractEnumExports(text) {
  const enums = {};
  const re = /export\s+const\s+(\w+)\s*(?::[^=]+)?=\s*z\.enum\(\s*\[([^\]]*)\]/g;
  let m;
  while ((m = re.exec(text))) {
    enums[m[1]] = [...m[2].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
  }
  return enums;
}

function extractSlashEntries(text) {
  const entries = {};
  const re = /export\s+const\s+(\w+)\s*(?::[^=]+)?=\s*\[/g;
  let m;
  while ((m = re.exec(text))) {
    if (!SLASH_ARRAY_NAME_RE.test(m[1])) continue;
    const open = m.index + m[0].length - 1;
    const close = findMatching(text, open);
    if (close < 0) break;
    re.lastIndex = close + 1;
    for (const seg of splitTopLevel(text.slice(open + 1, close))) {
      const t = seg.trim();
      if (!t.startsWith("{")) continue;
      const objOpen = t.indexOf("{");
      const objClose = findMatching(t, objOpen);
      if (objClose < 0) continue;
      const inner = t.slice(objOpen + 1, objClose);
      const name = inner.match(/\bname\s*:\s*"([^"]+)"/);
      if (!name) continue;
      const um = inner.match(/\busage\s*:\s*([\s\S]+)$/);
      if (!um) {
        entries[name[1]] = "";
        continue;
      }
      const raw = um[1].trim().replace(/,\s*$/, "");
      const lit = raw.match(/^(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')$/);
      entries[name[1]] = lit ? JSON.parse(`"${lit[1] ?? lit[2]}"`) : normExpr(raw);
    }
  }
  return entries;
}

function extractSchemaNames(text) {
  const names = new Set();
  for (const m of text.matchAll(/export\s+(?:type|interface)\s+(\w+)/g)) names.add(m[1]);
  for (const m of text.matchAll(/export\s+const\s+(\w+)\s*(?::[^=]+)?=/g)) {
    // bound the rhs peek at the NEXT export so one export's window never
    // bleeds into a following z.* declaration
    const rest = text.slice(m.index + m[0].length, m.index + m[0].length + 400);
    const cut = rest.search(/\bexport\s+(?:const|type|interface|function|class)\b/);
    const rhs = cut >= 0 ? rest.slice(0, cut) : rest;
    if (/Schema$/.test(m[1]) || /(?:^|[^\w.])z\./.test(rhs)) names.add(m[1]);
  }
  return [...names].sort();
}

function extractLimits(text, fileName, into, provenance, layerKey) {
  const objRe = /export\s+const\s+(\w+)\s*=\s*\{/g;
  let m;
  while ((m = objRe.exec(text))) {
    if (!LIMIT_NAME_RE.test(m[1])) continue;
    const open = m.index + m[0].length - 1;
    const close = findMatching(text, open);
    if (close < 0) break;
    objRe.lastIndex = close + 1;
    for (const p of topLevelPairs(text.slice(open + 1, close))) {
      const key = `${m[1]}.${p.key}`;
      into[key] = normExpr(p.value);
      provenance[`${layerKey}/${key}`] = fileName;
    }
  }
  const scalarRe = /export\s+const\s+(\w+)\s*(?::[^=]+)?=\s*([^;\n]+)[;\n]/g;
  while ((m = scalarRe.exec(text))) {
    if (!LIMIT_NAME_RE.test(m[1])) continue;
    const rhs = m[2].trim();
    let value = null;
    const sm = rhs.match(/^"((?:[^"\\]|\\.)*)"$/);
    if (sm) value = JSON.parse(`"${sm[1]}"`);
    else if (NUMERIC_EXPR_RE.test(rhs) && /\d/.test(rhs)) value = normExpr(rhs);
    if (value === null) continue;
    into[m[1]] = value;
    provenance[`${layerKey}/${m[1]}`] = fileName;
  }
}

// ---------------------------------------------------------------------------
// surface extraction
// ---------------------------------------------------------------------------

function listTsFiles(dir, prefix) {
  const out = [];
  let stat;
  try {
    stat = statSync(dir);
  } catch {
    return out;
  }
  if (!stat.isDirectory()) return out;
  for (const name of readdirSync(dir).sort()) {
    const p = join(dir, name);
    const rel = prefix ? `${prefix}/${name}` : name;
    const s = statSync(p);
    if (s.isDirectory()) out.push(...listTsFiles(p, rel));
    else if (name.endsWith(".ts")) out.push(rel);
  }
  return out;
}

function readIfExists(path) {
  try {
    return readFileSync(path, "utf8");
  } catch {
    return null;
  }
}

export function extractSurface({ sharedRoot, manifest = { consumed: {} }, commit = null, extractedAt = null }) {
  const files = []; // [relPath, absolutePath]
  for (const rel of listTsFiles(join(sharedRoot, SURFACE_SCAN_DIR), SURFACE_SCAN_DIR)) {
    files.push([rel, join(sharedRoot, rel)]);
  }
  for (const rel of CURATED_ROOT_FILES) {
    const p = join(sharedRoot, rel);
    try {
      statSync(p);
      files.push([rel, p]);
    } catch {
      /* missing curated file: contributes nothing; diff shows its removals */
    }
  }
  files.sort((a, b) => (a[0] < b[0] ? -1 : 1));

  const channels = {};
  const enums = {};
  const slash = {};
  const schemas = {};
  const limits = {};
  const digests = {};
  const provenance = {};

  for (const [rel, abs] of files) {
    const raw = readFileSync(abs, "utf8");
    const text = stripComments(raw);
    digests[rel] = sha256(text);

    if (rel === "channels.ts") {
      for (const map of extractStringConstMaps(text)) {
        for (const [k, v] of Object.entries(map.entries)) {
          const key = `${map.name}.${k}`;
          channels[key] = v;
          provenance[`channels/${key}`] = rel;
        }
      }
    }

    for (const [name, members] of Object.entries(extractEnumExports(text))) {
      enums[name] = members;
      provenance[`enums/${name}`] = rel;
    }

    for (const [name, usage] of Object.entries(extractSlashEntries(text))) {
      slash[name] = usage;
      provenance[`slash/${name}`] = rel;
    }

    const names = extractSchemaNames(text);
    if (names.length > 0) schemas[rel] = names;
    for (const n of names) provenance[`schemas/${rel}#${n}`] = rel;

    extractLimits(text, rel, limits, provenance, "limits");
  }

  return {
    schemaVersion: 1,
    kind: "surface-inventory",
    generatedAt: extractedAt ?? new Date().toISOString(),
    source: { sharedRoot, commit },
    layers: {
      channels: { entries: sortedEntries(channels) },
      enums: { entries: sortedEntries(enums) },
      slash: { entries: sortedEntries(slash) },
      schemas: { entries: sortedEntries(schemas) },
      limits: { entries: sortedEntries(limits) },
    },
    meta: { digests: sortedEntries(digests), provenance: sortedEntries(provenance) },
  };
}

// ---------------------------------------------------------------------------
// consumption + classification
// ---------------------------------------------------------------------------

function manifestValue(manifest, key) {
  return manifest?.consumed?.[key];
}

export function consumedOf(layer, name, manifest) {
  const exact = manifestValue(manifest, `${layer}/${name}`);
  if (exact !== undefined) return exact;
  const wildcard = manifestValue(manifest, `${layer}/*`);
  if (wildcard !== undefined) return wildcard;
  switch (layer) {
    case "channels":
      return false;
    case "enums":
    case "limits":
      return true;
    case "slash":
      return SLASH_DEFAULTS.has(name);
    case "schemas": {
      const file = name.split("#")[0];
      if (file.startsWith(`${SURFACE_SCAN_DIR}/`)) return true;
      return SCHEMA_FILE_DEFAULTS[file] ?? false;
    }
    default:
      return false;
  }
}

function classify(kind, consumed) {
  if (kind === "added") return "additive";
  if (kind === "body-changed") return "additive";
  return consumed ? "breaking" : "additive";
}

const ENTRY_LAYERS = ["channels", "slash", "limits"];

function diffEntryLayer(layer, baselineEntries, currentEntries, manifest, items) {
  const names = [...new Set([...Object.keys(baselineEntries), ...Object.keys(currentEntries)])].sort();
  for (const name of names) {
    const had = Object.prototype.hasOwnProperty.call(baselineEntries, name);
    const has = Object.prototype.hasOwnProperty.call(currentEntries, name);
    const consumed = consumedOf(layer, name, manifest);
    if (had && !has) {
      items.push({ layer, kind: "removed", name, detail: `removed from ${layer}`, consumed, classification: classify("removed", consumed) });
    } else if (!had && has) {
      items.push({ layer, kind: "added", name, detail: `added to ${layer}`, consumed, classification: classify("added", consumed) });
    } else if (JSON.stringify(baselineEntries[name]) !== JSON.stringify(currentEntries[name])) {
      items.push({
        layer,
        kind: "changed",
        name,
        detail: `${JSON.stringify(baselineEntries[name])} -> ${JSON.stringify(currentEntries[name])}`,
        consumed,
        classification: classify("changed", layer === "limits" ? true : consumed),
      });
    }
  }
}

// ---------------------------------------------------------------------------
// diff
// ---------------------------------------------------------------------------

export function diffSurfaces({ baseline, current, manifest = { consumed: {} } }) {
  const items = [];
  const layers = {};
  for (const layer of ["channels", "enums", "slash", "schemas", "limits"]) {
    layers[layer] = { added: [], changed: [], removed: [] };
  }

  diffEntryLayer("channels", baseline.layers.channels.entries, current.layers.channels.entries, manifest, items);
  diffEntryLayer("slash", baseline.layers.slash.entries, current.layers.slash.entries, manifest, items);
  diffEntryLayer("limits", baseline.layers.limits.entries, current.layers.limits.entries, manifest, items);

  // schemas: flatten file#name
  const flat = (m) => {
    const out = {};
    for (const [file, names] of Object.entries(m)) for (const n of names) out[`${file}#${n}`] = file;
    return out;
  };
  const bFlat = flat(baseline.layers.schemas.entries);
  const cFlat = flat(current.layers.schemas.entries);
  for (const name of [...new Set([...Object.keys(bFlat), ...Object.keys(cFlat)])].sort()) {
    const consumed = consumedOf("schemas", name, manifest);
    if (Object.prototype.hasOwnProperty.call(bFlat, name) && !Object.prototype.hasOwnProperty.call(cFlat, name)) {
      items.push({ layer: "schemas", kind: "removed", name, detail: "removed schema/type export", consumed, classification: classify("removed", consumed) });
      layers.schemas.removed.push(name);
    } else if (!Object.prototype.hasOwnProperty.call(bFlat, name) && Object.prototype.hasOwnProperty.call(cFlat, name)) {
      items.push({ layer: "schemas", kind: "added", name, detail: "added schema/type export", consumed, classification: classify("added", consumed) });
      layers.schemas.added.push(name);
    }
  }

  // enums: per-member diff, plus whole-export added/removed
  const bEnums = baseline.layers.enums.entries;
  const cEnums = current.layers.enums.entries;
  for (const name of [...new Set([...Object.keys(bEnums), ...Object.keys(cEnums)])].sort()) {
    const consumed = consumedOf("enums", name, manifest);
    const had = Object.prototype.hasOwnProperty.call(bEnums, name);
    const has = Object.prototype.hasOwnProperty.call(cEnums, name);
    if (had && !has) {
      items.push({ layer: "enums", kind: "removed", name, detail: "enum export removed", consumed, classification: classify("removed", consumed) });
      layers.enums.removed.push(name);
      continue;
    }
    if (!had && has) {
      items.push({ layer: "enums", kind: "added", name, detail: "enum export added", consumed, classification: classify("added", consumed) });
      layers.enums.added.push(name);
      continue;
    }
    const members = [...new Set([...(bEnums[name] ?? []), ...(cEnums[name] ?? [])])].sort();
    for (const member of members) {
      const hadM = (bEnums[name] ?? []).includes(member);
      const hasM = (cEnums[name] ?? []).includes(member);
      if (hadM === hasM) continue;
      const fullName = `${name}.${member}`;
      const kind = hasM ? "added" : "removed";
      items.push({
        layer: "enums",
        kind,
        name: fullName,
        detail: hasM ? "enum member added (unknown-tolerated raw, INV-8)" : "enum member removed",
        consumed,
        classification: classify(kind, consumed),
      });
      layers.enums[kind].push(fullName);
    }
  }

  // fill entry-layer arrays
  for (const item of items) {
    if (ENTRY_LAYERS.includes(item.layer)) layers[item.layer][item.kind].push(item.name);
  }

  // body digests: flag files whose digest changed WITHOUT any entry drift —
  // invisible to the name surface, so a human review is advised. Provenance is
  // looked up in BOTH extractions (removed entries only exist in the baseline),
  // with structural fallbacks for enum members and schema files.
  let bodyChanged = 0;
  const driftedFiles = new Set();
  const provenanceKeysFor = (item) => {
    const keys = [`${item.layer}/${item.name}`];
    if (item.layer === "enums" && item.name.includes(".")) {
      keys.push(`enums/${item.name.replace(/\.[^.]+$/, "")}`);
    }
    if (item.layer === "schemas") keys.push(`schemas/${item.name.split("#")[0]}`);
    return keys;
  };
  for (const item of items) {
    for (const meta of [baseline.meta, current.meta]) {
      for (const k of provenanceKeysFor(item)) {
        const f = meta.provenance?.[k];
        if (f) driftedFiles.add(f);
      }
    }
  }
  for (const [file, digest] of Object.entries(current.meta.digests ?? {})) {
    const before = baseline.meta.digests?.[file];
    if (before === undefined || before === digest) continue;
    if (driftedFiles.has(file)) continue;
    items.push({
      layer: "schemas",
      kind: "body-changed",
      name: file,
      detail: "body digest changed with no surface-name drift — manual review advised",
      consumed: consumedOf("schemas", file, manifest),
      classification: "additive",
    });
    bodyChanged++;
  }

  items.sort((a, b) => `${a.layer}/${a.kind}/${a.name}`.localeCompare(`${b.layer}/${b.kind}/${b.name}`));
  const breaking = items.filter((i) => i.classification === "breaking").length;
  const summary = {
    added: items.filter((i) => i.kind === "added").length,
    changed: items.filter((i) => i.kind === "changed").length,
    removed: items.filter((i) => i.kind === "removed").length,
    breaking,
    bodyChanged,
  };
  return {
    schemaVersion: 1,
    kind: "open-repo-drift-report",
    generatedAt: new Date().toISOString(),
    verdict: breaking > 0 ? "BREAKING" : "ADDITIVE",
    reviewRecommended: bodyChanged > 0,
    summary,
    layers: {
      channels: { added: layers.channels.added.sort(), changed: layers.channels.changed.sort(), removed: layers.channels.removed.sort() },
      enums: { added: layers.enums.added.sort(), changed: layers.enums.changed.sort(), removed: layers.enums.removed.sort() },
      slash: { added: layers.slash.added.sort(), changed: layers.slash.changed.sort(), removed: layers.slash.removed.sort() },
      schemas: { added: layers.schemas.added.sort(), changed: layers.schemas.changed.sort(), removed: layers.schemas.removed.sort() },
      limits: { added: layers.limits.added.sort(), changed: layers.limits.changed.sort(), removed: layers.limits.removed.sort() },
    },
    items,
  };
}
