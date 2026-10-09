#!/usr/bin/env bash
# the release pipeline (T3) prerequisite — the Mac live canary (report §14.2 T2), scripted end-to-end:
# pair → discover (vc34 fixed-workspace guard) → bridge → stream (pushed frame)
# → [live only: v4 hello/initialize/subscribe] → recover (same bridge identity
# across a forced disconnect) → diagnostics export (capture + report).
#
# The spike's capture is REDACTED and SCHEMA-VALIDATED before it is allowed
# anywhere but $TMPDIR (INV-16; the committed artifacts are sanitized only).
#
# vc34 cold-resume guards (normative):
#   --workspace-match must resolve to EXACTLY ONE workspace (else refuse);
#   --task-match must match the disposable synthetic task (else refuse).
#   The canary never guesses the first workspace/task (vc34 lesson: a
#   wrong-subscribe round forked tasks on the live desktop).
#
# INV-15: operator-MACHINE ritual. Never CI, never the production relay from a
# runner. The DEFAULT relay is the production endpoint — pass --relay to point
# at the in-repo MockRelay for a zero-side-effect rehearsal.
#
# Usage:
#   tools/canary/live-canary.sh --qr <url-or-env-SPIKE_QR_URL> \
#     --workspace-match <substring> --task-match <substring> \
#     [--relay ws://127.0.0.1:PORT] [--out-dir /tmp/...] [--keep-raw]
# Output: <out-dir>/canary-verdict.json {"verdict":"green|red","source":...}
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

QR="${SPIKE_QR_URL:-}"
WORKSPACE_MATCH=""
TASK_MATCH=""
RELAY="wss://zcode.z.ai/ws"
OUT_DIR=""
KEEP_RAW=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --qr) QR="$2"; shift 2 ;;
    --workspace-match) WORKSPACE_MATCH="$2"; shift 2 ;;
    --task-match) TASK_MATCH="$2"; shift 2 ;;
    --relay) RELAY="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --keep-raw) KEEP_RAW=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$WORKSPACE_MATCH" ]] || { echo "--workspace-match required (vc34 guard)" >&2; exit 2; }
[[ -n "$TASK_MATCH" ]] || { echo "--task-match required (vc34 guard)" >&2; exit 2; }
[[ -n "$QR" ]] || { echo "--qr or SPIKE_QR_URL required (fresh QR from the desktop's Mobile Remote Control dialog)" >&2; exit 2; }

OUT_DIR="${OUT_DIR:-$(mktemp -d /tmp/zcode-canary.XXXXXX)}"
mkdir -p "$OUT_DIR"
SOURCE="live"
[[ "$RELAY" != "wss://zcode.z.ai/ws" ]] && SOURCE="mock-rehearsal"
echo "[canary] out-dir=$OUT_DIR source=$SOURCE"

# ── build the spike driver ───────────────────────────────────────────────────
echo "[canary] building spike (gradle :spike:installDist)"
gradle -p "$REPO_ROOT" :spike:installDist --console=plain -q || { echo "spike build failed" >&2; exit 2; }
SPIKE_BIN="$REPO_ROOT/spike/build/install/spike/bin/spike"
[[ -x "$SPIKE_BIN" ]] || { echo "spike binary missing at $SPIKE_BIN" >&2; exit 2; }

# ── run the drill ────────────────────────────────────────────────────────────
CAPTURE_ID="canary-$(date +%Y%m%d-%H%M%S)"
# the spike arg parser consumes a value for every --flag, so give --mock one
MOCK_FLAG=""
[[ "$SOURCE" == "mock-rehearsal" ]] && MOCK_FLAG="--mock 1"
"$SPIKE_BIN" --stage canary \
  --qr "$QR" --relay "$RELAY" \
  --capture-dir "$OUT_DIR" --capture-id "$CAPTURE_ID" \
  --workspace-match "$WORKSPACE_MATCH" --task-match "$TASK_MATCH" \
  $MOCK_FLAG
CANARY_EXIT=$?

REPORT="$OUT_DIR/$CAPTURE_ID.canary.json"
RAW="$OUT_DIR/$CAPTURE_ID.jsonl"
[[ -f "$REPORT" ]] || { echo "canary report missing" >&2; exit 2; }

# ── diagnostics export hygiene: redact + validate the capture (INV-16) ─────
REDACTED="skipped"
if [[ -f "$RAW" && $KEEP_RAW -eq 0 ]]; then
  if node "$REPO_ROOT/tools/capture/redact.mjs" \
      --in "$RAW" \
      --out "$OUT_DIR/$CAPTURE_ID.redacted.jsonl" \
      --report "$OUT_DIR/$CAPTURE_ID.redaction-report.json" >/dev/null 2>&1; then
    if node "$REPO_ROOT/tools/capture/validate-fixture.mjs" "$OUT_DIR/$CAPTURE_ID.redacted.jsonl" >/dev/null 2>&1; then
      REDACTED="ok"
      rm -f "$RAW"
    else
      REDACTED="validation-failed (raw capture retained locally for review)"
    fi
  else
    REDACTED="redaction-failed (raw capture retained locally for review)"
  fi
elif [[ -f "$RAW" ]]; then
  REDACTED="kept-raw (--keep-raw)"
fi

# ── verdict envelope ─────────────────────────────────────────────────────────
node - "$REPORT" "$OUT_DIR/canary-verdict.json" "$SOURCE" "$REDACTED" <<'EOF'
const fs = require("fs");
const [reportPath, outPath, source, redacted] = process.argv.slice(2);
const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
const verdict = report.verdict === "green" && redacted !== "validation-failed" && redacted !== "redaction-failed" ? "green" : "red";
fs.writeFileSync(outPath, JSON.stringify({
  schemaVersion: 1,
  tool: "live-canary.sh",
  at: new Date().toISOString(),
  verdict,
  source,
  redaction: redacted,
  legs: report.legs,
  detail: report.detail,
  reportPath,
}, null, 2));
console.log(`[canary] verdict=${verdict} (source=${source}, redaction=${redacted}) → ${outPath}`);
process.exit(verdict === "green" ? 0 : 1);
EOF
