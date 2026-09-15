#!/usr/bin/env python3
"""Download artifacts from a GitHub Actions run - no gh CLI required.

Usage:
    python tools/get_run_artifacts.py <run-id>                 # list artifacts
    python tools/get_run_artifacts.py <run-id> <name> [dest]   # download one

Reads the token the same way git does (`git credential fill`), so nothing
secret is stored in the repo or in shell history.

Two GitHub API quirks this handles:
  * the artifact zip URL 302s to a signed storage URL that must be fetched
    WITHOUT the Authorization header (sending it there yields 403);
  * job logs do not exist until a run finishes, so a 404 here means "not yet".

Examples:
    python tools/get_run_artifacts.py 35009482058 screenshots ./shots
    python tools/get_run_artifacts.py 35009482058            # just list them
"""
import io
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile

REPO = "Zcc09/iptv-player"
API = "https://api.github.com"


def token() -> str:
    out = subprocess.run(
        ["bash", "-lc",
         "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill | grep password= | cut -d= -f2"],
        capture_output=True, text=True).stdout.strip()
    if not out:
        raise SystemExit("no GitHub token available from `git credential fill`")
    return out


def api(path: str, tok: str):
    req = urllib.request.Request(
        API + path,
        headers={"Authorization": f"token {tok}",
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "hermes"})
    try:
        return json.load(urllib.request.urlopen(req, timeout=30))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise SystemExit(
                "404 - either the run id/artifact name is wrong, or the run has not finished "
                "(GitHub only materialises artifacts and logs once a run ends)")
        raise


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def fetch_zip(url: str, tok: str) -> bytes:
    req = urllib.request.Request(url, headers={"Authorization": f"token {tok}",
                                               "User-Agent": "hermes"})
    try:
        resp = urllib.request.build_opener(_NoRedirect).open(req, timeout=30)
        signed = resp.geturl()
    except urllib.error.HTTPError as e:
        signed = e.headers["Location"]
    # plain GET, no auth header
    return urllib.request.urlopen(signed, timeout=180).read()


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    run = sys.argv[1]
    want = sys.argv[2] if len(sys.argv) > 2 else None
    dest = sys.argv[3] if len(sys.argv) > 3 else "."

    tok = token()
    arts = api(f"/repos/{REPO}/actions/runs/{run}/artifacts", tok)["artifacts"]
    if not arts:
        print(f"no artifacts on run {run}")
        return 1

    if want is None:
        print(f"artifacts on run {run}:")
        for a in arts:
            print(f"   {a['name']:16s} {a['size_in_bytes']:>12,} bytes  expired={a['expired']}")
        return 0

    target = next((a for a in arts if a["name"] == want), None)
    if target is None:
        raise SystemExit(f"no artifact named {want!r}; have: {[a['name'] for a in arts]}")

    blob = fetch_zip(f"{API}/repos/{REPO}/actions/artifacts/{target['id']}/zip", tok)
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        for info in z.infolist():
            path = os.path.join(dest, os.path.basename(info.filename))
            with open(path, "wb") as fh:
                fh.write(z.read(info))
            print(f"   {path}  ({info.file_size:,} bytes)")
    print(f"saved to {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
