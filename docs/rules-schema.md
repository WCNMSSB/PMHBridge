# 规则文件格式 v2

路径：`/data/local/tmp/pmh_rules.json`（0644）

模块与 helper 读同一个文件；helper 写文件走 `su`，写完回读校验。系统属性不再参与，因为
一个 `pkg/activity` 条目就超过 `PROP_VALUE_MAX`（92 字节）。

## 顶层

```json
{
  "version": 2,
  "identity": { ... },
  "packages": { "<包名>": { ... } },
  "patchRecipe": { ... }
}
```

## identity

进程级设备身份，由模块在 PICO 应用启动时套用。

| 键 | 说明 | 示例 |
| --- | --- | --- |
| `serial` | `Build.getSerial()` 的返回值 | `PA9X2C3D4E5F6` |
| `model` / `device` / `name` / `board` | `ro.product.*` | `A9210` / `sparrow` |
| `brand` / `manufacturer` | 厂商 | `Pico` |
| `fingerprint` | `ro.build.fingerprint` | `Pico/sparrow/sparrow:14/...` |
| `puiVersion` | 冒充的 PUI 版本 | `5.15.4` |
| `connect.modelBranch` | 互联按哪个机型分支渲染（`p4` / `p4u`） | `p4` |

每个包还可以自带 `identity` 覆盖，用于"只有互联假装成 PICO 4"这种场景。

## packages

```json
"com.pvr.swift": {
  "forceXr": false,
  "identity": { },
  "options": {
    "swift.generation": 2,
    "swift.unlockVersion": true,
    "swift.unlockFeatures": true
  },
  "activities": {
    "com.pvr.swift.MainActivity": { "xr": true }
  }
}
```

- `forceXr`：整包默认是否 XR，可被 activity 覆盖
- `activities.<类名>.xr`：该 activity 是否以沉浸式启动（模块在 vrshell 的判定里改写）
- `options`：应用专属参数，见下表

### 追踪器（`com.pvr.swift`）

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `swift.generation` | int | 1 = 一代界面，2 = 二代界面 |
| `swift.unlockVersion` | bool | 跳过 `isVersionSupported` 校验 |
| `swift.unlockFeatures` | bool | 强制 `FEAT_SWIFT` 等特性为真 |
| `swift.defaultWearMode` | string | 缺失配置时的默认佩戴模式 |

### 互联（`com.picovr.picostreamassistant`）

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `connect.forcePanel2d` | bool | 强制 2D 面板（避免 PSS 渲染路径） |
| `connect.pssStub` | bool | 中立化 PSS/Rm 的 native 方法 |
| `connect.skipNativeLib` | string | 不加载的 so，逗号分隔（默认 `rms_jni`） |
| `connect.modelBranch` | string | `p4` 走老渲染路径 |

## patchRecipe

helper 的"修补"页写入，PC 端 `tools/patch_apk.py` 读取后执行。

```json
"patchRecipe": {
  "apk": "/data/local/tmp/import.apk",
  "package": "com.picovr.picostreamassistant",
  "versionName": "10.6.6",
  "stripSharedUid": true,
  "addPermissions": true,
  "markVr": false,
  "activity": "com.picovr.picostreamassistant.ui.MainActivity"
}
```
