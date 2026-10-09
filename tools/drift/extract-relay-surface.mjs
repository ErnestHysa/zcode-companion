#!/usr/bin/env node
// the sensors bundle relay-surface extractor (project roadmap Phase 5, Mac leg).
// Scans the SHIPPED desktop bundle text (zcode.cjs + app.asar) for the
// Phase-1-corpus relay-adapter constant families and emits a relay-surface JSON:
//
//   node tools/drift/extract-relay-surface.mjs \
//     --asar /Applications/ZCode.app/Contents/Resources/app.asar \
//     --cjs  /Applications/ZCode.app/Contents/Resources/glm/zcode.cjs \
//     --out relay-current.json
//
// app.asar is scanned in bounded chunks (the file is ~330 MB); zcode.cjs is read
// whole. Files are never executed.
import { createHash } from "node:crypto";
import { openSync, readSync, closeSync, statSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { extractRelaySurfaceTexts } from "./relay-lib.mjs";

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

function sha256File(path) {
  const h = createHash("sha256");
  const fd = openSync(path, "r");
  try {
    const buf = Buffer.alloc(1024 * 1024);
    for (;;) {
      const n = readSync(fd, buf, 0, buf.length, null);
      if (n === 0) break;
      h.update(n === buf.length ? buf : buf.subarray(0, n));
    }
  } finally {
    closeSync(fd);
  }
  return h.digest("hex");
}

// Read a file as latin-1 text in bounded chunks with overlap (pattern spans are
// far smaller than the overlap). Returns the chunk texts.
function chunkedText(path, chunkBytes = 16 * 1024 * 1024, overlap = 64 * 1024) {
  const size = statSync(path).size;
  const chunks = [];
  const fd = openSync(path, "r");
  try {
    for (let off = 0; off < size; off += chunkBytes - overlap) {
      const len = Math.min(chunkBytes, size - off);
      const buf = Buffer.alloc(len);
      readSync(fd, buf, 0, len, off);
      chunks.push(buf.toString("latin1"));
      if (off + len >= size) break;
    }
  } finally {
    closeSync(fd);
  }
  return chunks;
}

const asarPath = arg("asar");
const cjsPath = arg("cjs");
const outPath = arg("out");
if (!asarPath && !cjsPath) {
  console.error("usage: extract-relay-surface.mjs [--asar <app.asar>] [--cjs <zcode.cjs>] [--out <path>] [--desktop-version <v>]");
  process.exit(2);
}

const texts = [];
const source = {};
if (cjsPath) {
  const p = resolve(cjsPath);
  const { size } = statSync(p);
  texts.push({ label: "zcode.cjs", text: chunkedText(p).join("") });
  source.cjsPath = p;
  source.cjsSha256 = sha256File(p);
  source.cjsBytes = size;
}
if (asarPath) {
  const p = resolve(asarPath);
  const { size } = statSync(p);
  texts.push({ label: "app.asar", text: chunkedText(p).join("") });
  source.appPath = p;
  source.appSha256 = sha256File(p);
  source.appBytes = size;
}
source.desktopVersion = arg("desktop-version") ?? null;
source.scannedAt = new Date().toISOString();

// Occurrence counts across chunked reads are approximate (overlap); presence
// semantics are exact.
const surface = extractRelaySurfaceTexts(texts, { source });
surface.meta.occurrencesApproximate = texts.length > 0;

const json = JSON.stringify(surface, null, 2) + "\n";
if (outPath) {
  writeFileSync(resolve(outPath), json);
  const presentCount = (l) => Object.values(surface.layers[l].entries).filter((v) => v === "present").length;
  console.error(
    `relay surface written: ${outPath} (` +
    `closeCodes=${Object.keys(surface.layers.closeCodes.entries).length}, ` +
    `adapterLimits=${Object.keys(surface.layers.adapterLimits.entries).length}, ` +
    `envelopeFields present=${presentCount("envelopeFields")}/${Object.keys(surface.layers.envelopeFields.entries).length}, ` +
    `errorCodes present=${presentCount("errorCodes")}/${Object.keys(surface.layers.errorCodes.entries).length})`,
  );
} else {
  process.stdout.write(json);
}
