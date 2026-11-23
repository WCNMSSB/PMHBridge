# 工作守则

## 永远不要重启 lspd

装完模块 APK 后**不要** `killall lspd` / 重启 LSPosed 守护进程。

正确做法：

1. `adb install -r` 模块 APK
2. 如果模块 APK 路径变了（`adb install` 会换路径），重写 `modules_config.db` 里的 `modules.apk_path`
3. **只重启作用域内的应用**（force-stop + 重新启动它们），让新代码在新进程里注入

原因：LSPosed 在进程 fork 时注入；重启 lspd 会带来 uid 缓存失效、注入丢失、system server 注入失败等一连串问题，
而重启作用域应用足以让新模块生效。

对应脚本：`tools/restart_scoped_apps.ps1`（后续实现），不要再写 killall lspd。