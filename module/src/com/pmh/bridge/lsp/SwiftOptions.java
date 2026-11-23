// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * PICO Motion Tracker ("Swift") options.
 *
 * The app refuses to start on Horizon OS for two reasons: SwiftAdapter reports a
 * system version it does not like, and Features cannot resolve PICO's
 * ProductConfig class so FEAT_SWIFT is false. Both answers are overridable from
 * the rule file:
 *
 *   swift.generation       1 or 2 (which UI generation to present)
 *   swift.unlockVersion    bypass the version gate
 *   swift.unlockFeatures   force FEAT_SWIFT/featureSupport
 */
final class SwiftOptions {
    private static final String TAG = "PmhSwift";

    private SwiftOptions() {
    }

    static void install(ClassLoader cl, String pkg) {
        boolean unlockVersion = RuleStore.optionBool(pkg, "swift.unlockVersion", true);
        boolean unlockFeatures = RuleStore.optionBool(pkg, "swift.unlockFeatures", true);
        int generation = RuleStore.optionInt(pkg, "swift.generation", 2);

        try {
            Class<?> adapter = XposedHelpers.findClass("com.pvr.swift.sdk.SwiftAdapter", cl);
            if (unlockVersion) {
                XposedBridge.hookAllMethods(adapter, "isVersionSupported", new TrueHook());
            }
            XposedBridge.hookAllMethods(adapter, "getSwiftVersion", new GenerationHook(generation));
            // Also observe what the native layer reports before we override it.
            try {
                Class<?> impl = XposedHelpers.findClass("com.pvr.swift.sdk.SwiftImpl", cl);
                XposedBridge.hookAllMethods(impl, "getSwiftVersion", new NativeValueHook());
            } catch (Throwable t) {
                log("native version observer failed: " + t);
            }
        } catch (Throwable t) {
            log("adapter hooks failed: " + t);
        }

        if (unlockFeatures) {
            try {
                Class<?> features = XposedHelpers.findClass("com.pvr.swift.feature.Features", cl);
                XposedBridge.hookAllMethods(features, "featureSupport", new TrueHook());
                XposedBridge.hookAllMethods(features, "swiftSupport", new TrueHook());
                XposedBridge.hookAllMethods(features, "isSwiftVersion", new TrueHook());
            } catch (Throwable t) {
                log("feature hooks failed: " + t);
            }
        }

        // Without tracker hardware every wear-mode lookup returns null and the
        // fragment dies in updateWearMode(). Skipping the update keeps the UI up;
        // the wear-mode card simply stays at its default.
        boolean skipWearMode = RuleStore.optionBool(pkg, "swift.skipWearMode", true);
        if (skipWearMode) {
            for (String cls : new String[]{
                    "com.pvr.swift.fragment.SwiftMainFragment",
                    "com.pvr.swift.fragment.WearModeFragment",
                    "com.pvr.swift.fragment.SwiftSettingFragment"}) {
                try {
                    Class<?> fragment = XposedHelpers.findClass(cls, cl);
                    XposedBridge.hookAllMethods(fragment, "updateWearMode", new SkipHook());
                    XposedBridge.hookAllMethods(fragment, "updateTrackingMode", new SkipHook());
                } catch (Throwable t) {
                    log("wear mode hook failed for " + cls + ": " + t);
                }
            }
        }

        // SwiftImpl's constructor reads two PICO properties and clears its feature
        // flag when they look wrong, after which every SDK call returns -1:
        //   ro.pxr.externalfunc   must be 1
        //   ro.pui.build.version  must be >= 550
        try {
            Class<?> utils = XposedHelpers.findClass("com.pvr.common.CommonUtils", cl);
            int hooked = 0;
            for (Method method : utils.getDeclaredMethods()) {
                if (!"getSystemProperties".equals(method.getName())) {
                    continue;
                }
                if (method.getParameterTypes().length != 2) {
                    continue;
                }
                XposedBridge.hookMethod(method, new PropertyHook());
                ++hooked;
            }
            log("pico properties faked (" + hooked + " getter(s))");
        } catch (Throwable t) {
            log("property hook failed: " + t);
        }

        log("swift options: gen=" + generation + " version=" + unlockVersion
                + " features=" + unlockFeatures + " skipWearMode=" + skipWearMode);
    }

    private static final class TrueHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.getResult() instanceof Boolean) {
                param.setResult(Boolean.TRUE);
            }
        }
    }

    private static final class GenerationHook extends XC_MethodHook {
        private final int mGeneration;

        GenerationHook(int generation) {
            mGeneration = generation;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.getResult() instanceof Integer) {
                param.setResult(mGeneration);
            }
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    /** Skips a void method that requires tracker hardware. */
    private static final class SkipHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
        }
    }

    /** Pretends the two PICO properties SwiftImpl insists on are present. */
    private static final class PropertyHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String key = (String) param.args[0];
            if (key == null) {
                return;
            }
            String value = null;
            if ("ro.pxr.externalfunc".equals(key)) {
                value = "1";
            } else if ("ro.pui.build.version".equals(key)) {
                value = "600";
            }
            if (value == null) {
                return;
            }
            Class<?> ret = ((Method) param.method).getReturnType();
            if (ret == int.class) {
                param.setResult(Integer.parseInt(value));
            } else if (ret == long.class) {
                param.setResult(Long.parseLong(value));
            } else {
                param.setResult(value);
            }
        }
    }

    /** Logs the value the native layer returns, without changing it. */
    private static final class NativeValueHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            log("native getSwiftVersion -> " + param.getResult());
        }
    }
}
