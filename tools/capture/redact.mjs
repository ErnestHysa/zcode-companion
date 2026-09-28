#!/usr/bin/env node
// Redaction tool — Phase 1.1 (security-critical, dev-time only).
// Converts a raw relay capture (JSONL: {dir, ts, msg}) into a sanitized fixture.
// Contract: docs/runbooks/capture.md. Property test: redact.test.mjs.
import { readFileSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";

// Fixed synthetic replacements (format-preserving, non-identifying).
const SYNTH = {
  hash: "SYNTH-SHA256-BASE64-44chars-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
  proof: "SYNTH-PROOF",
  nonce: "SYNTH-NONCE",
  deviceSid: "d_SYNTHTOKEN000000000000",
  deviceMid: "synth-device-mid",
  bridgeSessionId: "synth-bridge-session",
  recoveryId: "synth-recovery",
  windowControlSessionId: "synth-window-control",
  workspacePath: "/synthetic/ws",
  workspaceIdentity: "synth-workspace-identity",
  remoteSessionId: "synth-remote-session",
  label: "Synthetic Workspace",
  name: "Synthetic Name",
  title: "Synthetic Task",
  syntheticPayload: "c3ludGg=", // "synth"
};

const SECRET_FIELDS = new Set([
  "hash", "pass_hash", "proof", "nonce", "passHash",
]);
const TOKEN_FIELDS = new Set([
  "device_sid", "deviceSid", "device_mid", "deviceMid",
  "bridgeSessionId", "recoveryId", "windowControlSessionId",
  "workspaceKey", "workspaceIdentity", "remoteSessionId", "activeWorkspaceKey",
  "activeTaskId", "task_id", "taskId", "session_id", "sessionId",
  "id", "requestId", "subscriptionId", "connectionId", "client_id", "clientId",
]);
const CONTENT_FIELDS = new Set([
  "workspacePath", "label", "name", "title", "userAgent", "timezone",
  "model", "mode", "provider", "directory", "slug", "project", "path", "cwd",
]);

function crc32Wire(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return ((crc ^ 0xffffffff) >>> 0).toString(16).padStart(8, "0");
}

// Deterministic synthetic payload of a target byte length (content destroyed,
// transport geometry preserved).
function syntheticBytes(len) {
  const out = Buffer.alloc(len);
  const digest = createHash("sha256").update(`synth-${len}`).digest();
  for (let i = 0; i < len; i += 1) out[i] = digest[i % digest.length];
  return out;
}

const PATHY_RE = /(^|\s)[\/](Users|home)[\/][A-Za-z0-9_.-]+[\/]|^[A-Za-z]:[\\\\]/;

function redactValue(v, keyHint, report, depth, inPayload) {
  if (typeof v === "string") {
    // d_<base62> device sids can appear as VALUES anywhere (e.g. client ids)
    if (/^d_[A-Za-z0-9]{6,40}$/.test(v)) {
      report.replacements.push({ field: keyHint || "(string)", class: "deviceSid-in-value" });
      return SYNTH.deviceSid;
    }
    // QR link shapes anywhere: ...remote/v4?sid=...&hash=...
    if (/remote\/v[34]\?|hash=/.test(v)) {
      report.replacements.push({ field: keyHint || "(string)", class: "qr-link" });
      return "https://synthetic.invalid/remote/v4?sid=d_SYNTH&hash=SYNTH";
    }
    // Filesystem-path shapes anywhere (runbook: never commit personal paths).
    if (PATHY_RE.test(v)) {
      report.replacements.push({ field: keyHint || "(string)", class: "path-in-value" });
      return SYNTH.workspacePath;
    }
    // Unknown key carrying substantial text inside a business payload: redact (runbook
    // default — when in doubt, redact). Short strings (type names, ids, enum values) stay.
    if (keyHint === "dataBase64") {
      // rpc-frame payloads are handled by the synthetic-bytes branch; never fallback-redact.
      return v;
    }
    if (inPayload && v.length > 24 && !SECRET_FIELDS.has(keyHint) && !TOKEN_FIELDS.has(keyHint) && !CONTENT_FIELDS.has(keyHint)) {
      report.replacements.push({ field: keyHint || "(string)", class: "unknown-long-string" });
      return "synth-" + createHash("sha256").update(v).digest("hex").slice(0, 16);
    }
    if (SECRET_FIELDS.has(keyHint)) {
      report.replacements.push({ field: keyHint, class: "secret" });
      return SYNTH.nonce;
    }
    if (TOKEN_FIELDS.has(keyHint)) {
      report.replacements.push({ field: keyHint, class: "token" });
      if (/^d_/.test(v) || keyHint === "deviceSid" || keyHint === "device_sid") return SYNTH.deviceSid;
      if (keyHint.includes("bridge")) return SYNTH.bridgeSessionId;
      if (keyHint.includes("recovery")) return SYNTH.recoveryId;
      return "synth-" + createHash("sha256").update(v).digest("hex").slice(0, 12);
    }
    if (CONTENT_FIELDS.has(keyHint) && v.length > 0) {
      report.replacements.push({ field: keyHint, class: "content" });
      if (v.startsWith("/") || v.includes("/")) return SYNTH.workspacePath;
      if (keyHint === "name") return SYNTH.name;
      if (keyHint === "title") return SYNTH.title;
      return SYNTH.label;
    }
    return v;
  }
  if (Array.isArray(v)) return v.map((x) => redactValue(x, keyHint, report, depth + 1, inPayload));
  if (v && typeof v === "object") {
    const out = {};
    for (const [k, val] of Object.entries(v)) {
      if (SECRET_FIELDS.has(k)) {
        report.replacements.push({ field: k, class: "secret" });
        out[k] = k.toLowerCase().includes("nonce") ? SYNTH.nonce : SYNTH.hash;
        continue;
      }
      if (TOKEN_FIELDS.has(k)) {
        report.replacements.push({ field: k, class: "token" });
        const s = String(val);
        if (/^d_/.test(s)) out[k] = SYNTH.deviceSid;
        else if (k === "bridgeSessionId") out[k] = SYNTH.bridgeSessionId;
        else if (k === "recoveryId") out[k] = SYNTH.recoveryId;
        else if (k.toLowerCase().includes("mid")) out[k] = SYNTH.deviceMid;
        else out[k] = "synth-" + createHash("sha256").update(String(val)).digest("hex").slice(0, 12);
        continue;
      }
      if (CONTENT_FIELDS.has(k) && typeof val === "string" && val.length > 0) {
        report.replacements.push({ field: k, class: "content" });
        if (val.startsWith("/") || val.includes("/")) out[k] = SYNTH.workspacePath;
        else if (k === "name") out[k] = SYNTH.name;
        else if (k === "title") out[k] = SYNTH.title;
        else out[k] = SYNTH.label;
        continue;
      }
      out[k] = redactValue(val, k, report, depth + 1, inPayload);
    }
    return out;
  }
  return v;
}

function redactMessage(msg, report) {
  if (msg && msg.type === "data" && msg.payload) {
    const p = msg.payload;
    if (p.zcode_type === "rpc-frame") {
      // Destroy payload content; preserve size class + transport fields.
      const decoded = Buffer.from(p.dataBase64, "base64");
      const synth = syntheticBytes(decoded.length);
      p.dataBase64 = synth.toString("base64");
      p.messageBytes = synth.length === decoded.length ? p.messageBytes : synth.length;
      if (p.checksum && p.checksum.algorithm === "crc32") {
        p.checksum.value = crc32Wire(synth);
      }
      report.replacements.push({ field: "dataBase64", class: "rpc-frame-payload", bytes: decoded.length });
      const out = redactValue({ ...p }, "payload", report, 0, true);
      return { ...msg, payload: out };
    }
    const out = redactValue({ ...p }, "payload", report, 0);
    return { ...msg, payload: out };
  }
  return redactValue({ ...msg }, "msg", report, 0);
}

function main() {
  const args = process.argv.slice(2);
  const opt = (name) => {
    const i = args.indexOf(name);
    return i >= 0 ? args[i + 1] : undefined;
  };
  const inPath = opt("--in");
  const outPath = opt("--out");
  const reportPath = opt("--report");
  if (!inPath || !outPath) {
    console.error("usage: redact.mjs --in <capture.jsonl> --out <fixture.jsonl> [--report r.json]");
    process.exit(2);
  }
  const report = { tool: "redact.mjs v1", replacements: [], lines: 0 };
  const lines = readFileSync(inPath, "utf8").split("\n").filter((l) => l.trim().length > 0);
  const outLines = [];
  for (const line of lines) {
    report.lines += 1;
    const entry = JSON.parse(line); // {dir, ts, msg}
    const redacted = redactMessage(entry.msg, report);
    outLines.push(JSON.stringify({ dir: entry.dir, ts: entry.ts, msg: redacted }));
  }
  writeFileSync(outPath, outLines.join("\n") + "\n");
  if (reportPath) writeFileSync(reportPath, JSON.stringify(report, null, 2));
  console.log(`redacted ${report.lines} lines -> ${outPath} (${report.replacements.length} replacements)`);
}

main();
