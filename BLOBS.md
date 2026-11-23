# External blobs (not part of this repository)

This project deliberately keeps official and repackaged binaries out of the
GPL-3.0 source tree. `tools/pack_pmhb.py` merges them back in when building the
Magisk module, from a directory outside the project:

```
<repo>/blobs/pmhb/
```

`tools/pack_pmhb.py --blobs <dir>` overrides the location. Packaging fails with
a list of missing entries unless `--allow-missing-blobs` is passed.

## What must be there

| Path | Origin | Why it cannot be shipped |
| --- | --- | --- |
| `system/priv-app/PicoXRRuntime/**` | PICO's XRRuntime.apk, repacked manifest | PICO proprietary |
| `system/priv-app/BluetoothPeripheralService/**` | PICO system app | PICO proprietary |
| `system/priv-app/PvrConfiguration/**` | PICO system app | PICO proprietary |
| `system/lib64/libpxr*.so`, `lib*pxr*.so` | PICO runtime libraries | PICO proprietary |
| `system/lib64/libmeta_xrloader.so` | Horizon OS runtime backend | Meta proprietary |
| `system/lib64/libpmh_openxr_layer.so` | built from `src/pmh_layer.c` | build artifact |
| `system/lib64/libpmh_pico_bridge.so` | built from the compat facade source | build artifact |
| `system/lib64/libpmh_ctrl_shim.so` | built from `src/connect_ctrl_shim.c` | build artifact |
| `files/libpico_openxr_loader.so` | loader shim over Meta's loader | build artifact |
| `files/libopenxr_loader_real.so` | Horizon OS Khronos loader | Meta proprietary |
| `files/libc++.so` | Horizon OS `libc++` | platform library |
| `system/etc/pmh_pxr_services.dex` | built from the PXR service-name helper | build artifact |
| `bin/pico_xr_probe32/64` | device probes | build artifact |

## Rebuilding the build artifacts

* `libpmh_openxr_layer.so` — `src/pmh_layer.c`, NDK clang,
  `--target=aarch64-linux-android29 -shared -fPIC -O2 -llog`.
* `libpmh_ctrl_shim.so` — `src/connect_ctrl_shim.c`, same flags.
* `pmh_pxr_services.dex` — javac + d8 (`PublishProbe`), min-api 29.
* `files/libpico_openxr_loader.so` — `picoapponmhorizon/tools/gen_xr_shim.py`.

Everything else is extracted from the devices/firmware the project targets and
is intentionally kept out of version control.
