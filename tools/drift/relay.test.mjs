// Bundle-sensor relay-surface tests. Run:
//   node --test tools/drift/relay.test.mjs
//
// Hermetic: feeds synthetic minified-bundle text carrying the Phase-1-corpus
// constant families (relay close codes, QI adapter limits, envelope/rpc-frame
// field names, relay error codes) to the text-level extractor and pins the
// extracted surface + the classified diff against a seeded mutated bundle.
import { test } from "node:test";
import assert from "node:assert/strict";

import { extractRelaySurfaceTexts, diffRelaySurfaces } from "./relay-lib.mjs";

const BUNDLE_V1 = `
var closeCodes={SessionNotFound:4004,SessionConflict:4009,DesktopDisconnected:4010,SessionExpired:4011,WorkspaceClosed:4012,InvalidMobileConnection:4013};
var QI={maxPhysicalFrameBytes:1048576,maxMessageBytes:16777216,maxFragments:64,assemblyTimeoutMs:3e4,transportIdMaxChars:256};
function h(m){if(m.type==="data"){var p=m.payload;p.bridgeSessionId;p.bridgeGeneration;p.recoveryId;p.seq;p.messageSeq;}
if(m.zcode_type==="rpc-frame-ack"){var a=m; a.ackMessageSeq;}
if(m.fragmentIndex!==m.fragmentCount-1&&m.messageBytes>0&&m.checksum&&m.dataBase64)reassemble(m);}
function fail(c){switch(c){case"KICKED":case"AUTH_FAILED":case"INTERNAL":case"WRONG_PARAM":reconnect();}}
var ts={client_ts:Date.now(),server_ts:Date.now()};
`;

test("relay extraction pins the corpus constant families from bundle text", () => {
  const surf = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: BUNDLE_V1 }]);
  assert.deepEqual(surf.layers.closeCodes.entries, {
    DesktopDisconnected: "4010",
    InvalidMobileConnection: "4013",
    SessionConflict: "4009",
    SessionExpired: "4011",
    SessionNotFound: "4004",
    WorkspaceClosed: "4012",
  });
  assert.deepEqual(surf.layers.adapterLimits.entries, {
    assemblyTimeoutMs: "30000", // 3e4 normalized
    maxFragments: "64",
    maxMessageBytes: "16777216",
    maxPhysicalFrameBytes: "1048576",
    transportIdMaxChars: "256",
  });
  for (const k of ["ackMessageSeq", "bridgeGeneration", "bridgeSessionId", "checksum", "client_ts", "dataBase64", "fragmentCount", "fragmentIndex", "messageBytes", "messageSeq", "recoveryId", "rpc-frame-ack", "server_ts", "zcode_type"]) {
    assert.equal(surf.layers.envelopeFields.entries[k], "present", k);
  }
  assert.deepEqual(surf.layers.errorCodes.entries, {
    AUTH_FAILED: "present",
    INTERNAL: "present",
    KICKED: "present",
    WRONG_PARAM: "present",
  });
  assert.equal(surf.meta.perFamilySource.closeCodes, "zcode.cjs");
});

test("relay diff: seeded bundle change classifies exactly (limit + close code changed, error code gone)", () => {
  const baseline = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: BUNDLE_V1 }]);
  const BUNDLE_V2 = BUNDLE_V1
    .replace("maxFragments:64", "maxFragments:48")
    .replace("WorkspaceClosed:4012", "WorkspaceClosed:4014")
    .replace('case"WRONG_PARAM":', 'case"OTHER":');
  const current = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: BUNDLE_V2 }]);
  const report = diffRelaySurfaces({ baseline, current });

  assert.equal(report.kind, "relay-drift-report");
  assert.equal(report.verdict, "BREAKING");
  const key = (i) => `${i.layer}/${i.kind}/${i.name}`;
  const byKey = Object.fromEntries(report.items.map((i) => [key(i), i]));
  assert.deepEqual(Object.keys(byKey).sort(), [
    "adapterLimits/changed/maxFragments",
    "closeCodes/changed/WorkspaceClosed",
    "errorCodes/removed/WRONG_PARAM",
  ].sort());
  assert.equal(byKey["adapterLimits/changed/maxFragments"].classification, "breaking");
  assert.match(byKey["adapterLimits/changed/maxFragments"].detail, /64/);
  assert.match(byKey["adapterLimits/changed/maxFragments"].detail, /48/);
  assert.equal(byKey["closeCodes/changed/WorkspaceClosed"].classification, "breaking");
  assert.equal(report.summary.breaking, 3);
});

test("relay diff: identical bundles produce an empty ADDITIVE report", () => {
  const a = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: BUNDLE_V1 }]);
  const b = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: BUNDLE_V1 }]);
  const report = diffRelaySurfaces({ baseline: a, current: b });
  assert.equal(report.verdict, "ADDITIVE");
  assert.deepEqual(report.items, []);
});

test("relay diff: envelope field disappearing is BREAKING (strict rpc-frame geometry)", () => {
  const baseline = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: BUNDLE_V1 }]);
  const current = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: BUNDLE_V1.replace(/p\.messageSeq;/, "") }]);
  const report = diffRelaySurfaces({ baseline, current });
  assert.equal(report.verdict, "BREAKING");
  const item = report.items.find((i) => i.layer === "envelopeFields" && i.name === "messageSeq");
  assert.ok(item, "expected messageSeq disappearance item");
  assert.equal(item.kind, "removed");
  assert.equal(item.classification, "breaking");
});

test("relay extraction: absent constants extract as absent and diff as removed", () => {
  const baseline = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: BUNDLE_V1 }]);
  const current = extractRelaySurfaceTexts([{ label: "zcode.cjs", text: "var nothing=1;" }]);
  const report = diffRelaySurfaces({ baseline, current });
  assert.equal(report.verdict, "BREAKING");
  assert.ok(report.items.length >= 6 + 5 + 14 + 4 - 0);
  for (const item of report.items) assert.equal(item.kind, "removed");
});
