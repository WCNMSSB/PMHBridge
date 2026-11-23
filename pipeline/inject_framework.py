#!/usr/bin/env python3
"""Make a PICO APK self-contained enough to start on Horizon OS.

PICO apps compiled for PICO OS borrow framework classes that only exist there —
android.resourcemanager.Rm, com.pico.api.app.AppSession and friends — plus native
libraries (librms_*) that sit in /system/lib64 on a PICO headset. On Horizon OS
the classes are missing, so the app dies in Application.onCreate.

This script pulls exactly those pieces out of a firmware directory produced by
extract_firmware.ps1 and folds them into the APK:

  * every framework dex that defines a needed class becomes classes2.dex, 3, ...
  * librms*.so are placed in lib/arm64-v8a/
  * sharedUserId is stripped, WAKE_LOCK is added (both needed to install/launch)

Usage:
  python pipeline/inject_framework.py --apk in.apk --fw images/p4u-5.15.4 \
      --out dist/app-fw.apk [--sign]
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

MARKERS = [
    b"Landroid/resourcemanager/Rm;",
    b"Lcom/pico/api/app/AppSession;",
    b"Lcom/pico/api/app/IApiLayer;",
    b"Lcom/pico/api/app/IScenesManager;",
    # PICO ships a compatibility shim for their "smartisanos" framework classes;
    # Connect calls into it during startup.
    b"Lsmartisanos/",
]

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
PATCH = os.path.join(HERE, "..", "tools", "patch_apk.py")
SDK = os.environ.get("ANDROID_SDK_ROOT", os.path.join(ROOT, "tools", "android-sdk"))
BUILD_TOOLS = os.path.join(SDK, "build-tools", "35.0.0")
if not os.path.exists(os.path.join(BUILD_TOOLS, "zipalign.exe")):
    BUILD_TOOLS = os.path.join(SDK, "build-tools", "34.0.0")
KEYSTORE = os.path.join(ROOT, "testapp", "debug.keystore")


def run(cmd):
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True)


def framework_dexes(fw: str) -> list[tuple[str, bytes]]:
    """Return [(name, dex_bytes)] for each framework dex holding a needed class."""
    jar = os.path.join(fw, "framework", "framework.jar")
    if not os.path.exists(jar):
        raise SystemExit(f"missing {jar}")
    picked = []
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            if not info.filename.endswith(".dex"):
                continue
            blob = z.read(info)
            if any(marker in blob for marker in MARKERS):
                picked.append((info.filename, blob))
    if not picked:
        raise SystemExit("no framework dex contains the needed classes")
    return picked


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--fw", required=True, help="directory from extract_firmware.ps1")
    ap.add_argument("--out", required=True)
    ap.add_argument("--sign", action="store_true")
    args = ap.parse_args(argv)

    dexes = framework_dexes(args.fw)
    print("framework dexes:", [(n, len(b)) for n, b in dexes])

    libs = []
    for lib in ("librms_jni.so", "librmsclient.so", "librmscore.so"):
        path = os.path.join(args.fw, "lib64", lib)
        if os.path.exists(path):
            libs.append(path)
    ext = os.path.join(args.fw, "framework", "pico-framework-extension-api.jar")
    ext_dex = None
    if os.path.exists(ext):
        with zipfile.ZipFile(ext) as z:
            if "classes.dex" in z.namelist():
                ext_dex = z.read("classes.dex")

    work = tempfile.mkdtemp(prefix="pmh_fw_")
    try:
        staged = os.path.join(work, "staged.apk")
        with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
                staged, "w", zipfile.ZIP_STORED) as dst:
            for info in src.infolist():
                dst.writestr(info, src.read(info.filename))
            index = 2
            for _, blob in dexes:
                dst.writestr(f"classes{index}.dex", blob)
                index += 1
            if ext_dex is not None:
                dst.writestr(f"classes{index}.dex", ext_dex)
                index += 1
            for lib in libs:
                dst.write(lib, "lib/arm64-v8a/" + os.path.basename(lib))

        # sharedUserId must go before the APK can install; WAKE_LOCK because the
        # app was written assuming a system uid.
        patched = os.path.join(work, "patched.apk")
        run([sys.executable, PATCH, "--apk", staged, "--out", patched,
             "--strip-shared-uid",
             "--permission", "android.permission.WAKE_LOCK"])

        out = os.path.abspath(args.out)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        if args.sign:
            aligned = os.path.join(work, "aligned.apk")
            run([os.path.join(BUILD_TOOLS, "zipalign.exe"), "-f", "4", patched, aligned])
            run([os.path.join(BUILD_TOOLS, "apksigner.bat"), "sign",
                 "--ks", KEYSTORE, "--ks-pass", "pass:android",
                 "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
                 "--out", out, aligned])
        else:
            shutil.copyfile(patched, out)
        print("wrote", out, os.path.getsize(out), "bytes")
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
