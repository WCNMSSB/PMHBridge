# PMH 兼容层设计（Pxr 应用在 Horizon 上跑 VR）

## 目标

PICO（Pxr）应用不改包，在 Quest 上以 VR 模式运行，且手柄可用。

## 核心思路

```
PICO 应用（后台进程）
   │  渲染进我们提供的 SurfaceTexture（OES 纹理）
   ▼
PMH 宿主应用（Quest，沉浸式 OpenXR）
   │  自己的 OpenXR 会话
   │  把 OES 纹理提交为合成层
   │  Quest 手柄 → 映射为 PICO 手柄事件
   ▼
用户看到的 VR 画面 + 可用手柄
```

## 切入点

### 1. 画面捕获

`com.pico.xr.openxr_runtime.PicoOpenXRBinderStub.createAndroidSurface(int w, int h, int texId, boolean prot)`

- 内部：`new ExternalSurfaceTexture(texId, stub)` → `Surface` → `nativeSetSurfaceTexture(...)`
- Hook 后返回我们自己的 `SurfaceTexture` 封装的 `Surface`
- 应用照常渲染，像素进入我们持有的 OES 纹理

类来自运行时 APK（通过 `createPackageContext` 的 classloader），
因此 hook 必须在**类加载之后**安装，或直接 hook `android.view.SurfaceTexture` / `Surface` 的构造。

### 2. 姿态

应用需要 XR 姿态才会渲染。

- 方案 A：由宿主应用把 Quest 姿态写进一个共享内存，hook PICO 的 `GetHeadTrackingData`
- 方案 B：宿主直接向应用注入伪 3DOF 姿态（够用即可）

### 3. 控制器

- Quest 侧：`oculus.internal.tracking.IControllerTrackingService` 或 OpenXR action
- PICO 侧：`PXR.IPxrControllerService`（AIDL，共享内存 + 帧序）
- 中间：`pmh_inputd` 读 Quest、写 PICO 契约

### 4. 沉浸式

宿主应用自己建 OpenXR 会话（走 Meta 运行时），
把 OES 纹理作为 `XR_KHR_composition_layer_*` 层提交，
叠加射线/手柄模型由宿主渲染。

## 与当前工作的衔接

已经解决的部分可直接复用：

| 能力 | 现状 |
| --- | --- |
| 运行时库可见（白名单 143 条） | ✅ |
| PICO 服务桩（api_layer 等） | ✅ |
| vsync 伪造（tmpfs + bind mount） | ✅ |
| PICO 私有符号补丁 | ✅ |
| 沉浸式窗口切换 | ✅ |

已经确认不需要的：

- PICO 追踪服务（姿态由宿主提供）
- PICO 手柄（用 Quest 手柄）

## MVP 步骤

1. **验证捕获**：在 PICO 应用进程 hook `createAndroidSurface`，确认能拿到 OES 纹理
2. **宿主骨架**：一个能显示外部纹理的 Quest VR 应用（先糊在面板上）
3. **控制器**：读 Quest 手柄 → 写入 PICO 契约
4. **打磨**：姿态注入、层提交、性能

## 已知风险

- 运行时类在独立 classloader，hook 时机要准
- OES 纹理跨进程不可行 → 宿主与应用需同进程，或走共享内存/GPU 句柄
- 应用可能要求"前台可见"才渲染
