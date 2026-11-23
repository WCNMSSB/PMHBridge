package com.pmh.bridge.lsp;

import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Talks to the OpenXR loader from inside a host app.
 *
 * Horizon OS will not launch a standalone probe activity, so the check runs in
 * the PICO tracker app process: initialise the loader with this app's VM and
 * context, then create an instance. That makes the loader enumerate implicit
 * API layers, and PMHBridge's layer logs show up under the PMHXRLayer tag.
 */
public final class XrProbe {

    private static final String TAG = "PmhXrProbe";
    private static boolean sRan;

    private XrProbe() {
    }

    private static native String probe(Context context);

    public static void runOnce(final Context context) {
        synchronized (XrProbe.class) {
            if (sRan || context == null) {
                return;
            }
            sRan = true;
        }
        try {
            load(context);
        } catch (Throwable t) {
            log("loadLibrary failed: " + t);
            return;
        }
        try {
            String result = probe(context);
            log("probe: " + result);
        } catch (Throwable t) {
            log("probe failed: " + t);
        }
    }

    /** Real VR clients that can actually initialise the runtime. */
    private static final String[] PROBE_TARGETS = {
        "com.meta.curio.toybox",
        "com.meta.curio.ruler",
        "com.oculus.igvr",
        "com.vrchat.android",
        "com.oculus.vrshell",
        "com.pico.xr.openxr_runtime",
        "com.picoxr.ControllerTest",
    };

    public static boolean isProbeTarget(String pkg) {
        for (String target : PROBE_TARGETS) {
            if (target.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** Called from the module without a context: pulls the app from Xposed. */
    public static void runFromCurrentApp(final ClassLoader cl) {
        try {
            Object app = null;
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread", false, cl);
                app = activityThread.getMethod("currentApplication").invoke(null);
            } catch (Throwable ignored) {
            }
            if (app == null) {
                Class<?> helper = Class.forName("android.app.AndroidAppHelper", false, cl);
                app = helper.getMethod("currentApplication").invoke(null);
            }
            if (app instanceof Context) {
                runOnce((Context) app);
            } else {
                log("no application yet");
            }
        } catch (Throwable t) {
            log("currentApplication failed: " + t);
        }
    }

    /** Loads the module's own .so, which lives in the module APK's lib dir. */
    private static void load(Context context) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        java.io.File own = new java.io.File(context.getFilesDir(), "libxrprobe_v5.so");
        if (!own.exists() || own.length() == 0) {
            extract(context, own);
        }
        try {
            // The app can always read its own data dir, unlike /data/local/tmp.
            System.load(own.getAbsolutePath());
            log("loaded " + own.getAbsolutePath());
            return;
        } catch (Throwable t) {
            log("own copy failed: " + t);
        }
        try {
            System.load("/data/local/tmp/libxrprobe.so");
            return;
        } catch (Throwable ignored) {
        }
        try {
            android.content.pm.ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo("com.pmh.bridge.lsp", 0);
            if (info.nativeLibraryDir != null) {
                System.load(info.nativeLibraryDir + "/libxrprobe.so");
                return;
            }
        } catch (Throwable t) {
            last = new ClassNotFoundException("nativeLibraryDir: " + t);
        }
        try {
            System.loadLibrary("xrprobe");
        } catch (Throwable t) {
            throw last != null ? last : new ClassNotFoundException(String.valueOf(t));
        }
    }

    /** Copies the packaged probe library into the app's files dir. */
    private static void extract(Context context, java.io.File target) {
        try {
            // The Context here is the host app, so its assets do not contain our
            // probe: read the module APK itself.
            String apk = context.getPackageManager()
                    .getApplicationInfo("com.pmh.bridge.lsp", 0).sourceDir;
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk);
            java.io.InputStream in = zip.getInputStream(
                    zip.getEntry("assets/libxrprobe_v5.so"));
            java.io.FileOutputStream out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            target.setReadable(true, false);
            target.setExecutable(true, false);
            log("extracted " + target.length() + " bytes");
        } catch (Throwable t) {
            log("extract failed: " + t);
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
