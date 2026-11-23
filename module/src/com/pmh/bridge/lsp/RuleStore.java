// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * PMH rule file: /data/local/tmp/pmh_rules.json
 *
 * Everything the helper app configures lives here as JSON, because system
 * properties cap a value at 92 bytes and the per-activity rules overflow that
 * almost immediately.
 *
 * The file is small and read on demand; the parsed copy is cached per process and
 * refreshed when the file's mtime changes, so the helper app can change settings
 * without forcing a restart of every target process.
 */
final class RuleStore {
    private static final String TAG = "PmhRules";

    static final String PATH = "/data/local/tmp/pmh_rules.json";

    private static JSONObject sRoot;
    private static long sMtime;
    private static long sSize = -1;

    private RuleStore() {
    }

    private static synchronized JSONObject root() {
        File file = new File(PATH);
        long mtime = file.lastModified();
        long size = file.length();
        if (sRoot != null && mtime == sMtime && size == sSize) {
            return sRoot;
        }
        JSONObject parsed = null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[(int) Math.min(size, 1 << 20)];
            int n = in.read(buf);
            if (n > 0) {
                parsed = new JSONObject(new String(buf, 0, n, "UTF-8"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "rules unavailable: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        sRoot = parsed;
        sMtime = mtime;
        sSize = size;
        return sRoot;
    }

    static boolean loaded() {
        return root() != null;
    }

    private static JSONObject object(JSONObject parent, String key) {
        return parent == null ? null : parent.optJSONObject(key);
    }

    private static JSONObject pkgObject(String pkg) {
        JSONObject packages = object(root(), "packages");
        return object(packages, pkg);
    }

    /**
     * Should this activity start immersive?
     *
     * Resolution order: activity rule, then package "forceXr", then false.
     */
    static boolean isXr(String pkg, String activity) {
        JSONObject pkgNode = pkgObject(pkg);
        if (pkgNode == null) {
            return false;
        }
        JSONObject activities = object(pkgNode, "activities");
        JSONObject entry = object(activities, activity);
        if (entry != null && entry.has("xr")) {
            return entry.optBoolean("xr", false);
        }
        return pkgNode.optBoolean("forceXr", false);
    }

    /** Per-app option, e.g. option("com.pvr.swift", "swift.generation", 2). */
    static int optionInt(String pkg, String key, int fallback) {
        JSONObject pkgNode = pkgObject(pkg);
        if (pkgNode == null) {
            return fallback;
        }
        JSONObject options = object(pkgNode, "options");
        if (options == null) {
            return fallback;
        }
        return options.optInt(key, fallback);
    }

    static String optionString(String pkg, String key, String fallback) {
        JSONObject pkgNode = pkgObject(pkg);
        if (pkgNode == null) {
            return fallback;
        }
        JSONObject options = object(pkgNode, "options");
        if (options == null) {
            return fallback;
        }
        String value = options.optString(key, fallback);
        return value == null || value.isEmpty() ? fallback : value;
    }

    static boolean optionBool(String pkg, String key, boolean fallback) {
        JSONObject pkgNode = pkgObject(pkg);
        if (pkgNode == null) {
            return fallback;
        }
        JSONObject options = object(pkgNode, "options");
        if (options == null) {
            return fallback;
        }
        return options.optBoolean(key, fallback);
    }

    /** Global identity value, e.g. identity("serial"). */
    static String identity(String key, String fallback) {
        JSONObject identity = object(root(), "identity");
        if (identity == null) {
            return fallback;
        }
        String value = identity.optString(key, fallback);
        return value == null || value.isEmpty() ? fallback : value;
    }


    /**
     * Which packages get a PICO identity.
     *
     * Spoofing Build fields for every PICO app broke apps that branch on the real
     * Horizon device (pui_matrix crashed once), so the default is a short list of
     * packages that actually need a PICO identity to work, plus anything the rule
     * file lists under identity.apply or gives its own identity block.
     */
    private static final String[] DEFAULT_SPOOF = {
        "com.picovr.picostreamassistant",
        "com.picovr.store",
        "com.picovr.vrusercenter",
        "com.pvr.swift",
    };

    static boolean shouldSpoof(String pkg) {
        JSONObject pkgNode = pkgObject(pkg);
        if (pkgNode != null && pkgNode.optJSONObject("identity") != null) {
            return true;
        }
        JSONObject identity = object(root(), "identity");
        org.json.JSONArray apply = identity == null ? null : identity.optJSONArray("apply");
        if (apply != null) {
            for (int i = 0; i < apply.length(); ++i) {
                if (pkg.equals(apply.optString(i, ""))) {
                    return true;
                }
            }
            return false;
        }
        for (String candidate : DEFAULT_SPOOF) {
            if (candidate.equals(pkg)) {
                return true;
            }
        }
        return false;
    }


    /** Persistent apps whose background service starts must not crash them. */
    static boolean guardServices(String pkg) {
        if ("com.bytedance.pico.matrix".equals(pkg)) {
            return true;
        }
        JSONObject pkgNode = pkgObject(pkg);
        return pkgNode != null && pkgNode.optBoolean("guardServices", false);
    }

    /** Optional per-package identity override, used by Connect's model branch. */
    static String identityForPackage(String pkg, String key, String fallback) {
        JSONObject pkgNode = pkgObject(pkg);
        if (pkgNode != null) {
            JSONObject identity = object(pkgNode, "identity");
            if (identity != null) {
                String value = identity.optString(key, "");
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return identity(key, fallback);
    }
}
