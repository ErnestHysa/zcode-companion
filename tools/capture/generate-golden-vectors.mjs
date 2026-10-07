// Dev-time golden fixture generator — runs the REFERENCE TypeScript implementation
// (read-only import from the ZCode reference clone) and records expected byte output.
// Output: fixtures/protocol/golden-vectors.json. Never shipped, never part of CI deps.
import { writeFileSync, mkdirSync, readFileSync } from "node:fs";

// AAP-4 (§14.2 T2): regenerable against an arbitrary open-repo checkout.
const REPO = process.env.ZCODE_REFERENCE_REPO ?? "/Users/ernest/Desktop/DEVPROJECTS/zcode/repo";
const OUT = process.env.ZCODE_FIXTURES_OUT ??
  "/Users/ernest/Desktop/DEVPROJECTS/zcode/android-native/fixtures/protocol/golden-vectors.json";
import { Buffer } from "node:buffer";
const { serialize, deserialize, BufferWriter, BufferReader } = await import(`${REPO}/packages/rpc/src/serialization.ts`);
const { VSBuffer } = await import(`${REPO}/packages/rpc/src/buffer.ts`);
const { writeProtocolMessage, ProtocolMessage, ProtocolMessageType, ChunkStream } = await import(`${REPO}/packages/rpc/src/protocol.ts`);
// wire-binary.ts imports zod (only for a base64 validation schema). The reference clone has
// no node_modules and must stay untouched, so copy its text verbatim minus the zod-only lines.
{
  const src = readFileSync(`${REPO}/packages/shared/src/zcode-protocol-v4/wire-binary.ts`, "utf8");
  const stripped = src
    .replace(/^import \{ z \} from "zod";$/m, "")
    .replace(/export const topicWireBase64Schema[\s\S]*?;\n/, "const topicWireBase64Schema = null;\n");
  writeFileSync("/tmp/fixture-gen/wire-binary-verbatim.ts", stripped);
}
const { crc32WireBytes } = await import("/tmp/fixture-gen/wire-binary-verbatim.ts");

const hex = (u8) => Buffer.from(u8).toString("hex");
const cases = [];

function addSerialize(name, value) {
  const w = new BufferWriter();
  serialize(w, value);
  cases.push({ kind: "serialize", name, expected: hex(w.buffer.buffer) });
  // also verify the reference round-trips it
  const d = deserialize(new BufferReader(w.buffer));
  cases.push({ kind: "deserialize-check", name: name + "::reparse", expected: hex(serializeToBytes(d)) });
}
function serializeToBytes(v) {
  const w = new BufferWriter();
  serialize(w, v);
  return w.buffer.buffer;
}

// --- serialize cases ---------------------------------------------------------
addSerialize("undefined", undefined);
addSerialize("string-ascii", "hello zcode");
addSerialize("string-unicode", "你好，ZCode ✅");
addSerialize("string-escapes", "quote\" back\\slash\nnnewline\ttab\u0000ctrl\u0001");
addSerialize("int-0", 0);
addSerialize("int-1", 1);
addSerialize("int-127", 127);
addSerialize("int-128", 128);
addSerialize("int-16383", 16383);
addSerialize("int-16384", 16384);
addSerialize("int-max", 2147483647);
addSerialize("int-negative", -1);
addSerialize("int-min", -2147483648);
addSerialize("float", 1.5);
addSerialize("bool-true", true);
addSerialize("bool-false", false);
addSerialize("null", null);
addSerialize("empty-array", []);
addSerialize("array-mixed", [1, "two", true, null, { three: 3 }]);
addSerialize("empty-object", {});
addSerialize("object-simple", { b: true, a: 1, c: "x" });
addSerialize("object-nested", { outer: { inner: [1, 2, { deep: "v" }] }, flag: false });
addSerialize("object-unicode-key", { 键: "值", id: 42 });
addSerialize("uint8array-top", new Uint8Array([1, 2, 3, 250, 255]));
addSerialize("vsbuffer-top", VSBuffer.fromString("vsbuffer payload"));
addSerialize("object-nested-uint8array", { blob: new Uint8Array([0, 255, 128]), name: "x" });

// --- socket framing ----------------------------------------------------------
const frameCases = [
  ["regular-empty", ProtocolMessageType.Regular, 0, 0, new Uint8Array(0)],
  ["regular-payload", ProtocolMessageType.Regular, 7, 0, new Uint8Array([10, 20, 30])],
  ["keepalive", ProtocolMessageType.KeepAlive, 0, 12, new Uint8Array(0)],
  ["ack", ProtocolMessageType.Ack, 0, 42, new Uint8Array(0)],
  ["disconnect", ProtocolMessageType.Disconnect, 0, 99, new Uint8Array(0)],
  ["large-id", ProtocolMessageType.Regular, 4294967295, 4294967295, new Uint8Array([1])],
];
for (const [name, type, id, ack, data] of frameCases) {
  const bytes = writeProtocolMessage(new ProtocolMessage(type, id, ack, VSBuffer.wrap(data)));
  cases.push({ kind: "frame", name, expected: hex(bytes.buffer) });
}

// --- chunk-stream reassembly (reference behavior on split input) -------------
{
  const full = Buffer.from(writeProtocolMessage(new ProtocolMessage(ProtocolMessageType.Regular, 5, 3, VSBuffer.fromString("chunked-message-body"))).buffer);
  const plans = [[5, full.length], [0, 13, full.length], [13, 14, full.length], [full.length]];
  plans.forEach((plan, i) => {
    const cs = new ChunkStream();
    let off = 0;
    for (const b of plan) {
      if (b > off) {
        cs.acceptChunk(VSBuffer.wrap(new Uint8Array(Buffer.from(full.slice(off, b)))));
        off = b;
      }
    }
    const got = [];
    while (cs.byteLength >= 13) {
      const header = cs.peek(13);
      const len = header.readUInt32BE(9);
      if (cs.byteLength < 13 + len) break;
      cs.skip(13);
      got.push(hex(cs.read(len).buffer));
    }
    cases.push({ kind: "chunk-drain", name: `chunk-split-${i}`, expected: got });
  });
}

// --- CRC32 -------------------------------------------------------------------
const crcInputs = [
  ["empty", new Uint8Array(0)],
  ["one-zero", new Uint8Array([0])],
  ["123456789", new TextEncoder().encode("123456789")],
  ["binary", new Uint8Array([0xde, 0xad, 0xbe, 0xef, 0x00, 0xff])],
  ["hello", new TextEncoder().encode("hello zcode crc")],
];
for (const [name, input] of crcInputs) {
  cases.push({ kind: "crc32", name, expected: crc32WireBytes(input) });
}

// --- channel wire shapes -----------------------------------------------------
function chanRequest(reqType, id, channelName, name, arg) {
  const w = new BufferWriter();
  serialize(w, [reqType, id, channelName, name]);
  serialize(w, arg);
  return hex(w.buffer.buffer);
}
cases.push({ kind: "channel", name: "request-promise", expected: chanRequest(100, 1, "zcode-agent", "helloConversationV4", { kind: "clientHello", protocolVersion: 3 }) });
cases.push({ kind: "channel", name: "request-eventlisten", expected: chanRequest(102, 2, "zcode-agent", "onDynamicSessionEvent", { sessionId: "sess_x" }) });
{
  const w = new BufferWriter();
  serialize(w, [101, 3]);
  serialize(w, undefined);
  cases.push({ kind: "channel", name: "cancel", expected: hex(w.buffer.buffer) });
}
{
  const w = new BufferWriter();
  serialize(w, [204, 2]);
  serialize(w, { topic: "conversation/s1", frame: { kind: "complete" } });
  cases.push({ kind: "channel", name: "eventfire", expected: hex(w.buffer.buffer) });
}
{
  const w = new BufferWriter();
  serialize(w, [200]);
  serialize(w, undefined);
  cases.push({ kind: "channel", name: "initialize", expected: hex(w.buffer.buffer) });
}

mkdirSync("/Users/ernest/Desktop/DEVPROJECTS/zcode/android-native/fixtures/protocol", { recursive: true });
writeFileSync(
  OUT,
  JSON.stringify({
    metadata: {
      source: "ZCode reference clone packages/rpc + packages/shared (v3.14.3, commit 29628c9)",
      generator: "dev-time tsx script (tools/capture/generate-golden-vectors.mjs)",
      capturedAt: new Date().toISOString(),
      zcodeVersion: "3.14.3",
      sanitization: "synthetic vectors only — no real credentials, content, or traffic (INV-15/16)",
    },
    cases,
  }, null, 2) + "\n",
);
console.log(`wrote ${cases.length} golden vectors`);
