// Sensor script tests. Run:
//   node --test tools/sensors/sensors.test.mjs
//
// Covers the open-repo tag watch (against a REAL local git repo seeded with
// fake release tags — the E2E rehearsal mechanism) and the notifier's log
// contract. Hermetic except for the git CLI.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const WATCH = new URL("./open-repo-watch.mjs", import.meta.url).pathname;
const NOTIFY = new URL("./notify.mjs", import.meta.url).pathname;

function run(node_args) {
  return JSON.parse(execFileSync(process.execPath, node_args, { encoding: "utf8" }));
}

function seedUpstream() {
  const dir = mkdtempSync(join(tmpdir(), "fake-upstream-"));
  const g = (...args) => execFileSync("git", ["-C", dir, ...args], { encoding: "utf8" });
  g("init", "-q", "-b", "main");
  g("config", "user.email", "drift-rehearsal@example.invalid");
  g("config", "user.name", "drift rehearsal");
  writeFileSync(join(dir, "marker.txt"), "v1\n");
  g("add", "-A");
  g("commit", "-qm", "base");
  g("tag", "v3.14.3");
  writeFileSync(join(dir, "marker.txt"), "v2\n");
  g("add", "-A");
  g("commit", "-qm", "fake release");
  g("tag", "v3.14.4");
  // an older-series tag that must NOT win the version sort
  writeFileSync(join(dir, "marker.txt"), "v3\n");
  g("add", "-A");
  g("commit", "-qm", "old series tag");
  g("tag", "v3.9.10");
  return dir;
}

function seedState(dir, state) {
  const p = join(dir, `state-${Math.random().toString(36).slice(2)}.json`);
  if (state !== undefined) writeFileSync(p, JSON.stringify(state));
  return p;
}

test("open-repo watch detects a seeded fake release and reports isNew", () => {
  const repo = seedUpstream();
  try {
    const state = seedState(repo, { lastTag: "v3.14.3" });
    const out = run([WATCH, "--repo", repo, "--state", state]);
    assert.equal(out.latestTag, "v3.14.4");
    assert.equal(out.lastTag, "v3.14.3");
    assert.equal(out.isNew, true);
    assert.ok(!existsSync(join(repo, "state-v3.14.4"))); // no implicit state write
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
});

test("open-repo watch sorts versions numerically (3.9.10 < 3.14.3) and stays quiet when state matches", () => {
  const repo = seedUpstream();
  try {
    const state = seedState(repo, { lastTag: "v3.14.4" });
    const out = run([WATCH, "--repo", repo, "--state", state]);
    assert.equal(out.latestTag, "v3.14.4");
    assert.equal(out.isNew, false);
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
});

test("open-repo watch --update-state advances the recorded tag after reporting", () => {
  const repo = seedUpstream();
  try {
    const state = seedState(repo, { lastTag: "v3.14.3" });
    const out = run([WATCH, "--repo", repo, "--state", state, "--update-state"]);
    assert.equal(out.isNew, true);
    assert.deepEqual(JSON.parse(readFileSync(state, "utf8")), { lastTag: "v3.14.4" });
    const again = run([WATCH, "--repo", repo, "--state", state]);
    assert.equal(again.isNew, false);
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
});

test("open-repo watch: missing state file = first-run baseline (no false alarm)", () => {
  const repo = seedUpstream();
  try {
    const state = seedState(repo, undefined);
    const out = run([WATCH, "--repo", repo, "--state", state]);
    assert.equal(out.latestTag, "v3.14.4");
    assert.equal(out.lastTag, null);
    assert.equal(out.isNew, false);
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
});

test("open-repo watch: repo without tags reports null and exits cleanly", () => {
  const dir = mkdtempSync(join(tmpdir(), "no-tags-"));
  try {
    const g = (...args) => execFileSync("git", ["-C", dir, ...args], { encoding: "utf8" });
    g("init", "-q", "-b", "main");
    g("config", "user.email", "d@example.invalid");
    g("config", "user.name", "d");
    writeFileSync(join(dir, "x"), "x");
    g("add", "-A");
    g("commit", "-qm", "only main");
    const state = seedState(dir, { lastTag: "v1.0.0" });
    const out = run([WATCH, "--repo", dir, "--state", state]);
    assert.equal(out.latestTag, null);
    assert.equal(out.isNew, false);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

function notifyRun(args) {
  return execFileSync(process.execPath, [NOTIFY, ...args], { encoding: "utf8" });
}

test("notifier appends one JSON line per call and honors --no-osd", () => {
  const dir = mkdtempSync(join(tmpdir(), "notify-"));
  try {
    const log = join(dir, "notifications.log");
    notifyRun(["--title", "t1", "--message", 'm "quoted" text', "--log", log, "--no-osd"]);
    notifyRun(["--title", "t2", "--message", "m2", "--log", log, "--no-osd"]);
    const lines = readFileSync(log, "utf8").trim().split("\n").map((l) => JSON.parse(l));
    assert.equal(lines.length, 2);
    assert.equal(lines[0].title, "t1");
    assert.equal(lines[0].message, 'm "quoted" text');
    assert.equal(lines[0].osd, "skipped");
    assert.match(lines[1].at, /^\d{4}-\d{2}-\d{2}T/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("notifier records osd success and failure through a command override", () => {
  const dir = mkdtempSync(join(tmpdir(), "notify2-"));
  try {
    const log = join(dir, "n.log");
    notifyRun(["--title", "a", "--message", "a", "--log", log, "--osd-command", "/usr/bin/true"]);
    notifyRun(["--title", "b", "--message", "b", "--log", log, "--osd-command", "/usr/bin/false"]);
    const lines = readFileSync(log, "utf8").trim().split("\n").map((l) => JSON.parse(l));
    assert.equal(lines[0].osd, "sent");
    assert.match(lines[1].osd, /^failed:/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
