// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keeps PICO's OpenXR runtime service alive.
 *
 * The runtime is normally started at boot by PICO OS and never calls
 * startForeground itself. On Horizon we launch it by hand, so the platform kills
 * it after five seconds with
 *
 *   ForegroundServiceDidNotStartInTimeException
 *
 * and every attempt to bring the runtime up dies the same way. This hook does the
 * startForeground call on the service's behalf as soon as it is created.
 */
final class XrServiceHook {
    private static final String TAG = "PmhXrService";
    private static final String CHANNEL_ID = "pmh_xr_runtime";

    private XrServiceHook() {
    }

    static void install(ClassLoader cl, String pkg) {
        try {
            XposedHelpers.findAndHookMethod(Service.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    if (!(self instanceof Service)) {
                        return;
                    }
                    Service service = (Service) self;
                    try {
                        NotificationManager manager =
                                service.getSystemService(NotificationManager.class);
                        if (manager != null
                                && manager.getNotificationChannel(CHANNEL_ID) == null) {
                            manager.createNotificationChannel(new NotificationChannel(
                                    CHANNEL_ID, "PICO XR runtime",
                                    NotificationManager.IMPORTANCE_LOW));
                        }
                        Notification notification = new Notification.Builder(service, CHANNEL_ID)
                                .setContentTitle("PICO XR runtime")
                                .setSmallIcon(android.R.drawable.ic_menu_compass)
                                .setOngoing(true)
                                .build();
                        service.startForeground(1, notification);
                        Log.i(TAG, "startForeground issued for "
                                + service.getClass().getSimpleName());
                    } catch (Throwable t) {
                        Log.i(TAG, "startForeground failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": service hook installed for " + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed: " + t);
        }
    }
}
