#!/usr/bin/env python3
"""Whole-surface string counts over the shipped ZCode desktop asar (RIG-6, vc51 Phase 5).

Pass-12 (AH3) established that chunk-subset string counts UNDERCOUNT: enumerations
must count over ALL of `out/` — never one chunk. This script makes that method
durable: it counts byte-literal needles over the ENTIRE packed asar (a strict
superset of any out/ subsetting: header + every bundled file + source maps +
vendor chunks), sliding with bytes.find — regex bounded-repetition blows up on a
~327 MB binary, so no regexes (the AGENTS.md vc51-Phase-4 gotcha).

Usage:
  python3 tools/drift/asar-out-count.py                 # the standing needle set
  python3 tools/drift/asar-out-count.py needle [more…]  # ad-hoc needles (utf-8)

The asar path defaults to the shipped app; override with ASAR_PATH for a clone or
an updated install. Any future desktop-update enumeration cites these WHOLE-ASAR
counts (the baseline pass-12 measurements: zh-CN x209 whole-asar vs the x195
whole-out/ AH3 recount vs the x73 chunk-subset undercount; zai-light x57;
zcodeInteractionBehavior x37; "Remote connection" x5; WebRemoteControl x267).
"""
import sys
import hashlib

DEFAULT_ASAR = "/Applications/ZCode.app/Contents/Resources/app.asar"

# The AH3-drift needles (pass 12) — keep this set in sync with the report rows.
STANDING_NEEDLES = [
    "zh-CN",
    "zai-light",
    "zcodeInteractionBehavior",
    "Remote connection",
    "workspaceSidebar.projectsSection",
    "remote.trigger",
    "settings.locale.zh-CN",
    "Interface zoom",
    "WebRemoteControl",
]


def count(data: bytes, needle: bytes) -> int:
    n = 0
    i = data.find(needle)
    while i != -1:
        n += 1
        i = data.find(needle, i + 1)
    return n


def main() -> None:
    import os

    path = os.environ.get("ASAR_PATH", DEFAULT_ASAR)
    needles = sys.argv[1:] or STANDING_NEEDLES
    with open(path, "rb") as fh:
        data = fh.read()
    digest = hashlib.sha256(data).hexdigest()
    print(f"asar: {path}")
    print(f"bytes: {len(data)}")
    print(f"sha256: {digest[:16]}… (the drift check: compare with the recorded remediation target)")
    print("method: byte-literal sliding count over the ENTIRE asar (no chunk subsetting, no regex)")
    for needle in needles:
        print(f"{needle} x{count(data, needle.encode('utf-8'))}")


if __name__ == "__main__":
    main()
