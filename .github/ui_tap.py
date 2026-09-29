#!/usr/bin/env python3
"""Find and tap a UI element by its visible text on the connected device.

Used by the end-to-end suite to drive real dialogs (the in-app update prompts and
the system package installer) instead of calling internal APIs, so the test
covers what a person actually sees and touches.

    python3 .github/ui_tap.py --text "Download & Install" --wait 60

Exits 0 when the element was tapped, 1 when it never appeared.
"""

import argparse
import html
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


def candidates(xml, wanted, exact=False):
    """(score, text, x, y) for every node carrying the wanted text.

    Attribute values are HTML-escaped in the dump (`Download &amp; Install`,
    `&#128640; Update Available`), so unescape before comparing or an ampersand
    makes a button invisible to the search.

    Exact matches score better than substrings, and buttons beat labels, because
    a dialog title like "Update Ready to Install" also contains "Install".
    With exact=True only exact matches are considered at all - use it when a
    substring hit would tap the wrong control (waiting for the ready-to-install
    button must not settle for "Download & Install").
    """
    found = []
    want = wanted.lower()
    for node in NODE_RE.findall(xml):
        m = TEXT_RE.search(node)
        if not m:
            continue
        text = html.unescape(m.group(1)).strip()
        if not text:
            continue
        low = text.lower()
        if low == want:
            score = 0
        elif want in low and not exact:
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


def visible_texts(xml):
    return sorted({html.unescape(m.group(1)).strip() for m in TEXT_RE.finditer(xml)
                   if m.group(1).strip()})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", required=True, help="visible text to tap")
    ap.add_argument("--wait", type=int, default=30, help="seconds to keep looking")
    ap.add_argument("--exact", action="store_true", help="only accept an exact text match")
    args = ap.parse_args()

    deadline = time.time() + args.wait
    attempt = 0
    last_xml = ""
    while time.time() < deadline:
        attempt += 1
        last_xml = dump()
        hits = candidates(last_xml, args.text, exact=args.exact)
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
    for t in visible_texts(last_xml):
        print(f"  | {t}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
