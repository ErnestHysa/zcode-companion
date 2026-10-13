#!/usr/bin/env node
// AAP-5 rehearsal shim — a fake `gh` that RECORDS the release/PR calls the T3
// pipeline would make, validates their contract, and touches nothing remote.
// INV-15/16 + "NEVER push unless explicitly told": the rehearsal must not talk
// to GitHub at all, so the pipeline runs with --gh pointing at this recorder.
//
// Contract enforced (exit 2 on violation):
//   release create <tag>  --draft REQUIRED (a T3 release is ALWAYS a draft —
//     promoting it to public stays Ernest's click), --target REQUIRED,
//     --notes-file REQUIRED + must exist, every asset must exist on disk.
//   pr create --draft REQUIRED (BREAKING PRs are drafts), --title + --body-file
//     REQUIRED + must exist.
// Every accepted call appends one JSON line to $GH_RECORDER_LOG.
import { appendFileSync, writeFileSync, readFileSync, existsSync, statSync } from "node:fs";

const LOG = process.env.GH_RECORDER_LOG;
if (!LOG) {
  console.error("gh-recorder: GH_RECORDER_LOG not set");
  process.exit(2);
}

const args = process.argv.slice(2);
const sub = args[0]; // release | pr
const cmd = args[1]; // create

function record(obj) {
  appendFileSync(LOG, JSON.stringify({ ts: new Date().toISOString(), ...obj }) + "\n");
}

function flag(name) {
  const i = args.indexOf("--" + name);
  return i >= 0 ? args[i + 1] : undefined;
}

if (sub === "release" && cmd === "create") {
  const tag = args[2];
  const draft = args.includes("--draft");
  const target = flag("target");
  const notesFile = flag("notes-file");
  if (!tag || !draft || !target || !notesFile) {
    console.error("gh-recorder: release create requires <tag> --draft --target <sha> --notes-file <f>");
    process.exit(2);
  }
  if (!existsSync(notesFile)) {
    console.error(`gh-recorder: notes file missing: ${notesFile}`);
    process.exit(2);
  }
  const assets = args.slice(args.indexOf("--notes-file") + 2).filter((a) => !a.startsWith("--") && a !== tag && a !== target && a !== notesFile);
  for (const a of assets) {
    if (!existsSync(a)) {
      console.error(`gh-recorder: asset missing: ${a}`);
      process.exit(2);
    }
  }
  record({ kind: "release", command: "release create", tag, draft, target, notes: readFileSync(notesFile, "utf8"), assets });
  console.log(`gh-recorder: recorded DRAFT release ${tag} (target ${target}, ${assets.length} asset(s)) — nothing was uploaded`);
  process.exit(0);
}

if (sub === "pr" && cmd === "create") {
  const draft = args.includes("--draft");
  const title = flag("title");
  const bodyFile = flag("body-file");
  if (!draft || !title || !bodyFile || !existsSync(bodyFile)) {
    console.error("gh-recorder: pr create requires --draft --title <t> --body-file <f> (existing)");
    process.exit(2);
  }
  record({ kind: "pr", command: "pr create", title, draft, body: readFileSync(bodyFile, "utf8") });
  console.log(`gh-recorder: recorded DRAFT PR "${title}" — nothing was opened`);
  process.exit(0);
}

console.error(`gh-recorder: unsupported invocation: ${args.join(" ")}`);
process.exit(2);
