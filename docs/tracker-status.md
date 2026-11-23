# 一代体感追踪器 — 当前状态与验证方法

> 详细逆向过程见 `tracker-gen1.md`。本文只讲「现在能做什么、怎么验证」。

## 一句话状态

Quest 3 上已经能：扫描到 PICO Swift 追踪器 → 完成 PICO 私有 GATT 握手 →
收到 IMU → 解算四元数 → 通过 OpenXR 层改写 body tracking 的腿部关节。
界面（体感追踪器 App）能显示两行真实设备名。

**2026-11-22 15:04 双追踪器实测通过**：两台同时连接、各自出四元数、
层里左右腿分别跟随（详见文末「双追踪器实测」）。

## 组件

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| LSPosed 模块 | `module/` → `dist/pmh-bridge-module.apk` | 注入 Swift：AIDL 桩、GATT、IMU 解算、列表数据 |
| OpenXR 层 | `module-sys/pmh_openxr_layer/` | 隐式 API 层，接管 body tracking |
| PICO 系统应用 | `module-sys/pmh_btperipheral/` | PICO 自己的 BluetoothPeripheralService（备用） |
| 工具 | `tools/install_module.ps1`、`tools/restore_all.ps1` | 安装与灾难恢复 |

## 构建

```powershell
powershell -File PMHB/PMHBridge/module/build_module.ps1
python build/ziplayer3.py
```

产物：

```
dist/pmh-bridge-module.apk     ~82 KB
dist/pmh-openxr-layer.zip      ~7 KB
dist/pmh-btperipheral.zip      ~936 KB
```

## 部署

```powershell
$env:ANDROID_SERIAL="2G0YC5ZG4L008S"
powershell -File PMHB/PMHBridge/tools/install_module.ps1   # 桥接模块
powershell -File PMHB/PMHBridge/tools/restore_all.ps1      # 层 + PICO 系统应用（会重启）
```

注意：`restore_all.ps1` 需要 root；root 由 Singularity 提供，开机后不一定自动生效。

## 验证清单

**1. 模块注入**

```bash
adb logcat -d | grep PmhPeripheral
# 期望: ble probe: scanning for 25s
```

**2. 追踪器连接（追踪器开机后）**

```bash
adb logcat -d | grep -E "adv mac|swift: write 5cba|imu #"
# 期望:
#   adv mac=B0:A3:F2:10:47:60 name=Pico Swift ...
#   swift: write 5cba aa0100 ... aa020100
#   imu #250 gyro=.. accel=.. q=.. rpy=..
```

**3. 姿态通道**

```bash
adb shell su -c "ls -l /data/local/tmp/pmh_pose.bin"
# 有追踪器时 44 字节（2 个四元数），无则 28 字节（1 个）
```

**4. OpenXR 层**

```bash
adb logcat -s PMHXRLayer
# 期望:
#   negotiating layer XR_APILAYER_PMH_tracker_bridge
#   instance created through PMHBridge layer
#   xrLocateBodyJointsFB -> 0 active=1 joints=23
#   leg 0 upper=(..) lower=(..)
```

**5. 界面**

```bash
adb shell uiautomator dump /data/local/tmp/u.xml
# 期望出现:
#   配对体感追踪器
#   Motion Tracker DK H6061072
#   Motion Tracker DK H6061126
```

## 已知限制

- **PICO 原生栈**（`PicoNative`）默认关闭：`libalog.so` / `libbtperipheral_jni.so`
  的 `JNI_OnLoad` 绑定在 `com.pvr.btperipheral.PeripheralService` 上，
  加载到其他进程返回 `JNI_ERR` 并可能 abort。
- **追踪丢失对话框**会挡住应用启动；不要用 `pm disable com.oculus.guardian`
  （会破坏系统 UX，只剩透视+手势菜单）。
- **`public.libraries.txt`** 里非系统库必须带 `nopreload`，否则 zygote 预加载失败
  → system_server 崩溃循环。
- 真实 body tracking 改写需要「调用 `xrLocateBodyJointsFB` 的应用」+
  「追踪器在推数据」同时在位才能完整验证。

## 双追踪器实测（2026-11-22 15:04）

两台追踪器同时连接、同时出数据：

```
PmhTrackerLink: imu B0:A3:F2:10:47:60 #1800 rpy=(-74.4,-44.1,-165.9)
PmhTrackerLink: imu B0:A3:F2:10:47:97 #1950 rpy=(-42.8,-64.9,48.6)
/data/local/tmp/pmh_pose.bin   44 字节   （两个四元数）
```

层读取后左右腿各用各的姿态（两次采样结果不同，说明是实时数据）：

```
PMHXRLayer: leg 0 upper=(0.090,0.950,0.000) lower=(0.047,0.568,0.112)
PMHXRLayer: leg 1 upper=(-0.090,0.950,0.000) lower=(-0.102,0.551,0.019)
```

关键实现点：

- `TrackerLink`：每个追踪器一个 GATT 链路 + 一个 `ImuFusion`
- CCCD 判断用 `contains("2902")`（完整 UUID 不以 2902 结尾）
- 重连用 `connectGatt(context, true, ...)`（autoConnect）
- 扫描接受 `bond=12` 的设备（无名字广播也能识别）
- `PoseStore` 按 MAC 排序分配 slot 0/1 → 左/右腿
