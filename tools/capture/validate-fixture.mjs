#!/usr/bin/env node
// Fixture validator — structural schema check + INV-15/16 secret-pattern lint.
// Usage: node tools/capture/validate-fixture.mjs <fixture-file(s)...>
import { readFileSync } from "node:fs";

const SECRET_PATTERNS = [
  [/remote\/v[34]\?sid=d_/i, "QR pairing link"],
  [/"hash"\s*:\s*"(?!SYNTH)[A-Za-z0-9+/=]{30,}"/, "pass hash"],
  [/"pass_hash"\s*:\s*"(?!SYNTH)/, "pass hash (register)"],
  [/"proof"\s*:\s*"(?!SYNTH-PROOF)[A-Za-z0-9_-]{12,}"/, "auth proof"],
  [/"nonce"\s*:\s*"(?!SYNTH-NONCE)/, "auth nonce"],
  [/\/Users\/[A-Za-z0-9_]/, "macOS user path"],
  [/\/home\/[A-Za-z0-9_]\//, "unix home path"],
  [/sk-[A-Za-z0-9]{16,}/, "API key shape"],
  [/[A-Za-z0-9+/]{88}AAAA/, "gcp-style key shape"],
];

function crc32Wire(bytes) {
  let crc = 0xffffffff;
  for (const b of bytes) {
    crc ^= b;
    for (let i = 0; i < 8; i += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return ((crc ^ 0xffffffff) >>> 0).toString(16).padStart(8, "0");
}

// rpc-frame payloads are synthetic-but-size-preserving: exempt them from the size lint,
// instead DECODE each and scan the decoded content for secret patterns + verify CRC.
function scanRpcFrames(doc, problems) {
  for (const entry of doc.messages ?? []) {
    const p = entry.msg?.payload;
    if (p?.zcode_type !== "rpc-frame") continue;
    let decoded = null;
    try {
      decoded = Buffer.from(p.dataBase64, "base64");
    } catch {
      problems.push("rpc-frame dataBase64 not valid base64");
      continue;
    }
    if (p.checksum?.algorithm === "crc32" && crc32Wire(decoded) !== p.checksum.value) {
      problems.push(`rpc-frame crc32 mismatch (seq=${p.seq})`);
    }
    const text = decoded.toString("utf8");
    for (const [re, label] of SECRET_PATTERNS) {
      if (re.test(text)) problems.push(`decoded rpc-frame content: ${label}`);
    }
  }
}

function validate(path) {
  const raw = readFileSync(path, "utf8");
  let doc;
  try {
    doc = JSON.parse(raw);
  } catch (e) {
    // fixtures are JSONL of {dir,ts,msg} or a single JSON object — support both
    const lines = raw.split("\n").filter((l) => l.trim());
    doc = {
      metadata: { source: "raw-jsonl", zcodeVersion: "0.0.0", capturedAt: "unknown", sanitized: true, sanitizationTool: "redact.mjs", expectedInterpretation: "see messages" },
      messages: lines.map((l) => JSON.parse(l)),
    };
  }
  const problems = [];
  if (!doc.metadata) problems.push("missing metadata");
  else {
    for (const k of ["source", "zcodeVersion", "capturedAt", "sanitized", "sanitizationTool", "expectedInterpretation"]) {
      if (!(k in doc.metadata)) problems.push(`metadata missing '${k}'`);
    }
    if (doc.metadata.sanitized !== true) problems.push("metadata.sanitized must be true");
  }
  const msgs = doc.messages ?? [];
  if (!Array.isArray(msgs) || msgs.length === 0) problems.push("no messages");

  const text = JSON.stringify(doc);
  for (const [re, label] of SECRET_PATTERNS) {
    if (re.test(text)) problems.push(`INV-15/16 pattern detected: ${label}`);
  }
  scanRpcFrames(doc, problems);
  if (problems.length > 0) {
    console.error(`FAIL ${path}`);
    for (const p of problems) console.error("  -", p);
    process.exitCode = 1;
  } else {
    console.log(`OK ${path} (${msgs.length} messages)`);
  }
}

for (const p of process.argv.slice(2)) validate(p);
if (process.argv.length < 3) {
  console.error("usage: validate-fixture.mjs <fixture...>");
  process.exit(2);
}
