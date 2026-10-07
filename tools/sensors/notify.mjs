#!/usr/bin/env node
// AAP-2 notifier (android-gaps roadmap Phase 5). One JSON line per call:
//
//   {"at":"…","title":"…","message":"…","osd":"sent|skipped|failed: …"}
//
// OSD is best-effort: macOS `osascript display notification` when available
// (override with --osd-command for tests; silence with --no-osd). The log line
// is the durable notification record — the phase gate's "notification path
// demonstrated" evidence is this log.
import { execFileSync } from "node:child_process";
import { mkdirSync, appendFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const title = arg("title") ?? "drift";
const message = arg("message") ?? "";
const noOsd = process.argv.includes("--no-osd") || process.env.ZCODE_DRIFT_NO_OSD === "1";
const logPath = arg("log") ?? resolve(process.env.HOME ?? "/tmp", "Library/Logs/zcode-android-drift/notifications.log");

let osd = "skipped";
if (!noOsd) {
  const apple = (s) => s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  const cmd = arg("osd-command") ?? "osascript";
  const osdArgs = arg("osd-command")
    ? [title, message]
    : ["-e", `display notification "${apple(message)}" with title "${apple(title)}"`];
  try {
    execFileSync(cmd, osdArgs, { stdio: "ignore", timeout: 10_000 });
    osd = "sent";
  } catch (e) {
    osd = `failed: ${String(e.message).split("\n")[0].slice(0, 120)}`;
  }
}

mkdirSync(dirname(resolve(logPath)), { recursive: true });
appendFileSync(resolve(logPath), `${JSON.stringify({ at: new Date().toISOString(), title, message, osd })}\n`);
console.log(`notified: ${osd} -> ${logPath}`);
