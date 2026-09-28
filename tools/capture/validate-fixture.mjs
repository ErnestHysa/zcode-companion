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
  [/"dataBase64"\s*:\s*"[A-Za-z0-9+/=]{200,}"/, "large raw payload (must be synthetic)"],
  [/\/Users\/[A-Za-z0-9_]/, "macOS user path"],
  [/\/home\/[A-Za-z0-9_]\//, "unix home path"],
  [/sk-[A-Za-z0-9]{16,}/, "API key shape"],
  [/[A-Za-z0-9+/]{88}AAAA/, "gcp-style key shape"],
];

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
