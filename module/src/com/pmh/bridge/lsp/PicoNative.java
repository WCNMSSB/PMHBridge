package com.pmh.bridge.lsp;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import java.io.File;

import dalvik.system.DexClassLoader;
import de.robv.android.xposed.XposedBridge;

/**
 * Hosts PICO's own Bluetooth peripheral stack inside the tracker app process.
 *
 * Horizon OS refuses to let a normal app register the {@code peripheral_service}
 * system service, which is what PICO's privileged app does. Rather than
 * reimplement their protocol we load their classes and native parser from the
 * installed privileged APK and drive them directly: discovery hands them a
 * BluetoothDevice, and everything after that (GATT handshake, pairing, IMU
 * decoding, battery) is PICO's own code.
 */
public final class PicoNative {

    private static final String TAG = "PmhPicoNative";
    private static final String APP_DIR = "/system/priv-app/BluetoothPeripheralService";
    private static final String APK = APP_DIR + "/BluetoothPeripheralService.apk";
    private static final String LIB_DIR = APP_DIR + "/lib/arm64";

    private static ClassLoader sLoader;
    private static Object sConnectManager;
    private static boolean sLoaded;

    private PicoNative() {
    }

    /**
     * PICO's native parser registers a JNI_OnLoad for their own service class,
     * so loading it into another process aborts the app. Until that is solved
     * the native path stays disabled and PMHBridge uses its own GATT stack.
     */
    private static final boolean ENABLED = false;

    public static synchronized void start(Context context) {
        if (!ENABLED) {
            return;
        }
        if (sConnectManager != null) {
            return;
        }
        if (!new File(APK).exists()) {
            log("PICO apk missing at " + APK);
            return;
        }
        try {
            loadNative();
            sLoader = new DexClassLoader(APK, context.getCodeCacheDir().getAbsolutePath(),
                    LIB_DIR, context.getClassLoader());

            Class<?> configCls = Class.forName(
                    "com.pvr.btperipheral.manager.PeripheralConfigManager", true, sLoader);
            Object config = configCls.getConstructor(Context.class).newInstance(context);

            Class<?> remoteCls = Class.forName(
                    "com.pvr.btperipheral.RemoteDevices", true, sLoader);
            Object remote = allocate(remoteCls);

            Class<?> connectCls = Class.forName(
                    "com.pvr.btperipheral.ble.BleCentralConnectManager", true, sLoader);
            Object manager = connectCls
                    .getConstructor(Context.class, remoteCls, configCls)
                    .newInstance(context, remote, config);
            sConnectManager = manager;
            log("PICO connect manager ready");
        } catch (Throwable t) {
            log("start failed: " + unwrap(t));
        }
    }

    /** PICO's code handles the device from here on. */
    public static synchronized void connect(BluetoothDevice device) {
        if (!ENABLED) {
            return;
        }
        if (sConnectManager == null || device == null) {
            return;
        }
        try {
            sConnectManager.getClass()
                    .getMethod("connectGatt", BluetoothDevice.class, boolean.class)
                    .invoke(sConnectManager, device, false);
            log("PICO connectGatt " + device.getAddress());
        } catch (Throwable t) {
            log("connectGatt failed: " + unwrap(t));
        }
    }

    /** Auto-reconnect path for already bonded trackers. */
    public static synchronized void autoConnectAll() {
        if (sConnectManager == null) {
            return;
        }
        try {
            sConnectManager.getClass().getMethod("autoConnectAllDevices").invoke(sConnectManager);
            log("PICO autoConnectAllDevices");
        } catch (Throwable t) {
            log("autoConnect failed: " + unwrap(t));
        }
    }

    private static void loadNative() {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        // Both libraries are exposed through public.libraries.txt by the
        // PMHBridge system module so an app namespace can resolve them.
        // libalog.so is a JNI library: loading it eagerly fails with JNI_ERR.
        // The dynamic linker resolves it by soname from public.libraries instead.
        for (String name : new String[]{"libbtperipheral_jni.so"}) {
            try {
                System.load(LIB_DIR + "/" + name);
                log("loaded " + name);
            } catch (Throwable t) {
                try {
                    System.loadLibrary(name.substring(3, name.length() - 3));
                    log("loaded by name " + name);
                } catch (Throwable t2) {
                    log("load failed " + name + ": " + t + " / " + t2);
                }
            }
        }
    }

    private static Object allocate(Class<?> cls) throws Exception {
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = unsafeCls.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return unsafeCls.getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
