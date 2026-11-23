# pmhb — the single PMHBridge Magisk module

This tree contains **only source**: boot scripts, manifests, properties,
sepolicy, C sources and documentation. The official/repacked payloads that the
module installs (PICO runtime APK and libraries, the Meta loader, compiled
shims, prebuilt dex) are *not* in the repository — see
[`../../../BLOBS.md`](../../../BLOBS.md). They live in `<repo>/blobs/pmhb/` and
are merged in only while packaging.

The previously separate modules (`pmh_openxr_layer`, `pmh_btperipheral`,
`pmh_xrstack`, `pmh_inputd`, `pico_xr_compat`, `pico_xr_loader`, `iobt-fix`)
are consolidated here; their official payloads are archived in
`<repo>/blobs/pmhb/module-sys/_archive/`.

## Layout (source)

```
module.prop                       id=pmhb, name=PMHBridge
sepolicy.rule                     shared /data/local/tmp pose file access
system.prop                       PICO OS identity + IOBT enable props
README.md                         this file
src/
  pmh_layer.c                     OpenXR API layer (tracker -> body joints)
  connect_ctrl_shim.c             fake PICO controllers for Connect battery
system/etc/
  openxr/1/api_layers/implicit.d/pmh_tracker.json   implicit layer manifest
  openxr/pico/active_runtime.aarch64.json           PICO runtime manifest
  pmh_pico_runtime.json           facade runtime manifest
  public.libraries.txt            whitelist for every PMH/PICO library
  permissions/privapp-permissions-pico-bt.xml
service.d/
  20_pico_xr_loader.sh            bind-mount loader shim over PICO apps
  30_pico_xr_compat_probe.sh      read-only probe output
  pmh_battery.sh                  mirrors Quest controller battery to JSON
  pmh_pxr_services.sh             PXR service names (OFF by default)
post-fs-data.d/
  30_pico_xr_compat_inventory.sh  boot inventory dump
runtime/                          active_runtime manifests (arm64 / armv7)
tools/pico-xr-compat.sh           helper for manual probing
action.sh / uninstall.sh          Magisk hooks
```

Everything under `system/lib64/`, `system/lib/`, `system/priv-app/`, `files/`,
`bin/` and `system/etc/pmh_pxr_services.dex` is supplied by the blobs directory
at packaging time.

## Build and install

```powershell
python tools\pack_pmhb.py                     # source + blobs -> artifacts\pmhbridge\dist\pmhb-magisk.zip
powershell -File tools\install_pmhb.ps1       # push, install, chmod, disable old modules
```

`install_pmhb.ps1` verifies that the module tree extracted completely (Magisk
occasionally writes only `module.prop`), falls back to a manual unzip, fixes the
executable bits of every boot script, and writes `disable` markers into the old
split modules instead of deleting them.

## Opt-in switches

| File | Effect |
| --- | --- |
| `/data/adb/pmh_pxr_services.enable` | publish `pxrcontrollerservice` etc. as stubs |

The PXR stubs are **off by default**: they answer with success but carry no
data, and PICO Connect's session handshake gives up on those empty replies (the
PC transport then resets every few seconds).

## Regenerating the merge

`tools/merge_modules.py` rebuilds this tree from modules pulled off the device
into `build/merged-src/pmh_merge/` (outside the repository). It merges
`system/` trees, `public.libraries.txt`, `system.prop` and `sepolicy.rule`,
rewrites boot scripts so `MODDIR` points at `/data/adb/modules/pmhb`, and writes
everything with LF endings — CRLF in `service.d` silently breaks `case ... in`
on the device.
