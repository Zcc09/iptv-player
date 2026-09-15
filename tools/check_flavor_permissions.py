#!/usr/bin/env python3
"""Verify each build flavor declares what it must - and nothing it must not.

Google Play restricts REQUEST_INSTALL_PACKAGES and forbids apps installing their
own updates, so:
    play bundle  must NOT declare the permission
    github apk   MUST declare it (that is how it self-updates)

Both manifests are binary, so this reads them directly:
    APK -> AndroidManifest.xml is AXML (string pool is utf-16-le)
    AAB -> base/manifest/AndroidManifest.xml is protobuf (strings are utf-8)

Each artifact is also checked against a CONTROL string that has to be present,
so a broken scan fails loudly instead of silently reporting "permission absent"
- which is exactly how the first version of this check gave a false pass.

Usage: check_flavor_permissions.py <play.aab> <github-release.apk>
"""
import sys
import zipfile

PERMISSION = "REQUEST_INSTALL_PACKAGES"
CONTROL = "com.zcc09.iptvplayer"          # must appear in every manifest
ENCODINGS = ("utf-8", "utf-16-le")


def read_manifest(path):
    """Return the raw manifest bytes, whichever archive layout this is."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if "AndroidManifest.xml" in names:                 # APK
            return z.read("AndroidManifest.xml"), "AndroidManifest.xml (AXML)"
        if "base/manifest/AndroidManifest.xml" in names:   # AAB
            return z.read("base/manifest/AndroidManifest.xml"), "base/manifest/AndroidManifest.xml (proto)"
    raise SystemExit(f"FAIL: {path} has no manifest this script understands")


def contains(blob, needle):
    """True if needle is present in any of the encodings a manifest may use."""
    return any(needle.encode(enc) in blob for enc in ENCODINGS)


def check(path, want_permission, label):
    blob, kind = read_manifest(path)

    # control first: if this fails the scan itself is broken, not the build
    if not contains(blob, CONTROL):
        print(f"FAIL: {label}: control string {CONTROL!r} not found in {kind} "
              f"-> the scan is broken, refusing to report a pass")
        return False

    has = contains(blob, PERMISSION)
    if has != want_permission:
        expectation = "must declare" if want_permission else "must NOT declare"
        print(f"FAIL: {label}: {expectation} {PERMISSION} (found={has}) [{kind}]")
        return False

    print(f"OK:   {label}: {PERMISSION} present={has} (control confirmed readable) [{kind}]")
    return True


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    aab, apk = sys.argv[1], sys.argv[2]
    ok = check(aab, want_permission=False, label="play bundle")
    ok &= check(apk, want_permission=True, label="github apk")
    print()
    print("FLAVOR PERMISSION CHECK: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
