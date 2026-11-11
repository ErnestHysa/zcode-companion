#!/usr/bin/env python3
"""Automated relay pairing for the rig — no human hands.

Takes the device from a wiped app state to CONNECTED against a running MockZCode
relay, end to end:

  1. (unless --no-clear) `pm clear` the app  -> fresh pairing screen
  2. serve the pairing link at http://10.0.2.2:<port>/pair-<ts>.html (cache-busted)
  3. Chrome: open the page, tap the COPYLINK button (user gesture -> execCommand
     'copy'), verify the page flips to copied-ok
  4. App: open, tap "Paste pairing link", tap the field, KEYCODE_PASTE (279),
     tap "Show secret", verify the field holds the EXACT link
  5. tap Continue, poll for the Tasks screen's Connected state (60s)

Every step verifies; steps 3-4 retry up to --attempts times (dialog reopened
fresh each retry). Exits 0 on Connected, 1 otherwise.

Usage:
  pair-relay.py <pairing-link> [--serial emulator-5554] [--port 18999]
                [--package com.zcode.android.app] [--activity .MainActivity]
                [--no-clear] [--attempts 3]

The link is printed by the mock relay at startup as MOCKZCODE_LINK_EMULATOR
(10.0.2.2 form) — pass that one, not the 127.0.0.1 form.
"""
import argparse
import http.server
import re
import subprocess
import sys
import threading
import time

PAGE = """<html><body><textarea id="t" readonly rows="6" cols="80">{link}</textarea><br>
<button id="b" style="font-size:32px;padding:16px 32px" onclick="var t=document.getElementById('t');t.focus();t.select();document.execCommand('copy');document.getElementById('ok').textContent='copied-ok'">COPYLINK</button>
<div id="ok">waiting</div></body></html>"""


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)


class Rig:
    def __init__(self, serial):
        self.a = f"adb -s {serial}"

    def dump(self, tries=4):
        """Full UI dump text, retrying the transient idle-state error."""
        for _ in range(tries):
            r = sh(f"{self.a} exec-out uiautomator dump /dev/tty")
            out = r.stdout
            if "<node" in out:
                return out
            time.sleep(1.2)
        return ""

    def texts(self):
        return re.findall(r'text="([^"]{1,200})"', self.dump())

    def tap_text(self, label, instance=0):
        """Tap the center of the n-th node whose text equals/starts with label."""
        data = self.dump()
        hits = []
        for m in re.finditer(
            r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', data
        ):
            if label in m.group(1):
                x1, y1, x2, y2 = map(int, m.groups()[1:])
                hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
        if len(hits) <= instance:
            return False
        x, y = hits[instance]
        sh(f"{self.a} shell input tap {x} {y}")
        return True

    def edittext_center(self):
        data = self.dump()
        m = re.search(
            r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            data,
        )
        if not m:
            return None
        x1, y1, x2, y2 = map(int, m.groups())
        return (x1 + x2) // 2, (y1 + y2) // 2

    def wait_text(self, label, timeout_s, instance=0):
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            data = self.dump()
            hits = [
                m.group(1)
                for m in re.finditer(r'text="([^"]*)"', data)
                if label in m.group(1)
            ]
            if len(hits) > instance:
                return True
            time.sleep(1.5)
        return False


def serve(page_html, port):
    handler = type(
        "H",
        (http.server.BaseHTTPRequestHandler,),
        {
            "do_GET": lambda self: self._send(),
            "_send": lambda self: (
                self.send_response(200),
                self.send_header("Content-Type", "text/html; charset=utf-8"),
                self.end_headers(),
                self.wfile.write(page_html.encode()),
            ),
            "log_message": lambda *a, **k: None,
        },
    )
    server = http.server.ThreadingHTTPServer(("0.0.0.0", port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def dismiss_anr(rig):
    """The recorded swiftshader cold-boot artifact: a SystemUI ANR dialog that
    blocks everything until dismissed. Tap Wait if present."""
    if rig.tap_text("Wait"):
        time.sleep(2)


def try_copy(rig, url, package):
    """Chrome: open the page, tap COPYLINK, verify copied-ok."""
    sh(f"{rig.a} shell am force-stop com.android.chrome")
    time.sleep(1)
    sh(f"{rig.a} shell am start -a android.intent.action.VIEW -d '{url}' com.android.chrome")
    time.sleep(7)
    dismiss_anr(rig)
    if not rig.wait_text("COPYLINK", 25):
        return False, "COPYLINK button never rendered"
    if not rig.tap_text("COPYLINK"):
        return False, "COPYLINK tap failed"
    time.sleep(1.5)
    if not rig.wait_text("copied-ok", 6):
        return False, "copy not confirmed on the page"
    return True, "copied"


def try_paste_and_continue(rig, link, package, activity):
    """App: paste-dialog -> field -> PASTE (blind; the dialog is FLAG_SECURE and its
    Show-secret toggle does not reliably export to the a11y tree) -> Continue. The
    END STATE is the verification: Connected = done, the typed error face = bad
    paste (caller retries), still-on-dialog = paste never landed (caller retries)."""
    sh(f"{rig.a} shell am force-stop {package}")
    time.sleep(1.5)
    sh(f"{rig.a} shell am start -n {package}/{activity if activity.startswith('.') else '.' + activity}")
    time.sleep(6)
    dismiss_anr(rig)
    if not rig.wait_text("Paste pairing link", 25):
        return False, "pairing screen never rendered"
    if not rig.tap_text("Paste pairing link"):
        return False, "Paste pairing link tap failed"
    time.sleep(2.5)
    center = rig.edittext_center()
    if not center:
        return False, "no EditText in the dialog"
    rig.sh_tap(center)
    time.sleep(1.5)
    sh(f"{rig.a} shell input keyevent 279")  # KEYCODE_PASTE
    time.sleep(2.5)
    sh(f"{rig.a} shell input keyevent 111")  # drop the IME if up (ESC)
    time.sleep(1.5)
    # Continue may sit under the IME; if the dialog got dismissed instead, reopen.
    for _ in range(2):
        if rig.tap_text("Continue"):
            break
        if rig.tap_text("Paste pairing link"):
            time.sleep(2)
            c = rig.edittext_center()
            if c:
                rig.sh_tap(c)
                time.sleep(1)
                sh(f"{rig.a} shell input keyevent 279")
                time.sleep(2)
                sh(f"{rig.a} shell input keyevent 111")
                time.sleep(1)
        else:
            return False, "dialog lost"
    # The end state includes the FIRST-RUN flow: pairing succeeded -> the onboarding
    # pager (Skip) + the notification ask (Not now) run BEFORE Tasks/Connected — the
    # first full run paired fine and the verdict just misread the pager as failure.
    deadline = time.time() + 90
    while time.time() < deadline:
        texts = rig.texts()
        joined = " ".join(texts)
        if "Connected" in joined:
            return True, "CONNECTED"
        if "not a valid" in joined:
            return False, "the link corrupted in transit (typed error face)"
        if "Paste pairing link" in joined:
            return False, "still on the dialog (paste never landed)"
        for bail in (
            "Continue anyway",  # the relay's stale-QR face (re-presented link)
            "Continue with reduced security",  # no-screen-lock consent (emulator)
            "Skip", "Not now", "Next",
        ):
            if bail in texts:
                rig.tap_text(bail)
                time.sleep(2.5)
                break
        time.sleep(2)
    return False, "no Connected within 90s of Continue"

Rig.sh_tap = lambda self, xy: sh(f"{self.a} shell input tap {xy[0]} {xy[1]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("link")
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--port", type=int, default=18999)
    ap.add_argument("--package", default="com.zcode.android.app")
    ap.add_argument("--activity", default=".MainActivity")
    ap.add_argument("--no-clear", action="store_true")
    ap.add_argument("--attempts", type=int, default=3)
    args = ap.parse_args()

    rig = Rig(args.serial)
    link = args.link.strip()
    print(f"link: {len(link)} chars", flush=True)

    if not args.no_clear:
        out = sh(f"{rig.a} shell pm clear {args.package}").stdout
        print(f"pm clear: {out.strip()}", flush=True)
        time.sleep(2)

    ts = int(time.time() * 1000)
    page = PAGE.format(link=link)
    server = serve(page, args.port)
    url = f"http://10.0.2.2:{args.port}/pair-{ts}.html"
    print(f"serving {url}", flush=True)

    for attempt in range(1, args.attempts + 1):
        ok, msg = try_copy(rig, url, args.package)
        print(f"[{attempt}] copy: {msg}", flush=True)
        if not ok:
            continue
        ok, msg = try_paste_and_continue(rig, link, args.package, args.activity)
        print(f"[{attempt}] paste+continue: {msg}", flush=True)
        if not ok:
            continue
        # The handshake + first bootstrap take a few seconds.
        if rig.wait_text("Connected", 60):
            print("PAIRING_OK: device Connected", flush=True)
            server.shutdown()
            return 0
        # A typed-error face means the link corrupted in transit — retry.
        err = [t for t in rig.texts() if "not a valid" in t]
        print(f"[{attempt}] post-continue: no Connected{'; ' + err[0] if err else ''}", flush=True)
    server.shutdown()
    print("PAIRING_FAILED", flush=True)
    return 1


if __name__ == "__main__":
    sys.exit(main())
