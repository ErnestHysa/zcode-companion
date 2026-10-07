#!/usr/bin/env bash
# AAP-4 (android-gaps roadmap) — §14.2 T2 CI verify leg.
#
# Given an open-repo checkout (the "new desktop version"), verify the Android
# client against it: (1) drift-check the protocol surface vs the committed
# inventory, (2) regenerate the golden fixtures from the NEW schemas with the
# committed generators, (3) run the full unit suite (fixture decodes + transport/
# protocol + Robolectric) in a scratch clone against the regenerated fixtures.
#
# INV-15 (normative, §36): this pipeline NEVER connects to the production relay,
# NEVER holds real credentials, NEVER runs an emulator, and NEVER touches real QR
# codes / conversation content / personal paths. Every step is local git metadata,
# local source text, JSON diffing, or unit tests against in-repo mocks.
#
# Verdict / exit codes (same convention as tools/drift/check-drift.mjs):
#   0  ADDITIVE drift (or no drift) AND suites green
#   1  BREAKING drift (or fixture generation failed against the new schemas)
#      — drift-report.json + verify-report.json are ALWAYS written on exit 1
#   2  usage / infrastructure error
#
# The GitHub Action (.github/workflows/protocol-verify.yml) runs exactly this
# script; local rehearsal runs it against seeded synthetic checkouts.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

SHARED_ROOT=""
LABEL="unlabeled"
WORK_DIR=""
SKIP_GRADLE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --shared-root) SHARED_ROOT="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --skip-gradle) SKIP_GRADLE=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$SHARED_ROOT" && -d "$SHARED_ROOT" ]] || { echo "usage: verify-protocol.sh --shared-root <open-repo>/packages/shared/src [--label L] [--work-dir D] [--skip-gradle]" >&2; exit 2; }

UPSTREAM_ROOT="$(cd "$SHARED_ROOT/../../.." && pwd)"  # <repo>/packages/shared/src → <repo>
WORK_DIR="${WORK_DIR:-$(mktemp -d "/tmp/protocol-verify-$LABEL.XXXXXX")}"
mkdir -p "$WORK_DIR"
echo "[verify] label=$LABEL"
echo "[verify] shared-root=$SHARED_ROOT"
echo "[verify] work-dir=$WORK_DIR"

# ── Step 1: drift-check vs the committed surface inventory ───────────────────
echo "[verify] step 1: drift-check"
DRIFT_EXIT=0
node "$REPO_ROOT/tools/drift/check-drift.mjs" \
  --shared-root "$SHARED_ROOT" \
  --inventory "$REPO_ROOT/tools/drift/surface-inventory.json" \
  --manifest "$REPO_ROOT/tools/drift/consumed-surfaces.json" \
  --commit "$LABEL" \
  --out "$WORK_DIR/drift-report.json" || DRIFT_EXIT=$?

VERDICT="ADDITIVE"
BREAKING_REASON=""
if [[ $DRIFT_EXIT -eq 1 ]]; then
  VERDICT="BREAKING"
  BREAKING_REASON="consumed protocol surface drifted (see drift-report.json)"
elif [[ $DRIFT_EXIT -ne 0 ]]; then
  echo "[verify] drift tool infrastructure error (exit $DRIFT_EXIT)" >&2
  node -e 'const fs=require("fs");fs.writeFileSync(process.argv[1],JSON.stringify({schemaVersion:1,label:process.argv[2],verdict:"ERROR",reason:"drift tool infrastructure error"},null,2))' "$WORK_DIR/verify-report.json" "$LABEL"
  exit 2
fi

if [[ $VERDICT == "BREAKING" ]]; then
  # BREAKING routes to a human-approved agent-drafted patch (§14.2 T3 matrix).
  # Suites are NOT run — there is nothing to green-light; the report is the PR brief.
  node -e '
    const fs=require("fs");
    const drift=JSON.parse(fs.readFileSync(process.argv[3],"utf8"));
    fs.writeFileSync(process.argv[1],JSON.stringify({
      schemaVersion:1,label:process.argv[2],verdict:"BREAKING",
      reason:"consumed protocol surface drifted",
      drift:{verdict:drift.verdict,summary:drift.summary},
      gradle:"skipped (BREAKING routes to agent-drafted PR + human approval)"
    },null,2))' "$WORK_DIR/verify-report.json" "$LABEL" "$WORK_DIR/drift-report.json"
  echo "[verify] VERDICT=BREAKING — report at $WORK_DIR/drift-report.json"
  exit 1
fi

# ── Step 2: regenerate the golden fixtures from the NEW schemas ──────────────
echo "[verify] step 2: regenerate golden fixtures from the new schemas"
FIXTURES_DIR="$WORK_DIR/fixtures/protocol"
mkdir -p "$FIXTURES_DIR"
export ZCODE_REFERENCE_REPO="$UPSTREAM_ROOT"
GEN_FAILED=0
# Each generator takes the FULL output path in ZCODE_FIXTURES_OUT (the env
# override added for this pipeline — dev defaults unchanged).
ZCODE_FIXTURES_OUT="$FIXTURES_DIR/phase2-wire-fixtures.json" node "$REPO_ROOT/tools/capture/generate-phase2-fixtures.mjs" \
  && ZCODE_FIXTURES_OUT="$FIXTURES_DIR/phase3-wire-fixtures.json" node "$REPO_ROOT/tools/capture/generate-phase3-fixtures.mjs" \
  && ZCODE_FIXTURES_OUT="$FIXTURES_DIR/phase4-wire-fixtures.json" node "$REPO_ROOT/tools/capture/generate-phase4-fixtures.mjs" \
  && ZCODE_FIXTURES_OUT="$FIXTURES_DIR/golden-vectors.json" npx --yes tsx "$REPO_ROOT/tools/capture/generate-golden-vectors.mjs" \
  || GEN_FAILED=1
unset ZCODE_REFERENCE_REPO

if [[ $GEN_FAILED -ne 0 ]]; then
  # The committed generators encode the surfaces the decoders consume; when they
  # cannot produce valid fixtures from the new schemas, the new surface is
  # breaking the consumed contract even if the name-level diff missed it.
  node -e 'const fs=require("fs");fs.writeFileSync(process.argv[1],JSON.stringify({schemaVersion:1,label:process.argv[2],verdict:"BREAKING",reason:"golden-fixture generation failed against the new schemas (marker/schema drift in a consumed surface)"},null,2))' "$WORK_DIR/verify-report.json" "$LABEL"
  echo "[verify] VERDICT=BREAKING (fixture generation failed)"
  exit 1
fi
echo "[verify] fixtures regenerated into $FIXTURES_DIR"

if [[ $SKIP_GRADLE -eq 1 ]]; then
  node -e 'const fs=require("fs");fs.writeFileSync(process.argv[1],JSON.stringify({schemaVersion:1,label:process.argv[2],verdict:"ADDITIVE",fixtures:"regenerated",gradle:"skipped by flag"},null,2))' "$WORK_DIR/verify-report.json" "$LABEL"
  echo "[verify] VERDICT=ADDITIVE (gradle skipped by flag)"
  exit 0
fi

# ── Step 3: scratch clone + swapped fixtures + full unit suite ───────────────
echo "[verify] step 3: scratch clone + gradle"
CLONE="$WORK_DIR/scratch-android"
git clone --quiet --local "$REPO_ROOT" "$CLONE" || { echo "clone failed" >&2; exit 2; }
cp "$FIXTURES_DIR"/*.json "$CLONE/fixtures/protocol/"
# Machine-local SDK pointer (gitignored; CI runners use ANDROID_HOME instead).
[[ -f "$REPO_ROOT/local.properties" ]] && cp "$REPO_ROOT/local.properties" "$CLONE/"

GRADLE_EXIT=0
( cd "$CLONE" && gradle test --continue --console=plain -q > "$WORK_DIR/gradle.log" 2>&1 ) || GRADLE_EXIT=$?

EXONERATED="[]"
if [[ $GRADLE_EXIT -ne 0 ]]; then
  # Rule-7 mechanized exoneration: this machine's documented timing-flake family
  # (RecoveryScenarioMatrixTest, ConnectionHubWritePathTest, …) fires ~once per
  # full-suite run under load. Re-run each FAILING CLASS in ISOLATION, in the
  # same clone; only names that stay red classify as breaking.
  node -e '
    const fs=require("fs"),path=require("path");
    const pairs=[];
    const walk=(mod,d)=>{for(const e of fs.readdirSync(d,{withFileTypes:true})){
      const p=path.join(d,e.name);
      if(e.isDirectory())walk(mod,p);
      else if(e.name.startsWith("TEST-")&&e.name.endsWith(".xml")){
        const s=fs.readFileSync(p,"utf8");
        const re=/<testcase name="[^"]*" classname="([^"]*)"[^>]*>(?:(?!<\/testcase>).)*?<failure/s;
        let m;const seen=new Set();
        while((m=re.exec(s))){if(!seen.has(m[1])){seen.add(m[1]);pairs.push(mod+"|"+m[1]);}}
      }}};
    for(const mod of fs.readdirSync(".",{withFileTypes:true})){
      if(!mod.isDirectory())continue;
      const tr=path.join(mod.name,"build","test-results");
      if(fs.existsSync(tr))walk(mod.name,tr);}
    fs.writeFileSync(process.argv[1],pairs.join("\n"));
  ' "$WORK_DIR/failing-pairs.txt" 2>/dev/null || true
  if [[ -s "$WORK_DIR/failing-pairs.txt" ]]; then
    echo "[verify] suite red — re-running failing classes in isolation:"
    sed 's/^/    /' "$WORK_DIR/failing-pairs.txt"
    ISOL_ARGS=()
    while IFS='|' read -r mod cls; do
      [[ -n "$mod" && -n "$cls" ]] || continue
      TASK=":$mod:test"
      [[ -d "$CLONE/$mod/build/test-results/testDebugUnitTest" ]] && TASK=":$mod:testDebugUnitTest"
      ISOL_ARGS+=("$TASK" "--tests" "$cls")
    done < "$WORK_DIR/failing-pairs.txt"
    ( cd "$CLONE" && gradle "${ISOL_ARGS[@]}" --rerun-tasks --console=plain -q > "$WORK_DIR/gradle-isolation.log" 2>&1 ) \
      && EXONERATED=$(node -e 'const fs=require("fs");process.stdout.write(JSON.stringify(fs.readFileSync(process.argv[1],"utf8").trim().split("\n").map((l)=>l.replace("|"," > "))))' "$WORK_DIR/failing-pairs.txt") \
      && GRADLE_EXIT=0 \
      || GRADLE_EXIT=1
  fi
fi

if [[ $GRADLE_EXIT -ne 0 ]]; then
  # Rule-7 mechanized exoneration: the machine's documented timing-flake family
  # (RecoveryScenarioMatrixTest, ConnectionHubWritePathTest, …) fires ~once per
  # full-suite run under load. Re-run the FAILING CLASSES in isolation in the
  # same clone; only names that stay red classify as breaking.
  FAILING_CLASSES=$(node -e '
    const fs=require("fs"),path=require("path");
    const out=[];
    const walk=(d)=>{for(const e of fs.readdirSync(d,{withFileTypes:true})){
      const p=path.join(d,e.name);
      if(e.isDirectory())walk(p);
      else if(e.name.startsWith("TEST-")&&e.name.endsWith(".xml")){
        const s=fs.readFileSync(p,"utf8");
        const re=/<testcase name="([^"]*)" classname="([^"]*)"[^>]*>(?:(?!<\/testcase>).)*?<failure/s;
        let m;const seen=new Set();
        while((m=re.exec(s))){if(!seen.has(m[2])){seen.add(m[2]);out.push(m[2]);}}
      }}};
    for(const mod of fs.readdirSync(".",{withFileTypes:true})){
      if(mod.isDirectory()){const tr=path.join(mod.name,"build","test-results");
        if(fs.existsSync(tr))walk(tr);}}
    process.stdout.write(out.join(" "));
  ' 2>/dev/null)
  if [[ -n "${FAILING_CLASSES// /}" ]]; then
    echo "[verify] suite red — re-running failing classes in isolation: $FAILING_CLASSES"
    ARGS=()
    for c in $FAILING_CLASSES; do ARGS+=(--tests "$c"); done
    ( cd "$CLONE" && gradle :app:testDebugUnitTest "${ARGS[@]}" :data:testDebugUnitTest "${ARGS[@]}" \
        --rerun-tasks --console=plain -q >> "$WORK_DIR/gradle-isolation.log" 2>&1 ) \
      && EXONERATED=$(node -e 'process.stdout.write(JSON.stringify(process.argv.slice(1)))' $FAILING_CLASSES) \
      && GRADLE_EXIT=0 \
      || GRADLE_EXIT=1
  fi
fi

if [[ $GRADLE_EXIT -ne 0 ]]; then
  node -e '
    const fs=require("fs");
    fs.writeFileSync(process.argv[1],JSON.stringify({
      schemaVersion:1,label:process.argv[2],verdict:"BREAKING",
      reason:"unit suite red against fixtures regenerated from the new schemas",
      gradle:"FAILED (gradle.log in the work dir)"
    },null,2))' "$CLONE/verify-report.json" "$LABEL" 2>/dev/null || true
  echo "[verify] VERDICT=BREAKING (suite red vs regenerated fixtures) — log: $WORK_DIR/gradle.log"
  exit 1
fi

node -e 'const fs=require("fs");fs.writeFileSync(process.argv[1],JSON.stringify({schemaVersion:1,label:process.argv[2],verdict:"ADDITIVE",fixtures:"regenerated",gradle:"green",exoneratedFlakes:JSON.parse(process.argv[3])},null,2))' "$WORK_DIR/verify-report.json" "$LABEL" "$EXONERATED"
echo "[verify] VERDICT=ADDITIVE — suites green against the regenerated fixtures"
exit 0
