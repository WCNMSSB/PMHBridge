# Module consolidation and release prep

## What was consolidated

Seven Magisk modules became one (`module-sys/pmhb`, id `pmhb`, name `PMHBridge`):

| Old module | Content now inside pmhb |
| --- | --- |
| `pmh_openxr_layer` | OpenXR tracker layer, PICO→Meta facade, controller shim, implicit layer manifest, battery mirror, PXR service stubs, sepolicy |
| `pmh_btperipheral` | PICO `BluetoothPeripheralService` priv-app |
| `pmh_xrstack` | ported PICO OpenXR runtime priv-app and its libraries |
| `pmh_inputd` | native helper that registers PXR binder service names |
| `pico_xr_compat` | inject lane, `libmeta_xrloader.so` backend, PICO identity props, probes |
| `pico_xr_loader` | bind-mount loader shim over installed PICO apps |
| `iobt-fix` | Inside-Out Body Tracking property |

`tools/merge_modules.py` performs the merge from `build/merged-src/pmh_merge/`
(modules pulled off the device). The old module directories are kept under
`<repo>/blobs/pmhb/module-sys/_archive/` and are disabled with a `disable`
marker rather than deleted, so rollback is `rm /data/adb/modules/<name>/disable`.

## Source / blobs split

`PMHB/` contains only code we wrote (GPL-3.0). Official and repacked binaries
(PICO runtime APK, PICO libs, Meta loader, compiled shims, prebuilt dex and
probes) live in `<repo>/blobs/pmhb/` and are merged at packaging time by
`tools/pack_pmhb.py`, which fails with a list of missing entries unless
`--allow-missing-blobs` is given. See `BLOBS.md`.

## Bugs found while consolidating

1. **CRLF in boot scripts.** The merge step wrote scripts through Python text
   mode on Windows, producing CRLF. `case ... in` then failed on device with
   `syntax error: unexpected 'in'`. `pack_pmhb.py` normalises to LF and
   `merge_modules.py` now writes with `newline="\n"`.
2. **Lost executable bits.** Windows cannot set +x, so Magisk skipped every
   `service.d` script. The packer writes POSIX modes through `ZipInfo`, and
   `install_pmhb.ps1` chmods after extraction.
3. **Partial extraction.** `magisk --install-module` occasionally leaves only
   `module.prop`. The installer verifies `system/` exists and falls back to a
   manual `unzip`.
4. **`layers_of()` double zero.** `grep -c` prints `0` and exits non-zero, so
   `|| echo 0` appended a second line and the `[ ... ]` test received two
   arguments (`0: unexpected '0'`). Fixed to `c=$(grep -c ...); echo ${c:-0}`.
5. **Creating files on the read-only system mount.** The loader script tried to
   create `libopenxr_loader_real.so` under `/system/priv-app/...`; it now skips
   absent files there instead of failing.
6. **Blocking service.d.** `pmh_battery.sh` looped forever in the foreground and
   would block later scripts; it now re-execs detached under a pidfile so
   repeated boots do not stack duplicate mirrors.

After these fixes, on device: `apply.log` reports `done, 3 package(s)`, the
loader shim is bind-mounted into `com.picovr.picostreamassistant` and
`com.pico.browser`, one battery daemon runs, and the layer, facade and priv-apps
are mounted from the single module.

## Release checklist

- [x] `LICENSE` (GPL-3.0) at project root
- [x] `README.md` (English + 中文), `BLOBS.md`
- [x] `.gitignore` excludes `build/`, `dist/`, Gradle outputs, probes and blobs
- [x] `tools/pack_pmhb.py` / `tools/install_pmhb.ps1` / `tools/merge_modules.py`
- [x] No official binaries inside `PMHB/`
- [ ] `git init` + initial commit (git is not installed on this build host)
- [ ] Tag `v1.0` after a clean build from blobs

## Known limitation

PICO Connect's VR mode still needs the PC-side SteamVR host to send frames; the
headset stops the stream ~8 s after `video_render_openxr` starts with no frame.
Steam Link VR on the same headset connects to the PC but receives
`Error 450 Host not responding`, i.e. the blocker is the PC, not Horizon OS.

## Build outputs live outside the project

Everything generated -- `build/`, `dist/`, Gradle caches, the module probe
assets and the LZ4 test rigs -- was moved to `<repo>/artifacts/pmhbridge/`
(mirroring the old relative paths). `tools/pack_pmhb.py`,
`module/build_module.ps1`, `tools/install_module.ps1`,
`tools/install_pmhb.ps1`, `tools/restore_all.ps1` and
`tools/bundle_runtime_deps.ps1` were repointed accordingly, so the source tree
holds no binaries or build products at all.

Current split:

| Tree | Size | Content |
| --- | --- | --- |
| `PMHB/PMHBridge/` | 106 files, 0.6 MB | our source only |
| `<repo>/blobs/pmhb/` | 233 files | official / repacked binaries |
| `<repo>/artifacts/pmhbridge/` | 1160 files, ~8.7 GB | build outputs and test rigs |
