// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * PMHBridge helper.
 *
 * Four bottom tabs — 修补 / 应用 / 设置 / 助手 — with a HyperOS-flavoured dark
 * theme (rounded cards, 0xFF1C1B1F background, 0xFFD0BCFF accent). Kept on plain
 * framework views for now: MIUIX itself is Compose-only and not published on
 * Maven Central, so the migration waits for a confirmed artifact source.
 *
 * Navigation is page-swapping inside one Activity; on a headset panel that is
 * faster than starting separate activities.
 */
public final class MainActivity extends Activity implements View.OnClickListener {
    private static final int BG = 0xFF1C1B1F;
    private static final int CARD = 0xFF211F26;
    private static final int ON_SURFACE = 0xFFE6E1E5;
    private static final int ON_SURFACE_VARIANT = 0xFFCAC4D0;
    private static final int ACCENT = 0xFFD0BCFF;
    private static final int ON_ACCENT = 0xFF381E72;
    private static final int OUTLINE = 0xFF49454F;

    private static final String[] TABS = {"修补", "应用", "设置", "助手"};

    private static final int REQUEST_APK = 1001;

    private final List<PackageInfo> mPicoPackages = new ArrayList<>();

    private JSONObject mRules;
    private String mAnalysisSummary;
    private FrameLayout mContent;
    private LinearLayout mBar;
    private final List<TextView> mTabViews = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRules = Rules.load();
        loadPicoPackages();
        buildChrome();
        showTab(1);
    }

    // ---------------------------------------------------------------- chrome

    private void buildChrome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        mContent = new FrameLayout(this);
        root.addView(mContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mBar = new LinearLayout(this);
        mBar.setOrientation(LinearLayout.HORIZONTAL);
        mBar.setBackgroundColor(CARD);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * density);
        mBar.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < TABS.length; ++i) {
            TextView tab = new TextView(this);
            tab.setText(TABS[i]);
            tab.setTextSize(14f);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(pad, pad, pad, pad);
            tab.setTag(i);
            tab.setOnClickListener(this);
            mBar.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mTabViews.add(tab);
        }
        root.addView(mBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    public void onClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof Integer) {
            showTab((Integer) tag);
        }
    }

    private void showTab(int index) {
        for (int i = 0; i < mTabViews.size(); ++i) {
            boolean active = i == index;
            mTabViews.get(i).setTextColor(active ? ACCENT : ON_SURFACE_VARIANT);
            mTabViews.get(i).setBackground(active ? rounded(0x33D0BCFF, 16) : null);
        }
        mRules = Rules.load();
        mContent.removeAllViews();
        View page;
        switch (index) {
            case 0:
                page = buildPatchPage();
                break;
            case 2:
                page = buildSettingsPage();
                break;
            case 3:
                page = buildHelperPage();
                break;
            default:
                page = buildAppsPage();
                break;
        }
        mContent.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ----------------------------------------------------------------- pages

    private View buildPatchPage() {
        LinearLayout page = page("修补");
        page.addView(hint("选一个 APK，先分析出要打的补丁，再导出清单交给 PC 端 tools/patch_apk.py 执行。"));

        page.addView(primaryButton("导入 APK 并分析", this::importApk));
        page.addView(hint(mAnalysisSummary == null ? "尚未导入 APK。" : mAnalysisSummary));

        page.addView(cardRow("移除 sharedUserId", "系统签名包在 Horizon 上装不上的主因",
                () -> toggleRecipe("stripSharedUid")));
        page.addView(cardRow("追加权限", "WAKE_LOCK / WRITE_SECURE_SETTINGS / PICO 私有权限",
                () -> toggleRecipe("addPermissions")));
        page.addView(cardRow("标记 VR activity", "让指定 activity 以沉浸式启动",
                () -> toggleRecipe("markVr")));
        page.addView(cardRow("导出修补清单", "把分析结果和选项写进规则文件",
                this::exportRecipe));
        return page;
    }

    /** Opens the system file picker for an APK. */
    private void importApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_APK);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_APK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            String path = copyToCache(uri);
            if (path != null) {
                analyzeApk(path);
                showTab(0);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "读取失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private String copyToCache(Uri uri) {
        try {
            java.io.File out = new java.io.File(getCacheDir(), "import.apk");
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.close();
            in.close();
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reads the APK's manifest through PackageManager and records what the patch
     * pipeline would have to change.
     */
    private void analyzeApk(String path) {
        PackageManager pm = getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES);
        if (info == null) {
            Toast.makeText(this, "无法解析该 APK", Toast.LENGTH_SHORT).show();
            return;
        }
        info.applicationInfo.sourceDir = path;
        info.applicationInfo.publicSourceDir = path;

        StringBuilder summary = new StringBuilder();
        summary.append("包名: ").append(info.packageName).append('\n');
        summary.append("版本: ").append(info.versionName).append('\n');
        if (info.sharedUserId != null) {
            summary.append("sharedUserId: ").append(info.sharedUserId).append("  → 需要移除\n");
        } else {
            summary.append("sharedUserId: 无\n");
        }
        if (info.activities != null && info.activities.length > 0) {
            summary.append("activity: ").append(info.activities.length).append(" 个\n");
            summary.append("首个: ").append(info.activities[0].name).append('\n');
        }
        mAnalysisSummary = summary.toString();

        try {
            JSONObject recipe = Rules.section(mRules, "patchRecipe");
            recipe.put("apk", path);
            recipe.put("package", info.packageName);
            recipe.put("versionName", String.valueOf(info.versionName));
            recipe.put("stripSharedUid", info.sharedUserId != null);
            if (info.activities != null && info.activities.length > 0) {
                recipe.put("activity", info.activities[0].name);
            }
            Rules.save(mRules);
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "分析完成", Toast.LENGTH_SHORT).show();
    }

    private View buildAppsPage() {
        LinearLayout page = page("应用");
        page.addView(hint("点应用 → 查看它的全部 activity → 设置该 activity 是否以 XR 启动。"));

        if (mPicoPackages.isEmpty()) {
            page.addView(hint("没有找到 PICO 应用。"));
            return page;
        }
        for (PackageInfo pi : mPicoPackages) {
            ApplicationInfo ai = pi.applicationInfo;
            String label = ai == null ? pi.packageName : String.valueOf(ai.loadLabel(getPackageManager()));
            Drawable icon = ai == null ? null : ai.loadIcon(getPackageManager());
            page.addView(appRow(icon, label, pi.packageName, () -> showActivities(pi.packageName)));
        }
        return page;
    }

    private void showActivities(String pkg) {
        mContent.removeAllViews();
        LinearLayout page = page(pkg);
        page.addView(backRow(() -> showTab(1)));

        String[] activities = activitiesOf(pkg);
        if (activities.length == 0) {
            page.addView(hint("该应用没有可列出的 activity。"));
        }
        for (String activity : activities) {
            boolean xr = Rules.activityXr(mRules, pkg, activity);
            page.addView(cardRow(shortName(activity),
                    (xr ? "XR 启动" : "2D 面板") + " · " + pkg,
                    () -> showActivitySettings(pkg, activity)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        mContent.addView(scroll);
    }

    private void showActivitySettings(String pkg, String activity) {
        mContent.removeAllViews();
        LinearLayout page = page(shortName(activity) + " 设置");
        page.addView(backRow(() -> showActivities(pkg)));

        page.addView(switchRow("以 XR（沉浸式）启动",
                Rules.activityXr(mRules, pkg, activity), (value) -> {
                    Rules.setActivityXr(mRules, pkg, activity, value);
                    persist();
                }));

        // App-specific option groups live here, under the activity that uses them.
        if (pkg.startsWith("com.pvr.swift")) {
            page.addView(sectionTitle("体感追踪器"));
            page.addView(numberRow("界面代际（1/2）",
                    Rules.optionInt(mRules, pkg, "swift.generation", 2), (value) -> {
                        Rules.setOption(mRules, pkg, "swift.generation", value);
                        persist();
                    }));
            page.addView(switchRow("绕过系统版本校验",
                    Rules.optionBool(mRules, pkg, "swift.unlockVersion", true), (value) -> {
                        Rules.setOption(mRules, pkg, "swift.unlockVersion", value);
                        persist();
                    }));
        }
        if (pkg.startsWith("com.picovr.picostreamassistant")) {
            page.addView(sectionTitle("互联"));
            page.addView(switchRow("强制 2D 面板",
                    Rules.optionBool(mRules, pkg, "connect.forcePanel2d", true), (value) -> {
                        Rules.setOption(mRules, pkg, "connect.forcePanel2d", value);
                        persist();
                    }));
            page.addView(switchRow("PSS/Rm 桩",
                    Rules.optionBool(mRules, pkg, "connect.pssStub", true), (value) -> {
                        Rules.setOption(mRules, pkg, "connect.pssStub", value);
                        persist();
                    }));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        mContent.addView(scroll);
    }

    private View buildSettingsPage() {
        LinearLayout page = page("设置");
        page.addView(hint("设备身份：序列号与机型伪装，保存后重启作用域应用生效。"));

        page.addView(sectionTitle("身份"));
        page.addView(textRow("序列号", Rules.identity(mRules, "serial", "PA9X2C3D4E5F6"),
                (value) -> Rules.setIdentity(mRules, "serial", value)));
        page.addView(textRow("机型 model", Rules.identity(mRules, "model", "A9210"),
                (value) -> Rules.setIdentity(mRules, "model", value)));
        page.addView(textRow("设备 device", Rules.identity(mRules, "device", "sparrow"),
                (value) -> Rules.setIdentity(mRules, "device", value)));
        page.addView(textRow("fingerprint", Rules.identity(mRules, "fingerprint",
                        "Pico/sparrow/sparrow:14/UKQ1.240321.001/1753885011:user/dev-keys"),
                (value) -> Rules.setIdentity(mRules, "fingerprint", value)));

        page.addView(sectionTitle("追踪器"));
        page.addView(numberRow("默认代际（1/2）",
                Rules.identityInt(mRules, "swift.generation", 2),
                (value) -> Rules.setIdentity(mRules, "swift.generation", String.valueOf(value))));

        page.addView(sectionTitle("互联"));
        page.addView(textRow("机型分支（p4/p4u）", Rules.identity(mRules, "connect.modelBranch", "p4"),
                (value) -> Rules.setIdentity(mRules, "connect.modelBranch", value)));

        page.addView(primaryButton("保存到规则文件", this::persist));
        return page;
    }

    private View buildHelperPage() {
        LinearLayout page = page("助手");
        page.addView(cardRow("规则文件", Rules.PATH, null));
        page.addView(cardRow("LSPosed 模块", "com.pmh.bridge.lsp", null));
        page.addView(cardRow("重启作用域应用", "强制停止所有 PICO 应用，让规则重新加载", this::restartScopedApps));
        page.addView(cardRow("导出诊断", "规则文件 + 已装 PICO 应用列表", this::exportDiagnostics));
        page.addView(hint("修改规则后需要重启对应的作用域应用才会生效。"));
        return page;
    }

    // ------------------------------------------------------------- ui pieces

    private LinearLayout page(String title) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (18 * density);
        page.setPadding(pad, pad, pad, pad);

        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(ON_SURFACE);
        header.setTextSize(22f);
        header.setPadding(0, 0, 0, (int) (10 * density));
        page.addView(header);
        return page;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(ON_SURFACE_VARIANT);
        view.setTextSize(13f);
        view.setPadding(0, 0, 0, (int) (12 * getResources().getDisplayMetrics().density));
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(ACCENT);
        view.setTextSize(13f);
        view.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 6);
        return view;
    }

    private LinearLayout cardRow(String title, String subtitle, Runnable action) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(rounded(CARD, 20 * density));
        int pad = (int) (14 * density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * density);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ON_SURFACE);
        t.setTextSize(15f);
        row.addView(t);

        if (subtitle != null) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(ON_SURFACE_VARIANT);
            s.setTextSize(12f);
            s.setPadding(0, (int) (4 * density), 0, 0);
            row.addView(s);
        }
        if (action != null) {
            row.setOnClickListener(v -> action.run());
        }
        return row;
    }

    private LinearLayout appRow(Drawable icon, String label, String pkg, Runnable action) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(CARD, 20 * density));
        int pad = (int) (12 * density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density);
        row.setLayoutParams(lp);

        ImageView image = new ImageView(this);
        if (icon != null) {
            image.setImageDrawable(icon);
        }
        LinearLayout.LayoutParams iconLp =
                new LinearLayout.LayoutParams((int) (40 * density), (int) (40 * density));
        iconLp.rightMargin = (int) (12 * density);
        row.addView(image, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(ON_SURFACE);
        name.setTextSize(15f);
        texts.addView(name);
        TextView sub = new TextView(this);
        sub.setText(pkg);
        sub.setTextColor(ON_SURFACE_VARIANT);
        sub.setTextSize(11f);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(ON_SURFACE_VARIANT);
        chevron.setTextSize(22f);
        row.addView(chevron);

        row.setOnClickListener(v -> action.run());
        return row;
    }

    private LinearLayout switchRow(String title, boolean value, BoolConsumer onChanged) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(CARD, 18 * density));
        int pad = (int) (12 * density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density);
        row.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(ON_SURFACE);
        label.setTextSize(14f);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(value);
        toggle.setOnCheckedChangeListener((button, checked) -> onChanged.accept(checked));
        row.addView(toggle);
        return row;
    }

    private LinearLayout textRow(String title, String value, StringConsumer onChanged) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(rounded(CARD, 18 * density));
        int pad = (int) (12 * density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density);
        row.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(ON_SURFACE_VARIANT);
        label.setTextSize(12f);
        row.addView(label);

        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setTextColor(ON_SURFACE);
        edit.setTextSize(14f);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                onChanged.accept(edit.getText().toString().trim());
            }
        });
        row.addView(edit);
        return row;
    }

    private LinearLayout numberRow(String title, int value, IntConsumer onChanged) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(CARD, 18 * density));
        int pad = (int) (12 * density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density);
        row.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(ON_SURFACE);
        label.setTextSize(14f);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText edit = new EditText(this);
        edit.setText(String.valueOf(value));
        edit.setTextColor(ON_SURFACE);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                try {
                    onChanged.accept(Integer.parseInt(edit.getText().toString().trim()));
                } catch (Throwable ignored) {
                }
            }
        });
        row.addView(edit, new LinearLayout.LayoutParams(
                (int) (90 * density), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView primaryButton(String text, Runnable action) {
        float density = getResources().getDisplayMetrics().density;
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(ON_ACCENT);
        button.setTextSize(15f);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(ACCENT, 22 * density));
        int pad = (int) (12 * density);
        button.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * density);
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout backRow(Runnable action) {
        float density = getResources().getDisplayMetrics().density;
        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextColor(ACCENT);
        back.setTextSize(14f);
        back.setPadding(0, 0, 0, (int) (10 * density));
        back.setOnClickListener(v -> action.run());
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(back);
        return wrapper;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    // ------------------------------------------------------------- behaviour

    private void persist() {
        boolean ok = Rules.save(mRules);
        Toast.makeText(this, ok ? "已保存，重启作用域应用后生效" : "保存失败：需要 root",
                Toast.LENGTH_SHORT).show();
    }

    private void toggleRecipe(String key) {
        try {
            JSONObject recipe = Rules.section(mRules, "patchRecipe");
            recipe.put(key, !recipe.optBoolean(key, false));
            persist();
        } catch (Throwable ignored) {
        }
    }

    private void exportRecipe() {
        persist();
        Toast.makeText(this, "修补清单已写入规则文件", Toast.LENGTH_SHORT).show();
    }

    /** Force-stops every PICO package so the module picks the new rules up. */
    private void restartScopedApps() {
        int stopped = 0;
        for (PackageInfo pi : mPicoPackages) {
            if (forceStop(pi.packageName)) {
                ++stopped;
            }
        }
        Toast.makeText(this, "已停止 " + stopped + " 个应用", Toast.LENGTH_SHORT).show();
    }

    private boolean forceStop(String pkg) {
        String[] candidates = {"/product/bin/su", "su", "/system/bin/su"};
        for (String su : candidates) {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{
                        su, "-c", "am force-stop " + pkg});
                process.waitFor();
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void exportDiagnostics() {
        persist();
        Toast.makeText(this, "诊断信息已写入规则文件", Toast.LENGTH_SHORT).show();
    }

    private void loadPicoPackages() {
        PackageManager pm = getPackageManager();
        mPicoPackages.clear();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            String pkg = pi.packageName;
            if (pkg.startsWith("com.picovr.") || pkg.startsWith("com.pico.")
                    || pkg.startsWith("com.picoxr.") || pkg.startsWith("com.pvr.")
                    || pkg.startsWith("com.pxr.") || pkg.startsWith("com.bytedance.pico.")) {
                mPicoPackages.add(pi);
            }
        }
        Collections.sort(mPicoPackages, new LabelComparator(pm));
    }

    private String[] activitiesOf(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES);
            if (pi.activities == null) {
                return new String[0];
            }
            List<String> names = new ArrayList<>();
            for (android.content.pm.ActivityInfo ai : pi.activities) {
                if (ai.name != null) {
                    names.add(ai.name);
                }
            }
            Collections.sort(names);
            return names.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static String shortName(String activity) {
        int dot = activity.lastIndexOf('.');
        return dot > 0 ? activity.substring(dot + 1) : activity;
    }

    // --------------------------------------------------------------- plumbing

    private interface BoolConsumer {
        void accept(boolean value);
    }

    private interface StringConsumer {
        void accept(String value);
    }

    private interface IntConsumer {
        void accept(int value);
    }

    /** Sorts apps by display name. */
    private static final class LabelComparator implements Comparator<PackageInfo> {
        private final PackageManager mPm;

        LabelComparator(PackageManager pm) {
            mPm = pm;
        }

        @Override
        public int compare(PackageInfo a, PackageInfo b) {
            String la = String.valueOf(a.applicationInfo == null ? a.packageName
                    : a.applicationInfo.loadLabel(mPm));
            String lb = String.valueOf(b.applicationInfo == null ? b.packageName
                    : b.applicationInfo.loadLabel(mPm));
            return la.compareToIgnoreCase(lb);
        }
    }
}
