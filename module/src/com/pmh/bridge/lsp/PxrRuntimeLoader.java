// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Loads PICO's XR runtime into a PXR app's process.
 *
 * PXR titles (Unity + libpxr_api.so) do not bind the runtime service; they expect
 * the runtime driver to live in-process. On PICO OS it is installed system-wide,
 * so dlopen("libpxrruntime.so") just works. On Horizon the runtime sits inside
 * /system/priv-app/PicoXRRuntime, which an app namespace does not search.
 *
 * This loader walks that directory and System.load()s every library in a
 * dependency-friendly order, retrying a few passes so that libraries whose
 * dependencies appear later still get loaded. Once the runtime is mapped, PXR's
 * own driver lookup finds it.
 */
final class PxrRuntimeLoader {
    private static final String TAG = "PmhPxrLoader";
    /**
     * The runtime libraries are exposed through /system/lib64 + public.libraries.txt
     * (with nopreload) by the KernelSU module, because an app namespace cannot
     * dlopen anything under /system/priv-app.
     */
    private static final String RUNTIME_LIB_DIR = "/system/lib64";

    /** Loaded before the runtime itself; they are its dependencies. */
    private static final List<String> FIRST = Arrays.asList(
            "libdl_android.so",
            "libc++.so",
            "libvndksupport.so",
            "libhidlbase.so",
            "libhidltransport.so",
            "libhwbinder.so",
            "libutils.so",
            "libcutils.so"
    );

    /** The runtime proper, in an order that satisfies inter-library references. */
    private static final List<String> RUNTIME = Arrays.asList(
            // The plugin must register its JNI bridge before the API layer runs;
            // loading it first is what makes JNI_OnLoad_interface non-null.
            "libopenxr_loader.so",
            "libpxrruntime_service.so",
            "libpxrcompositor.so",
            "libpxrinputmanager.so",
            "libpxrruntime.so",
            "libpxrplatformloader.so"
    );

    private static boolean sLoaded;

    private PxrRuntimeLoader() {
    }

    /** Hooks application startup so the runtime is mapped before PXR initialises. */
    static void install(ClassLoader cl, String pkg) {
        sAppLoader = cl;
        try {
            XposedBridge.hookAllMethods(
                    android.app.Instrumentation.class,
                    "callApplicationOnCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sLoaded) {
                                sLoaded = true;
                                attachFrameworkDexes(pkg);
                                load(pkg);
                            }
                        }
                    });
            // PXR initialisation pulls in Chromium's WebView support library, whose
            // JNI_OnLoad aborts when the WebView provider classes are absent:
            //   Abort message: 'Unable to find class
            //   com/android/webview/chromium/DrawFunctor'
            // The runtime does not need WebView, so the load is skipped.
            XposedBridge.hookAllMethods(
                    java.lang.Runtime.class,
                    "loadLibrary0",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String name = param.args.length > 1
                                    ? String.valueOf(param.args[1]) : "";
                            XposedBridge.log(TAG + ": loadLibrary0 " + name);
                            if (name != null && name.contains("webview")) {
                                XposedBridge.log(TAG + ": skipped " + name);
                                param.setResult(null);
                            }
                        }
                    });
            // The WebView support library is also pulled in through System.load()
            // with an absolute path, which the loadLibrary0 hook cannot see.
            XposedBridge.hookAllMethods(
                    java.lang.System.class,
                    "load",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String path = param.args.length > 0
                                    ? String.valueOf(param.args[0]) : "";
                            if (path != null && path.contains("webviewchromium_plat_support")) {
                                XposedBridge.log(TAG + ": skipped System.load " + path);
                                param.setResult(null);
                            }
                        }
                    });
            // Find out who pulls WebView in during PXR start-up.
            try {
                Class<?> factory = Class.forName("android.webkit.WebViewFactory");
                XposedBridge.hookAllMethods(factory, "getProvider", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        StringBuilder trace = new StringBuilder("WebViewFactory.getProvider from:");
                        for (StackTraceElement e : new Throwable().getStackTrace()) {
                            trace.append(" | ").append(e.getClassName())
                                 .append('.').append(e.getMethodName());
                        }
                        XposedBridge.log(TAG + ": " + trace);
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": webview probe failed: " + t);
            }
            XposedBridge.log(TAG + ": armed for " + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed: " + t);
        }
    }


    /**
     * Appends PICO's framework dexes to the app's class loader.
     *
     * PXR needs PICO framework classes (PxrApplication, AppSession, android.app.Ext*)
     * and the target APK must stay untouched, so the dexes are staged on the device
     * (/data/local/tmp/pico_fw) and appended with BaseDexClassLoader.addDexPath.
     */
    private static void attachFrameworkDexes(String pkg) {
        File dir = new File("/data/local/tmp/pico_fw");
        File[] dexes = dir.listFiles();
        if (dexes == null) {
            XposedBridge.log(TAG + ": no framework dexes found");
            return;
        }
        ClassLoader cl = PxrRuntimeLoader.class.getClassLoader();
        int added = 0;
        for (File dex : dexes) {
            if (!dex.getName().endsWith(".dex")) {
                continue;
            }
            try {
                XposedHelpers.callMethod(cl, "addDexPath", dex.getAbsolutePath(), false);
                ++added;
            } catch (Throwable t) {
                try {
                    XposedHelpers.callMethod(cl, "addDexPath", dex.getAbsolutePath());
                    ++added;
                } catch (Throwable ignored) {
                    XposedBridge.log(TAG + ": dex add failed for " + dex.getName() + ": " + t);
                }
            }
        }
        XposedBridge.log(TAG + ": " + added + " framework dexes attached in " + pkg);
    }

    /** A class from the target application, used as the load0() caller. */
    private static Class<?> sFromClass;
    private static ClassLoader sAppLoader;

    private static void resolveFromClass(ClassLoader cl, String pkg) {
        for (String name : new String[]{"com.unity3d.player.UnityPlayer", pkg + ".MainActivity"}) {
            try {
                sFromClass = Class.forName(name, false, cl);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void load(String pkg) {
        resolveFromClass(sAppLoader != null ? sAppLoader : PxrRuntimeLoader.class.getClassLoader(), pkg);
        File dir = new File(RUNTIME_LIB_DIR);
        if (!dir.isDirectory()) {
            Log.i(TAG, "runtime lib dir missing: " + RUNTIME_LIB_DIR);
            return;
        }
        List<String> order = new ArrayList<>(FIRST);
        order.addAll(RUNTIME);
        // Only PXR/OpenXR libraries are preloaded. Sweeping every library in
        // /system/lib64 drags in unrelated JNI_OnLoad implementations (WebView's
        // aborts without its provider) and destabilises the app.
        for (File file : dir.listFiles()) {
            String name = file.getName();
            if (!name.endsWith(".so")) {
                continue;
            }
            boolean ours = name.startsWith("libpxr")
                    || name.contains("openxr")
                    || name.startsWith("libpmh")
                    || name.startsWith("libvndksupport")
                    || name.startsWith("libdl_android");
            if (ours && !order.contains(name)) {
                order.add(name);
            }
        }

        boolean[] loaded = new boolean[order.size()];
        for (int pass = 0; pass < 6; ++pass) {
            int progress = 0;
            for (int i = 0; i < order.size(); ++i) {
                if (loaded[i]) {
                    continue;
                }
                String path = RUNTIME_LIB_DIR + "/" + order.get(i);
                try {
                    // Loading from module code would bind the library to the module's
                    // class loader, and its JNI FindClass calls (Unity's NativeLoader)
                    // would then fail. load0() with a class from the app keeps the
                    // library attached to the application.
                    if (sFromClass != null) {
                        XposedHelpers.callMethod(Runtime.getRuntime(), "load0",
                                sFromClass, path);
                    } else {
                        System.load(path);
                    }
                    loaded[i] = true;
                    ++progress;
                    if (order.get(i).startsWith("libpxr") || order.get(i).contains("openxr")) {
                        XposedBridge.log(TAG + ": loaded " + order.get(i));
                    }
                } catch (Throwable ignored) {
                    // Retried on the next pass; dependencies may not be mapped yet.
                }
            }
            if (progress == 0) {
                break;
            }
        }
        int total = 0;
        for (boolean value : loaded) {
            if (value) {
                ++total;
            }
        }
        XposedBridge.log(TAG + ": " + total + "/" + order.size() + " runtime libs in " + pkg);
    }
}
