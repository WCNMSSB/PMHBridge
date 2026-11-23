// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.content.ContentResolver;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Presents a PICO device identity to PICO apps.
 *
 * Two things are needed at once:
 *
 * 1. Build.getSerial() must answer with a PICO-style serial, otherwise PICO's
 *    account SDK throws SecurityException on Horizon OS.
 * 2. Brand/model/device/fingerprint must look like PICO, because the SDKs branch
 *    on them (Connect picks its rendering path from the model).
 *
 * Values come from the PMH rule file, with an optional per-package override so a
 * single app can pretend to be an older model when its newer code path does not
 * work here.
 */
final class IdentityCompat {
    private static final String TAG = "PmhIdentity";

    private static final String[] FIELDS = {
        "BRAND", "MANUFACTURER", "MODEL", "DEVICE", "PRODUCT",
        "BOARD", "FINGERPRINT", "ID", "DISPLAY", "HARDWARE",
    };

    private IdentityCompat() {
    }

    static void install(String pkg) {
        String serial = RuleStore.identityForPackage(pkg, "serial", "PA9X2C3D4E5F6");
        try {
            XposedHelpers.findAndHookMethod(Build.class, "getSerial", new SerialHook(serial));
            setStatic(Build.class, "SERIAL", serial);
        } catch (Throwable t) {
            log("serial hook failed: " + t);
        }

        // PICO apps sometimes read a "pui" version property; expose the configured one.
        String puiVersion = RuleStore.identityForPackage(pkg, "puiVersion", "");
        if (puiVersion != null && !puiVersion.isEmpty()) {
            try {
                Class<?> props = XposedHelpers.findClass("android.os.SystemProperties", Build.class.getClassLoader());
                XposedHelpers.findAndHookMethod(props, "get", String.class,
                        new PuiHook(puiVersion));
                XposedHelpers.findAndHookMethod(props, "get", String.class, String.class,
                        new PuiHook(puiVersion));
                log("puiVersion " + puiVersion + " exposed");
            } catch (Throwable t) {
                log("pui hook failed: " + t);
            }
        }

        Map<String, String> values = new HashMap<>();
        for (String field : FIELDS) {
            String key = field.toLowerCase(java.util.Locale.ROOT);
            String value = RuleStore.identityForPackage(pkg, key, "");
            if (value != null && !value.isEmpty()) {
                values.put(field, value);
                setStatic(Build.class, field, value);
            }
        }
        // Many PICO SDKs derive their "UDID" from ANDROID_ID or the IMEI and then
        // refuse to continue when it is null (Motion Tracker dies with
        // "getUDID() must not be null"). A stable synthetic value is enough.
        try {
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString",
                    ContentResolver.class, String.class, new XcAndroidIdHook(pkg));
        } catch (Throwable t) {
            log("android_id hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(TelephonyManager.class, "getDeviceId", new FixedStringHook());
            XposedHelpers.findAndHookMethod(TelephonyManager.class, "getImei", new FixedStringHook());
        } catch (Throwable ignored) {
        }

        installPropertyHook(Build.class.getClassLoader(), pkg);

        if (!values.isEmpty()) {
            log("identity applied for " + pkg + ": " + values);
        }
    }

    /** Property names the PICO SDKs read directly instead of going through Build. */
    private static final String[][] PROP_MAP = {
        {"ro.product.brand", "brand"},
        {"ro.product.manufacturer", "manufacturer"},
        {"ro.product.model", "model"},
        {"ro.product.device", "device"},
        {"ro.product.name", "name"},
        {"ro.product.board", "board"},
        {"ro.build.fingerprint", "fingerprint"},
        {"ro.build.id", "id"},
        {"ro.build.display.id", "display"},
        {"ro.hardware", "hardware"},
        {"ro.board.platform", "board"},
        {"ro.soc.model", "socModel"},
        {"ro.soc.manufacturer", "socManufacturer"},
        {"ro.build.version.sdk", "sdk"},
        {"ro.pico.tag", "picoTag"},
    };

    /** Serves the PICO 4 Ultra property values to the title's SDK. */
    private static void installPropertyHook(ClassLoader cl, String pkg) {
        try {
            Class<?> props = XposedHelpers.findClass("android.os.SystemProperties", cl);
            XposedBridge.hookAllMethods(props, "get", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object first = param.args.length > 0 ? param.args[0] : null;
                    if (!(first instanceof String)) {
                        return;
                    }
                    String key = (String) first;
                    for (String[] pair : PROP_MAP) {
                        if (!pair[0].equals(key)) {
                            continue;
                        }
                        String value = RuleStore.identityForPackage(pkg, pair[1], "");
                        if (value != null && !value.isEmpty()) {
                            param.setResult(value);
                        }
                        return;
                    }
                }
            });
            log("property template active");
        } catch (Throwable t) {
            log("property template failed: " + t);
        }
    }

    private static void setStatic(Class<?> cls, String name, String value) {
        try {
            XposedHelpers.setStaticObjectField(cls, name, value);
        } catch (Throwable ignored) {
        }
    }

    /** Answers Build.getSerial with the configured PICO serial. */
    private static final class SerialHook extends XC_MethodHook {
        private final String mSerial;

        SerialHook(String serial) {
            mSerial = serial;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(mSerial);
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    /** Returns a stable synthetic ANDROID_ID. */
    private static final class XcAndroidIdHook extends XC_MethodHook {
        private final String mPkg;

        XcAndroidIdHook(String pkg) {
            mPkg = pkg;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if ("android_id".equals(param.args[1])) {
                param.setResult("pmh" + Integer.toHexString(mPkg.hashCode()) + "00000000");
            }
        }
    }

    /** Fixed IMEI so SDKs that require a non-null device id get one. */
    private static final class FixedStringHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult("000000000000000");
        }
    }

    /** Answers PUI-version property lookups with the configured value. */
    private static final class PuiHook extends XC_MethodHook {
        private final String mVersion;

        PuiHook(String version) {
            mVersion = version;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String key = (String) param.args[0];
            if (key != null && key.toLowerCase(java.util.Locale.ROOT).contains("pui")) {
                param.setResult(mVersion);
            }
        }
    }
}
