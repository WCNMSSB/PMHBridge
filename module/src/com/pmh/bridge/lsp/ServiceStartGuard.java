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
 * Keeps persistent PICO apps out of a crash loop.
 *
 * com.bytedance.pico.matrix is a persistent app: when its process dies the system
 * restarts it in the background, its Application starts a service during
 * onCreate, and Android 12+ throws BackgroundServiceStartNotAllowedException -
 * which kills the app - so the system restarts it again, forever.
 *
 * Swallowing that one exception lets the process stay up. The service it wanted
 * is not started, which costs a background feature but stops the loop.
 */
final class ServiceStartGuard {
    private static final String TAG = "PmhServiceGuard";

    private ServiceStartGuard() {
    }

    static void install(ClassLoader cl, String pkg) {
        try {
            Class<?> contextImpl = XposedHelpers.findClass("android.app.ContextImpl", cl);
            int hooked = 0;
            for (Method method : contextImpl.getDeclaredMethods()) {
                String name = method.getName();
                if (!"startService".equals(name) && !"startServiceCommon".equals(name)
                        && !"startForegroundService".equals(name)) {
                    continue;
                }
                XposedBridge.hookMethod(method, new GuardHook(method));
                ++hooked;
            }
            log("service start guard installed for " + pkg + " (" + hooked + " method(s))");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    private static final class GuardHook extends XC_MethodHook {
        private final Method mMethod;

        GuardHook(Method method) {
            mMethod = method;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object result = XposedBridge.invokeOriginalMethod(
                        mMethod, param.thisObject, param.args);
                param.setResult(result);
            } catch (Throwable t) {
                // BackgroundServiceStartNotAllowedException and friends: the caller
                // treats startService as fire-and-forget, so returning null is safe.
                Log.i(TAG, "suppressed " + t.getClass().getSimpleName()
                        + " from " + mMethod.getName());
                param.setResult(null);
            }
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
