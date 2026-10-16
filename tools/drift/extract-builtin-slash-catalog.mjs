#!/usr/bin/env node
// A1.2 refresh step: regenerate the phone's EMBEDDED builtin slash catalog from a
// ZCode open-repo checkout (the desktop's own source of truth).
//
//   node tools/drift/extract-builtin-slash-catalog.mjs \
//        --shared-src <zcode-repo>/packages/shared/src/zcode-slash-command-help.ts \
//        [--out features/conversation/src/main/kotlin/com/zcode/android/features/conversation/port/BuiltinSlashCatalog.kt]
//
// GENERATED output — regenerate, never hand-edit (the drift pin
// BuiltinSlashCatalogDriftTest additionally locks the committed catalog against
// tools/drift/surface-inventory.json, the extracted desktop list).
import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const srcPath = resolve(arg("shared-src") ?? "");
if (!srcPath) {
  console.error("usage: extract-builtin-slash-catalog.mjs --shared-src <zcode-slash-command-help.ts> [--out <BuiltinSlashCatalog.kt>]");
  process.exit(2);
}

const src = readFileSync(srcPath, "utf8");

// Parse the BUILTIN_ZCODE_SLASH_COMMAND_HELP_ENTRIES blocks (name/summary/usage).
const blocks = src.split(/\n    \{/).slice(1);
const entries = blocks
  .map((b) => ({
    name: (b.match(/name: "([^"]+)"/) || [])[1],
    summary: (b.match(/summary: "([^"]+)"/) || [])[1],
    usage: (b.match(/usage:\s*(?:\n\s*)?"([^"]+)"/) || [])[1],
  }))
  .filter((e) => e.name && e.summary && e.usage);

if (entries.length === 0) {
  console.error("no catalog entries parsed — the source shape changed?");
  process.exit(2);
}

const kotlinEscape = (s) => s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
const inputHint = (usage) => {
  const rest = usage.replace(/^\/[A-Za-z-]+/, "").trim();
  return rest ? `"${kotlinEscape(rest)}"` : "null";
};

const body = entries
  .map((e) => `        Entry("${kotlinEscape(e.name)}", "${kotlinEscape(e.summary)}", ${inputHint(e.usage)}),`)
  .join("\n");

const file = `package com.zcode.android.features.conversation.port

/**
 * FIELD (2026-10-12) + A1.2: the desktop's BUILTIN slash command catalog, embedded
 * as a GENERATED list. The relay adapter does not forward workspace-config
 * subscribes for device connections (live-verified: fault.subscribe.sessionNotFound),
 * so the live catalog NEVER loads on the phone — this fallback serves the builtin
 * commands (which the desktop serves from its local CLI server connection).
 * GENERATED from zcode-slash-command-help.ts BUILTIN_ZCODE_SLASH_COMMAND_HELP_ENTRIES.
 *
 * Refresh (build step, not a one-time capture):
 *   node tools/drift/extract-builtin-slash-catalog.mjs \\
 *        --shared-src <zcode-repo>/packages/shared/src/zcode-slash-command-help.ts \\
 *        --out features/conversation/src/main/kotlin/com/zcode/android/features/conversation/port/BuiltinSlashCatalog.kt
 * The committed golden inventory (tools/drift/surface-inventory.json) is the drift
 * gate: BuiltinSlashCatalogDriftTest asserts name-for-name equality with it.
 * Regenerate, never hand-edit.
 */
object BuiltinSlashCatalog {
    data class Entry(val name: String, val description: String, val inputHint: String?)
    val COMMANDS: List<Entry> = listOf(
${body}
    )
}
`;

const out = arg("out");
if (out) {
  writeFileSync(resolve(out), file);
  console.error(`BuiltinSlashCatalog written: ${out} (${entries.length} entries)`);
} else {
  process.stdout.write(file);
}
