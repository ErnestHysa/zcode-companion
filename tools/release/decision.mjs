// the release pipeline (T3) (project roadmap) — §14.2 T3 decision matrix (report §14.2, and the
// roadmap's absolute Deferred-scope gate: "Never auto-ship breaking").
//
// Inputs (all boolean/enum, recorded verbatim in the run log):
//   driftVerdict  "ADDITIVE" | "BREAKING" | anything else (treated as unknown)
//   suitesGreen   the T2 verify leg ran green (fixtures regenerated + unit suite)
//   canaryGreen   the Mac live-canary ritual reported green
//
// Matrix (§14.2 T3):
//   ADDITIVE  + suites green + canary green → auto-ship  (versionCode bump,
//               MAX_TESTED_DESKTOP bump, assembleRelease, sign, DRAFT release
//               with drift-report-generated notes — draft, so the publish is
//               still Ernest's click)
//   BREAKING  (regardless of the other inputs) → agent-pr (an agent drafts the
//               patch PR with the drift report attached; HUMAN APPROVAL gates
//               any ship — the vc34 lesson: a wrong protocol patch stranded on
//               the phone is worse than the delay)
//   anything else (unknown verdict, red suites, failed canary) → halt

export function decideRelease({ driftVerdict, suitesGreen, canaryGreen }) {
  const reasons = [];

  // THE ABSOLUTE GATE — asserted by decision.test.mjs over every input combo:
  // a BREAKING verdict can never produce an auto-ship decision.
  if (driftVerdict === "BREAKING") {
    reasons.push("BREAKING drift: auto-ship forbidden (roadmap Deferred scope, absolute)");
    reasons.push("route: agent drafts the patch PR with drift-report.json attached; HUMAN APPROVAL required before any ship");
    return { action: "agent-pr", reasons };
  }

  if (driftVerdict !== "ADDITIVE") {
    reasons.push(`drift verdict "${driftVerdict}" is neither ADDITIVE nor BREAKING — investigate the drift tool run`);
    reasons.push("route: halt; re-run the drift leg and classify before any release decision");
    return { action: "halt", reasons };
  }
  reasons.push("drift verdict ADDITIVE: no consumed protocol surface changed");

  if (suitesGreen !== true) {
    reasons.push("T2 verify suites are not green — nothing ships on a red suite");
    return { action: "halt", reasons };
  }
  reasons.push("T2 verify green: fixtures regenerated from the new schemas + unit suite passed");

  if (canaryGreen !== true) {
    reasons.push("Mac live canary is not green — the release-adjacent ritual must pass first");
    return { action: "halt", reasons };
  }
  reasons.push("Mac live canary green: pair → subscribe → stream → recover → diagnostics export");

  reasons.push("all three matrix legs green → fully automatic pipeline to a SIGNED DRAFT release");
  return {
    action: "auto-ship",
    reasons,
    steps: [
      "bump versionCode + versionName (app/build.gradle.kts)",
      "bump CompatibilityGate.MAX_TESTED_DESKTOP to the verified desktop version",
      "baseline-profile regen (macrobenchmark workflow; skipped only when flagged)",
      "assembleRelease (signed via the off-repo stable keystore)",
      "sha256 the artifact",
      "gh release create --draft with drift-report-generated notes + APK + .sha256",
    ],
  };
}
