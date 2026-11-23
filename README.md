# PMHBridge

Run PICO apps on Meta Quest 3 (Horizon OS) without modifying Horizon OS.
GPL-3.0-or-later · 周-zhou(WCNMSSB) and Deepseek

PMHBridge is an LSPosed module plus one Magisk module that together make PICO
apps behave as if they were running on a PICO device: device identity, the
OpenXR runtime they expect, PICO's proprietary service names, controller battery
reporting and PICO's own BLE peripheral service for the first-generation Motion
Trackers.

Target firmware: PICO OS 5.15.4 (P4U / sparrow). Target app: PICO Connect 10.6.6.
Helper package `com.pmh.bridge`, LSPosed module `com.pmh.bridge.lsp`, shared rule
file `/data/local/tmp/pmh_rules.json`.

## What works

| Capability | State |
| --- | --- |
| PICO Connect 10.6.6 launches and streams the 2D desktop | works |
| Quest controller battery shown in PICO Connect's PC client | works |
| PICO Swift (gen 1) trackers: two links, IMU, quaternion per tracker | works |
| Tracker poses injected into Horizon body tracking through an OpenXR layer | works |
| PICO Connect VR mode | blocked: needs the PC-side SteamVR host to stream frames |
| OpenXR layer / runtime facade | works, verified on device |

Details and evidence for every row live in `docs/`.

## Repository layout

| Path | Purpose |
| --- | --- |
| `module/` | LSPosed module (`com.pmh.bridge.lsp`): XR routing, identity spoofing, tracker and Connect options, service shims |
| `module-sys/pmhb/` | the single Magisk module: boot scripts, manifests, props, sepolicy, C sources |
| `helper-compose/` | the helper app (Compose + MIUIX, four tabs) |
| `helper/` | older View-based helper, kept for reference |
| `pipeline/` | firmware extraction and repacking |
| `tools/` | patch, pack, sign and install scripts |
| `tests/` | host-side tests (IMU fusion) |
| `docs/` | reverse-engineering notes, status and handover |
| `BLOBS.md` | which official binaries must be supplied locally to build the Magisk module |

`build/` and `dist/` live outside the project, under `<repo>/artifacts/pmhbridge/`.

## Requirements

* Quest 3 on Horizon OS, rooted with Magisk (Zygisk) and LSPosed.
* For the tracker path: a PICO Swift gen-1 tracker, and for the Connect path:
  PICO Connect 10.6.6 with its PC client on the same network.
* Build inputs that are not distributed here; see `BLOBS.md`.

## Build

```powershell
# Magisk module (source + local blobs -> dist/pmhb-magisk.zip)
python PMHB\PMHBridge\tools\pack_pmhb.py

# install it, retire the old split modules, fix script modes
powershell -File PMHB\PMHBridge\tools\install_pmhb.ps1 -Serial <adb-serial>

# LSPosed module APK
powershell -File PMHB\PMHBridge\module\build_module.ps1
powershell -File PMHB\PMHBridge\tools\install_module.ps1
```

The C sources for the OpenXR layer and the controller shim are in
`module-sys/pmhb/src/`; build commands are listed in `BLOBS.md`.

## Design notes

* Nothing is written into `/system`; the Magisk module uses overlay mounts and
  bind mounts only, so uninstalling restores the device.
* PICO service names are published by a root `app_process` (`PmhPxrSvc`). This
  is **off by default** because empty stub replies make PICO Connect's session
  handshake give up and the PC transport resets in a loop.
* PICO Connect's VR mode streams SteamVR; the headset watches
  `video_render_openxr` for frames and stops the stream after ~8 s when none
  arrive. Horizon OS has no PXR service stack, which is why
  `pxrhmdservice`/`pxrcontrollerservice` are registered by us instead.
* The LSPosed module never restarts `lspd`; scoped apps are restarted instead.

## License

GPL-3.0-or-later. See `LICENSE`. Official PICO/Meta binaries are **not** part of
this repository; see `BLOBS.md`.

---

## 中文说明

PMHBridge 让 PICO 应用在 Meta Quest 3（Horizon OS）上像在 PICO 设备上一样运行，
不修改 Horizon OS：

* **LSPosed 模块**（`com.pmh.bridge.lsp`）负责进程内的运行时路由、机型伪装、
  追踪器与互联选项、系统服务兜底。
* **单个 Magisk 模块**（`module-sys/pmhb/`）负责任宿 OpenXR 层与运行时门面、
  PICO 服务名、手柄电量镜像、PICO BLE 外设服务，以及 PICO 运行时的 priv-app。

已可用：互联 10.6.6 的 2D 桌面串流、PC 端手柄电量显示、双体感追踪器
（各自四元数）与 OpenXR 层的腿部骨骼注入。

未打通：互联的 VR 模式——它串流的是 PC 端 SteamVR，PC 主机没有推帧时头显侧
`video_render_openxr` 约 8 秒后自动停流。

开源说明：本仓库只包含我们自己的源码与脚本（GPL-3.0）。PICO / Meta 的官方
二进制不在仓库内，构建前请按 `BLOBS.md` 在 `<repo>/blobs/pmhb/` 准备。
