#!/usr/bin/env python3
"""Find and tap a UI element by its visible text on the connected device.

Used by the end-to-end suite to drive real dialogs (the in-app update prompts and
the system package installer) instead of calling internal APIs, so the test
covers what a person actually sees and touches.

    python3 .github/ui_tap.py --text "Download & Install" --wait 60

Exits 0 when the element was tapped, 1 when it never appeared.
"""

import argparse
import re
import subprocess
import sys
import time

NODE_RE = re.compile(r"<node[^>]*>")
TEXT_RE = re.compile(r'text="([^"]*)"')
BOUNDS_RE = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def adb(*args, timeout=60):
    try:
        return subprocess.run(
            ["adb", *args], capture_output=True, text=True, timeout=timeout
        ).stdout
    except subprocess.TimeoutExpired:
        return ""


def dump(timeout=60):
    adb("shell", "rm", "-f", "/sdcard/ui_tap.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/ui_tap.xml", timeout=timeout)
    return adb("shell", "cat", "/sdcard/ui_tap.xml")


def candidates(xml, wanted):
    """(score, text, x, y) for every node carrying the wanted text.

    Exact matches score better than substrings, and buttons beat labels, because
    a dialog title like "Update Ready to Install" also contains "Install".
    """
    found = []
    for node in NODE_RE.findall(xml):
        m = TEXT_RE.search(node)
        if not m:
            continue
        text = m.group(1).strip()
        if not text:
            continue
        low = text.lower()
        want = wanted.lower()
        if low == want:
            score = 0
        elif want in low:
            score = 2
        else:
            continue
        b = BOUNDS_RE.search(node)
        if not b:
            continue
        x1, y1, x2, y2 = (int(v) for v in b.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        if 'class="android.widget.Button"' in node:
            score -= 1
        if 'enabled="false"' in node:
            score += 3
        found.append((score, text, (x1 + x2) // 2, (y1 + y2) // 2))
    return sorted(found)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", required=True, help="visible text to tap")
    ap.add_argument("--wait", type=int, default=30, help="seconds to keep looking")
    args = ap.parse_args()

    deadline = time.time() + args.wait
    attempt = 0
    last_xml = ""
    while time.time() < deadline:
        attempt += 1
        last_xml = dump()
        hits = candidates(last_xml, args.text)
        if hits:
            score, text, x, y = hits[0]
            print(f'  found "{text}" at ({x},{y}) score={score} attempt={attempt}')
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)],
                           capture_output=True, timeout=60)
            print(f'  tapped "{text}"')
            return 0
        time.sleep(4)

    print(f'  never found "{args.text}" in {args.wait}s ({attempt} dumps)', file=sys.stderr)
    print("  --- visible text on screen ---", file=sys.stderr)
    for t in {m.group(1).strip() for m in TEXT_RE.finditer(last_xml) if m.group(1).strip()}:
        print(f"  | {t}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
