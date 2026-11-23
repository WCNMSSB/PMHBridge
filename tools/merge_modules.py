"""Merge every PMH/PICO Magisk module into one `pmhb` module tree.

Sources (pulled from the device into build/merged-src/pmh_merge):
  pmh_openxr_layer  - OpenXR tracker layer, PICO->Meta facade, controller shim
  pmh_btperipheral  - PICO BluetoothPeripheralService as a priv-app
  pmh_xrstack       - ported PICO OpenXR runtime as a priv-app
  pmh_inputd        - native helper that registers PXR binder service names
  pico_xr_compat    - inject lane, Meta runtime backend, PICO identity props
  pico_xr_loader    - bind-mounts the loader shim over installed PICO apps
  iobt-fix          - re-enables Inside-Out Body Tracking

The result keeps one module.prop (id=pmhb), one union of system/ trees, merged
public.libraries.txt / system.prop / sepolicy.rule, and one service entry per
original script with MODDIR pointed at the merged module.
"""
import os
import shutil

SRC = r"K:\PICOXRRUNTIME2HORIZONOS\build\merged-src\pmh_merge"
DST = r"K:\PICOXRRUNTIME2HORIZONOS\PMHB\PMHBridge\module-sys\pmhb"
MODULE_ID = "pmhb"
MODULE_PATH = "/data/adb/modules/" + MODULE_ID

SOURCES = [
    "pmh_openxr_layer",
    "pmh_btperipheral",
    "pmh_xrstack",
    "pmh_inputd",
    "pico_xr_compat",
    "pico_xr_loader",
    "iobt-fix",
]

# direct copies that are not part of system/
EXTRA_DIRS = {
    "pico_xr_compat": ["runtime", "bin", "tools"],
    "pico_xr_loader": ["files"],
    "pmh_openxr_layer": ["service.d"],
}


def copy_tree(src, dst):
    for root, _dirs, files in os.walk(src):
        for name in files:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, src)
            target = os.path.join(dst, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy2(full, target)


def merge_library_list(paths):
    lines = []
    seen = set()
    for p in paths:
        if not os.path.isfile(p):
            continue
        with open(p, encoding="utf-8", errors="replace") as f:
            for line in f:
                entry = line.strip()
                if not entry or entry.startswith("#"):
                    continue
                key = entry.split()[0]
                if key in seen:
                    continue
                seen.add(key)
                lines.append(entry)
    return lines


def merge_kv_props(paths):
    merged = {}
    order = []
    for p in paths:
        if not os.path.isfile(p):
            continue
        with open(p, encoding="utf-8", errors="replace") as f:
            for line in f:
                text = line.rstrip("\n")
                if not text.strip() or text.lstrip().startswith("#"):
                    continue
                key = text.split("=", 1)[0].strip()
                if key not in merged:
                    order.append(key)
                merged[key] = text
    return [merged[k] for k in order]


def main():
    if os.path.isdir(DST):
        shutil.rmtree(DST)
    os.makedirs(DST)

    lib_lists = []
    prop_files = []
    sep_rules = []

    for name in SOURCES:
        src = os.path.join(SRC, name)
        if not os.path.isdir(src):
            print("missing source:", name)
            continue
        sys_src = os.path.join(src, "system")
        if os.path.isdir(sys_src):
            copy_tree(sys_src, os.path.join(DST, "system"))
        for extra in EXTRA_DIRS.get(name, []):
            if os.path.isdir(os.path.join(src, extra)):
                copy_tree(os.path.join(src, extra), os.path.join(DST, extra))
        # module root files that are not scripts
        for fname in ("action.sh", "uninstall.sh", "customize.sh"):
            p = os.path.join(src, fname)
            if os.path.isfile(p):
                shutil.copy2(p, os.path.join(DST, fname))
        lib_lists.append(os.path.join(sys_src, "etc", "public.libraries.txt"))
        prop_files.append(os.path.join(src, "system.prop"))
        sp = os.path.join(src, "sepolicy.rule")
        if os.path.isfile(sp):
            with open(sp, encoding="utf-8", errors="replace") as f:
                sep_rules.append("# ---- %s ----\n%s" % (name, f.read().strip()))

    # merged library list / props / sepolicy
    libs = merge_library_list(lib_lists)
    etc = os.path.join(DST, "system", "etc")
    os.makedirs(etc, exist_ok=True)
    with open(os.path.join(etc, "public.libraries.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(libs) + "\n")

    props = merge_kv_props(prop_files)
    if props:
        with open(os.path.join(DST, "system.prop"), "w", encoding="utf-8") as f:
            f.write("\n".join(props) + "\n")

    if sep_rules:
        with open(os.path.join(DST, "sepolicy.rule"), "w", encoding="utf-8") as f:
            f.write("\n\n".join(sep_rules) + "\n")

    # boot scripts: keep each original script, but point MODDIR at pmhb
    svc_dir = os.path.join(DST, "service.d")
    pfd_dir = os.path.join(DST, "post-fs-data.d")
    os.makedirs(svc_dir, exist_ok=True)

    def install_script(srcfile, target_dir, new_name):
        with open(srcfile, encoding="utf-8", errors="replace") as f:
            body = f.read()
        body = body.replace("MODDIR=${0%/*}", "MODDIR=" + MODULE_PATH)
        os.makedirs(target_dir, exist_ok=True)
        out = os.path.join(target_dir, new_name)
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            f.write(body.replace("\r\n", "\n"))
        os.chmod(out, 0o755)

    for name, script, target, new_name in [
        ("pico_xr_loader", "service.sh", svc_dir, "20_pico_xr_loader.sh"),
        ("pico_xr_compat", "service.sh", svc_dir, "30_pico_xr_compat_probe.sh"),
        ("pico_xr_compat", "post-fs-data.sh", pfd_dir, "30_pico_xr_compat_inventory.sh"),
        ("pmh_openxr_layer", "service.sh", None, None),
    ]:
        p = os.path.join(SRC, name, script)
        if target and os.path.isfile(p):
            install_script(p, target, new_name)

    # module.prop
    with open(os.path.join(DST, "module.prop"), "w", encoding="utf-8") as f:
        f.write(
            "id=%s\n"
            "name=PMHBridge\n"
            "version=v1.0\n"
            "versionCode=1\n"
            "author=周-zhou(WCNMSSB) and Deepseek\n"
            "description=Single PMHBridge Magisk module: OpenXR tracker layer, PICO runtime facade, "
            "controller battery shim, PXR service names, PICO XR runtime, BLE peripheral service, "
            "loader overlay and PICO identity props.\n" % MODULE_ID
        )

    total = sum(len(files) for _r, _d, files in os.walk(DST))
    print("merged", total, "files into", DST)


if __name__ == "__main__":
    main()
