// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Drives the PICO tracker UI without touch input.
 *
 * The Swift panel on Horizon cannot be reached with injected motion events, so the
 * fragment that starts discovery is pushed directly once the V1 activity is up.
 */
public final class SwiftUiAuto {
    private static final String TAG = "PmhSwiftUi";
    private static final boolean ENABLED = true;
    private static volatile boolean jumped = false;

    private SwiftUiAuto() {
    }

    public static void install(final ClassLoader cl) {
        if (!ENABLED) {
            return;
        }
        // The privacy gate aborts launchV1Home before any tracker UI appears, and
        // its dialog cannot be clicked from adb. Treat the guide as accepted.
        try {
            Class<?> state = Class.forName("com.pvr.swift.SwiftStateManager", false, cl);
            XposedBridge.hookAllMethods(state, "isPrivacyAuthorized", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            XposedBridge.log(TAG + ": privacy gate bypassed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": privacy hook failed: " + t);
        }
        // CoverActivity is the "start experience" splash; finishing it runs the
        // IntentStarter that launches the V1 tracker UI.
        try {
            Class<?> cover = Class.forName("com.pvr.swift.cover.CoverActivity", false, cl);
            XposedBridge.hookAllMethods(cover, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Activity activity = (Activity) param.thisObject;
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                android.content.Intent i = new android.content.Intent();
                                i.setClassName("com.pvr.swift",
                                        "com.pvr.swift.MainV1Activity");
                                i.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                activity.startActivity(i);
                                XposedBridge.log(TAG + ": started MainV1Activity directly");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": direct start failed: " + t);
                            }
                            // Finishing the cover here makes the activity's destroy
                            // race the new launch and the system kills the process
                            // with "destroyTimeout"; the cover can stay behind the
                            // tracker UI and is finished by the app itself later.
                        }
                    }, 2500);
                }
            });
            XposedBridge.log(TAG + ": CoverActivity hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cover hook failed: " + t);
        }
        try {
            Class<?> act = Class.forName("com.pvr.swift.MainV1Activity", false, cl);
            XposedBridge.hookAllMethods(act, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Activity activity = (Activity) param.thisObject;
                    final Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(new Runnable() {
                        private int tries;

                        @Override
                        public void run() {
                            // The app re-navigates to ConnectFragment on its own, so
                            // keep asserting the tracker list for a while.
                            jumped = false;
                            jumpToScan(activity, cl);
                            if (++tries < 12) {
                                handler.postDelayed(this, 4000L);
                            }
                        }
                    }, 2500);
                }
            });
            XposedBridge.log(TAG + ": MainV1Activity hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed: " + t);
        }
    }

    private static void jumpToScan(Activity activity, ClassLoader cl) {
        if (jumped) {
            return;
        }
        try {
            int id = activity.getResources().getIdentifier(
                    "fragment_container", "id", activity.getPackageName());
            if (id == 0) {
                XposedBridge.log(TAG + ": fragment_container not found");
                return;
            }
            Object fm = activity.getClass().getMethod("getSupportFragmentManager")
                    .invoke(activity);
            Class<?> fragmentCls = Class.forName("androidx.fragment.app.Fragment", false, cl);
            Class<?> scanCls = Class.forName("com.pvr.swift.fragment.TrackerListFragment", false, cl);
            Object fragment = scanCls.getConstructor().newInstance();
            Object tx = fm.getClass().getMethod("beginTransaction").invoke(fm);
            tx.getClass().getMethod("replace", int.class, fragmentCls).invoke(tx, id, fragment);
            tx.getClass().getMethod("commitAllowingStateLoss").invoke(tx);
            jumped = true;
            XposedBridge.log(TAG + ": pushed TrackerListFragment");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": jump failed: " + t);
        }
    }
}
