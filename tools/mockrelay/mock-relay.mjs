#!/usr/bin/env node
// MockZCode relay — Phase 1 test harness (S35.4 incremental rule: only what Phase 1 needs).
// Implements: auth challenge/proof (server side), pair_status_ack, heartbeat ack,
// bootstrap-response, workspace-list-response, workspace-bridge-ready, rpc-frame echo,
// rpc-frame-ack. Dev-time test tooling; NEVER shipped in the app; synthetic data only.
//
// Usage: node mock-relay.mjs --port 8091 --pass-hash <hash> --sid <d_...> [--wrong-nonce]
import { createHash, createHmac, randomBytes } from "node:crypto";
import http from "node:http";
import crypto from "node:crypto";

const args = process.argv.slice(2);
const opt = (n, d) => {
  const i = args.indexOf("--" + n);
  return i >= 0 ? args[i + 1] : d;
};
const PORT = Number(opt("port", 8091));
const PASS_HASH = opt("pass-hash", "synthetic-passhash-key");
const EXPECTED_SID = opt("sid", "d_syntheticdevice000001");
const SCENARIO = opt("scenario", "happy"); // happy | wrong-proof | double-terminal

// WebSocket server over raw http (RFC 6455, minimal: text frames, no fragmentation in).
const server = http.createServer((req, res) => {
  res.writeHead(426);
  res.end("websocket only");
});

const clients = new Map(); // ws -> {role, sid, paired}

server.on("upgrade", (req, socket) => {
  const key = req.headers["sec-websocket-key"];
  if (!key) { socket.destroy(); return; }
  const accept = createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );
  socket.setNoDelay(true);
  let authed = false;
  let clientSid = null;
  let nonceIssued = null;
  let proofAnswered = false;

  let sendCount = 0;
  const send = (obj) => {
    const payload = Buffer.from(JSON.stringify(obj), "utf8");
    sendCount += 1;
    console.log("[mock] send#" + sendCount + " type=" + obj.type + " zt=" + (obj.payload?.zcode_type ?? "") + " bytes=" + payload.length);
    const header = Buffer.alloc(2);
    header[0] = 0x81; // FIN + text
    if (payload.length < 126) {
      header[1] = payload.length;
      socket.write(Buffer.concat([header, payload]));
    } else {
      header[1] = 126;
      const ext = Buffer.alloc(2);
      ext.writeUInt16BE(payload.length);
      socket.write(Buffer.concat([header, ext, payload]));
    }
  };

  const challengeAndPair = (role) => {
    nonceIssued = randomBytes(16).toString("hex");
    send({ type: "auth_challenge", nonce: nonceIssued });
    // server-side expected proof (mirrors desktop construction exactly)
    const expected = createHmac("sha256", PASS_HASH)
      .update(`${nonceIssued}|${role}|${clientSid}`)
      .digest("base64url");
    return expected;
  };

  const bridges = new Map(); // recoveryId -> last rpc-frame payload bytes
  let buffer = Buffer.alloc(0);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    // parse frames
    while (buffer.length >= 2) {
      const opcode = buffer[0] & 0x0f;
      const masked = (buffer[1] & 0x80) !== 0;
      let len = buffer[1] & 0x7f;
      let off = 2;
      if (len === 126) {
        if (buffer.length < 4) return;
        len = buffer.readUInt16BE(2);
        off = 4;
      } else if (len === 127) {
        if (buffer.length < 10) return;
        len = Number(buffer.readBigUInt64BE(2));
        off = 10;
      }
      if (!masked) { socket.destroy(); return; }
      const maskKey = buffer.subarray(off, off + 4);
      off += 4;
      if (buffer.length < off + len) return;
      const payloadBuf = Buffer.from(buffer.subarray(off, off + len));
      for (let i = 0; i < payloadBuf.length; i += 1) payloadBuf[i] ^= maskKey[i % 4];
      buffer = buffer.subarray(off + len);
      if (opcode === 0x8) { // close
        socket.end();
        return;
      }
      if (opcode === 0x9) { // ping -> pong
        const pong = Buffer.from([0x8a, payloadBuf.length + 128]);
        socket.write(Buffer.concat([Buffer.from([0x8a, 0x80 | payloadBuf.length]), maskKey, payloadBuf]));
        continue;
      }
      if (opcode !== 0x1) continue; // text only for this mock
      const text = payloadBuf.toString("utf8");
      console.log("[mock] frame opcode=" + opcode + " text=" + text.slice(0, 80));
      let msg;
      try { msg = JSON.parse(text); } catch (e) { console.log("[mock] JSON parse fail:", e.message); continue; }
      try {

      if (!authed) {
        if (msg.type === "auth_init" && msg.role === "terminal") {
          clientSid = msg.device_sid;
          clients.set(socket, { role: msg.role, sid: clientSid });
          if (clientSid !== EXPECTED_SID) {
            send({ type: "error", code: "AUTH_FAILED", message: "mock: unknown device_sid" });
            continue;
          }
          const expected = challengeAndPair(msg.role);
          // store expected proof for verification when auth_response arrives
          socket._expectedProof = expected;
          socket._proofSent = () => {
            send({ type: "auth_ack", pair_status: "matched" });
            send({ type: "pair_status_ack", pair_status: "matched" });
            authed = true;
            clients.get(socket).paired = true;
          };
          continue;
        }
        if (msg.type === "auth_response") {
          if (msg.proof === socket._expectedProof) socket._proofSent();
          else send({ type: "error", code: "AUTH_FAILED", message: "mock: proof mismatch" });
          continue;
        }
        if (msg.type === "device_register_init") {
          send({ type: "device_register_ack", device_sid: EXPECTED_SID });
          socket._expectedProof = null;
          // register then immediate auth_init expected
          continue;
        }
        continue;
      }

      // authenticated flows
      // App payloads arrive inside {type:"data",payload:{zcode_type:...}}} envelopes.
      if (msg.type === "data" && msg.payload && typeof msg.payload === "object") {
        const p = msg.payload;
        if (p.zcode_type === "bootstrap-request") {
          msg.requestId = p.requestId;
          msg.type = "bootstrap-request";
          // fall through to legacy branch via dispatch
        } else if (p.zcode_type === "workspace-list-request") {
          msg.requestId = p.requestId;
          msg.type = "workspace-list-request";
        } else if (p.zcode_type === "workspace-bridge-open") {
          msg.requestId = p.requestId;
          msg.type = "workspace-bridge-open";
          Object.assign(msg, p);
          // Minimal replay semantics: recovery by recoveryId replays the bridge's last frame.
          if (p.recoveryId && bridges.has(p.recoveryId)) {
            setImmediate(() => sendRpcFrame(p.bridgeSessionId, bridges.get(p.recoveryId)));
          }
        } else if (p.zcode_type === "rpc-frame") {
          msg.payload = p;
          // fall through to rpc-frame handling below
        } else {
          continue;
        }
      }
      if (msg.type === "bootstrap-request") {
        send({
          type: "data",
          payload: {
            zcode_type: "bootstrap-response",
            requestId: msg.requestId,
            success: true,
            result: {
              windowControlSessionId: "synth-window-1",
              desktopAppVersion: "3.14.3",
              workspaces: [
                { workspacePath: "/synthetic/ws-one", label: "Synthetic One", kind: "local", workspaceKey: "synthetic-ws-one" },
              ],
              tasks: [{ id: "synth-task-1", title: "Synthetic task", status: "idle" }],
            },
          },
          server_ts: Date.now(),
        });
        continue;
      }
      if (msg.type === "workspace-list-request") {
        send({
          type: "data",
          payload: {
            zcode_type: "workspace-list-response",
            requestId: msg.requestId,
            success: true,
            result: {
              workspaces: [
                { workspacePath: "/synthetic/ws-one", label: "Synthetic One", kind: "local", workspaceKey: "synthetic-ws-one" },
              ],
              tasks: [{ id: "synth-task-1", title: "Synthetic task", status: "idle" }],
              activeWorkspaceKey: "synthetic-ws-one",
            },
          },
        });
        continue;
      }
      if (msg.type === "workspace-bridge-open") {
        send({
          type: "data",
          payload: {
            zcode_type: "workspace-bridge-ready",
            requestId: msg.requestId,
            bridgeSessionId: msg.bridgeSessionId,
            bridgeGeneration: 0,
            bridge: {
              kind: "local",
              bridgeSessionId: msg.bridgeSessionId,
              bridgeGeneration: 0,
              workspaceKey: msg.workspaceKey,
              workspacePath: "/synthetic/ws-one",
              initialTaskId: "synth-task-1",
            },
          },
        });
        // Push one rpc-frame echoing a serialized Channel EventFire so the client can
        // exercise decode: [204, 99] + {"topic":"conversation/synthetic","frame":{...}}
        // Built here by a minimal serializer mirroring the wire format.
        const body = JSON.stringify({
          topic: "conversation/synth-task-1",
          frame: { wireVersion: 3, kind: "complete", deliveryKind: "initial", eventSeq: 1 },
        });
        const header = JSON.stringify([204, 99, "zcode-agent", "onDynamicConversationFrame"]);
        const frameBytes = serializeRpc([
          { t: "arr", items: [{ t: "int", v: 204 }, { t: "int", v: 99 }] },
          { t: "obj", v: body },
        ]);
        bridges.set(msg.bridgeSessionId, frameBytes);
        sendRpcFrame(msg.bridgeSessionId, frameBytes);
        continue;
      }
      if (msg.type === "data" && msg.payload?.zcode_type === "rpc-frame") {
        // ack the message and echo a tiny EventFire back
        send({ type: "data", payload: { zcode_type: "rpc-frame-ack", bridgeSessionId: msg.payload.bridgeSessionId, ackMessageSeq: msg.payload.messageSeq } });
        continue;
      }
      if (msg.type === "data") {
        // control payloads: ignore in mock
        continue;
      }
      } catch (e) { console.log("[mock] handler exception:", e.message); }
    }
  });

  const sendRpcFrame = (bridgeSessionId, bytes) => {
    const b64 = bytes.toString("base64");
    let crc = 0xffffffff;
    for (const b of bytes) {
      crc ^= b;
      for (let i = 0; i < 8; i += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
    send({
      type: "data",
      payload: {
        zcode_type: "rpc-frame",
        bridgeSessionId,
        bridgeGeneration: 0,
        seq: 1,
        messageSeq: 1,
        fragmentIndex: 0,
        fragmentCount: 1,
        messageBytes: bytes.length,
        checksum: { algorithm: "crc32", value: ((crc ^ 0xffffffff) >>> 0).toString(16).padStart(8, "0") },
        dataBase64: b64,
      },
    });
  };

  socket.on("error", () => {});
});

// Minimal serializer matching serialization.ts (subset used above).
function vql(n) {
  if (n === 0) return Buffer.from([0]);
  const out = [];
  let v = n;
  while (v !== 0) {
    let b = v & 0x7f;
    v = v >>> 7;
    if (v > 0) b |= 0x80;
    out.push(b);
  }
  return Buffer.from(out);
}
function ser(value) {
  if (typeof value === "undefined") return Buffer.from([0]);
  if (value.t === "str") {
    const d = Buffer.from(value.v, "utf8");
    return Buffer.concat([Buffer.from([1]), vql(d.length), d]);
  }
  if (value.t === "int") return Buffer.concat([Buffer.from([6]), vql(value.v)]);
  if (value.t === "arr") {
    const parts = [Buffer.from([4]), vql(value.items.length)];
    for (const item of value.items) parts.push(ser(item));
    return Buffer.concat(parts);
  }
  if (value.t === "obj") {
    const d = Buffer.from(value.v, "utf8");
    return Buffer.concat([Buffer.from([5]), vql(d.length), d]);
  }
  throw new Error("unsupported");
}
function serializeRpc(values) {
  return Buffer.concat(values.map(ser));
}

server.listen(PORT, () => {
  console.log(`mock relay listening on ws://127.0.0.1:${PORT} (scenario=${SCENARIO})`);
});
