# 交接说明（2026-11-22 收尾）

设备：Quest 3 `2G0YC5ZG4L008S`（USB）/ `192.168.0.106:5555`（WiFi）

## 最新进展（14:35）：PICO Connect 10.6.6 已跑通

```
connect pid: 14928   （主进程）
connect pid: 14985   （:deviceservice）
PmhConnect: Build.getSerial called, spoofing
PmhConnect: wake lock stubbed
PmhConnect: wifi lock stubbed
PmhConnect: startForeground bypassed for StreamingDeviceService
PmhConnect: PSS stub installed (skip=rms_jni)
```

界面已渲染出「互联 / 电脑连接 / 跨端使用电脑桌面 1/3」引导页与「可连接设备」面板。

**关键结论：之前 10.6.6 的 `getSerial` 崩溃并不是权限问题，而是 LSPosed 没注入**
（scope 被卸载清掉 + 新装包需重启）。补回 scope 并重启后 hook 生效，一次通过。

同时「体感追踪器」面板也正常显示两行 `Motion Tracker DK H6061xxx`。

## 现在的阻塞

追踪丢失的 guardian 对话框常驻前台：

```
topResumedActivity = com.oculus.guardian/com.oculus.vrguardianservice.guardiandialog.GuardianDialogActivity
SystemUXController: Launch is blocked because: a Reprojected OS dialog is currently showing.
```

所有应用启动都会被缓存而不执行。**必须戴头显让房间追踪恢复**（或点「继续，不追踪」），
之后下面的验证命令才有意义。

## 一键验证

```powershell
$adb = "C:\Users\77456\Desktop\platform-tools-r36.0.2-windows\adb.exe"
$d   = "2G0YC5ZG4L008S"

# 1) 启动 PICO Connect 10.6.6 并看 hook
& $adb -s $d shell "am start -n com.picovr.picostreamassistant/com.picovr.picostreamassistant.ui.MainActivity"
& $adb -s $d logcat -d | Select-String "PmhConnect"

# 2) 启动体感追踪器并看扫描
& $adb -s $d shell "am start -n com.pvr.swift/com.pvr.swift.MainActivity"
& $adb -s $d logcat -d | Select-String "PmhPeripheral: ble probe|adv mac|swift:|imu #"

# 3) 层是否加载
& $adb -s $d logcat -s PMHXRLayer:V
```

## 已知的坑

| 坑 | 处理 |
| --- | --- |
| 卸载应用会清掉 LSPosed scope | 重新 `insert into scope(...)` 后**重启** |
| 新装/换版应用不注入 | 必须重启一次，LSPosed 只认开机时的包列表 |
| `public.libraries.txt` 缺 `nopreload` | 会让 zygote 预加载失败 → system_server 崩溃循环 → 黑屏 |
| `pm disable com.oculus.guardian` | 会破坏系统 UX（只剩透视+手势菜单），不要用 |
| C 盘空间 | 低于 1 GB 时 adb/aapt 会 ENOSPC，先清 `pip cache` 与 `.gradle\caches` |
| root 掉线 | 重启直到 `su -c id` 返回 uid=0（Singularity 自动 root 不稳定） |

## 各组件现状

| 组件 | 状态 | 证据 |
| --- | --- | --- |
| 桥接模块 `pmh-bridge-module.apk` | 已装（82802 B） | Swift 里 `PmhPeripheral` / `PmhTrackerList` 日志 |
| OpenXR 层 | 已挂载（11136 B） | `PMHXRLayer: instance created through PMHBridge layer` |
| PICO 系统应用 | 已装 | `pm path com.pvr.btperipheral` → `/system/priv-app/...` |
| PICO Connect 10.6.6 | 已装，未能启动 | guardian 对话框阻塞 |
| 体感追踪器 | 可运行 | 界面显示两行 `Motion Tracker DK H6061xxx` |

## 追踪器链路验证进度

```
① 直连已绑定 MAC          ✅ 日志: gatt: direct connect attempt ... bond=12
② BLE 扫描                ✅ 日志: ble probe: scanning for 25s
③ GATT 握手 + 开 IMU      ✅ 早前实测: swift: write 5cba aa020100
④ IMU → 四元数            ✅ 早前实测: imu #2550 ... q=.. rpy=..
⑤ 姿态文件                ✅ 28/44 字节，属主正确
⑥ 层改写关节              ✅ 合成姿态实测: leg 0 / leg 1 位置正确
⑦ 真实追踪器 + 真实应用   ⏳ 待追踪器开机 + 能启动应用
```

## 本次会话最后新增（Round 39）

给 shell 加了「启动拦截绕过」：hook `com.oculus.systemux.SystemUXController` 里
所有无参且返回 boolean 的方法，强制返回 false。这样即使 guardian 对话框存在，
应用启动不再被缓存拦截。

对应日志（尚未验证）：

```
PmhBridge: launch guard bypassed via com.oculus.systemux.SystemUXController
```

如果这个类名猜错，日志会显示 `launch guard class not found`，此时需要从
`/system/priv-app/VrShell/VrShell.apk` 里确认真实类名。

## 最终产物

```
pmh-bridge-module.apk   80.9 KB   SHA256 8FD86ADE54B85A77…
pmh-openxr-layer.zip     7.4 KB   SHA256 4C58195B301EF863…
pmh-btperipheral.zip   936   KB   PICO 自家服务（备用）
pmh-picoconnect.zip     12.9 MB   特权 Connect（会让 system_server 崩，勿用）
```

## 下次继续时建议顺序


1. 戴头显 → 对话框消失
2. 追踪器开机（短按，蓝灯闪）
3. 跑上面「一键验证」的三段命令
4. 若 Connect 10.6.6 仍崩在 `getSerial`：做 DEX 补丁替换 `DeviceUtil.c()`
   （需要 baksmali/smali，本次会话未安装）

## Quest 手柄电量（2026-11-22 找到）

Horizon OS 不提供 Java/OpenXR 接口，但系统会把每个手柄的电量写进文件：

```
/data/vendor/misc/sensors/batteryinfo/<controller-id>   ->  "90"
```

实测：

```
9e47f23a48644982 = 90
bb022470d41de00b = 80
```

目录权限 `system:system 600`，普通应用读不到，所以用 Magisk `service.d` 轮询并镜像成
世界可读文件：

```
/data/local/tmp/pmh_battery.json   {"9e47f23a48644982": 90, "bb022470d41de00b": 80}
```

脚本：`module-sys/pmh_openxr_layer/service.d/pmh_battery.sh`（每 5 秒同步一次）。

排查过程中确认的无效路径：`dumpsys vrpowermanager`、`IControllerTrackingService`、
Bluetooth（手柄不是 BT 设备）、运行时 APK 里的 OpenXR 扩展（无 battery 相关）。

## Quest 手柄电量 → 互联（2026-11-22 打通）

目标：让 **PC 端互联界面**显示 Quest 手柄电量。实测通过（PC 端显示 90% / 70%）。

数据链：

```
SyncBossHAL ──> /data/vendor/misc/sensors/batteryinfo/<id> ──(Magisk service.d)──>
/data/local/tmp/pmh_battery.json ──(libpmh_ctrl_shim.so)──>
GetControllerData() ──> SendBatteryInfo() ──> DeviceBatteryVolumesEvent ──> PC
```

两处修改：

1. **service.d/pmh_battery.sh**：每 5 秒把系统电量目录镜像成世界可读 JSON。
2. **libpmh_ctrl_shim.so**（打进 Connect APK 的 lib/arm64-v8a）：构造函数里定位
   `libstreaming_server.so`，把 `PxrControllerModuleImpl::GetControllerData`
   （偏移 0x540DFC）改成跳板，返回两条伪造的 `ControllerData`：

   | 偏移 | 含义 | 值 |
   | --- | --- | --- |
   | +0x10 | side_type | 0=左 1=右 |
   | +0x14 | active | 1 |
   | +0x30 | 电量% | 来自 JSON |

   元素步长 0x38（从 `SendBatteryInfo` 反汇编确认）。

3. **DEX 补丁**：`w2/k.d()` 会去找 PICO 私有 `poweradvisor` 服务，
   Quest 上缺 `android.os.IPowerAdvisor` 类 → `NoClassDefFoundError`。
   用 baksmali 反编译后把该方法改成 `return-void`，再 smali 回编。

工具链（本次补齐）：`tools/smali/` 下的 baksmali 2.5.2 + dexlib2 + util + guava +
smali-fat。规则：`tools/` 仅存放第三方工具，不进 GPL 源码。

## PICO 互联 VR 模式：结论（2026-11-22）

**不可行**，原因不是权限也不是登录，而是原生渲染管线缺 PICO 的 VR 服务。

证据（15 秒 maps 采样 + 完整 logcat）：

| 观察 | 说明 |
| --- | --- |
| `StreamingGameActivity` 启动后 8 秒自动 finish | `GameActivity` + 原生 `android_main` 返回 |
| 期间只加载 `libpico_openxr_loader.so`（我们的 shim） | 真 runtime `libopenxr_loader_real.so` 从未加载 |
| 没有 `xrCreateInstance` 相关日志 | VR 会话从未发起 |
| 无 tombstone | 不是崩溃，是主动退出 |
| PC 端每 5.5 秒 `Connection reset by peer` | 会话协商不成功 |
| 去掉控制器 shim 后现象不变 | 与电量映射无关 |
| Java 层 `H1`/`I1`/`DeviceUtil` 无调用 | 退出决策在 native |

结论：`main.cpp` 在等 PICO 的 VR 显示服务（PICO `vrservice` / 专有合成管线），
Horizon OS 没有。要支持就得重写 PICO 的 VR 运行时，超出移植范畴。

**可用的替代**：互联的 **2D 桌面模式**实测正常（能看到电脑画面），日常使用够用。

登录提示「登录过期 / 未登录 Wi-Fi 无法发现电脑」不影响 2D 串流。
强制 `AccountLoginInfo.isLoginFlag()` 返回 true 的试验无效，已回退。

## VR 模式（PICO 互联）现状 — 2026-11-22

### 已打通
- Quest 上注册 PICO PXR 服务名（root app_process，带接口描述符）：
  `pxrhmdservice` / `pxrcontrollerservice` / `pxr_net` / `pxreyetrackingservice` /
  `pxr_notification` / `pxrfanservice`
- 服务开机自启：`module-sys/pmh_openxr_layer/service.d/pmh_pxr_services.sh`
- 客户端事务已能到达（日志 `PmhPxrSvc ... transact code=17/19/3`），stub 返回成功
- PICO→Meta OpenXR 门面（`libpmh_pico_bridge.so`）广告 PICO 扩展

### 未打通
VR 活动每次 8 秒退出。日志链路：

```
PssAPi.startSteamVR -> 0
PSS: start steamvr render
PSS: WaitForGameActivity ... game activity launched
PSS: video_render_openxr ... （无任何帧）
8 秒后 PSS: handleStopStream -> Java onStopStream -> removeActivity
```

结论：**头显侧已就绪，缺的是 PC 端 SteamVR 的视频流**。
PC 曾上报 `steamvr true / PrepareStreaming`，但之后无帧，说明 PC 侧 SteamVR 未真正出图
（虚拟 HMD 未就绪或 SteamVR 未启动完成）。

### 下一步（需要 PC 侧）
1. 启动 SteamVR 至 Ready，确认它识别到 PICO 互联的虚拟 HMD
2. 再进 VR 模式，观察 `video_render_openxr` 是否开始出帧

### 已确认可用
2D 桌面串流、PC 端手柄电量（90%/70%）、双体感追踪器 + 腿部骨骼。
