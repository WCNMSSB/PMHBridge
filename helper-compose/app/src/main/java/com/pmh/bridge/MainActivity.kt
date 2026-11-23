package com.pmh.bridge

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

private val ACCENT = Color(0xFFD0BCFF)
private val ON_SURFACE = Color(0xFFE6E1E5)
private val ON_VARIANT = Color(0xFFCAC4D0)

private enum class Tab(val label: String) {
    PATCH("修补"),
    APPS("应用"),
    SETTINGS("设置"),
    HELPER("助手"),
}

private sealed interface Page {
    data object Apps : Page
    data class Activities(val pkg: String) : Page
    data class ActivitySettings(val pkg: String, val activity: String) : Page
}

/** Everything the UI needs about one installed app. */
private data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Bitmap?,
)

class MainActivity : ComponentActivity() {

    private lateinit var rules: JSONObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules = RulesIO.load(this)
        val apps = loadApps()
        setContent {
            MiuixTheme(colors = darkColorScheme()) {
                BridgeApp(
                    apps = apps,
                    rules = rules,
                    onRules = { updated ->
                        rules = updated
                        RulesIO.save(this, updated)
                    },
                    activitiesOf = ::activitiesOf,
                    importApk = ::analyzeApk,
                    restartScoped = {
                        apps.filterNot { it.pkg == "com.bytedance.pico.matrix" }
                            .forEach { forceStop(it.pkg) }
                    },
                )
            }
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        return pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            .filter { isPico(it.packageName) }
            .map { info ->
                AppEntry(
                    pkg = info.packageName,
                    label = labelOf(info),
                    icon = try {
                        info.applicationInfo?.loadIcon(pm)?.toBitmap(48, 48)
                    } catch (t: Throwable) {
                        null
                    },
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun labelOf(info: PackageInfo): String = try {
        info.applicationInfo?.loadLabel(packageManager)?.toString() ?: info.packageName
    } catch (t: Throwable) {
        info.packageName
    }

    private fun isPico(pkg: String): Boolean =
        pkg.startsWith("com.picovr.") || pkg.startsWith("com.pico.") ||
            pkg.startsWith("com.picoxr.") || pkg.startsWith("com.pvr.") ||
            pkg.startsWith("com.pxr.") || pkg.startsWith("com.bytedance.pico.")

    private fun activitiesOf(pkg: String): List<String> = try {
        packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
            ?.mapNotNull { it.name }?.sorted() ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    private fun analyzeApk(uri: Uri): String {
        // A staged file keeps its real path so the generated patch.sh is runnable on
        // the PC; anything coming from the file picker is copied into the cache.
        val file = if (uri.scheme == "file" && uri.path != null) {
            java.io.File(uri.path!!)
        } else {
            java.io.File(cacheDir, "import.apk").also { target ->
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return "读取失败"
                } catch (t: Throwable) {
                    return "读取失败: $t"
                }
            }
        }
        val info = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_ACTIVITIES,
        ) ?: return "无法解析该 APK"
        info.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        var summary = buildString {
            appendLine("包名: ${info.packageName}")
            appendLine("版本: ${info.versionName}")
            appendLine("sharedUserId: ${info.sharedUserId ?: "无"}")
            appendLine("activity: ${info.activities?.size ?: 0} 个")
        }
        val apkPath = file.absolutePath
        val recipe = RulesIO.section(rules, "patchRecipe")
        recipe.put("apk", file.absolutePath)
        recipe.put("package", info.packageName)
        recipe.put("versionName", info.versionName ?: "")
        recipe.put("stripSharedUid", info.sharedUserId != null)
        info.activities?.firstOrNull()?.name?.let { recipe.put("activity", it) }
        RulesIO.save(this, rules)

        // Drop a runnable recipe + script on shared storage so the PC side can run
        // exactly what was analysed here.
        try {
            val dir = java.io.File(getExternalFilesDir(null) ?: filesDir, "patch")
            dir.mkdirs()
            java.io.File(dir, "recipe.json").writeText(recipe.toString(2))
            val noRoot = info.sharedUserId != null
            val cmd = buildString {
                append("python tools/patch_apk.py")
                append(" --apk \"$apkPath\"")
                append(" --out \"patched.apk\"")
                if (noRoot) append(" --no-root")
                info.activities?.firstOrNull()?.name?.let { append(" --vr-activity \"$it\"") }
                append(" --sign")
            }
            java.io.File(dir, "patch.sh").writeText(
                "#!/bin/sh\n# generated by PMHBridge\n$cmd\n"
            )
            summary += "\n\n修补脚本: " + java.io.File(dir, "patch.sh").absolutePath
        } catch (t: Throwable) {
            summary += "\n\n写导出文件失败: $t"
        }
        return summary
    }

    private fun forceStop(pkg: String) {
        for (su in listOf("/product/bin/su", "su", "/system/bin/su")) {
            try {
                Runtime.getRuntime().exec(arrayOf(su, "-c", "am force-stop $pkg")).waitFor()
                return
            } catch (t: Throwable) {
            }
        }
    }
}

@Composable
private fun BridgeApp(
    apps: List<AppEntry>,
    rules: JSONObject,
    onRules: (JSONObject) -> Unit,
    activitiesOf: (String) -> List<String>,
    importApk: (Uri) -> String,
    restartScoped: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.APPS) }
    var page by remember { mutableStateOf<Page>(Page.Apps) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.PATCH -> PatchTab(importApk)
                Tab.APPS -> when (val current = page) {
                    is Page.Apps -> AppsTab(apps) { page = Page.Activities(it.pkg) }
                    is Page.Activities -> ActivitiesTab(
                        pkg = current.pkg,
                        activities = activitiesOf(current.pkg),
                        rules = rules,
                        onRules = onRules,
                        onBack = { page = Page.Apps },
                        onOpen = { page = Page.ActivitySettings(current.pkg, it) },
                    )
                    is Page.ActivitySettings -> ActivitySettingsTab(
                        pkg = current.pkg,
                        activity = current.activity,
                        rules = rules,
                        onRules = onRules,
                        onBack = { page = Page.Activities(current.pkg) },
                    )
                }
                Tab.SETTINGS -> SettingsTab(rules, onRules)
                Tab.HELPER -> HelperTab(restartScoped)
            }
        }
        TabBar(tab) { tab = it; page = Page.Apps }
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tab.entries.forEach { entry ->
            val active = entry == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) Color(0x33D0BCFF) else Color.Transparent,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelect(entry) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.label,
                    color = if (active) ACCENT else ON_VARIANT,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
        color = ACCENT,
        fontSize = 13.sp,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        color = ON_VARIANT,
        fontSize = 12.sp,
    )
}

@Composable
private fun AppsTab(apps: List<AppEntry>, onOpen: (AppEntry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SectionTitle("PICO 应用") }
        item { Hint("点应用 → 查看它全部 activity → 设置该 activity 是否以 XR 启动。") }
        items(apps) { entry ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(entry) },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = entry.icon
                    if (icon != null) {
                        androidx.compose.foundation.Image(
                            bitmap = icon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(entry.label, color = ON_SURFACE, fontSize = 15.sp)
                        Text(entry.pkg, color = ON_VARIANT, fontSize = 11.sp)
                    }
                    Text("›", color = ON_VARIANT, fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun ActivitiesTab(
    pkg: String,
    activities: List<String>,
    rules: JSONObject,
    onRules: (JSONObject) -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                "‹ 返回",
                modifier = Modifier.padding(vertical = 10.dp).clickable { onBack() },
                color = ACCENT,
                fontSize = 14.sp,
            )
        }
        item { SectionTitle(pkg) }
        item {
            var packageXr by remember { mutableStateOf(RulesIO.forceXr(rules, pkg)) }
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("整包标记为 XR 能力（forceXr）", color = ON_SURFACE, fontSize = 14.sp)
                        Text("下面每个 activity 的开关可单独覆盖", color = ON_VARIANT, fontSize = 10.sp)
                    }
                    Switch(checked = packageXr, onCheckedChange = { checked ->
                        packageXr = checked
                        RulesIO.setForceXr(rules, pkg, checked)
                        onRules(rules)
                    })
                }
            }
        }
        if (activities.isEmpty()) {
            item { Hint("该应用没有可列出的 activity。") }
        }
        items(activities) { activity ->
            val xr = RulesIO.activityXr(rules, pkg, activity)
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(activity) },
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(activity.substringAfterLast('.'), color = ON_SURFACE, fontSize = 15.sp)
                    Text(
                        if (xr) "XR 启动" else "2D 面板",
                        color = ON_VARIANT,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivitySettingsTab(
    pkg: String,
    activity: String,
    rules: JSONObject,
    onRules: (JSONObject) -> Unit,
    onBack: () -> Unit,
) {
    var xr by remember { mutableStateOf(RulesIO.activityXr(rules, pkg, activity)) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(
            "‹ 返回",
            modifier = Modifier.padding(vertical = 10.dp).clickable { onBack() },
            color = ACCENT,
            fontSize = 14.sp,
        )
        SectionTitle(activity.substringAfterLast('.'))
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("标记为 XR 能力（仅对本身支持 VR 的应用生效）", color = ON_SURFACE, fontSize = 14.sp)
                    Text(activity, color = ON_VARIANT, fontSize = 10.sp)
                }
                Switch(checked = xr, onCheckedChange = { checked ->
                    xr = checked
                    RulesIO.setActivityXr(rules, pkg, activity, checked)
                    onRules(rules)
                })
            }
        }
        if (pkg.startsWith("com.pvr.swift")) {
            SectionTitle("体感追踪器")
            OptionSwitch(
                title = "绕过系统版本校验",
                pkg = pkg,
                key = "swift.unlockVersion",
                fallback = true,
                rules = rules,
                onRules = onRules,
            )
            OptionSwitch(
                title = "强制 Swift 特性为真",
                pkg = pkg,
                key = "swift.unlockFeatures",
                fallback = true,
                rules = rules,
                onRules = onRules,
            )
            OptionChoice(
                title = "界面代际",
                pkg = pkg,
                key = "swift.generation",
                options = listOf("1" to "一代", "2" to "二代"),
                fallback = "2",
                rules = rules,
                onRules = onRules,
            )
        }
        if (pkg.startsWith("com.picovr.picostreamassistant")) {
            SectionTitle("互联")
            OptionSwitch(
                title = "PSS / Rm 桩",
                pkg = pkg,
                key = "connect.pssStub",
                fallback = true,
                rules = rules,
                onRules = onRules,
            )
            OptionSwitch(
                title = "强制 2D 面板",
                pkg = pkg,
                key = "connect.forcePanel2d",
                fallback = true,
                rules = rules,
                onRules = onRules,
            )
            OptionChoice(
                title = "机型分支",
                pkg = pkg,
                key = "connect.modelBranch",
                options = listOf("p4" to "PICO 4", "p4u" to "PICO 4 Ultra"),
                fallback = "p4",
                rules = rules,
                onRules = onRules,
            )
        }
    }
}

@Composable
private fun PatchTab(importApk: (Uri) -> String) {
    var summary by remember { mutableStateOf("尚未导入 APK。") }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            summary = importApk(uri)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("修补")
        Hint("导入 APK → 分析 → 生成修补清单；PC 端 tools/patch_apk.py 执行。")
        Button(
            onClick = { launcher.launch(arrayOf("application/vnd.android.package-archive")) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text("导入 APK 并分析")
        }

        val ctx = androidx.compose.ui.platform.LocalContext.current
        val importDir = remember(ctx) {
            java.io.File(ctx.getExternalFilesDir(null) ?: java.io.File("/sdcard"), "import")
        }
        val staged = remember(importDir) {
            val made = importDir.mkdirs()
            val files = importDir.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }
            android.util.Log.i(
                "PmhImport",
                "dir=${importDir.absolutePath} exists=${importDir.exists()} mkdir=$made files=${files?.size ?: -1}",
            )
            files?.sortedBy { it.name } ?: emptyList()
        }
        if (staged.isNotEmpty()) {
            SectionTitle("导入目录中的 APK")
            staged.forEach { file ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                        summary = importApk(android.net.Uri.fromFile(file))
                    },
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(file.name, color = ON_SURFACE, fontSize = 13.sp)
                        Text(
                            "${file.length() / 1024} KB",
                            color = ON_VARIANT,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            Hint("目录: ${importDir.absolutePath}")
        } else {
            Hint("把 APK 放到 ${importDir.absolutePath} 后会列在这里，可免文件选择器直接分析。")
        }
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(summary, modifier = Modifier.padding(12.dp), color = ON_SURFACE, fontSize = 12.sp)
        }
        Hint("免 root 补丁：去 sharedUserId、删特权权限、补 WAKE_LOCK / QUERY_ALL_PACKAGES。")
    }
}

@Composable
private fun SettingsTab(rules: JSONObject, onRules: (JSONObject) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("身份")
        IdentityField("序列号", rules, "serial", onRules)
        IdentityField("机型 model", rules, "model", onRules)
        IdentityField("设备 device", rules, "device", onRules)
        IdentityField("品牌 brand", rules, "brand", onRules)
        IdentityField("厂商 manufacturer", rules, "manufacturer", onRules)
        IdentityField("fingerprint", rules, "fingerprint", onRules)
        IdentityField("PUI 版本（伪装）", rules, "puiVersion", onRules)
        IdentityApplyField(rules, onRules)

        SectionTitle("体感追踪器（默认值）")
        OptionChoice(
            title = "界面代际",
            pkg = "com.pvr.swift",
            key = "swift.generation",
            options = listOf("1" to "一代", "2" to "二代"),
            fallback = "2",
            rules = rules,
            onRules = onRules,
        )
        OptionSwitch(
            title = "跳过佩戴模式更新（无硬件时避免崩溃）",
            pkg = "com.pvr.swift",
            key = "swift.skipWearMode",
            fallback = true,
            rules = rules,
            onRules = onRules,
        )
        OptionSwitch(
            title = "绕过系统版本校验",
            pkg = "com.pvr.swift",
            key = "swift.unlockVersion",
            fallback = true,
            rules = rules,
            onRules = onRules,
        )
        OptionSwitch(
            title = "强制 Swift 特性为真",
            pkg = "com.pvr.swift",
            key = "swift.unlockFeatures",
            fallback = true,
            rules = rules,
            onRules = onRules,
        )

        SectionTitle("互联（默认值）")
        OptionChoice(
            title = "机型分支",
            pkg = "com.picovr.picostreamassistant",
            key = "connect.modelBranch",
            options = listOf("p4" to "PICO 4", "p4u" to "PICO 4 Ultra"),
            fallback = "p4",
            rules = rules,
            onRules = onRules,
        )
        OptionSwitch(
            title = "PSS / Rm 桩",
            pkg = "com.picovr.picostreamassistant",
            key = "connect.pssStub",
            fallback = true,
            rules = rules,
            onRules = onRules,
        )
        OptionSwitch(
            title = "强制 2D 面板",
            pkg = "com.picovr.picostreamassistant",
            key = "connect.forcePanel2d",
            fallback = true,
            rules = rules,
            onRules = onRules,
        )
    }
}

@Composable
private fun IdentityApplyField(rules: JSONObject, onRules: (JSONObject) -> Unit) {
    var value by remember { mutableStateOf(RulesIO.identityApply(rules).joinToString(", ")) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("身份生效的包名（逗号分隔，空=默认四个）", color = ON_VARIANT, fontSize = 12.sp)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = TextStyle(fontSize = 13.sp, color = ON_SURFACE),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                "保存",
                modifier = Modifier.padding(top = 8.dp).clickable {
                    RulesIO.setIdentityApply(
                        rules,
                        value.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    )
                    onRules(rules)
                },
                color = ACCENT,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun IdentityField(
    title: String,
    rules: JSONObject,
    key: String,
    onRules: (JSONObject) -> Unit,
) {
    var value by remember { mutableStateOf(RulesIO.identity(rules, key, "")) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, color = ON_VARIANT, fontSize = 12.sp)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = TextStyle(fontSize = 14.sp, color = ON_SURFACE),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                "保存",
                modifier = Modifier.padding(top = 8.dp).clickable {
                    RulesIO.setIdentity(rules, key, value)
                    onRules(rules)
                },
                color = ACCENT,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun HelperTab(restartScoped: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("助手")
        Hint("规则文件: " + RulesIO.PATH)
        Hint("模块包名: com.pmh.bridge.lsp")
        Button(
            onClick = { restartScoped() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text("重启作用域应用")
        }
        Hint("改完规则重启对应应用即可；不要重启 lspd。")
    }
}

@Composable
private fun OptionSwitch(
    title: String,
    pkg: String,
    key: String,
    fallback: Boolean,
    rules: JSONObject,
    onRules: (JSONObject) -> Unit,
) {
    var checked by remember { mutableStateOf(RulesIO.optionBool(rules, pkg, key, fallback)) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), color = ON_SURFACE, fontSize = 14.sp)
            Switch(checked = checked, onCheckedChange = { value ->
                checked = value
                RulesIO.setOptionBool(rules, pkg, key, value)
                onRules(rules)
            })
        }
    }
}

@Composable
private fun OptionChoice(
    title: String,
    pkg: String,
    key: String,
    options: List<Pair<String, String>>,
    fallback: String,
    rules: JSONObject,
    onRules: (JSONObject) -> Unit,
) {
    var current by remember { mutableStateOf(RulesIO.optionString(rules, pkg, key, fallback)) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, color = ON_VARIANT, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (value, label) ->
                    val active = value == current
                    Box(
                        Modifier
                            .background(
                                if (active) Color(0x33D0BCFF) else Color(0x11FFFFFF),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable {
                                current = value
                                RulesIO.setOptionString(rules, pkg, key, value)
                                onRules(rules)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            label,
                            color = if (active) ACCENT else ON_VARIANT,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
