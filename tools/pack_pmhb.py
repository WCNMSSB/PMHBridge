"""Package the PMHBridge Magisk module from source + external blobs.

PMHB/ holds only code we wrote (GPL-3.0): boot scripts, manifests, props,
sepolicy and C sources. The official/repacked payloads -- PICO's runtime APK and
libraries, the Meta loader, prebuilt probes, compiled shims -- live outside the
source tree in <repo>/blobs/pmhb/ and are merged in here.

Usage:
    python tools\\pack_pmhb.py [--blobs <dir>] [--out <zip>]

The blobs directory is not distributed with the repository; see BLOBS.md.
"""
import argparse
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.abspath(os.path.join(HERE, ".."))          # PMHB/PMHBridge
REPO = os.path.abspath(os.path.join(PROJECT, "..", ".."))    # repo root

DEFAULT_MODULE = os.path.join(PROJECT, "module-sys", "pmhb")
DEFAULT_BLOBS = os.path.join(REPO, "blobs", "pmhb")
DEFAULT_OUT = os.path.join(REPO, "artifacts", "pmhbridge", "dist", "pmhb-magisk.zip")

SKIP_NAMES = {"update", "remove", "disable"}

# Blobs without which the module would be useless at runtime.
REQUIRED_BLOBS = [
    "system/lib64/libpmh_openxr_layer.so",
    "system/lib64/libpmh_pico_bridge.so",
    "system/lib64/libpmh_ctrl_shim.so",
    "system/lib64/libmeta_xrloader.so",
    "system/etc/pmh_pxr_services.dex",
    "system/priv-app/PicoXRRuntime/PicoXRRuntime.apk",
    "system/priv-app/BluetoothPeripheralService",
    "files/libpico_openxr_loader.so",
    "files/libopenxr_loader_real.so",
    "files/libc++.so",
]


def collect(root):
    entries = {}
    if not os.path.isdir(root):
        return entries
    for base, _dirs, files in os.walk(root):
        for name in files:
            if name in SKIP_NAMES:
                continue
            full = os.path.join(base, name)
            rel = os.path.relpath(full, root).replace("\\", "/")
            entries[rel] = full
    return entries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--blobs", default=DEFAULT_BLOBS)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--allow-missing-blobs", action="store_true")
    args = ap.parse_args()

    missing = [b for b in REQUIRED_BLOBS
               if not os.path.exists(os.path.join(args.blobs, b.replace("/", os.sep)))]
    if missing and not args.allow_missing_blobs:
        print("missing blobs (see BLOBS.md):", file=sys.stderr)
        for b in missing:
            print("  " + b, file=sys.stderr)
        return 2
    if missing:
        print("warning: packaging without", len(missing), "blobs")

    entries = collect(DEFAULT_MODULE)
    entries.update(collect(args.blobs))

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    if os.path.exists(args.out):
        os.remove(args.out)

    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in sorted(entries):
            data = open(entries[rel], "rb").read()
            if rel.endswith(".sh"):
                data = data.replace(b"\r\n", b"\n")
            info = zipfile.ZipInfo(rel)
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = 0o755 if (rel.endswith(".sh") or rel.startswith("bin/")) else 0o644
            info.external_attr = (0o100000 | mode) << 16
            z.writestr(info, data)

    print("packed %d files -> %s (%d bytes)" % (len(entries), args.out, os.path.getsize(args.out)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
