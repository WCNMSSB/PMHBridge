// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge;

import android.util.Log;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.FileInputStream;

/**
 * Client side of the PMH rule file.
 *
 * The same JSON the LSPosed module reads is edited here: /data/local/tmp/pmh_rules.json.
 * Reads need no root; writes go through `su -c cat > file` because the file lives
 * outside the app sandbox.
 *
 * Defaults are kept in code so a missing or broken file never blocks the UI.
 */
final class Rules {
    private static final String TAG = "PmhRulesClient";

    static final String PATH = "/data/local/tmp/pmh_rules.json";

    private Rules() {
    }

    /** Reads the rule file; returns an empty object when absent. */
    static JSONObject load() {
        try {
            FileInputStream in = new FileInputStream(PATH);
            byte[] buf = new byte[1 << 20];
            int n = in.read(buf);
            in.close();
            if (n <= 0) {
                return new JSONObject();
            }
            return new JSONObject(new String(buf, 0, n, "UTF-8"));
        } catch (Throwable t) {
            Log.i(TAG, "load failed: " + t);
            return new JSONObject();
        }
    }

    /** Writes the rule file as root. Returns true when the write verifies. */
    static boolean save(JSONObject root) {
        String json;
        try {
            json = root.toString(2);
        } catch (Throwable t) {
            json = root.toString();
        }
        String[] candidates = {"/product/bin/su", "su", "/system/bin/su"};
        for (String su : candidates) {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{
                        su, "-c", "cat > " + PATH + " && chmod 644 " + PATH});
                DataOutputStream out = new DataOutputStream(process.getOutputStream());
                out.writeBytes(json);
                out.flush();
                out.close();
                process.waitFor();
                if (verify(json)) {
                    return true;
                }
            } catch (Throwable t) {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        return false;
    }

    private static boolean verify(String expected) {
        try {
            FileInputStream in = new FileInputStream(PATH);
            byte[] buf = new byte[1 << 20];
            int n = in.read(buf);
            in.close();
            if (n <= 0) {
                return false;
            }
            JSONObject got = new JSONObject(new String(buf, 0, n, "UTF-8"));
            return got.toString().equals(new JSONObject(expected).toString());
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- helpers used by the settings pages ----------------------------------

    static JSONObject section(JSONObject root, String key) {
        JSONObject node = root.optJSONObject(key);
        if (node == null) {
            node = new JSONObject();
            try {
                root.put(key, node);
            } catch (Throwable ignored) {
            }
        }
        return node;
    }

    static JSONObject packageNode(JSONObject root, String pkg) {
        JSONObject packages = section(root, "packages");
        JSONObject node = packages.optJSONObject(pkg);
        if (node == null) {
            node = new JSONObject();
            try {
                packages.put(pkg, node);
            } catch (Throwable ignored) {
            }
        }
        return node;
    }

    static JSONObject activityNode(JSONObject root, String pkg, String activity) {
        JSONObject pkgNode = packageNode(root, pkg);
        JSONObject activities = section(pkgNode, "activities");
        JSONObject node = activities.optJSONObject(activity);
        if (node == null) {
            node = new JSONObject();
            try {
                activities.put(activity, node);
            } catch (Throwable ignored) {
            }
        }
        return node;
    }

    static boolean activityXr(JSONObject root, String pkg, String activity) {
        JSONObject node = activityNode(root, pkg, activity);
        return node.optBoolean("xr", false);
    }

    static void setActivityXr(JSONObject root, String pkg, String activity, boolean xr) {
        JSONObject node = activityNode(root, pkg, activity);
        try {
            node.put("xr", xr);
        } catch (Throwable ignored) {
        }
    }

    // ---- identity ------------------------------------------------------------

    static String identity(JSONObject root, String key, String fallback) {
        JSONObject node = section(root, "identity");
        String value = node.optString(key, "");
        return value == null || value.isEmpty() ? fallback : value;
    }

    static int identityInt(JSONObject root, String key, int fallback) {
        try {
            return Integer.parseInt(identity(root, key, String.valueOf(fallback)));
        } catch (Throwable t) {
            return fallback;
        }
    }

    static void setIdentity(JSONObject root, String key, String value) {
        JSONObject node = section(root, "identity");
        try {
            node.put(key, value);
        } catch (Throwable ignored) {
        }
    }

    // ---- per-app options -----------------------------------------------------

    static boolean optionBool(JSONObject root, String pkg, String key, boolean fallback) {
        JSONObject options = section(packageNode(root, pkg), "options");
        return options.optBoolean(key, fallback);
    }

    static int optionInt(JSONObject root, String pkg, String key, int fallback) {
        JSONObject options = section(packageNode(root, pkg), "options");
        return options.optInt(key, fallback);
    }

    static void setOption(JSONObject root, String pkg, String key, boolean value) {
        JSONObject options = section(packageNode(root, pkg), "options");
        try {
            options.put(key, value);
        } catch (Throwable ignored) {
        }
    }

    static void setOption(JSONObject root, String pkg, String key, int value) {
        JSONObject options = section(packageNode(root, pkg), "options");
        try {
            options.put(key, value);
        } catch (Throwable ignored) {
        }
    }
}
