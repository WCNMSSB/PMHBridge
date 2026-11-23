// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Logs the full stack of an uncaught exception before the process dies.
 *
 * PICO apps often carry their own crash reporter that swallows the stack, so a
 * user-visible "app stopped" leaves nothing useful in logcat. ThreadGroup's
 * uncaughtException runs before the default handler, which makes it the right
 * place to record what actually threw.
 */
final class CrashLogHook {
    private static final String TAG = "PmhCrash";

    private CrashLogHook() {
    }

    static void install(String pkg) {
        try {
            XposedHelpers.findAndHookMethod(ThreadGroup.class, "uncaughtException",
                    Thread.class, Throwable.class, new LogHook(pkg));
            XposedBridge.log(TAG + ": crash logger armed for " + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed: " + t);
        }
    }

    private static final class LogHook extends XC_MethodHook {
        private final String mPkg;

        LogHook(String pkg) {
            mPkg = pkg;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Throwable throwable = (Throwable) param.args[1];
            if (throwable == null) {
                return;
            }
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            String stack = writer.toString();
            // Keep each log line short enough for logcat.
            for (String line : stack.split("\n")) {
                if (!line.trim().isEmpty()) {
                    XposedBridge.log(TAG + "[" + mPkg + "] " + line.trim());
                }
            }
            Log.e(TAG, "uncaught in " + mPkg + ": " + throwable);
        }
    }
}
