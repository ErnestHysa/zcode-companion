// AAP-5 — decision-matrix tests. The load-bearing pin: NO input combination with
// driftVerdict=BREAKING ever yields an auto-ship decision (the roadmap's absolute
// Deferred-scope gate), plus the §14.2 T3 matrix truth table.
import { test } from "node:test";
import assert from "node:assert/strict";
import { decideRelease } from "./decision.mjs";

test("ADDITIVE + suites green + canary green → auto-ship", () => {
  const d = decideRelease({ driftVerdict: "ADDITIVE", suitesGreen: true, canaryGreen: true });
  assert.equal(d.action, "auto-ship");
  assert.ok(Array.isArray(d.steps) && d.steps.length >= 5);
});

test("ABSOLUTE: BREAKING never auto-ships — exhaustive over every input combination", () => {
  for (const suitesGreen of [true, false, undefined, null]) {
    for (const canaryGreen of [true, false, undefined, null]) {
      const d = decideRelease({ driftVerdict: "BREAKING", suitesGreen, canaryGreen });
      assert.notEqual(d.action, "auto-ship", `auto-ship leaked at suites=${suitesGreen} canary=${canaryGreen}`);
      assert.equal(d.action, "agent-pr");
      assert.ok(d.reasons.some((r) => /HUMAN APPROVAL/i.test(r)));
    }
  }
});

test("BREAKING with every leg green still routes to the agent-drafted PR", () => {
  const d = decideRelease({ driftVerdict: "BREAKING", suitesGreen: true, canaryGreen: true });
  assert.equal(d.action, "agent-pr");
});

test("unknown drift verdict halts", () => {
  const d = decideRelease({ driftVerdict: "CONFUSED", suitesGreen: true, canaryGreen: true });
  assert.equal(d.action, "halt");
});

test("ADDITIVE with red suites halts (even with green canary)", () => {
  const d = decideRelease({ driftVerdict: "ADDITIVE", suitesGreen: false, canaryGreen: true });
  assert.equal(d.action, "halt");
});

test("ADDITIVE with failed canary halts (even with green suites)", () => {
  const d = decideRelease({ driftVerdict: "ADDITIVE", suitesGreen: true, canaryGreen: false });
  assert.equal(d.action, "halt");
});

test("every decision carries recorded reasons (matrix decisions are logged)", () => {
  for (const verdict of ["ADDITIVE", "BREAKING", "whatever"]) {
    for (const a of [true, false]) {
      for (const b of [true, false]) {
        const d = decideRelease({ driftVerdict: verdict, suitesGreen: a, canaryGreen: b });
        assert.ok(d.reasons.length >= 2, `reasons missing for ${verdict}/${a}/${b}`);
      }
    }
  }
});
