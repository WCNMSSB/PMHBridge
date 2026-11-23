// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 鍛?zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PMHBridge LSPosed entry point.
 *
 * Routing rules:
 *
 *   com.oculus.vrshell                 -> VR/2D launch decision (XrHook)
 *   com.picovr.*, com.pico.*, com.pvr.* -> PICO identity (IdentityCompat)
 *   com.pvr.swift                      -> tracker options
 *   com.picovr.picostreamassistant     -> connect options
 *
 * Every per-app option is read from the PMH rule file, so the helper app owns all
 * configuration and this module stays stateless.
 */

public final class PmhModule implements IXposedHookLoadPackage {
    private static boolean isPxrTitle(String pkg) {
        return "com.picoxr.ControllerTest".equals(pkg)
                || "com.Pico.RealmDream".equals(pkg)
                || "com.vrchat.android".equals(pkg);
    }

    private static final String TAG = "PmhBridge";

    /**
     * The shell refuses to start any app while a guardian dialog is up, which
     * leaves the headset unusable when tracking is lost. Neutralise that check
     * so PMHBridge can still drive PICO apps for testing.
     */
    private static void installLaunchGuardBypass(ClassLoader cl) {
        String[] candidates = {
            "com.oculus.systemux.SystemUXController",
            "com.oculus.systemux.SystemUxController",
        };
        for (String name : candidates) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                for (final java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getReturnType() != boolean.class || m.getParameterTypes().length == 0) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(false);
                        }
                    });
                }
                XposedBridge.log(TAG + ": launch guard bypassed via " + name);
                return;
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + ": launch guard class not found");
    }

    /** Runs the OpenXR loader probe inside a real VR client process. */
    private static void scheduleProbe(final ClassLoader cl, String pkg) {
        if (!XrProbe.isProbeTarget(pkg)) {
            return;
        }
        XposedBridge.log("PmhXrProbe: scheduling probe for " + pkg);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                XrProbe.runFromCurrentApp(cl);
            }
        }, 8000L);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        String pkg = lpp.packageName;
        XposedBridge.log(TAG + ": load " + pkg);
        if (pkg == null) {
            return;
        }

        // VRChat runs on its own; every hook we installed made it abort inside
        // libmain's JNI_OnLoad, so it is left completely untouched.
        if ("com.vrchat.android".equals(pkg)) {
            XposedBridge.log(TAG + ": vrchat left untouched");
            return;
        }

        if ("com.oculus.vrshell".equals(pkg)) {
            XrHook.install(lpp.classLoader);
            scheduleProbe(lpp.classLoader, pkg);
            installLaunchGuardBypass(lpp.classLoader);
            return;
        }

        // RealmDream is published with a capitalised vendor prefix, so it must be
        // admitted explicitly instead of relying on the case-sensitive prefixes.
        if (!isPico(pkg) && !isPxrTitle(pkg)) {
            return;
        }

        if (RuleStore.shouldSpoof(pkg)) {
            IdentityCompat.install(pkg);
        }

        // Persistent PICO apps get restarted in the background by the system and
        // then die on Android 12+ service-start rules; keep them alive.
        ExtIntentCompat.install(lpp.classLoader);

        if ("com.picovr.picostreamassistant".equals(pkg)) {
            CrashLogHook.install(pkg);
        }

        // Force multi-backend PICO titles onto the PXR backend instead of the
        // Quest UnityOpenXR path, which cannot get composition layers on Horizon.
        if ("com.Pico.RealmDream".equals(pkg)) {
            try {
                XposedBridge.hookAllMethods(java.lang.Runtime.class, "loadLibrary0",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String name = param.args.length > 1
                                        ? String.valueOf(param.args[1]) : "";
                                if (name != null && name.contains("UnityOpenXR")) {
                                    XposedBridge.log(TAG + ": blocked " + name);
                                    param.setResult(null);
                                    return;
                                }
                                // Unity loads libmain.so from Java; logging the stack shows
                                // which loader context FindClass will use there.
                                if ("main".equals(name)) {
                                    XposedBridge.log(TAG + ": loading libmain.so\n"
                                            + android.util.Log.getStackTraceString(
                                                    new Throwable("libmain")));
                                }
                            }
                        });
                XposedBridge.log(TAG + ": realm dream forced to PXR backend");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": realm dream hook failed: " + t);
            }
        }

        if ("com.pico.xr.openxr_runtime".equals(pkg)) {
            // Hooks inside the runtime process destabilised it; it stays untouched.
            XposedBridge.log(TAG + ": runtime hooks skipped");
        } else if (isPxrTitle(pkg)) {
            // Observing Surface creation is enough to know whether the in-process
            // runtime hands the compositor a display surface, and it cannot disturb
            // class loading the way a loader hook does.
            try {
                XposedBridge.hookAllConstructors(android.view.Surface.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                StringBuilder sb = new StringBuilder("Surface created");
                                for (Object arg : param.args) {
                                    sb.append(' ').append(arg);
                                }
                                XposedBridge.log(TAG + ": " + sb);
                            }
                        });
                XposedBridge.log(TAG + ": surface constructor hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": surface hook failed: " + t);
            }

            // The quit may still surface through Java (Activity.finish / Runtime.halt)
            // even though the decision is made in native code.
            try {
                XposedBridge.hookAllMethods(android.app.Activity.class, "finish",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Throwable t = new Throwable("Activity.finish " + param.thisObject);
                                XposedBridge.log(TAG + ": " + android.util.Log.getStackTraceString(t));
                            }
                        });
                XposedBridge.hookAllMethods(java.lang.Runtime.class, "halt",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Throwable t = new Throwable("Runtime.halt(" + param.args[0] + ")");
                                XposedBridge.log(TAG + ": " + android.util.Log.getStackTraceString(t));
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": finish hook failed: " + t);
            }


            // The title quits itself a few seconds into XR init; logging the caller of
            // killProcess reveals which check decided to bail out.
            try {
                XposedBridge.hookAllMethods(android.os.Process.class, "killProcess",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                XposedBridge.log(TAG + ": killProcess(" + param.args[0] + ")\n"
                                        + android.util.Log.getStackTraceString(
                                                new Throwable("killProcess")));
                            }
                        });
                XposedBridge.log(TAG + ": killProcess hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": killProcess hook failed: " + t);
            }

            // PICO's framework service is absent on Horizon; serve it from Java so
            // the AIDL parcel layout stays in the platform's hands.
            // Only the PICO framework-backed title needs this shim; installing it
            // for other titles disturbed their start-up.
            if ("com.Pico.RealmDream".equals(pkg)) {
                try {
                    ApiLayerCompat.install(lpp.classLoader);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": api layer compat failed: " + t);
                }
            }

            // Surface probe: shows whether the PICO runtime hands the title an
            // external texture we could take over for the compat layer.
            try {
                Class<?> st = Class.forName("android.view.SurfaceTexture");
                XposedBridge.hookAllConstructors(st,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                StringBuilder sb = new StringBuilder("SurfaceTexture");
                                for (Object arg : param.args) {
                                    sb.append(' ').append(arg);
                                }
                                XposedBridge.log(TAG + ": " + sb);
                            }
                        });
                XposedBridge.log(TAG + ": surface texture probe installed");
            } catch (Throwable err) {
                XposedBridge.log(TAG + ": surface probe failed: " + err);
            }

            // Only real PXR titles get the runtime driver. Loading it into shell or
            // store processes makes them die at start-up, which shows up as a hang.
            PxrRuntimeLoader.install(lpp.classLoader, pkg);
        }

        if (RuleStore.guardServices(pkg)) {
            ServiceStartGuard.install(lpp.classLoader, pkg);
        }

        scheduleProbe(lpp.classLoader, pkg);

        if (pkg.startsWith("com.pvr.swift")) {
            PeripheralServiceStub.install(lpp.classLoader);
            SwiftOptions.install(lpp.classLoader, pkg);
            SwiftUiAuto.install(lpp.classLoader);
            SwiftTrackerList.install(lpp.classLoader);
            final ClassLoader picoLoader = lpp.classLoader;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object app = Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication").invoke(null);
                        if (app != null) {
                            PicoNative.start((android.content.Context) app);
                        }
                    } catch (Throwable t) {
                        de.robv.android.xposed.XposedBridge.log(
                                "PmhPicoNative: no context: " + t);
                    }
                }
            }, 4000L);
            final ClassLoader probeLoader = lpp.classLoader;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    XrProbe.runFromCurrentApp(probeLoader);
                }
            }, 6000L);
        } else if (pkg.startsWith("com.picovr.picostreamassistant")) {
            ConnectOptions.install(lpp.classLoader, pkg);
        }

        XposedBridge.log(TAG + ": attached to " + pkg);
        Log.i(TAG, "attached to " + pkg);
    }

    private static boolean isPico(String pkg) {
        return pkg.startsWith("com.picovr.")
            || pkg.startsWith("com.pico.")
            || pkg.startsWith("com.picoxr.")
            || pkg.startsWith("com.pvr.")
            || pkg.startsWith("com.pxr.")
            || pkg.startsWith("com.bytedance.pico.");
    }
}
