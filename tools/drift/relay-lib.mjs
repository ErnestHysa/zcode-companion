// AAP-2 relay-surface library (android-gaps roadmap Phase 5): extracts the
// Phase-1-corpus relay-adapter constant families from the SHIPPED desktop bundle
// text (zcode.cjs + app.asar, raw text scan — the relay side is closed source)
// and diffs it against the committed baseline. Corpus basis:
// docs/protocol/relay-control-plane.md (v3.14.3 [VERIFIED] values) + §33 of
// architectural.md (close-code mapping). Text fingerprint, same discipline as
// drift-lib: deterministic, sorted, no bundle execution.
import { createHash } from "node:crypto";

// S17 (architectural.md §7): relay close codes verbatim from the bundle.
export const RELAY_CLOSE_CODES = [
  "SessionNotFound",
  "SessionConflict",
  "DesktopDisconnected",
  "SessionExpired",
  "WorkspaceClosed",
  "InvalidMobileConnection",
];

// Adapter limits (bundle "QI" object, relay-control-plane.md §2).
export const RELAY_ADAPTER_LIMITS = [
  "maxPhysicalFrameBytes",
  "maxMessageBytes",
  "maxFragments",
  "assemblyTimeoutMs",
  "transportIdMaxChars",
];

// Physical envelope + rpc-frame/ack field names (strict zod schemas).
export const RELAY_ENVELOPE_FIELDS = [
  "client_ts",
  "server_ts",
  "zcode_type",
  "rpc-frame",
  "rpc-frame-ack",
  "bridgeSessionId",
  "bridgeGeneration",
  "recoveryId",
  "messageSeq",
  "ackMessageSeq",
  "fragmentIndex",
  "fragmentCount",
  "messageBytes",
  "checksum",
  "dataBase64",
];

// Relay error-frame codes (relay-control-plane.md §1).
export const RELAY_ERROR_CODES = ["KICKED", "AUTH_FAILED", "INTERNAL", "WRONG_PARAM"];

const NUM_TOKEN = "([0-9][0-9_]*(?:[eE][0-9]+)?)";

function safeNum(token) {
  if (/^[0-9_]+$/.test(token) || /^[0-9]+[eE][0-9]+$/.test(token)) {
    return String(Number(token.replace(/_/g, "")));
  }
  return token;
}

// Adapter-limit values are expression fingerprints: numeric literals normalize
// (3e4 -> 30000), everything else (16*1024*1024, fc.maxFrameBytes alias chains)
// is kept as normalized raw text so any upstream re-expression flags as drift.
function normLimitValue(raw) {
  const v = raw.trim().replace(/\s+/g, "").replace(/([\d_])_(?=[\d_])/g, "$1").replace(/[;,]$/, "");
  return safeNum(v);
}

function sha256Of(text) {
  return createHash("sha256").update(text, "utf8").digest("hex");
}

function sortedEntries(obj) {
  const out = {};
  for (const k of Object.keys(obj).sort()) out[k] = obj[k];
  return out;
}

function firstMatch(texts, buildRegex) {
  for (const { label, text } of texts) {
    const re = new RegExp(buildRegex().source, "g");
    const m = re.exec(text);
    if (m) return { label, value: m };
  }
  return null;
}

// The shipped bundle defines the adapter limits as ONE co-located object
// (corpus "QI": {maxPhysicalFrameBytes:fc.maxFrameBytes, maxMessageBytes:
// 16*1024*1024, maxFragments:64, assemblyTimeoutMs:3e4, transportIdMaxChars:
// fc.transportEnvelopeIdMaxChars}). Usage sites mention the keys without
// `key:value` forms, so candidates are ranked by how many limits actually
// PARSE in their window — the definition window wins.
function extractAdapterLimits(texts) {
  const parseWindow = (win) => {
    const entries = {};
    for (const k of RELAY_ADAPTER_LIMITS) {
      const km = win.match(new RegExp(`\\b${k}\\s*[:=]\\s*([^,}\\n]{1,80})`));
      if (km) entries[k] = normLimitValue(km[1]);
    }
    return entries;
  };
  let best = null;
  for (const { label, text } of texts) {
    let idx = 0;
    while ((idx = text.indexOf("maxPhysicalFrameBytes", idx)) >= 0) {
      const win = text.slice(idx, idx + 400);
      const entries = parseWindow(win);
      const parsed = Object.keys(entries).length;
      if (parsed >= 3 && (!best || parsed > best.parsedCount)) {
        best = { entries, label, parsedCount: parsed };
      }
      idx += 1;
      if (best && best.parsedCount === RELAY_ADAPTER_LIMITS.length) break;
    }
    if (best && best.parsedCount === RELAY_ADAPTER_LIMITS.length) break;
  }
  if (best) return { entries: best.entries, label: best.label };
  const entries = {};
  let label = null;
  for (const k of RELAY_ADAPTER_LIMITS) {
    const hit = firstMatch(texts, () => new RegExp(`\\b${k}\\s*[:=]\\s*([^,}\\n]{1,80})`));
    if (hit) {
      entries[k] = normLimitValue(hit.value[1]);
      label ??= hit.label;
    }
  }
  return { entries, label };
}

export function extractRelaySurfaceTexts(texts, { generatedAt = null, source = {} } = {}) {
  const closeCodes = {};
  const adapterLimits = {};
  const envelopeFields = {};
  const errorCodes = {};
  const perFamilySource = {};
  const occurrences = {};

  const allText = texts.map((t) => t.text).join("\n");

  for (const name of RELAY_CLOSE_CODES) {
    const hit = firstMatch(texts, () => new RegExp(`\\b${name}"?\\s*[:=]\\s*${NUM_TOKEN}`));
    if (hit) {
      closeCodes[name] = safeNum(hit.value[1]);
      perFamilySource.closeCodes ??= hit.label;
    }
  }

  const adapter = extractAdapterLimits(texts);
  for (const [k, v] of Object.entries(adapter.entries)) adapterLimits[k] = v;
  if (adapter.label) perFamilySource.adapterLimits = adapter.label;

  for (const field of RELAY_ENVELOPE_FIELDS) {
    const pattern = /^[a-z_][\w$]*$/.test(field)
      ? new RegExp(`\\b${field}\\b`, "g")
      : new RegExp(`"${field.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}"`, "g");
    const n = (allText.match(pattern) ?? []).length;
    occurrences[field] = n;
    envelopeFields[field] = n > 0 ? "present" : "absent";
  }

  for (const code of RELAY_ERROR_CODES) {
    const n = (allText.match(new RegExp(`\\b${code}\\b`, "g")) ?? []).length;
    occurrences[code] = n;
    errorCodes[code] = n > 0 ? "present" : "absent";
  }

  return {
    schemaVersion: 1,
    kind: "relay-surface",
    generatedAt: generatedAt ?? new Date().toISOString(),
    source,
    layers: {
      closeCodes: { entries: sortedEntries(closeCodes) },
      adapterLimits: { entries: sortedEntries(adapterLimits) },
      envelopeFields: { entries: sortedEntries(envelopeFields) },
      errorCodes: { entries: sortedEntries(errorCodes) },
    },
    meta: { perFamilySource, occurrences },
  };
}

// ---------------------------------------------------------------------------
// diff
// ---------------------------------------------------------------------------

function consumedOf(layer, manifest) {
  return manifest?.consumed?.[`${layer}/*`] ?? true;
}

function classify(kind, consumed) {
  if (kind === "added") return "additive";
  return consumed ? "breaking" : "additive";
}

export function diffRelaySurfaces({ baseline, current, manifest = { consumed: {} } }) {
  const items = [];
  const layers = {};
  for (const layer of ["closeCodes", "adapterLimits", "envelopeFields", "errorCodes"]) {
    layers[layer] = { added: [], changed: [], removed: [] };
  }

  for (const layer of ["closeCodes", "adapterLimits"]) {
    const consumed = consumedOf(layer, manifest);
    const b = baseline.layers[layer].entries;
    const c = current.layers[layer].entries;
    for (const name of [...new Set([...Object.keys(b), ...Object.keys(c)])].sort()) {
      const had = Object.prototype.hasOwnProperty.call(b, name);
      const has = Object.prototype.hasOwnProperty.call(c, name);
      if (had && !has) {
        items.push({ layer, kind: "removed", name, detail: `${layer} constant no longer found in bundle`, consumed, classification: classify("removed", consumed) });
        layers[layer].removed.push(name);
      } else if (!had && has) {
        items.push({ layer, kind: "added", name, detail: `new ${layer} constant in bundle`, consumed, classification: classify("added", consumed) });
        layers[layer].added.push(name);
      } else if (had && has && b[name] !== c[name]) {
        items.push({ layer, kind: "changed", name, detail: `${b[name]} -> ${c[name]}`, consumed, classification: "breaking" });
        layers[layer].changed.push(name);
      }
    }
  }

  for (const layer of ["envelopeFields", "errorCodes"]) {
    const consumed = consumedOf(layer, manifest);
    const b = baseline.layers[layer].entries;
    const c = current.layers[layer].entries;
    for (const name of [...new Set([...Object.keys(b), ...Object.keys(c)])].sort()) {
      if (b[name] === c[name]) continue;
      const kind = b[name] === "present" ? "removed" : "added";
      const detail = kind === "removed"
        ? `${layer.replace(/s$/, "")} literal disappeared from bundle`
        : `${layer.replace(/s$/, "")} literal appeared in bundle`;
      items.push({ layer, kind, name, detail, consumed, classification: classify(kind, consumed) });
      layers[layer][kind].push(name);
    }
  }

  items.sort((a, b) => `${a.layer}/${a.kind}/${a.name}`.localeCompare(`${b.layer}/${b.kind}/${b.name}`));
  const breaking = items.filter((i) => i.classification === "breaking").length;
  const summary = {
    added: items.filter((i) => i.kind === "added").length,
    changed: items.filter((i) => i.kind === "changed").length,
    removed: items.filter((i) => i.kind === "removed").length,
    breaking,
    bodyChanged: 0,
  };
  return {
    schemaVersion: 1,
    kind: "relay-drift-report",
    generatedAt: new Date().toISOString(),
    verdict: breaking > 0 ? "BREAKING" : "ADDITIVE",
    reviewRecommended: false,
    summary,
    layers,
    items,
    check: { baselineSource: baseline.source ?? null, currentSource: current.source ?? null },
  };
}
