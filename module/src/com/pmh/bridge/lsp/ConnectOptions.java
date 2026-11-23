// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * PICO Connect ("互联") options.
 *
 * Connect 10.x changes rendering path with the device model: newer builds take a
 * PICO-only panel path (PSS/Rm) that cannot work on Horizon OS, older ones draw a
 * normal Activity. Two knobs:
 *
 *   connect.modelBranch   "p4" pretends to be a PICO 4 so the older path is used
 *   connect.pssStub       neutralise PSS/Rm natives so the process starts at all
 *   connect.skipNativeLib  libraries that must not be dlopen'd (comma separated)
 */
final class ConnectOptions {
    private static final String TAG = "PmhConnect";

    private ConnectOptions() {
    }

    private static Object allocateInstance(Class<?> cls) throws Exception {
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = unsafeCls.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return unsafeCls.getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
    }

    static void install(ClassLoader cl, String pkg) {
        boolean pssStub = RuleStore.optionBool(pkg, "connect.pssStub", true);
        String modelBranch = RuleStore.optionString(pkg, "connect.modelBranch", "p4");
        String skip = RuleStore.optionString(pkg, "connect.skipNativeLib", "rms_jni");

        // Android 14 hides the hardware serial from normal apps; Connect reads it
        // during Application.onCreate and dies with a SecurityException.
        try {
            XposedHelpers.findAndHookMethod(android.os.Build.class, "getSerial",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            log("Build.getSerial called, spoofing");
                            param.setResult("PMH0000000000");
                        }
                    });
            log("Build.getSerial spoofed");
        } catch (Throwable t) {
            log("getSerial hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(android.provider.Settings.Secure.class,
                    "getString", android.content.ContentResolver.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ("android_id".equals(param.args[1])) {
                                param.setResult("9774d56d682e549c");
                            }
                        }
                    });
            log("android_id spoofed");
        } catch (Throwable t) {
            log("android_id hook failed: " + t);
        }

        // Connect never declared WAKE_LOCK because PICO shipped it with the
        // system uid. Give it a stub wake lock so the platform check is skipped.
        try {
            Class<?> wakeLockCls = Class.forName("android.os.PowerManager$WakeLock");
            final Object stub = allocateInstance(wakeLockCls);
            XposedHelpers.findAndHookMethod(android.os.PowerManager.class, "newWakeLock",
                    int.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(stub);
                        }
                    });
            for (String m : new String[]{"acquire", "release", "setReferenceCounted"}) {
                try {
                    XposedBridge.hookAllMethods(wakeLockCls, m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            log("wake lock stubbed");
        } catch (Throwable t) {
            log("wake lock stub failed: " + t);
        }

        // VR mode: log the request that asks the PC to start SteamVR streaming.
        try {
            Class<?> api = Class.forName("com.picoxr.streaming.picoconnecthmd.PssAPi", false, cl);
            for (final java.lang.reflect.Method m : api.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().contains("steamvr")) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        log("PssAPi." + param.method.getName() + " -> " + param.getResult()
                                + (param.getThrowable() != null ? " ex=" + param.getThrowable() : ""));
                    }
                });
            }
            log("steamvr api probes installed");
        } catch (Throwable t) {
            log("steamvr api probe failed: " + t);
        }

        // Desktop mode has a toolbar button that asks the PC to start SteamVR
        // streaming; without it the VR activity never receives a stream and
        // closes itself after its 8 s timeout.
        try {
            Class<?> desktop = Class.forName(
                    "com.picovr.picostreamassistant.ui.DesktopActivity", false, cl);
            XposedHelpers.findAndHookMethod(desktop, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    final Object activity = param.thisObject;
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        java.lang.reflect.Method m = activity.getClass()
                                                .getDeclaredMethod("triggerStartSteamVR");
                                        m.setAccessible(true);
                                        m.invoke(activity);
                                        log("auto triggerStartSteamVR sent");
                                    } catch (Throwable t) {
                                        log("auto triggerStartSteamVR failed: " + t);
                                    }
                                }
                            }, 4000L);
                }
            });
            log("steamvr auto-trigger installed");
        } catch (Throwable t) {
            log("steamvr auto-trigger failed: " + t);
        }

        // Route the app's OpenXR loader to our PICO->Meta facade, which
        // advertises the PICO extensions the native VR code probes for.
        try {
            // The facade may only be used by the VR renderer: switching the
            // whole process to it stalls the PSS session handshake.
            Class<?> vrActivity = Class.forName(
                    "com.picoxr.streaming.deviceservice.StreamingGameActivity", false, cl);
            XposedHelpers.findAndHookMethod(vrActivity, "onCreate",
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Class<?> os = Class.forName("android.system.Os");
                                os.getMethod("setenv", String.class, String.class,
                                                boolean.class)
                                        .invoke(null, "XR_RUNTIME_JSON",
                                                "/system/etc/pmh_pico_runtime.json", Boolean.TRUE);
                                log("XR_RUNTIME_JSON set for VR activity");
                            } catch (Throwable t) {
                                log("VR env failed: " + t);
                            }
                        }
                    });
            log("VR facade hook installed");
        } catch (Throwable t) {
            log("VR facade hook failed: " + t);
        }

        try {
            Class<?> os = Class.forName("android.system.Os");
            os.getMethod("setenv", String.class, String.class, boolean.class)
                    .invoke(null, "XR_RUNTIME_JSON", "/system/etc/pmh_pico_runtime.json",
                            Boolean.TRUE);
            log("XR_RUNTIME_JSON set globally to facade");
        } catch (Throwable t) {
            log("XR_RUNTIME_JSON set failed: " + t);
        }

        // Native shim: pretend two PICO controllers exist so the battery thread
        // forwards the Quest controller levels to the PC client.
        // Load the controller shim as early as possible: the battery thread can
        // start before Application.onCreate runs.
        try {
            System.load("/system/lib64/libpmh_ctrl_shim.so");
            log("controller shim preloaded from /system/lib64");
        } catch (Throwable t) {
            log("system shim preload failed: " + t);
        }

        try {
            // The module's own class loader cannot see the app's native libs and
            // the application object does not exist yet, so wait for onCreate.
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.content.Context ctx =
                                        (android.content.Context) param.thisObject;
                                String dir = ctx.getApplicationInfo().nativeLibraryDir;
                                System.load(dir + "/libpmh_ctrl_shim.so");
                                log("controller shim loaded from " + dir);
                            } catch (Throwable t) {
                                log("controller shim not loaded: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("controller shim hook failed: " + t);
        }

        // 10.6.x reads the serial through its own DeviceUtil; hook that too in
        // case the framework method is called from a path Xposed misses.
        try {
            Class<?> deviceUtil = Class.forName(
                    "com.picovr.picostreamassistant.util.DeviceUtil", false, cl);
            for (java.lang.reflect.Method m : deviceUtil.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Short-circuit before the framework call can throw.
                            param.setResult("PMH0000000000");
                        }
                    });
                }
            }
            log("DeviceUtil serial shim installed");
        } catch (Throwable t) {
            log("DeviceUtil shim failed: " + t);
        }

        // VR mode: the activity closes itself after ~7s on Quest. Log every
        // DeviceUtil capability check so the failing predicate is visible.
        try {
            Class<?> deviceUtil = Class.forName(
                    "com.picovr.picostreamassistant.util.DeviceUtil", false, cl);
            int hooked = 0;
            for (final java.lang.reflect.Method m : deviceUtil.getDeclaredMethods()) {
                if (m.getReturnType() != boolean.class || m.getParameterTypes().length != 0) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        log("DeviceUtil." + param.method.getName() + " -> " + param.getResult());
                    }
                });
                ++hooked;
            }
            log("DeviceUtil probes: " + hooked);
        } catch (Throwable t) {
            log("DeviceUtil probe failed: " + t);
        }

        // VR mode never reaches startDisplay on Quest; log every call into the
        // display manager so we can see where the flow stops.
        try {
            Class<?> displayManager = Class.forName(
                    "com.picovr.picostreamassistant.display.a", false, cl);
            int hooked = 0;
            for (final java.lang.reflect.Method m : displayManager.getDeclaredMethods()) {
                if (m.isSynthetic()) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("display.a." + param.method.getName() + " called");
                    }
                });
                ++hooked;
            }
            log("display probes: " + hooked);
        } catch (Throwable t) {
            log("display probe failed: " + t);
        }

        // VR mode: record what the display start path returns, otherwise the
        // activity just closes after a timeout with no visible reason.
        try {
            Class<?> displayManager = Class.forName(
                    "com.picovr.picostreamassistant.display.a", false, cl);
            for (java.lang.reflect.Method m : displayManager.getDeclaredMethods()) {
                if (!m.getName().equals("H1") && !m.getName().equals("I1")) {
                    continue;
                }
                if (m.getReturnType() != int.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        log("display " + param.method.getName() + " -> " + param.getResult()
                                + (param.getThrowable() != null ? " ex=" + param.getThrowable() : ""));
                    }
                });
            }
            log("display diagnostics installed");
        } catch (Throwable t) {
            log("display diagnostics failed: " + t);
        }

        // WifiLock has the same WAKE_LOCK requirement; Connect takes one in
        // MainActivity.setupWifiStrategy. Neutralise it as well.
        try {
            Class<?> wifiLockCls = Class.forName("android.net.wifi.WifiManager$WifiLock");
            final Object wifiStub = allocateInstance(wifiLockCls);
            XposedBridge.hookAllMethods(android.net.wifi.WifiManager.class, "createWifiLock",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(wifiStub);
                        }
                    });
            for (String m : new String[]{"acquire", "release", "setReferenceCounted"}) {
                try {
                    XposedBridge.hookAllMethods(wifiLockCls, m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            log("wifi lock stubbed");
        } catch (Throwable t) {
            log("wifi lock stub failed: " + t);
        }

        // Android 14 rejects startForeground(id, notification) when the service
        // declares no foregroundServiceType; Connect was written for a PICO build
        // where the platform did not care.
        try {
            XposedHelpers.findAndHookMethod(Service.class, "startForeground",
                    int.class, Notification.class, new ForegroundTypeHook());
            log("foreground service type shim installed");
        } catch (Throwable t) {
            log("foreground shim failed: " + t);
        }

        if (pssStub) {
            try {
                XposedHelpers.findAndHookMethod(Runtime.class, "loadLibrary0",
                        Class.class, String.class, new LoadHook(skip));
                log("PSS stub installed (skip=" + skip + ")");
            } catch (Throwable t) {
                log("PSS stub failed: " + t);
            }
        }
        log("connect options: modelBranch=" + modelBranch + " pssStub=" + pssStub);
    }

    /**
     * Intercepts library loads coming from PICO's patched framework classes and
     * neutralises the class's native methods instead of letting the load fail.
     */
    private static final class LoadHook extends XC_MethodHook {
        private final String[] mSkip;

        LoadHook(String skip) {
            mSkip = skip == null ? new String[0] : skip.split(",");
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object from = param.args[0];
            String name = (String) param.args[1];
            if (name == null || !(from instanceof Class)) {
                return;
            }
            Class<?> cls = (Class<?>) from;
            String clsName = cls.getName();
            boolean picoFramework = clsName.startsWith("android.resourcemanager.")
                    || clsName.startsWith("android.net.NetworkUtils")
                    || clsName.startsWith("com.pico.api.app.");
            boolean skip = false;
            for (String s : mSkip) {
                if (name.equals(s.trim())) {
                    skip = true;
                    break;
                }
            }
            if (!picoFramework && !skip) {
                return;
            }
            neutralise(cls);
            param.setResult(null);
            Log.i(TAG, "suppressed load of " + name + " for " + clsName);
        }
    }

    private static void neutralise(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isNative(m.getModifiers())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new DefaultValueHook(m));
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class DefaultValueHook extends XC_MethodHook {
        private final Class<?> mReturnType;

        DefaultValueHook(Method method) {
            mReturnType = method.getReturnType();
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (mReturnType == long.class) {
                param.setResult(0L);
            } else if (mReturnType == int.class) {
                param.setResult(0);
            } else if (mReturnType == boolean.class) {
                param.setResult(Boolean.FALSE);
            } else {
                param.setResult(null);
            }
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    /**
     * Skips startForeground entirely.
     *
     * Android 14 compares the requested type against the service's manifest
     * attribute; Connect declares none, so neither the two-arg nor the three-arg
     * overload can succeed. Posting the notification directly keeps the service
     * alive without claiming foreground status.
     */
    private static final class ForegroundTypeHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object self = param.thisObject;
            if (!(self instanceof Service)) {
                return;
            }
            try {
                Service service = (Service) self;
                NotificationManager manager =
                        service.getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.notify((Integer) param.args[0], (Notification) param.args[1]);
                }
                Log.i(TAG, "startForeground bypassed for " + service.getClass().getSimpleName());
            } catch (Throwable t) {
                Log.i(TAG, "startForeground bypass failed: " + t);
            }
            param.setResult(null);
        }
    }
}
