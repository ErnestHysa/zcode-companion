// Redaction property test (§43.2-1.1): no prohibited substring survives the redactor,
// whatever the input. Run: node --test tools/capture/redact.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";

const TOOL = new URL("./redact.mjs", import.meta.url).pathname;

function runRedactor(lines) {
  const dir = mkdtempSync(join(tmpdir(), "redact-test-"));
  const inPath = join(dir, "in.jsonl");
  const outPath = join(dir, "out.jsonl");
  writeFileSync(inPath, lines.map((l) => JSON.stringify(l)).join("\n") + "\n");
  execFileSync(process.execPath, [TOOL, "--in", inPath, "--out", outPath]);
  const out = readFileSync(outPath, "utf8").split("\n").filter((l) => l.trim()).map((l) => JSON.parse(l));
  rmSync(dir, { recursive: true, force: true });
  return out;
}

const SECRETS = {
  passHash: "KkZ9vbase64hashValue44charsLongHereAAAAAAAAAAAAAAAAAAA=", // realistic 44-char shape
  proof: "hmac-proof-base64url-value-AbC123_-",
  nonce: "server-nonce-9f8e7d6c5b4a",
  qrLink: "https://zcode.z.ai/remote/v4?sid=d_SyntheticSid00000000001&hash=KkZ9vbase64&t=1727500000000&mid=mid-123",
  deviceSid: "d_SyntheticSid00000000001",
  prompt: "Fix the auth bug in /Users/ernest/secretproject/src/auth.ts without breaking tests",
  personalPath: "/Users/ernest/secretproject/src/auth.ts",
};

function assertNoneSurvive(output, forbidden) {
  const text = JSON.stringify(output);
  for (const s of forbidden) {
    assert.ok(!text.includes(s), `prohibited substring survived: ${s.slice(0, 40)}…`);
  }
}

test("auth handshake secrets never survive", () => {
  const out = runRedactor([
    { dir: "send", ts: 1, msg: { type: "auth_init", role: "terminal", device_sid: SECRETS.deviceSid, meta: { name: "Ernest MacBook" }, client_ts: 1 } },
    { dir: "recv", ts: 2, msg: { type: "auth_challenge", nonce: SECRETS.nonce } },
    { dir: "send", ts: 3, msg: { type: "auth_response", device_sid: SECRETS.deviceSid, proof: SECRETS.proof, client_ts: 3 } },
    { dir: "recv", ts: 4, msg: { type: "device_register_init", device_mid: "mid-value-1", pass_hash: SECRETS.passHash, meta: {}, client_ts: 4 } },
  ]);
  assertNoneSurvive(out, [SECRETS.proof, SECRETS.nonce, SECRETS.passHash, SECRETS.deviceSid]);
});

test("QR links are destroyed wherever they appear", () => {
  const out = runRedactor([
    { dir: "recv", ts: 1, msg: { type: "data", payload: { zcode_type: "app-error", error: SECRETS.qrLink } } },
    { dir: "send", ts: 2, msg: { note: SECRETS.qrLink } },
  ]);
  assertNoneSurvive(out, [SECRETS.qrLink, SECRETS.deviceSid, SECRETS.passHash]);
});

test("rpc-frame payload content is destroyed while transport geometry survives", () => {
  const payload = Buffer.from(JSON.stringify({ big: SECRETS.prompt, pad: "x".repeat(500) }));
  const frame = {
    dir: "recv", ts: 1,
    msg: {
      type: "data",
      payload: {
        zcode_type: "rpc-frame", bridgeSessionId: "bridge-1", seq: 1, messageSeq: 7,
        fragmentIndex: 0, fragmentCount: 1, messageBytes: payload.length,
        checksum: { algorithm: "crc32", value: "deadbeef" }, dataBase64: payload.toString("base64"),
      },
    },
  };
  const out = runRedactor([frame]);
  assertNoneSurvive(out, [SECRETS.prompt, payload.toString("base64")]);
  const redactedFrame = out[0].msg.payload;
  assert.equal(redactedFrame.messageBytes, payload.length, "messageBytes preserved");
  assert.equal(Buffer.from(redactedFrame.dataBase64, "base64").length, payload.length, "payload size preserved");
  // CRC recomputed over the synthetic bytes
  let crc = 0xffffffff;
  for (const b of Buffer.from(redactedFrame.dataBase64, "base64")) {
    crc ^= b;
    for (let i = 0; i < 8; i += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  const hex = ((crc ^ 0xffffffff) >>> 0).toString(16).padStart(8, "0");
  assert.equal(redactedFrame.checksum.value, hex, "checksum matches synthetic bytes");
});

test("personal paths and task titles never survive bootstrap payloads", () => {
  const out = runRedactor([
    {
      dir: "recv", ts: 1,
      msg: {
        type: "data",
        payload: {
          zcode_type: "workspace-list-response", requestId: "r1", success: true,
          result: {
            workspaces: [
              { workspacePath: SECRETS.personalPath, label: "secretproject", kind: "local", workspaceIdentity: "ident-1" },
            ],
            tasks: [{ id: "t-1", title: "Fix my auth bug", status: "running" }],
          },
        },
      },
    },
  ]);
  assertNoneSurvive(out, [SECRETS.personalPath, "secretproject", "Fix my auth bug", "ident-1"]);
});

test("fuzz: adversarial mixtures never leak any secret", () => {
  const lines = [];
  for (let i = 0; i < 200; i += 1) {
    const kind = i % 5;
    if (kind === 0) lines.push({ dir: "send", ts: i, msg: { type: "auth_response", device_sid: SECRETS.deviceSid, proof: SECRETS.proof + i, client_ts: i } });
    else if (kind === 1) lines.push({ dir: "recv", ts: i, msg: { type: "data", payload: { zcode_type: "rpc-frame", bridgeSessionId: `bridge-${i}`, seq: i, messageSeq: i, fragmentIndex: 0, fragmentCount: 1, messageBytes: 10, checksum: { algorithm: "crc32", value: "00000000" }, dataBase64: Buffer.from(SECRETS.personalPath + i).toString("base64") } } });
    else if (kind === 2) lines.push({ dir: "recv", ts: i, msg: { type: "data", payload: { zcode_type: "bootstrap-response", requestId: `r-${i}`, success: true, result: { windowControlSessionId: `wc-${i}`, workspaces: [{ workspacePath: `${SECRETS.personalPath}-${i}`, label: `repo-${i}`, kind: "local" }], tasks: [] } } } });
    else if (kind === 3) lines.push({ dir: "send", ts: i, msg: { type: "pair_status_query", device_sid: SECRETS.deviceSid, client_ts: i } });
    else lines.push({ dir: "recv", ts: i, msg: { weird: { nested: [SECRETS.qrLink, { deeper: SECRETS.prompt }] } } });
  }
  const out = runRedactor(lines);
  assertNoneSurvive(out, [
    SECRETS.deviceSid, SECRETS.personalPath, SECRETS.proof,
  ]);
  // Proof values had per-line suffixes — check base + one suffix.
  assert.ok(!JSON.stringify(out).includes("hmac-proof-base64url-value"), "proof base survived");
});

test("output is well-formed JSONL with dir/ts/msg preserved", () => {
  const out = runRedactor([{ dir: "send", ts: 42, msg: { type: "auth_init", role: "terminal", device_sid: SECRETS.deviceSid, meta: {}, client_ts: 42 } }]);
  assert.equal(out.length, 1);
  assert.equal(out[0].dir, "send");
  assert.equal(out[0].ts, 42);
  assert.ok(out[0].msg && typeof out[0].msg === "object");
});
