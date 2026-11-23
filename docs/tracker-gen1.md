# PICO 体感追踪器（一代）移植研究记录

本文记录一代追踪器（`Pico Swift H6061xxx`）在 Meta Quest 3 / Horizon OS 上的移植过程、
已经确认的协议事实、以及当前卡点。所有结论都带可复现的证据路径。

## 1. 硬件与链路

| 环节 | 芯片 | 链路 | 备注 |
| --- | --- | --- | --- |
| 追踪器 ↔ 头显 | FR8016HD（Freqchip BLE SoC） | BLE 5.0，自定义 GATT | 广播 `Pico Swift H6061xxx`，UUID `0xFEFF` |
| 头显 ↔ 手柄 | nRF52833（基站 MCU） | 私有 2.4G | 与追踪器无关，见 `PhoenixSta` 固件 |
| 头显 AP ↔ 基站 | SPI | 厂商私有 | `libstationopticsservice.so` 里的 `tracker_fd/station_fd` |

关键区分：**追踪器走 BLE，手柄走私有射频**。早期把两者混为一谈是错的。

## 2. 追踪器 GATT 表（实测）

```
service 00001800-0000-1000-8000-00805f9b34fb
  char 00002a00 (name, read)          -> "Pico Swift H6061072"
  char 00002a01 (appearance, read)
  char 00002a04 (conn params, read)
  char 00002ac9 (central addr resolution, read)
service 00001801-0000-1000-8000-00805f9b34fb
  char 00002a05 (service changed, indicate + CCCD)
service 0000fff0-0000-1000-8000-00805f9b34fb      <- 主数据服务
  char 0783b03e-8535-b5a0-7140-a304d2495cb8  props=0x1C (write / write-no-resp / notify)
       desc 00002902 (CCCD), desc 00002901 (user description = "Characteristic 1")
  char 0783b03e-8535-b5a0-7140-a304d2495cba  props=0x0E (write / write-no-resp)
service 02f00000-0000-0000-0000-00000000fe00      <- 命令/控制服务
  char ...ff00  props=0x02 (read)
  char ...ff01  props=0x0C (write / write-no-resp)
  char ...ff02  props=0x12 (read + notify, CCCD)
  char ...ff03  props=0x02 (read)
```

## 3. 已确认的通信事实

1. 写入 `ff01` 任何 ASCII 命令，`ff02` 回 `02 <首字母> 00 00`，周期约 0.7 s。
   - 这是 ACK/回声，不含语义。
2. 写入 `5cb8` / `5cba`（ASCII、二进制、TSYS 头、基站风格帧）均无数据回推。
3. 通知已在 `5cb8`、`5cba`、`ff02` 上全部打开（CCCD 写入 `status=0`）。
4. 追踪器会主动连 Quest 的 GATT server（Meta 侧），说明 BLE 控制器链路本身可用。

### 尝试过的帧格式（全部失败）

基站固件里的帧构造（`0x8bf0`）：

```
[len+7][cmd][flags|0x80][u32 value][payload]
命令 0x05 = 控制（radio_interval / imu_mode / irled）
命令 0x00 = 数据
```

按此格式写入若干帧（`07 05 80 00 00 00 00` 等），追踪器仍只回 ACK。

## 4. 基站固件（`PhoenixSta`）

来源：`5.9.9-202408300028-RELEASE-user-phoenix-b8636-4aab325299/station_bin/`

```
PhoenixSta_sv2.84_PR_20240516_b284_app.bin   118,740 B，明文，向量表在偏移 0
```

证据：

- `svc` 指令仅 1 条，且无 SoftDevice 符号 → 不含 BLE 协议栈
- 完整私有射频 API：`radio_payload_add` / `radio_recv_buf_parse` /
  `radio_send_buf_get` / `radio_send_buf_push` / `radio_send_buf_check_ack`
- 追踪器管理：`fstorage_pair_handler`、`bond_info_flash_read/write`、
  `set imu workmode success`、`receive GET_IMU_STATIC_STATUS cmd`

结论：`PhoenixSta` 管的是**手柄**等私有射频设备；追踪器的 IMU 不走这里。

本地产物：

```
build/station_fw.elf          ELF 包装，可直接 objdump
build/station_fw.asm.txt      47,624 行反汇编
build/p4u-bin/               其它从固件提取的二进制
```

## 4.5 真实协议（Neo3 工程机 + HCI 抓包）

来源：`com.pvr.btperipheral`（`/system/app/BluetoothPeripheralService/BluetoothPeripheralService.apk`）
反编译 + Neo3 上开启 `persist.bluetooth.btsnooplogmode=full` 后实际配对抓包。

### GATT 端点

```
service  0000fff0-0000-1000-8000-00805f9b34fb
write    0783b03e-8535-b5a0-7140-a304d2495cba     ← 命令写入
notify   0783b03e-8535-b5a0-7140-a304d2495cb8     ← 应答 / IMU
```

### 请求（全部以 0xAA 开头）

| 字节 | 含义 |
| --- | --- |
| `AA 01 00` | GET_TRACKER_INFO |
| `AA 0A 00` | GET_BATTERY |
| `AA 03 00 <mac[6]> <role>` | BIND，role `01`=左 `02`=右 |
| `AA 0B <latency> 00` | SET_LATENCY |
| `AA 02 00 00` | DISABLE_IMU_NOTIFY |
| `AA 02 01 00` | **ENABLE_IMU_NOTIFY** |
| `AA 04 00 <params> <crc>` | SET_IMU_CALIBRATION |
| `AA 05 00` | GET_IMU_CALIBRATION |
| `AA 06 00` | POWER_OFF |

### 应答（0xBB 开头）

```
bb 01 <ascii name>                       GET_INFO 应答
bb 03 01 76                              BIND 应答
bb 0A 05 00 c6 13 01 01 00 00 00 00 c8   电量/状态
bb 0B <latency> <crc>                    SET_LATENCY 应答
bb 02 00 01 14                           DISABLE_IMU 应答
```

### IMU 数据帧（0xCC，27 字节）

实测 19,091 帧（约 100 Hz），结构：

```
[0]      0xCC
[1..3]   24 位递增计数（时序）
[4..5]   gyro X   int16 LE（范围约 ±7000）
[6..7]   gyro Y
[8..9]   gyro Z
[10..11] accel X  （静止时均值 ≈ +1973）
[12..13] accel Y
[14..15] accel Z
[16..17] 附加量（约 -850）
[18..23] 磁力计 X/Y/Z（原始值，±32000）
[24..25] 温度/状态（均值 ≈ 4478）
[26]     校验/序号
```

### 实际抓包的命令顺序

```
AA0100  → bb01 "Pico Swift H6061072..."
AA0A00  → bb0A 电量
AA0300 B0A3F2104760 01   → bb0301 76      BIND 左
AA0300 B0A3F2104797 02                    BIND 右
AA0B6300 → bb0B63 D9                      设置延迟 0x63
AA020000 → bb0200 01 14                   关闭 IMU 通知
AA0B0700                                   设置延迟 7
AA020100                                   **开启 IMU 通知**
AA0B0000                                   延迟归零
```

## 5. 追踪器固件

来源：模拟器镜像 `system/etc/trackerbin/`

```
Swift2.0_sv1.89_20241213_b189_app.bin   193,385 B
```

- 熵 7.99，无 zlib/lz4/gzip 头，找不到向量表 → 加密
- 明文串：`Pico-Tracker-2.0`、`pair fine !!!!!`、`set_imu_work_mode %02X`、`imu thread Resume`
- 同目录 `public.pem` 为签名公钥（RSA）

结论：协议实现加密，无法直接反编译。

## 6. Swift App 适配（已完成部分）

`com.pvr.swift` 2.0.5 重签名后可装在 Quest 3 并稳定运行：

```
dist/swift-quest.apk          重签名版
dist/swift-quest-compat.apk   额外去掉 PICO 专属权限
```

模块侧（`module/src/com/pmh/bridge/lsp/`）：

| 文件 | 作用 |
| --- | --- |
| `SwiftOptions.java` | 版本门、feature 开关、属性伪造 |
| `SwiftUiAuto.java` | 隐私门绕过、CoverActivity 自动跳过、直达扫描页 |
| `PeripheralServiceStub.java` | `peripheral_service` 桩、TSYS 帧解析、设备推送、GATT 探测 |
| `IdentityCompat.java` | 序列号/机型/属性伪造 |

关键实现细节：

- PICO 的 AIDL 代理在标准 interface token 前写了 12 字节头：
  `0xC2000004, -1, "TSYS"`，解析时必须跳过（按 4 字节步进找描述符最稳）
- 回复是**裸 Parcel**（`writeNoException` 开头），加 TSYS 头会让客户端抛
  `Unknown exception code`
- 回调用 `IPeripheralScanCallback.transact(1, ...)` 推送，parcel 必须带
  `writeInterfaceToken`，且需要传 reply Parcel（否则对端 NPE）
- `PeripheralDevice` 只是一个 MAC 字符串，其余属性全部靠后续 AIDL 调用查询

## 7. AIDL 接口全表（`com.pvr.btperipheral.api.a`）

| Code | 方法 | 返回 |
| --- | --- | --- |
| 4 | `getBondedDevices()` | `PeripheralDevice[]` |
| 5 | `k()` | 扫描到的设备 |
| 6 | `u(dev)` | `String` |
| 7 | `A(dev)` | `String`（连接） |
| 8 | `E(dev)` | `String`（断开） |
| 9/10 | `r`/`G` | `String` |
| 11 | `w(dev)` | `int`（电量） |
| 12 | `l(dev)` | `int`（连接状态，2=已连接） |
| 13 | `x(dev)` | `int` |
| 14 | `j(dev)` | `boolean` |
| 15 | `f(dev)` | `boolean` |
| 16 | `F(callback)` | 注册扫描回调 |
| 17 | `s(callback)` | 注销 |
| 18/19 | `v`/`d` | `boolean` |
| 21 | `o(dev, String)` | `boolean` |
| 23/25/29 | `t`/`p`/`q` | `int` |
| 30/31 | `b`/`B` | `boolean` |

扫描回调 `IPeripheralScanCallback`：

```
transact 1: writeInt(present) + writeString(mac)   -> onDeviceFound
transact 2: writeInt(state)                        -> 扫描状态
```

## 8. 当前状态

| 能力 | 状态 |
| --- | --- |
| Swift APK 安装/启动 | ✅ |
| 自动进入扫描页（无需点击） | ✅ |
| BLE 扫描到追踪器 | ✅ |
| 设备推送到 Swift 列表 | ✅（扫描页出现两条） |
| 已配对集合注入（不崩溃） | ✅（`c.c()` hook） |
| 数据层同步 | ✅（`Sync Tracker Live Data: [mac1, mac2]`） |
| 「我的追踪器」显示 | ❌ Fragment 未渲染 |
| 真实配对/IMU | ❌ 缺协议 |

已知崩溃及修复：

- `PeripheralDevice.hashCode()` NPE：AIDL 数组反序列化出 null 地址 → 改为在
  app 进程内构造 `PeripheralDevice(mac)` 并 hook `c.c()`
- `destroyTimeout`：`CoverActivity.finish()` 与新 Activity 启动竞争 → 不再主动 finish
- 扫描无结果：`targetSdk 29` 在 Android 14 上扫 BLE 需要定位权限 → `pm grant
  com.pvr.swift android.permission.ACCESS_FINE_LOCATION`

## 8.5 `ff01` 命令通道实测

单字节命令扫描 `0x00~0xFF` + 两字节组合探测的结果：

| 命令 | `ff02` 响应 | 说明 |
| --- | --- | --- |
| `0x00` | `00 00 01 00 11`（5 字节） | 有结构，非回声 |
| `0x01` | `00 01 04 00 00 00 00 00` | 4 字节 payload = 0 |
| `0x02` | `00 02 04 00 03 00 00 00` | 4 字节 payload = 3 |
| `0x03~0xFF` | 无（只有 `02 xx 00 00` 回声） | 未实现/需要前置条件 |
| 两字节 `0200/0201/1300/1301/...` | 无 | 不支持该组合形式 |

响应格式推测：

```
[0x00][cmd][len][payload...]
```

结论：

- `ff01` 是**管理/查询通道**，只有少量命令，且不推 IMU
- 长写（>=10 字节）会被拒（`ok=false`），说明它只有 1 字节命令语义
- IMU 数据应在 `0xFFF0` 服务（`5cb8`/`5cba`），但用命令通道无法触发

## 9. 协议验证（Windows 直连，2026-11-21）

用 `bleak` 从 PC 直接连追踪器，完整复现了配对流程：

```
connected B0:A3:F2:10:47:60
write aa0100   → bb01 "Pico Swift H6061072C2210PC2210MH6061 072GSW097..."
write aa0a00   → bb0a ...
write aa0300b0a3f210476001 → bb030176
write aa0b6300 → bb0b63d9
write aa020000 → bb02000114
write aa0b0700 → bb0b07dd
write aa020100 → bb020101d0        ← 开启 IMU
之后 946 帧 27 字节 0xCC 数据
```

结论：协议完全正确，命令和 IMU 帧都能在任意 BLE 主机上复现，不依赖 PICO 服务。

字段统计（19091 帧，静置状态）：

```
[4..9]   gyro X/Y/Z   均值 ≈ 0，范围 ±7000
[10..15] accel X/Y/Z  均值 ≈ 1973 / -1 / 454（约 1g 在 X 轴）
[18..23] 磁力计 X/Y/Z 原始值 ±32000
[24..25] 温度/状态    均值 ≈ 4478
```

## 10. 已知阻碍

- Quest 每次重启后 root（Singularity）丢失；恢复 root 后 LSPosed 的 Zygisk
  需要 zygote 在 root 之后重新加载，手动起 `lspd` 会崩溃
- 需要一条"root → zygote 重启 → lspd 自动启动"的固定流程，或改用持久化 root
  （设备上已装 `me.weishu.kernelsu`）

## 11. Quest 上跑通（2026-11-22）

环境（root 开机自启后）：

```
su -c id                     → uid=0(root) u:r:magisk:s0
ps -A | grep lspd            → lspd 运行中
PmhPeripheral / PmhSwift 日志 → 模块已注入 com.pvr.swift
```

实际日志（`/data/adb/lspd/log/modules_*.log`）：

```
PmhPeripheral: swift: write 5cba aa0100
PmhPeripheral: swift: write 5cba aa0a00
PmhPeripheral: swift: write 5cba aa0301
PmhPeripheral: swift: write 5cba aa020100
PmhPeripheral: gatt: notify ...5cb8 len=27 = ccc32f03f9fff9ff2f006e07b2011efefcfcf37c087e1b82000518  <-- DATA
...
PmhPeripheral: swift: handshake done
```

结论：**一代追踪器在 Quest 3 上成功输出 IMU 帧**（27 字节 `0xCC`），
整条链路无需 PICO 服务。

注意：实测用的握手是简化版（`aa0100 / aa0a00 / aa0301 / aa020100`），
没有带 MAC 的 `aa0300` BIND 也能启动数据流——说明追踪器只要收到
ENABLE_IMU 就会推送。

## 11.1 Swift 界面打通（阶段 A）

空列表的原因有三层，逐个解决：

1. **数据源**：`SwiftDataSource.getMChangingTrackerLiveData()` 只由 App 自己配对过的
   设备填充 → 模块定时灌入 `Tracker(battery, bondState, PeripheralDevice)`。
2. **刷新路径**：页面刷新走静态方法 `(fragment, adapter, CopyOnWriteArrayList)`，
   R8 把名字抹掉了 → 按签名动态查找后调用（日志 `refresh method b`）。
3. **导航**：App 在列表为空时会自动跳 `ConnectFragment`，把列表页替换掉 →
   `SwiftUiAuto` 改为每 4 秒断言一次
   `TrackerListFragment`（`PmhSwiftUi: pushed TrackerListFragment`）。

结果：

```
PmhTrackerList: refresh rows 2 frag=TrackerListFragment rvChildren=2 adapterItems=2
PmhTrackerList: fragment resumed: TrackerListFragment
```

界面上出现「配对体感追踪器」面板，两行追踪器 + 倒计时 + 「完成」按钮。

## 11.2 原生行样式（阶段 A 收尾）

列表行的模型是 `Tracker(itemStatus, itemType, PeripheralDevice)`，不是
直接传电量/绑定态——电量、连接态、名字都由构造函数从 `PeripheralDevice`
的 AIDL 回复里取（`v()` 绑定、`w()` 连接、`s()` 电量）。

`ConnectFragment` 的映射关系：

```
itemType 5 = 分组标题行（device 传 null）
itemType 4 = 设备行
itemStatus 0 = 未配对 / 2 = 已连接 / 1 = 连接中
名字        = BtKt.getFunnyName(device) → "Motion Tracker DK H6061072"
```

模块按这个映射构造行，界面即显示原生样式：

```
Motion Tracker DK H6061072   完成
Motion Tracker DK H6061126   完成
```

## 11.5 OpenXR API Layer（阶段 C 机制）

Horizon OS 的 loader（`/system/lib64/libopenxr_loader.so`）支持标准隐式层：

```
/system/etc/openxr/1/api_layers/implicit.d/
/system/etc/openxr/1/api_layers/explicit.d/
XR_API_LAYER_PATH
```

已实现并安装 Magisk 模块 `module-sys/pmh_openxr_layer`：

```
system/lib64/libpmh_openxr_layer.so
system/etc/openxr/1/api_layers/implicit.d/pmh_tracker.json
```

层是纯透传，在 `xrNegotiateLoaderApiLayerInterface` / 实例创建时打
`PMHXRLayer` 日志，后续在这里改写 body tracking 调用。

验证现状：文件已挂载；用 `xrprobe` 直接调 loader 时返回
`xrInitializeLoaderKHR was not successfully called`——这个 loader 要求
先带 Android `applicationVM`/`applicationContext` 调 `xrInitializeLoaderKHR`，
所以必须从 APK 里验证，下一步做一个最小 OpenXR 测试 APK。

## 11.5.1 验证工具

`PMHB/PMHBridge/tools/xrlayertest/`：最小 OpenXR 测试 APK（`xrInitializeLoaderKHR`
+ `xrCreateInstance`，带 Quest 的 `com.oculus.intent.category.VR`）。

构建：

```
powershell -File PMHB/PMHBridge/tools/xrlayertest/build.ps1
```

现状：APK 能装、进程能起（`onImmersiveActivityAppeared` 出现），但 Activity
代码没有执行到（无任何应用侧日志），layer 协商日志也还没出现。
下一步改为在已能稳定运行的 `com.pvr.swift` 进程内由模块加载同一个 probe。

## 11.5.2 进程内探针结果

模块内置 `libxrprobe.so`，在目标进程里：

1. `dlopen("/system/lib64/libopenxr_loader.so")`
2. 通过 `xrGetInstanceProcAddr(0, "xrInitializeLoaderKHR")` 取入口
   （这个 loader 只导出 `xrCreateInstance` / `xrGetInstanceProcAddr`）
3. 带 `JavaVM` + `Context` 调 `xrInitializeLoaderKHR`

结果：

```
com.pvr.swift      init=-1 create=-6
com.oculus.vrshell init=-1 create=-6
```

`-1 = XR_ERROR_INITIALIZATION_FAILED`。原因：Meta 的运行时库在
`/apex/com.meta.xr/priv-app/VrDriver/VrDriver.apk!/lib/arm64-v8a/libopenxr_forwardloader.so`，
普通进程的 linker namespace 打不开别人 APK 里的 .so，所以系统 loader 初始化失败。

另外确认：应用自带的 `libopenxr_loader.so`（如 Instagram）确实会扫描
`/etc/openxr/1/api_layers/implicit.d/` 与 `XR_API_LAYER_PATH`，
所以我们的隐式层放在正确位置，只差一个真正创建 OpenXR 实例的进程。

## 11.5.3 层的命名空间问题与姿态通道

实测 `dlopen` 失败原因：

```
layer dlopen failed: library "/system/lib64/libpmh_openxr_layer.so"
is not accessible for the namespace "clns-11"
```

应用命名空间只允许 `public.libraries.txt` 里列出的系统库，所以修法是：

```
/system/etc/public.libraries.txt  += libpmh_openxr_layer.so
layer json: "library_path": "libpmh_openxr_layer.so"   (用 soname，不用绝对路径)
```

**姿态通道**（模块 ↔ 层）：

```
模块 PoseStore  →  /data/user/0/<pkg>/files/pmh_pose.bin
层   pmh_layer  →  读同一个文件（用 /proc/self/cmdline 反推包名）
```

文件格式：

```
uint32 magic   = "PMH1" (0x31484D50)
uint32 version = 1
uint32 count   = 追踪器数量
float  q[4]    = 每个追踪器的 x, y, z, w（20 Hz 更新）
```

模块侧在 `ImuFusion` 收敛后每 50 ms 写一次；层在实例创建时打印该文件状态，
后续在这里把姿态喂进 body tracking 调用。

## 11.5.4 层的验证（2026-11-22）

`public.libraries.txt` 修好后，探针在 `com.oculus.vrshell` 进程里跑通整条链：

```
PmhXrProbe: extracted 7880 bytes
PmhXrProbe: loaded /data/user/0/com.oculus.vrshell/files/libxrprobe_v2.so
PmhXrProbe: layer dlopen ok, negotiate=0x771524fa50
PMHXRLayer: negotiating layer XR_APILAYER_PMH_tracker_bridge (loader 1..1)
PmhXrProbe: negotiate -> 0
```

`negotiate -> 0` 就是 `XR_SUCCESS`：层的协商函数在真实 VR 进程里被调用并正确返回。

探针库的部署方式（无需 root 写应用目录）：

1. 构建时把 `libxrprobe.so` 放进模块 APK 的 `assets/libxrprobe_v2.so`
2. 注入后用 `PackageManager` 取模块 APK 路径，`ZipFile` 读出条目
3. 写进宿主应用自己的 `files/` 目录后 `System.load()`

`xrInitializeLoaderKHR` 仍返回 `-1`，因为 Meta 运行时在
`/apex/com.meta.xr/priv-app/VrDriver/VrDriver.apk!/lib/...`，普通进程无法
dlopen 别人 APK 内的库。这也说明**不需要我们自己初始化**，只等真实 OpenXR
应用创建实例时由它自己的 loader 枚举我们的 implicit 层。

## 11.5.5 自动加载成功（2026-11-22）

致命的一行是 loader 的校验信息：

```
 is missing "disable_environment"
```

OpenXR 规范要求 implicit 层清单必须带 `disable_environment` 字段，否则 loader
直接跳过。补上后：

```json
"disable_environment": "XR_DISABLE_PMH_TRACKER_LAYER"
```

重启 vrshell 后，loader **自动**枚举并加载了我们的层：

```
10:05:24.963 PMHXRLayer: negotiating layer XR_APILAYER_PMH_tracker_bridge (loader 1..1)
10:05:24.963 PMHXRLayer: instance created through PMHBridge layer
10:05:24.963 PMHXRLayer: pose: /data/user/0/com.oculus.vrshell/files/pmh_pose.bin not present yet
```

三行分别是：

1. 层的 `xrNegotiateLoaderApiLayerInterface` 被 loader 自动调用
2. 实例创建走的是我们的 `xrCreateApiLayerInstance`
3. 姿态通道已接通（只是追踪器还没推数据）

**这意味着 PMHBridge 已经位于 VR 运行时的 OpenXR 调用链中**，后续只要在
`xrGetInstanceProcAddr` 里接过 body tracking 相关函数即可注入姿态。

## 11.5.6 body tracking 拦截（2026-11-22）

运行时 `libvrapiimpl.so` 暴露的是 **XR_FB_body_tracking**：

```
xrCreateBodyTrackerFB / xrDestroyBodyTrackerFB
xrLocateBodyJointsFB / xrGetBodySkeletonFB
xrRequestBodyTrackingFidelityMETA
xrResetBodyTrackingCalibrationMETA
```

层里已经在 `xrGetInstanceProcAddr` 中接管：

```c
if (strcmp(name, "xrCreateBodyTrackerFB") == 0) { *function = layer_create_body_tracker; }
if (strcmp(name, "xrLocateBodyJointsFB") == 0)  { *function = layer_locate_body_joints; }
```

两个包装函数会转调下一层并打印：

```
PMHXRLayer: xrCreateBodyTrackerFB -> 0 tracker=...
PMHXRLayer: xrLocateBodyJointsFB -> 0 active=1 joints=... conf=...
```

`XrBodyJointLocationsFB` 的字段（`isActive`/`confidence`/`jointCount`/
`jointLocations`）已按 ABI 对齐定义，下一轮在 `layer_locate_body_joints`
里替换腿部关节即可。

当前 vrshell 并不调用 body tracking，所以还没有实际调用日志；需要一个
真正使用 IOBT 的应用来触发。

## 11.5.7 腿部 IK 验证（2026-11-22）

探针里带了腿部正向运动学自检（大腿 0.42 m、小腿 0.40 m，真实人体尺寸），
在 Quest 上实测输出：

```
ik rest:  knee=(0.120,0.530,0.000) ankle=(0.120,0.130,0.000)
ik 30deg: knee=(0.120,0.586,-0.210) ankle=(0.120,0.240,-0.410)
```

- 静止：髋 0.95 m，膝 0.53（−0.42），踝 0.13（−0.40）✅
- 绕 X 轴 30°：膝 y = 0.95 − 0.42·cos30° = 0.586，z = −0.42·sin30° = −0.210 ✅

### 关节索引（勘误）

早期版本用了 8..11，那是错的。按 **XR_FB_body_tracking** 规范：

```
1  LeftHip          2  RightHip
15 LeftUpperLeg     16 LeftLowerLeg
19 RightUpperLeg    20 RightLowerLeg
23 关节总数
```

`pmh_apply_pose` 已改为读髋关节 1/2 的位置，写腿部 15/16/19/20，
并要求 `jointCount >= 23`。

旋转方向正确（抬腿向后为 −Z），可以直接搬到层里替换 body tracking 的关节。

## 11.5.8 Quest 上实时姿态（2026-11-22）

追踪器开机后，Swift 进程内完整链路跑通：

```
PmhPeripheral: imu #2550 gyro=10,1,0 accel=1441,-1139,921 temp=7669 \
               q=0.851,-0.334,-0.405,-0.015 rpy=-51.2,-44.4,20.1
PmhPose: /data/user/0/com.pvr.swift/files/pmh_pose.bin  (28 字节)
```

### 双追踪器与共享路径

姿态通道现在支持**左右两个追踪器**：

```
文件头: "PMH1", version=1, count=2
数据  : 2 × (x, y, z, w)

slot 0 = MAC 排序后的第一个 → 左腿关节 15/16
slot 1 = 第二个           → 右腿关节 19/20
```

写入路径优先用共享位置 `/data/local/tmp/pmh_pose.bin`（配合 Magisk
`sepolicy.rule` 放行应用读写），失败时回退到应用自己的 `files/` 目录。


期间为了让应用能起来，临时执行过
`pm disable-user com.oculus.guardian` 并已恢复（追踪丢失对话框会卡住窗口申请）。

### 通道问题（下一步要解决）

姿态文件写在**各自应用**的 files 目录，而层运行在**游戏进程**里，两者路径不同，
所以层读不到 Swift 写的姿态。需要改成共享通道，候选：

1. `/data/local/tmp/pmh_pose.bin` + Magisk `sepolicy.rule` 放行应用读取
2. 通过一个 binder 服务把姿态暴露给层

## 11.5.9 一次系统崩溃的教训（2026-11-22）

现象：装完模块重启后 `system_server` 反复崩溃，`pm`/`am` 报
`Can't find service: package`，头显只剩透视和手势菜单。

根因：

```
/system/etc/public.libraries.txt
    libalog.so                  ← 没有 nopreload
    libbtperipheral_jni.so      ← 没有 nopreload
```

`public.libraries.txt` 里不带 `nopreload` 的库会被 **zygote 预加载**。
`libbtperipheral_jni` 依赖 `libchrome`（APEX 内的库），zygote 加载失败 →
系统服务器起不来。

修法：所有非系统库都加 `nopreload`：

```
libalog.so nopreload
libbtperipheral_jni.so nopreload
libpmh_openxr_layer.so nopreload
```

另外 `pm disable com.oculus.guardian` 虽然能让应用启动，但会破坏系统 UX
（只剩透视 + 手势菜单），测试时应避免使用；改用正常等待追踪恢复。

恢复脚本：`tools/restore_all.ps1`（等 root → 重装两个模块 → 重启 → 校验）。

## 11.5.10 PICO 原生栈的 JNI 限制（2026-11-22）

尝试把 PICO 自己的 `BleCentralConnectManager` 直接跑在 Swift 进程里，结果：

```
PmhPicoNative: load failed libalog.so:
    UnsatisfiedLinkError: JNI_ERR returned from JNI_OnLoad
```

原因：`libalog.so` / `libbtperipheral_jni.so` 的 `JNI_OnLoad` 是按
`com.pvr.btperipheral.PeripheralService` 写死的，加载到别的进程直接返回
`JNI_ERR`，继续强行加载会让宿主进程 abort（出现过 tombstone）。

所以：

- `PicoNative` 保留代码但 `ENABLED = false`，默认走 PMHBridge 自己的 GATT 栈
- 如果要真正复用 PICO 的解析器，需要**让它们的服务进程跑起来**（需要
  `android.uid.system`，卡在平台签名），或在 `JNI_OnLoad` 之前 patch 掉校验

## 11.6 PICO 自家服务（走他们自己的）

把 `com.pvr.btperipheral` 装成特权系统应用（Magisk 模块
`module-sys/pmh_btperipheral`），native 库依赖链（`libchrome` → `libevent`）
由系统命名空间解决，但卡在：

```
SecurityException: App UIDs cannot add services.
```

它靠 `sharedUserId=android.uid.system` + 平台签名注册 `peripheral_service`，
我们无法伪造 Meta 的平台证书。绕法是在 Swift 进程内用 `DexClassLoader`
加载它的 BLE 类，不经过 ServiceManager。

## 11.4 姿态解算（阶段 B）

`ImuFusion.java`：Mahony 互补滤波，输入 27 字节 `0xCC` 帧的陀螺/加速度。

```
accel  约 2048 counts / g（静止模长 2052）
gyro   假设 ±2000 dps 器件，16.4 counts / (deg/s)
起机   前 100 帧平均作为陀螺零偏并扣除
输出   四元数 + 欧拉角（roll/pitch/yaw）
```

离线验证（Neo3 抓包 19,091 帧）：

```
#    0 rpy=(   0.0,  -0.3,  -0.0)
#12000 rpy=(  14.7, -74.8,  -3.6)
#19090 rpy=(  -3.2, -72.6,  25.9)
```

pitch 稳定在 −75° 左右，与静态加速度 `(1973, -1, 454)` 推算的
倾角一致（追踪器平放时 X 轴朝上），说明滤波收敛正确。

设备侧日志格式：

```
PmhPeripheral: imu #100 gyro=... accel=... temp=... q=0.98,0.01,... rpy=1.2,3.4,5.6
```

## 12. 下一步

1. **`com.pvr.btperipheral.apk`**：已从 Neo3 工程机取得
   （`/system/app/BluetoothPeripheralService/BluetoothPeripheralService.apk`）
2. **把 IMU 帧接进 Quest**：模块里解析 `0xCC` 帧，输出到日志/测试 App
3. **接入游戏**：需要自建 body tracking 驱动或 XR 输入桥，属于独立工程
4. **FramePicoTracker**（GitHub `Rulez777/FramePicoTracker`）：Steam Frame 侧的
   研究项目，含 80 字节 envelope 与姿态解算，可参考

## 12. 参考文件位置

```
build/neo3/BluetoothPeripheralService.apk    协议实现 APK（Neo3）
build/neo3/jadx/                             反编译源码
build/neo3/btsnoop.log                       真实配对抓包
build/ble_verify.py                          Windows 直连验证脚本
build/station_fw.asm.txt                     基站固件反汇编
build/emulator/trackerbin/                   追踪器固件 + 公钥
build/swift-jadx/                            Swift 反编译源码
build/fpt/                                   FramePicoTracker 相关源码
```
