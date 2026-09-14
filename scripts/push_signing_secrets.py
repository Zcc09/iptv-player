#!/usr/bin/env python3
"""Upload the release signing secrets to the GitHub repo (sealed with the repo public key)."""
import base64
import json
import os
import sys
import urllib.request

from nacl import encoding, public

REPO = os.environ.get("REPO", "Zcc09/iptv-player")
TOKEN = os.environ["GH_TOKEN"]
KEY_DIR = os.environ["KEY_DIR"]
PASSWORD_FILE = os.path.join(KEY_DIR, "pass.txt")
KEYSTORE_B64 = os.path.join(KEY_DIR, "keystore.b64")

API = "https://api.github.com"


def api(path, method="GET", data=None):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(
        API + path,
        data=body,
        method=method,
        headers={
            "Authorization": f"token {TOKEN}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
            "User-Agent": "iptv-player-ci",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read()
        return resp.status, (json.loads(raw) if raw else {})


def seal(public_key: str, value: str) -> str:
    pk = public.PublicKey(public_key.encode(), encoding.Base64Encoder())
    sealed = public.SealedBox(pk)
    return base64.b64encode(sealed.encrypt(value.encode())).decode()


def main() -> int:
    status, payload = api(f"/repos/{REPO}/actions/secrets/public-key")
    if status != 200:
        print(f"cannot read public key: {status} {payload}")
        return 1
    key_id = payload["key_id"]
    key_value = payload["key"]

    password = open(PASSWORD_FILE).read().strip()
    keystore = open(KEYSTORE_B64).read().strip()
    if not keystore:
        print("keystore base64 is empty")
        return 1

    secrets = {
        "KEYSTORE_BASE64": keystore,
        "KEYSTORE_PASSWORD": password,
        "KEY_ALIAS": "iptv",
        "KEY_PASSWORD": password,
    }
    for name, value in secrets.items():
        status, payload = api(
            f"/repos/{REPO}/actions/secrets/{name}",
            method="PUT",
            data={"encrypted_value": seal(key_value, value), "key_id": key_id},
        )
        shown = f"{len(value)} chars" if name == "KEYSTORE_BASE64" else "(hidden)"
        if status in (201, 204):
            print(f"  ok   {name} = {shown}")
        else:
            print(f"  FAIL {name}: {status} {payload}")
            return 1

    status, payload = api(f"/repos/{REPO}/actions/secrets")
    names = sorted(s["name"] for s in payload.get("secrets", []))
    print("secrets now on the repo:", names)
    return 0


if __name__ == "__main__":
    sys.exit(main())
