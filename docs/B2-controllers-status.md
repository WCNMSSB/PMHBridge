# B2：PICO 后端的控制器定位与按键

## 目标

PICO 应用（VRChat Android 版等）在 Quest 3 上以 **PICO XR 后端**运行，并且手柄的
**定位（6DoF pose）** 和 **按键** 都能被应用读到。

不改任何 APK，全部通过 KernelSU 模块 / `/system` 层 / LSPosed 模块实现。

## 已验证可用（不必重做）

| 能力 | 证据 |
| --- | --- |
| PICO 运行时作为 priv-app 运行 | `/system/priv-app/PicoXRRuntime/PicoXRRuntime.apk` |
| 运行时原生依赖全部解析 | `missing (0)`，110 个库 |
| 合成器初始化 | `PxrCompositor: vk_init ... pxrInitialize` |
| vsync 错误归零 | `GetVsyncEvent ERROR` 计数 0 |
| PXR 初始化 | `PxrAPI ... load_runtime = 0` |
| 真沉浸式 | `renderingEnabled: 1`，`submittedFrames` 增长 |
| evdev 按键可读 | `type=1 code=311 value=1`（`/dev/input/event4`） |
| Quest 控制器追踪服务可访问 | 应用进程 `tracking service: 0xb4000070...`（非空） |

## 关键数据源

### 按键 / 扳机 / 摇杆

```
设备节点：/dev/input/event4（每次开机编号可能变化，必须枚举）
识别方式：能取到 ABS_X 且能取到 ABS_RZ
设备名：  Device 0xBB022470D41DE00B
```

权限：`root:input 0660`，普通应用 uid 读不到，需要模块 `post-fs-data.sh` 里
`chmod 666 /dev/input/event*`，或由 root 守护进程转发。

### 手柄 pose

Quest 侧存在服务：

```
oculus.internal.tracking.IControllerTrackingService/default
```

- `service check` 返回 `found`
- 应用进程 `AServiceManager_getService()` 返回非空 binder（已验证）

但该接口是 **native AIDL**（`libbinder_ndk`），系统里没有 Java 定义，
`service call` 无法直接调用（返回 `Unknown error` / `Not a data message`），
框架 dex 里也搜不到 `IPxrControllerTrackingService` 相关类。

## PICO 侧需要什么

`libpxrcontrollerclient.pxr.so` 通过 AIDL 连接 `IPxrControllerService`：

```
getControllerShareMemory      ← 姿态/按键共享内存
getVibrateSharedMemory
getCurrentFrameSequence
createHapticStream
addServiceListener / removeServiceListener
pauseVibrate / resumeVibrate / addPCMVibrateData / addPHFVibrateData / addURIVibrateData
getSpeed / setSpeed / setGuardMotor / getHookKeyEnable / setControllerKeyState
```

数据不是逐帧 binder 调用，而是**共享内存 + 帧序号**。

运行时 APK 内还带有 OpenXR interaction profile：

```
/interaction_profiles/meta/touch_controller_quest_2
/interaction_profiles/meta/touch_plus_controller
/interaction_profiles/oculus/touch_controller
```

说明输入层是 OpenXR 架构，理论上能接受 Quest 手柄的描述。

## 下一步（按顺序）

1. **确定服务注册名**
   - `IPxrControllerService` 在 PICO OS 上由哪个系统组件注册、用什么 instance 名
   - 方法：在运行时进程内 hook `AServiceManager_getService` 并打印 name
   - 载体：扩展现有的 `libpxrvsync.so`（它已随 APK 加载进运行时进程），
     或新建独立的 priv-app 服务进程

2. **实现服务桩**
   - 用 NDK 写 `AIBinder` 服务，`AServiceManager_addService` 注册
   - `getControllerShareMemory` 返回自建 ashmem（先全零 + 帧号递增）
   - 其余震动类方法空实现

3. **填充共享内存**
   - 姿态：从 Quest tracking 服务读（需先解决 native AIDL 调用）
   - 按键：从 evdev 读
   - 布局：先零填充，按 PXR 日志逐步试探字段偏移

4. **验证**
   - VRChat 里手柄模型是否跟着动
   - 扳机 / 摇杆是否有响应

## 已知的坑（务必避开）

| 坑 | 后果 | 规避 |
| --- | --- | --- |
| 把运行时库加载 hook 应用到 `com.oculus.vrshell` | 桌面崩溃，头显"卡二" | 只对指定 PXR 标题生效 |
| 在应用进程里隐藏 `libpxr_api.so` | 应用起不来 | 不要整体隐藏 PXR 库 |
| `public.libraries.txt` 条目不加 `nopreload` | zygote 预加载崩溃，开不了机 | 必须加 `nopreload` |
| 在 `preAppSpecialize` 里读 maps 找应用库 | 那时库还没加载 | 改到重试线程 |
| Zygisk 注入所有进程 | vrshell 崩溃 | 按包名过滤，或改用独立服务 |

## 当前模块状态

```
/data/adb/modules/pmh_xrstack        PICO 运行时 priv-app（保留，必需）
/data/adb/modules/pmh_zygisk_input   已删除（用户要求，不再使用 Zygisk）
LSPosed: com.pmh.bridge.lsp          已安装，作用域含 vrshell 等
```

## 结论

技术路线已经打通到"能拿到手柄按键和 tracking 服务句柄"这一步。
剩下的是**把 Quest 的输入数据按 PICO 的共享内存格式喂进 PXR 输入服务**，
这是纯工程实现，不再是可行性问题。


## 2026-11-12 追加：PICO profile → Quest Touch 的绑定翻译（已验证成功）

### 关键成果

`compat/src/pico_meta_bridge.cpp`（PICO→Meta 兼容桥）新增
`xrSuggestInteractionProfileBindings` 拦截：

* 把 `bytedance/pico4*_controller`、`pico_neo3_controller` 改写到 Meta profile
* 逐条试探 binding，只保留后端接受的条目
* 多候选 profile 依次尝试（meta/touch_controller_plus 不接受，
  `oculus/touch_controller` 接受 28 条）

实测日志：

```
PicoMetaBridge: profile /interaction_profiles/oculus/touch_controller kept 28/36 => 0
PicoMetaBridge: suggest bindings /interaction_profiles/bytedance/pico4_controller -> meta/touch_controller_plus => 0
```

`=> 0` 即 `XR_SUCCESS`。会话随后进入 `XR_SESSION_STATE_FOCUSED`，
VrApi 渲染 56–60 FPS，说明画面与手柄绑定都已打通。

### 注入方式（唯一成功的一种）

Quest 不读 Khronos 的 active_runtime.json，`XR_RUNTIME_JSON` 也无效，
因此只能用 LD_PRELOAD 把 loader 的 `dlopen("openxr_loader")` 重定向到兼容桥：

```
/data/local/tmp/libpmh_openxr_preload.so   （dlopen 重定向）
/data/local/tmp/libpico_meta_bridge.so     （兼容桥）
/data/local/tmp/libmeta_xrloader.so        （Meta 后端）
/data/local/tmp/libc++.so                  （桥内部按需加载）

setprop wrap.com.vrchat.android LD_PRELOAD=/data/local/tmp/libpmh_openxr_preload.so
```

注意：全局预加载 `libc++.so`（放进 LD_PRELOAD 列表）会让 vrshell 崩溃，
必须改为在桥内部按需 `dlopen`。

### 未解决：应用 8 秒后自退

```
17:10:59  Start proc 21503:com.vrchat.android
17:11:08  Process com.vrchat.android has died
zn-daemon64: injected process died: status=0x0 exited with 0
```

排查结论：

| 手段 | 结果 |
| --- | --- |
| 补 `xrLogSdkApiPICO` 空实现 | ✅ 该错误消失，但退出依旧 |
| hook `Process.killProcess` | 未触发 |
| hook `System.exit` / `Runtime.exit` / `Runtime.halt` | 未触发 |
| hook `Activity.finish` | 未触发 |
| hook `SystemProperties.get` | 未触发（native 直接读属性） |
| 伪造 `ro.product.model= PICO 4 Ultra` 等 | ❌ 无效 |

结论：退出发生在 il2cpp / PICO SDK 的 native 代码里（`exit(0)`），
Java/LSPosed 层无法干预。要越过只剩 native hook 或修改 APK。

### 另一个已修正的严重问题

`/system/etc/public.libraries.txt` **绝不能带 UTF-8 BOM**，否则：

```
Abort message: 'Cannot parse /system/etc/public.libraries.txt:
  Malformed line "\ufeff# See https://..."'
```

zygote 直接 abort，表现为"卡二"（开不了机）。写入时务必用无 BOM 的 UTF-8。


## 2026-11-14 追加：梦之境（RealmDream）沉浸式与 PICO 服务桩

### 已打通

1. **沉浸式启动**：给副本 APK 的 UnityPlayerActivity 加两个 category 即可
   ```xml
   <category android:name="com.oculus.intent.category.VR"/>
   <category android:name="org.khronos.openxr.intent.category.IMMERSIVE_HMD"/>
   ```
   系统日志随后出现 `ImmersiveAppChangedCallback` 与 `Interstitial type: IMMERSIVE`。

2. **PICO 系统服务桩**：`pmh_inputd` 用 libbinder_ndk 注册了几个 PICO 侧缺失的服务，
   一律返回成功（事务码全部 STATUS_OK）：
   ```
   ConfigurationService   (com.pvr.configuration.IConfigServiceInterface)
   pxr_notification
   pxreyetrackingservice
   pvr_manager
   pxrcontrollerservice   (PXR.IPxrControllerService)
   ```
   注册成功后在 `service list` 可见，应用不再因 "not published / Waited one second" 卡死。

3. **安装损坏会导致 `NativeLoader` 缺失**：
   ```
   java.lang.ClassNotFoundException: com.unity3d.player.NativeLoader
   JNI DETECTED ERROR: java_class == null
   ```
   重装干净 APK 后消失——这是安装状态问题，不是 APK 缺陷。

### 仍未打通

- 应用停在启动画面，不进入 XR 会话：
  ```
  PxrCompositor: call GetHeadTrackingData failed
  PxrCompositor: MilliSecondsToNextVSync(): Error! VSync time is in the future by 18446744027136 ms
  ```
  `18446744027136` = 0xFFFFFFFF0000，属于时间戳下溢（拿到 0）。

- `libpxrvsync.so` 已加载进运行时进程（`/proc/<pid>/maps` 可见），但它的
  `vsync_event` 管道方案没有覆盖合成器实际使用的读取方式
  （可能是 `fopen`+`fread`，绕过我们拦截的 `read(2)`）。

### 下一步

1. 在 shim 里同时拦截 `fopen`/`fread`（或直接拦截 `open` 返回带数据的 fd），
   让 `vsync_timing` / `bl_pwm_timing` 也返回单调时间戳。
2. 头部追踪：把 Quest 的 head pose 喂给运行时的 tracking 接口。
3. 之后再回头看 XR 会话能否进入、是否出帧。

### 重要经验

- LSPosed 模块注入梦之境会引入 `java_class == null` 崩溃，**测试时不要给它挂作用域**。
- 副本 APK 只改了清单（加 VR category），其余未动。


## 2026-11-15 追加：vsync 打通，卡在首帧提交

### 已解决（系统层，不用改 shim）

```bash
mount -t tmpfs none /sys/class/drm
mkdir -p /sys/class/drm/sde-crtc-0 /sys/class/drm/card0-sde-crtc-0
# daemon 每 1/72s 覆写 8 字节单调时间戳到 /data/local/tmp/pmh_vsync_fifo
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/sde-crtc-0/vsync_event
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/sde-crtc-0/vsync_timing
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/card0-sde-crtc-0/vsync_event
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/card0-sde-crtc-0/vsync_timing
```

效果：`GetVsyncEvent ERROR` 2461 → 0；Unity 主线程从 `xrWaitFrame` 进入 `libunity.so` 主循环。

### 服务桩的稳定组合（关键）

```
ConfigurationService / pxr_notification / api_layer  → 运行时 0 崩溃
pxreyetrackingservice / pxrcontrollerservice / pvr_manager / pxrTrackingservice
  → 加上就会让运行时 incStrong abort，暂不启用
```

通用桩必须“静默”（只回 STATUS_OK，不写 payload），否则运行时崩溃。

### 当前卡点

- 应用启动、沉浸式生效、合成器 63 FPS、`InterstitialController: onImmersiveActivityAppeared`
- 但 `PXRSDK_PM ENGINE FPS: 0`，系统加载遮罩不撤（等首帧提交）
- 运行时唯一报错：`libtrackingclient.pxr.so` 加载失败（缺 PICO 私有 binder 符号
  `_ZN7android22BnTrainArrviesCallback10onTransactEjRKNS_6ParcelEPS1_j`），
  `GetHeadTrackingData` 为 NULL

### 下一步

1. 用 ELF 补丁给 `libsysperftracker.so` 去掉该未定义符号（或补依赖），让 tracking client 能加载
2. 或实现 `pxrTrackingservice` 的真实桩，提供头部姿态


## 2026-11-15 续：最后一层是 PICO 追踪服务

### 已定位

```
libtrackingclient.pxr.so  →  PXR::ITrackingService
  方法: GetShareMemory / GetParameters / AddServiceListener ...
  数据: PXR::DataBufferParcelable（Parcel 传 fd+size）
        PXR::TrackerDataParcelable
        共享内存结构 trackingservice_head_sensor_data_t
```

该服务在 PICO 固件里是独立系统守护进程，**导出的 APK 中没有任何实现方**。

### 已排除的捷径

- `persist.pvr.sdk.trackingmode`：设置后运行时仍走 GetHeadTrackingData，无效
- 用自己的 `pxrTrackingservice` 桩：接口校验不通过（需要真实 AIDL）
- 符号改名（TrainArrvies 系列）只是让库能加载，数据仍需服务提供
- 模拟器 sysroot 里的 `libheadtracking.so` 是姿态滤波库，不是数据源

### 下一步（工程量明确的独立项目）

1. 反汇编 `libtrackingclient.pxr.so` 的 `GetHeadSensorBufferWithSem`，推出结构体偏移
2. 用 libbinder_ndk 写最小 `ITrackingService`（GetShareMemory + GetParameters）
3. 共享内存填静止姿态，验证首帧提交

### 当前设备可用状态

- vsync 伪造脚本（tmpfs + bind mount）已验证有效
- `libsysperftracker.so` 已打补丁（TrainArrvies → operator delete）
- pmh_inputd 桩：ConfigurationService / pxr_notification / api_layer
