# PMHBridge 项目全量总结

> 项目：把 **PICO 4 Ultra（Pxr）应用** 迁移到 **Meta Quest 3 / Horizon OS** 上运行。
> 约束：**不修改原 APK**；能用 Magisk / system.prop 解决的不用 LSPosed；**不使用 Zygisk**。
> 项目位置：`PMHB/PMHBridge/`（重构后从旧目录迁入，原名已改为 PMHBridge）。

---

## 0. 一句话结论

PICO 应用已经能在 Quest 上**启动、进入沉浸式、由 PICO 合成器稳定出帧（63 FPS）**，
卡在最后一环：**PICO 的头部追踪服务（`ITrackingService`）在 Quest 上不存在**，应用因此不提交首帧，
系统加载遮罩不撤。

已确定新方向：**不再逐点修补 PICO 内部缺陷，改做"兼容层"**——PICO 应用把画面渲染进我们提供的纹理，
沉浸式和手柄由我们自己的宿主应用接管。

---

## 1. 环境与基础设施

### 1.1 设备

| 项 | 值 |
| --- | --- |
| 设备 | Quest 3（代号 `eureka`），Android 14 / SDK 34 |
| Root | KernelSU（经 **Singularity** 授权，**每次重启需手动重新打开**） |
| SELinux | 需要时 `setenforce 0`（Permissive） |
| 连接 | adb 无线 `192.168.0.106:5555`（IP 会变），USB 序列号 `2G0YC5ZG4L008S` |

### 1.2 工具链

```
adb          C:\Users\77456\Desktop\platform-tools-r36.0.2-windows\adb.exe
JAVA_HOME    C:\Program Files\Zulu\zulu-21
Gradle       tools\gradle\gradle-9.6.0\bin\gradle.bat
GRADLE_HOME  tools\gradle-home
NDK          tools\ndk\r27c\android-ndk-r27c
SDK          tools\android-sdk（build-tools 35.0.0）
```

### 1.3 目录结构（`PMHB/PMHBridge/`）

```
module/        LSPosed 模块（Java hooks）
module/inputd/ 原生守护进程 pmh_inputd（binder 服务桩）
module/src/native/pxr_vsync_shim.c   vsync / 预加载 shim
compat/        PICO→Meta 的 OpenXR 门面（libpico_meta_bridge.so）
tools/         install_module.ps1、surface-probe 等
docs/          设计、状态、进度文档
dist/          构建产物
```

---

## 2. 已完成的工作（按主题）

### 2.1 库可见性白名单（`public.libraries.txt`）

**问题**：PICO 运行时库放在 `/system/lib64`，应用命名空间看不到，报
`library "libpxreyetrackingbuffer.so" ... is not accessible for namespace: clns-7`。

**解法**：维护一份合并白名单（stock + PICO 运行时库 + 兼容库），安装到模块的 `system/etc/`。

**踩过的坑（三条，都导致过开机失败）**：

1. **UTF-8 BOM 会让 zygote 直接 abort**
   ```
   Abort message: 'Cannot parse /system/etc/public.libraries.txt: Malformed line "﻿# See https://..."
   ```
   必须写成**无 BOM** 的 UTF-8。
2. 每条库要加 `nopreload`，否则 zygote 预加载失败。
3. 用 `sort -u` 合并会同时留下 `libx.so` 和 `libx.so nopreload` 两份，**破坏开机**。

**结果**：最终 **143 条**，开机正常，PICO 运行时库在应用中加载 **45/46**。

### 2.2 PICO 运行时移植

- 运行时以 **priv-app** 形式由模块 `pmh_xrstack` 安装
- 原始 APK 用 apktool 重打包，补上 `FOREGROUND_SERVICE` 权限，解决
  `ForegroundServiceDidNotStartInTimeException`
- 运行时对外提供 AIDL 服务 `pxrcontrollerservice`，描述符 `PXR.IPxrControllerService`

### 2.3 OpenXR 桥（PICO 运行时 → Meta 运行时）

**注入链**（唯一让绑定被接受的方式）：

```
LD_PRELOAD=/data/local/tmp/libpmh_openxr_preload.so
  → 拦截 dlopen / android_dlopen_ext
  → openxr_loader    → /data/local/tmp/libpico_meta_bridge.so
  → libmeta_xrloader → /data/local/tmp/libmeta_xrloader.so
  → libc++           → /data/local/tmp/libc++.so
```

**关键修复**：

| 问题 | 解法 |
| --- | --- |
| `xrSuggestInteractionProfileBindings` 返回 -22 `XR_ERROR_PATH_UNSUPPORTED` | 逐条绑定探测 + 候选 profile 列表（`oculus/touch_controller` 等） |
| `libc++.so not found ... in namespace clns-6` | 在 bridge 内部**按需** dlopen libc++（不能放进全局 LD_PRELOAD，会导致 vrshell 死亡） |
| `backend has no xrPathToString (result=-12)` | 用**活的 instance** 重新解析函数指针 |
| `Failed to obtain proc address for xrLogSdkApiPICO` | 加空实现桩 |

**成果**：绑定接受 **28/36**；会话状态走通 `READY → SYNCHRONIZED → VISIBLE → FOCUSED`；`VrApi FPS=56~60/72`。

### 2.4 显示桥发现

PICO 运行时的显示入口：

```java
interface IPicoOpenXR {
    void    connect(ParcelFileDescriptor fd);          // 事务码 3，必须先调用
    int     getTexIdOes();                             // 事务码 1
    Surface createAndroidSurface(int w, int h,
                                 int texId, boolean prot);  // 事务码 2
}
```

**重要纠正**：`createAndroidSurface` 的第 3 个参数**是纹理 id，不是格式**。
实现里 `new ExternalSurfaceTexture(texId, this)` → `new Surface(st)` → `nativeSetSurfaceTexture(...)`。

**踩坑**：冷启动直接调用会让运行时（未初始化）**SIGSEGV**。

### 2.5 服务桩（`pmh_inputd`）

Quest 上缺失的 PICO 系统服务，用 libbinder_ndk 注册同名桩：

| 服务 | 状态 | 备注 |
| --- | --- | --- |
| `ConfigurationService` | ✅ | 应用启动必需 |
| `pxr_notification` | ✅ | |
| `api_layer` | ✅ | 21 个方法按返回类型精确回复 |
| `pxreyetrackingservice` / `pxrcontrollerservice` / `pvr_manager` / `pxrTrackingservice` | ⚠️ | 加上会让运行时崩溃，暂不启用 |

**最关键的一条经验**：
> 通用桩必须**静默**——只返回 `STATUS_OK`，不写任何 payload。
> 写 8 个零字节会让运行时在 `RefBase::incStrong` 处 abort，极难定位。

`api_layer` 的接口（从 `build/p4u_fwdex/classes5.dex` 反编译）：
`registerAppSession(IBinder,int)`、`getShowing3dApp()`、`getTopAppOnDefaultDisplay()` 等，事务码 1~27。

另一个坑：AIDL 服务端**不消费**传入 parcel 里的 binder，会让客户端引用计数失衡。但"消费"的方式也踩过雷（读 token 后读 binder 会 SIGSEGV），最终选择静默 + 不读取。

### 2.6 vsync 时间戳（最有效的一次修复）

**现象**：
```
PxrCompositor: MilliSecondsToNextVSync(): Error! VSync time is in the future by 18446741929984 ms!
```
`18446741929984 ≈ 2^64/1e6`，典型下溢——因为读到的 vsync 时间是 0。

**根因**：Quest 的 `/sys/class/drm/card0-sde-crtc-0/vsync_event` 返回**文本**：
```
VSYNC=1521046026929
AVR_STATUS=-61
```
而 PICO 合成器按 **8 字节二进制**解析。

**为什么改 shim 没用**：合成器用 `fread`（stdio），而 shim 是运行时 `dlopen` 进来的（RTLD_LOCAL），
不参与全局符号解析——shim 日志里 `probe` 记录为 0 就是证据。

**最终解法**（纯系统层，不改任何 so）：

```bash
mount -t tmpfs none /sys/class/drm
mkdir -p /sys/class/drm/sde-crtc-0 /sys/class/drm/card0-sde-crtc-0
# daemon 每 1/72 秒把 8 字节单调时间戳写入 /data/local/tmp/pmh_vsync_fifo
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/sde-crtc-0/vsync_event
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/sde-crtc-0/vsync_timing
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/card0-sde-crtc-0/vsync_event
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/card0-sde-crtc-0/vsync_timing
```

**效果**：`GetVsyncEvent ERROR` **2461 次 → 0 次**；Unity 主线程从 `xrWaitFrame`（nanosleep）
进入 `libunity.so` 主循环。

> 注：FIFO 不能 bind mount（会被当成 loop 设备），必须用**普通文件 + 每帧覆写**。

### 2.7 PICO 私有 binder 符号

`libsysperftracker.so` 引用了 PICO 改造版 libbinder 的私有类：
`android::BnTrainArrviesCallback`、`android::EventsTrainRegister` 等。

处理：直接改 `.dynstr` 里的符号名（改成 `_ZdlPv` 这种必然存在的符号），`dlopen` 即可成功。

**代价**：这是打地鼠，后面还有 `libpxrguiex.so` 要 `ImageManagerExt::sLock`……**不可持续**。

### 2.8 沉浸式窗口

给 APK **副本**的启动 Activity 加两个 category：

```xml
<category android:name="com.oculus.intent.category.VR"/>
<category android:name="org.khronos.openxr.intent.category.IMMERSIVE_HMD"/>
```

系统随后：
```
ImmersiveAppChangedCallback: com.Pico.RealmDream
InterstitialManager: Interstitial type: IMMERSIVE ... for regular app launch
VolumetricWindowManagerServiceImpl: ... type = 2 ...
```

---

## 3. 两个目标应用的现状

### 3.1 梦之境（`com.Pico.RealmDream`）

| 环节 | 状态 |
| --- | --- |
| 启动 | ✅ 稳定运行 |
| 沉浸式 | ✅ `type=2` |
| PICO 运行时 | ✅ pxrruntime 加载 |
| 合成器 | ✅ 63 FPS，`LayerCnt=1` |
| Unity 主循环 | ✅ 跑在 `libunity.so` |
| 引擎出帧 | ❌ `PXRSDK_PM ENGINE FPS: 0` |
| 画面 | ❌ 卡在系统加载遮罩 |

**根因链**：
```
libtrackingclient.pxr.so → PXR::ITrackingService
  （GetShareMemory / GetParameters / AddServiceListener）
→ TrackingService_GetHeadTrackingData 为 NULL
→ PxrCompositor: GetHeadTrackingData failed
→ 应用不提交首帧 → 系统遮罩不撤
```

**这个服务在导出的 APK 里没有实现方**（PICO 固件里是独立系统守护进程）。

### 3.2 VRChat（`com.vrchat.android`）

- 用户反馈：**PICO 版本原本能打开、头部追踪正常，只是没有控制器和控制器追踪**
- 我们早期的桥：绑定 28/36、会话可见，但 **8 秒后自退**（native `exit(0)`，Java 层 hook 全部抓不到）
- 后来发现：**我们自己的模块钩子会导致它 `java_class == null` abort**（`libmain` 的 `JNI_OnLoad` 找不到 `com.unity3d.player.NativeLoader`）
- 现在模块对 VRChat **完全跳过**

---

## 4. 兼容层方案（新方向）

### 4.1 架构

```
PICO 应用（后台进程）
   │  渲染进我们提供的 OES 纹理
   ▼
PMH 宿主应用（Quest，自家 OpenXR 会话）
   ├─ 沉浸式显示（合成层）
   └─ Quest 手柄 → 映射为 PICO 手柄事件
```

### 4.2 切入点

```java
PicoOpenXRBinderStub.createAndroidSurface(int w, int h, int texId, boolean prot)
```

Hook 它，把返回的 Surface 换成我们自己的 `SurfaceTexture` → 应用画面进入我们持有的纹理。

### 4.3 为什么可行

- 不需要 PICO 追踪服务（姿态由宿主提供）
- 不需要 PICO 手柄（用 Quest 手柄）
- **不改 APK**

### 4.4 风险

- 运行时类在独立 classloader，hook 时机要准
- **OES 纹理跨进程不可行** → 需要同进程，或走 AHardwareBuffer / GPU 共享句柄

详见 `docs/compat-layer-design.md`。

---

## 5. 问题与修复总表

| # | 问题 | 根因 | 修复 |
| --- | --- | --- | --- |
| 1 | 开机卡二 / zygote abort | `public.libraries.txt` 带 UTF-8 BOM | 无 BOM 重写 |
| 2 | 开机失败（第二次） | `sort -u` 留下重复条目 | 手工合并 + `nopreload` |
| 3 | 应用看不到 PICO 库 | 命名空间限制 | 143 条白名单 |
| 4 | `ForegroundServiceDidNotStartInTimeException` | 运行时清单缺权限 | apktool 加 `FOREGROUND_SERVICE` |
| 5 | 绑定 `-22 XR_ERROR_PATH_UNSUPPORTED` | PICO profile 在 Meta 上不存在 | 逐条探测 + 候选列表，28/36 接受 |
| 6 | `libc++.so not found` | 命名空间隔离 | bridge 内按需 dlopen |
| 7 | `backend has no xrPathToString` | 用了错误的 instance | 用活 instance 解析 |
| 8 | `xrLogSdkApiPICO` 取址失败 | 函数不存在 | 空实现桩 |
| 9 | `NativeLoader` ClassNotFound | **APK 安装损坏**（非 APK 缺陷） | 重装干净 APK |
| 10 | 模块注入后 `java_class == null` | dex 挂载 / 钩子破坏类加载 | 不注入（后彻底移除 dex 挂载） |
| 11 | `incStrong() ... strong refs = 0` | 通用 binder 桩写了非法 payload | 桩必须静默 |
| 12 | `GetVsyncEvent ERROR` 刷屏 | 文本 vs 二进制时间戳 | tmpfs + bind mount 伪造 DRM 节点 |
| 13 | `Timeout while requesting window placement` | 我们的窗口类型改写 | 关闭改写 |
| 14 | 所有应用启动失败 | **残留系统对话框**（`Reprojected OS dialog`） | 手动关闭 |
| 15 | `libsysperftracker.so` 加载失败 | PICO 私有 binder 符号 | 符号改名（不可持续） |

---

## 6. 事故记录（如实）

### 6.1 VRChat 应用数据被清空

- **原因**：我执行了 `pm uninstall com.vrchat.android` 排除干扰，卸载会清数据
- **已修复**：用本地 `build/vrc.apk`（同版本 `2026.4.1p1`）重装
- **不可恢复**：登录态与设置需重新登录

### 6.2 LSPosed 配置库被写坏

- **原因**：修改作用域时把 `/data/adb/lspd/config/modules_config.db` 写成空表
- **影响**：模块对所有应用都不再注入
- **备份就绪**：`build/mc9.db`（7 张表、9 条作用域）
- **待执行**（需 root）：
  ```bash
  cp /data/local/tmp/mc9.db /data/adb/lspd/config/modules_config.db
  reboot
  ```

---

## 7. 硬性规则（协作约定）

1. **永远不要重启 lspd** —— 改作用域后重启作用域内的 app 即可。
2. `adb install -r` 模块后必须用 `tools/install_module.ps1`（它会重写 `modules.apk_path`）。
3. **不要给 `com.oculus.vrshell` 加额外 scope/hook**（会"卡二"）。
4. **不要用 Zygisk**。
5. **不要修改 VRChat 原包**。
6. `libc++` **不能**放进全局 LD_PRELOAD（会导致 vrshell 死亡）。
7. 重启后 **Singularity 需要重新授权 root**。

---

## 8. 当前设备状态

```
Quest 3 / Horizon OS，root 需 Singularity 授权
pmh_inputd        运行中（ConfigurationService / pxr_notification / api_layer）
PICO 运行时       可启动
梦之境            能启动、沉浸式、63 FPS，卡加载（缺姿态）
VRChat            PICO 版已重装，模块已跳过对其注入
LSPosed 配置      损坏，待用 build/mc9.db 恢复
PICO 4 Ultra 机型模板  已写入 rules（Pico/A9210/sparrow/UKQ1.240321.001）
```

---

## 9. 下一步

1. 恢复 LSPosed 配置（需 root）
2. 验证兼容层前提：`SurfaceTexture` 探针是否命中
   ```
   期望日志：PmhBridge: SurfaceTexture <args...>
   ```
3. 命中后做宿主骨架（先 2D 面板显示纹理，再升级 VR 合成层）
4. 控制器桥（Quest `IControllerTrackingService` → PICO `PXR.IPxrControllerService`）
5. 姿态注入（宿主写共享内存，或伪 3DOF）

---

## 10. 三条核心经验

1. **PICO 运行时深度绑定它们改过的 AOSP 框架**（libbinder/libutils 私有类、追踪服务、vsync 设备）。
   逐点打补丁不可持续，必须"宿主接管"。
2. **通用 binder 桩必须静默**。想当然写返回值会破坏引用计数，表现成 `incStrong()` abort，极难定位。
3. **系统残留对话框会静默阻塞所有应用启动**。排查启动失败先看
   `Launch is blocked because: a Reprojected OS dialog is currently showing`。
