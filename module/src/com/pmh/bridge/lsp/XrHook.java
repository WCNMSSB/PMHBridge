// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Decides whether a PICO activity starts as VR or as a plain 2D panel.
 *
 * com.oculus.vrshell gates this in an obfuscated helper; the relevant method takes
 * (PackageManager, category, pkg, cls) and answers "is this a VR activity". PICO
 * apps declare PICO's own markers, which Horizon ignores, so without help every
 * PICO app opens as a panel.
 *
 * The answer now comes from the PMH rule file, per activity, so the helper app can
 * flip a single activity without touching the APK.
 */
final class XrHook {
    private static final String TAG = "PmhXr";

    private static boolean sInstalled;
    private static int sLogged;
    private static int sSeen;

    private XrHook() {
    }

    /** Logs the placement request the shell builds for a launch. */
    private static boolean sFieldsLogged;
    private static boolean sParamsLogged;

    private static void installPlacementProbe(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.oculus.vrshell.ShellSpatialWindowManagerService", cl);
            XposedBridge.hookAllMethods(cls, "onNewWindowPlacementRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object request = param.args.length > 0 ? param.args[0] : null;
                            if (request == null) {
                                return;
                            }
                            try {
                                if (!sFieldsLogged) {
                                    sFieldsLogged = true;
                                    StringBuilder sb = new StringBuilder("request fields:");
                                    for (java.lang.reflect.Field f
                                            : request.getClass().getDeclaredFields()) {
                                        sb.append(' ').append(f.getName());
                                    }
                                    log(sb.toString());
                                }
                                Object intent = XposedHelpers.getObjectField(
                                        request, "mLaunchIntent");
                                String target = intent == null ? null
                                        : ((android.content.Intent) intent).getComponent()
                                                .getPackageName();
                                log("request pkg=" + target + " xr="
                                        + (target != null && RuleStore.isXr(target,
                                                ((android.content.Intent) intent).getComponent()
                                                        .getClassName())));
                                String cls = ((android.content.Intent) intent)
                                        .getComponent().getClassName();
                                // Only the PICO build needs a forced immersive window;
                                // rewriting it for others makes the shell time out.
                                if (target == null || !target.equals("com.Pico.RealmDream")) {
                                    return;
                                }
                                Object params = XposedHelpers.getObjectField(
                                        request, "mPlacementParams");
                                if (params == null) {
                                    return;
                                }
                                if (!sParamsLogged) {
                                    sParamsLogged = true;
                                    StringBuilder sb = new StringBuilder("params fields:");
                                    for (java.lang.reflect.Field f
                                            : params.getClass().getDeclaredFields()) {
                                        sb.append(' ').append(f.getName());
                                    }
                                    log(sb.toString());
                                }
                                // The parameter class is obfuscated; locate the type field
                                // by shape (an int whose name mentions "type").
                                boolean changed = false;
                                for (java.lang.reflect.Field f
                                        : params.getClass().getDeclaredFields()) {
                                    if (f.getType() != int.class) {
                                        continue;
                                    }
                                    String n = f.getName().toLowerCase();
                                    if (!n.contains("type")) {
                                        continue;
                                    }
                                    f.setAccessible(true);
                                    int before = f.getInt(params);
                                    if (before != 2) {
                                        f.setInt(params, 2);
                                        log("forced immersive: " + f.getName()
                                                + " " + before + " -> 2 for " + target);
                                        changed = true;
                                    }
                                }
                                if (!changed) {
                                    log("no type field found on " + params.getClass().getName());
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("PmhXr: placement rewrite failed: " + t);
                            }
                        }
                    });
            log("placement probe installed");
        } catch (Throwable t) {
            XposedBridge.log("PmhXr: placement probe failed: " + t);
        }
    }

    static void install(ClassLoader cl) {
        // Placement interception is disabled: answering the shell's placement
        // request late makes it time out and the launch is dropped.
        // installPlacementProbe(cl);
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> holder = XposedHelpers.findClass("X.007", cl);
            int hooked = 0;
            for (Method m : holder.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                boolean candidate = params.length == 4
                        && params[1] == String.class
                        && params[2] == String.class
                        && params[3] == String.class
                        && m.getReturnType() == boolean.class;
                if (!candidate) {
                    continue;
                }
                XposedBridge.hookMethod(m, new DecisionHook());
                ++hooked;
            }
            log("XR decision hooked (" + hooked + " method(s))");

            // The decision hook only answers "does this activity support VR"; the
            // shell still builds the launch intent from the manifest, and PICO
            // manifests do not carry Horizon's VR category. Tag the intent as we
            // hand it to the system so immersive launches actually happen.
            try {
                Class<?> contextImpl = XposedHelpers.findClass("android.app.ContextImpl", cl);
                XposedBridge.hookAllMethods(contextImpl, "startActivity", new IntentTagHook());
                log("launch intent tagging installed");
            } catch (Throwable t) {
                log("intent tagging failed: " + t);
            }
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    /** Applies the rule after the shell made its own decision. */
    private static final class DecisionHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object result = param.getResult();
            if (!(result instanceof Boolean)) {
                return;
            }
            String category = (String) param.args[1];
            String pkg = (String) param.args[2];
            String cls = (String) param.args[3];
            if (category == null || pkg == null || cls == null) {
                return;
            }
            if (sSeen < 60) {
                ++sSeen;
                log("decide cat=" + category + " pkg=" + pkg + " cls=" + cls
                        + " shell=" + result);
            }
            if (!"com.oculus.intent.category.VR".equals(category)) {
                return;
            }
            boolean wanted = RuleStore.isXr(pkg, cls);
            if (wanted == (Boolean) result) {
                return;
            }
            param.setResult(wanted);
            if (sLogged < 20) {
                ++sLogged;
                log((wanted ? "VR: " : "2D: ") + pkg + "/" + cls);
            }
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    /** Adds Horizon's VR category to launches of activities the rule file marks XR. */
    private static final class IntentTagHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            for (Object arg : param.args) {
                if (!(arg instanceof Intent)) {
                    continue;
                }
                Intent intent = (Intent) arg;
                ComponentName component = intent.getComponent();
                if (component == null) {
                    continue;
                }
                String pkg = component.getPackageName();
                String cls = component.getClassName();
                if (!RuleStore.isXr(pkg, cls)) {
                    continue;
                }
                if (!intent.hasCategory("com.oculus.intent.category.VR")) {
                    intent.addCategory("com.oculus.intent.category.VR");
                }
            }
        }
    }
}
