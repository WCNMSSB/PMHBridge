// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replaces PICO's Intent.getExt() path.
 *
 * PICO OS patched android.content.Intent with getExt()/IExtIntent so apps could
 * carry an "ext" payload; PICO's ExtIntent wrapper reads it. Horizon's Intent has
 * no such method, so ExtIntent.getExtras() throws NoSuchMethodError at
 * class-load time of the caller - which is what killed Connect the moment it
 * tried to open the streaming display:
 *
 *   NoSuchMethodError: No virtual method getExt()Landroid/content/IExtIntent;
 *     at android.content.ExtIntent.getExtras(ExtIntent.java:1)
 *     at z2.c.c(XRDisplayManager.java:14)
 *     at com.picovr.picostreamassistant.service.AssistantService.f(...)
 *
 * Falling back to the intent's normal extras keeps the callers working.
 */
final class ExtIntentCompat {
    private static final String TAG = "PmhExtIntent";

    private ExtIntentCompat() {
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> extIntent = XposedHelpers.findClass("android.content.ExtIntent", cl);
            XposedHelpers.findAndHookMethod(extIntent, "getExtras", Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Bundle extras = ((Intent) param.args[0]).getExtras();
                            param.setResult(extras != null ? extras : new Bundle());
                        }
                    });
            XposedBridge.log(TAG + ": ExtIntent.getExtras() served from standard extras");
        } catch (Throwable t) {
            // The class only exists in APKs that embed PICO's extension jar.
            XposedBridge.log(TAG + ": not installed: " + t);
        }
    }
}
