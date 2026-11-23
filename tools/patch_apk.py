#!/usr/bin/env python3
"""Patch an APK so it installs and behaves on Horizon OS.

This is the CLI half of the helper app's "修补" tab: the app collects the user's
choices, and this script performs them offline on the PC. Steps are individually
optional so the same script covers "just strip the shared uid" up to a full
repackage.

What it can do
--------------
* strip android:sharedUserId        (the #1 reason PICO/system APKs refuse to install)
* add <uses-permission> entries     (WAKE_LOCK, WRITE_SECURE_SETTINGS, PICO perms)
* mark an activity as VR            (com.oculus.intent.category.VR)
* unmark an activity as VR          (back to a 2D panel)
* re-sign with the project's debug keystore

Grouping patches (recipe):
    python tools/patch_apk.py --apk in.apk --out out.apk \
        --strip-shared-uid \
        --permission android.permission.WAKE_LOCK \
        --permission android.permission.WRITE_SECURE_SETTINGS \
        --vr-activity com.picovr.picostreamassistant.ui.MainActivity \
        --sign

The individual transformations reuse the battle-tested scripts in the old
tools/ directory (binary-XML surgery); this file only chains them.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
LEGACY_TOOLS = os.path.abspath(os.path.join(HERE, "..", "..", "..", "tools"))
SDK = os.environ.get("ANDROID_SDK_ROOT", r"K:\PICOXRRUNTIME2HORIZONOS\tools\android-sdk")
BUILD_TOOLS = os.path.join(SDK, "build-tools", "34.0.0")
KEYSTORE = r"K:\PICOXRRUNTIME2HORIZONOS\testapp\debug.keystore"

# Permissions that only ever work for system uid / priv-app on PICO OS. Keeping
# them makes a non-root install fail or the app crash on first use.
PRIVILEGED_PERMISSIONS = [
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.WRITE_SETTINGS",
    "android.permission.MANAGE_ACTIVITY_STACKS",
    "android.permission.READ_LOGS",
    "android.permission.DUMP",
    "android.permission.INJECT_EVENTS",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.REBOOT",
    "android.permission.DEVICE_POWER",
]


def run(cmd: list[str]) -> None:
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True)


def tool(name: str) -> str:
    path = os.path.join(LEGACY_TOOLS, name)
    if not os.path.exists(path):
        raise SystemExit(f"missing helper script: {path}")
    return path


def strip_shared_uid(apk: str, out: str) -> None:
    """Delete android:sharedUserId instead of replacing it.

    A fake shared id installs, but the process then runs under a uid that does not
    correspond to the package, and LSPosed cannot map that process back to the
    package's scope - the module silently never loads.
    """
    run([sys.executable, os.path.join(HERE, "remove_manifest_attr.py"),
         "--apk", apk, "--out", out,
         "--tag", "manifest",
         "--attr", "sharedUserId"])


def add_permission(apk: str, out: str, permission: str) -> None:
    run([sys.executable, tool("add_permission.py"),
         "--apk", apk, "--out", out, "--permission", permission])


def mark_vr(apk: str, out: str, activity: str) -> None:
    run([sys.executable, tool("add_vr_category.py"),
         "--apk", apk, "--out", out, "--activity", activity])


def unmark_vr(apk: str, out: str, activity: str) -> None:
    run([sys.executable, tool("remove_intent_category.py"),
         "--apk", apk, "--out", out,
         "--activity", activity,
         "--category", "com.oculus.intent.category.VR"])


def drop_permission(apk: str, out: str, permissions: list[str]) -> None:
    cmd = [sys.executable, os.path.join(HERE, "remove_permission.py"),
           "--apk", apk, "--out", out]
    for permission in permissions:
        cmd += ["--permission", permission]
    run(cmd)


def sign(apk: str, out: str) -> None:
    aligned = apk + ".aligned"
    run([os.path.join(BUILD_TOOLS, "zipalign.exe"), "-f", "4", apk, aligned])
    run([os.path.join(BUILD_TOOLS, "apksigner.bat"), "sign",
         "--ks", KEYSTORE, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
         "--out", out, aligned])
    os.remove(aligned)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--strip-shared-uid", action="store_true")
    ap.add_argument("--permission", action="append", default=[])
    ap.add_argument("--vr-activity", action="append", default=[])
    ap.add_argument("--no-vr-activity", action="append", default=[])
    ap.add_argument("--no-root", action="store_true",
                    help="免 root 修补：去 sharedUserId、删特权权限、补必要权限")
    ap.add_argument("--drop-permission", action="append", default=[])
    ap.add_argument("--sign", action="store_true")
    args = ap.parse_args(argv)

    work = tempfile.mkdtemp(prefix="pmh_patch_")
    try:
        current = os.path.abspath(args.apk)
        step = 0

        def next_path() -> str:
            nonlocal step
            step += 1
            return os.path.join(work, f"step{step}.apk")

        if args.no_root:
            args.strip_shared_uid = True
            args.drop_permission += PRIVILEGED_PERMISSIONS
            args.permission += ["android.permission.WAKE_LOCK",
                               "android.permission.QUERY_ALL_PACKAGES"]

        if args.strip_shared_uid:
            nxt = next_path()
            strip_shared_uid(current, nxt)
            current = nxt

        for permission in args.permission:
            nxt = next_path()
            add_permission(current, nxt, permission)
            current = nxt

        if args.drop_permission:
            nxt = next_path()
            drop_permission(current, nxt, args.drop_permission)
            current = nxt

        for activity in args.vr_activity:
            nxt = next_path()
            mark_vr(current, nxt, activity)
            current = nxt

        for activity in args.no_vr_activity:
            nxt = next_path()
            unmark_vr(current, nxt, activity)
            current = nxt

        out = os.path.abspath(args.out)
        if args.sign:
            sign(current, out)
        else:
            shutil.copyfile(current, out)
        print("wrote", out, os.path.getsize(out), "bytes")
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
