// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
package com.pmh.bridge.lsp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Binder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Provides a stand-in for PICO's "peripheral_service".
 *
 * The Motion Tracker app looks the BLE peripheral manager up through
 * ServiceManager rather than binding an APK:
 *
 *     avc: denied { find } ... uid=10202 name=peripheral_service
 *
 * On PICO OS that service is registered by the platform; on Horizon OS nothing
 * registers it, so the app gets null and pairing aborts. Returning a local Binder
 * lets the app continue, and every transaction it makes is logged so the AIDL
 * surface can be reconstructed step by step.
 */
final class PeripheralServiceStub {
    private static final String TAG = "PmhPeripheral";
    private static final String SERVICE = "peripheral_service";

    private static volatile Binder sStub;
    private static int sLogged;

    /** Trackers seen by our own BLE scan, in discovery order (address -> name). */
    private static final java.util.LinkedHashMap<String, String> sDevices =
            new java.util.LinkedHashMap<>();
    /** Callback binder the app registered for scan results (IPeripheralScanCallback). */
    private static volatile android.os.IBinder sScanCallback;

    private PeripheralServiceStub() {
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> serviceManager = XposedHelpers.findClass("android.os.ServiceManager", cl);
            int hooked = 0;
            for (Method method : serviceManager.getDeclaredMethods()) {
                if (!"getService".equals(method.getName())
                        && !"getServiceOrThrow".equals(method.getName())) {
                    continue;
                }
                XposedBridge.hookMethod(method, new LookupHook());
                ++hooked;
            }
            log("peripheral service stub installed (" + hooked + " lookup(s))");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
        installDeviceSetHook(cl);
        // Populate before the app's first sync: the "my trackers" list is built
        // once during onResume and would otherwise start empty.
        seedBonded();
    }

    /**
     * Replaces the adapter's device sets with objects built from our own MACs.
     *
     * The AIDL array reply round-trips through the app's PeripheralDevice creator
     * and comes back with a null address on Horizon, which makes the app's HashSet
     * throw on hashCode(); building the objects in-process avoids that path.
     */
    private static void installDeviceSetHook(final ClassLoader cl) {
        try {
            final Class<?> devCls = Class.forName("com.pvr.btperipheral.api.PeripheralDevice",
                    false, cl);
            Class<?> adapter = Class.forName("com.pvr.btperipheral.api.c", false, cl);
            int hooked = 0;
            for (final Method method : adapter.getDeclaredMethods()) {
                if (!"c".equals(method.getName()) && !"d".equals(method.getName())) {
                    continue;
                }
                if (method.getParameterTypes().length != 0
                        || !java.util.Set.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            java.util.LinkedHashSet<Object> set = new java.util.LinkedHashSet<>();
                            for (String mac : snapshotDevices()) {
                                set.add(devCls.getConstructor(String.class)
                                        .newInstance(mac));
                            }
                            if (!set.isEmpty()) {
                                param.setResult(java.util.Collections.unmodifiableSet(set));
                                log(method.getName() + " -> " + set.size() + " device(s)");
                            }
                        } catch (Throwable t) {
                            log("device set hook failed: " + t);
                        }
                    }
                });
                ++hooked;
            }
            if (hooked > 0) {
                log("device set hook installed (" + hooked + ")");
            }
        } catch (Throwable t) {
            log("device set hook install failed: " + t);
        }
    }

    /** Application context, used by the per-tracker GATT links. */
    static android.content.Context appContext() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return app instanceof android.content.Context ? (android.content.Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called by TrackerLink when a tracker's GATT link comes up. */
    static void onLinkConnected(String mac) {
        sLinkedMac = mac;
        log("link up " + mac + " total=" + TrackerLink.linkCount());
    }

    static void onLinkDisconnected(String mac) {
        if (mac != null && mac.equals(sLinkedMac)) {
            sLinkedMac = null;
        }
        log("link down " + mac);
    }

    private static volatile String sLinkedMac;

    /** MAC of the tracker we currently hold a GATT link to, or null. */
    static String linkedMac() {
        return sLinkedMac;
    }

    /** MACs of every tracker we know about, for other module classes. */
    static java.util.List<String> deviceMacs() {
        return snapshotDevices();
    }

    private static java.util.List<String> snapshotDevices() {
        synchronized (sDevices) {
            return new java.util.ArrayList<>(sDevices.keySet());
        }
    }

    private static Binder stub() {
        Binder local = sStub;
        if (local == null) {
            synchronized (PeripheralServiceStub.class) {
                if (sStub == null) {
                    sStub = new StubBinder();
                }
                local = sStub;
            }
        }
        return local;
    }

    private static final class LookupHook extends XC_MethodHook {
        /** Meta's own peripheral service on Horizon OS, used as a redirect target. */
        private static final String QUEST_SERVICE = "bluetooth_peripheral";

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args.length == 0 || !SERVICE.equals(param.args[0])) {
                return;
            }
            android.os.IBinder real = RuleStore.optionBool(
                    "com.pvr.swift", "peripheral.redirect", false)
                    ? queryQuestService(param) : null;
            if (real != null) {
                param.setResult(real);
                log("redirected " + SERVICE + " -> " + QUEST_SERVICE);
                return;
            }
            param.setResult(stub());
            log("returned stub for " + SERVICE);
        }

        private android.os.IBinder queryQuestService(MethodHookParam param) {
            try {
                Object result = XposedBridge.invokeOriginalMethod(
                        (Method) param.method, param.thisObject,
                        new Object[]{QUEST_SERVICE});
                // Remote services come back as BinderProxy, not Binder, so accept any
                // IBinder here.
                if (result instanceof android.os.IBinder) {
                    return (android.os.IBinder) result;
                }
                log("quest service lookup returned " + result);
            } catch (Throwable t) {
                log("quest service lookup failed: " + t);
            }
            return null;
        }
    }

    /** Answers every transaction with a default reply and records its code. */
    private static final class StubBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            String detail = "";
            android.os.IBinder callback = null;
            String devMac = null;
            try {
                if (data != null) {
                    detail = " size=" + data.dataSize();
                    // Two layouts show up: PICO's proxies sometimes prepend a
                    // 12-byte header (magic, -1, "TSYS") and sometimes not, so try
                    // every 4-byte offset until the interface token parses.
                    for (int pos = 0; pos <= 24 && callback == null; pos += 4) {
                        try {
                            data.setDataPosition(pos);
                            String token = data.readString();
                            if (token != null && token.contains("IPeripheral")) {
                                if (pos != 0) {
                                    detail += " off=" + pos;
                                }
                                detail += " token=" + token;
                                if (code == 16) {
                                    callback = data.readStrongBinder();
                                    detail += " cb=" + (callback != null);
                                } else if (code >= 6) {
                                    try {
                                        data.readInt();
                                        String mac = data.readString();
                                        if (mac != null && mac.length() == 17
                                                && mac.indexOf(':') == 2) {
                                            devMac = mac;
                                            detail += " mac=" + mac;
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                detail += " parse=" + t.getClass().getSimpleName();
            }
            if (sLogged < 60) {
                ++sLogged;
                if (sLogged <= 3) {
                    try {
                        StringBuilder st = new StringBuilder();
                        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
                        for (int i = 3; i < Math.min(trace.length, 14); ++i) {
                            st.append("\n    at ").append(trace[i]);
                        }
                        Log.i(TAG, "caller of code " + code + ":" + st);
                    } catch (Throwable ignored) {
                    }
                }
                String hex = "";
                try {
                    byte[] raw = data == null ? new byte[0] : data.marshall();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(raw.length, 160); ++i) {
                        sb.append(String.format("%02x", raw[i]));
                    }
                    hex = " hex=" + sb;
                } catch (Throwable ignored) {
                }
                Log.i(TAG, "transaction code=" + code + detail + hex);
                XposedBridge.log(TAG + ": transaction code=" + code + detail + hex);
            }
            BleProbe.startOnce();
            if (reply != null) {
                try {
                    // Replies are plain Parcels: the client starts with
                    // readException(), so no TSYS header may precede the status.
                    reply.writeNoException();
                    writeReply(code, callback, devMac, reply);
                } catch (Throwable t) {
                    Log.i(TAG, "reply failed: " + t);
                }
            }
            return true;
        }
    }

    /**
     * Shapes the reply for each AIDL transaction code.
     *
     * Codes and return types come from the app's own IPeripheral proxy:
     *   4/5   -> PeripheralDevice[] (each item is just a MAC string)
     *   11    -> battery, 14/15 -> connected/bonded, 16/17 -> scan callback
     */
    private static void writeReply(int code, android.os.IBinder cb, String devMac, Parcel reply) {
        switch (code) {
            case 4: {
                reply.writeInt(0);
                log("reply 4 -> 0 bonded (app-side set hook supplies devices)");
                return;
            }
            case 5: {
                java.util.List<String> macs = new java.util.ArrayList<>(sDevices.keySet());
                reply.writeInt(macs.size());
                for (String mac : macs) {
                    reply.writeString(mac);
                }
                log("reply 5 -> " + macs.size() + " scanned");
                return;
            }
            case 16: {
                if (cb != null) {
                    sScanCallback = cb;
                    log("scan callback registered");
                    seedBonded();
                    pushScanResult(cb, true);
                } else {
                    log("scan callback missing");
                }
                reply.writeInt(1);
                return;
            }
            case 17:
                sScanCallback = null;
                reply.writeInt(1);
                return;
            case 6: {
                String name = null;
                if (devMac != null) {
                    synchronized (sDevices) {
                        name = sDevices.get(devMac);
                    }
                }
                reply.writeString(name == null || name.isEmpty() ? "Pico Swift" : name);
                return;
            }
            case 7:
                reply.writeString("");
                if (devMac != null) {
                    connectTracker(devMac);
                }
                return;
            case 8:
            case 9:
            case 10:
                reply.writeString("");
                return;
            case 11:
                reply.writeInt(GattProbe.sBattery >= 0 ? GattProbe.sBattery : 100);
                return;
            case 12: {
                // 2 = connected. Only report that for the tracker we hold a link to.
                boolean linked = devMac != null && devMac.equals(GattProbe.sLinked);
                reply.writeInt(linked ? 2 : 0);
                return;
            }
            case 13:
                reply.writeInt(0);
                return;
            case 20:
            case 23:
            case 25:
            case 29:
                reply.writeInt(1);
                return;
            default:
                reply.writeInt(1);
        }
    }

    /** Opens the GATT link the app asked for when the user taps "connect". */
    private static void connectTracker(String mac) {
        try {
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return;
            }
            android.bluetooth.BluetoothDevice dev = adapter.getRemoteDevice(mac);
            log("connect requested " + mac);
            GattProbe.connectOnce(dev);
        } catch (Throwable t) {
            log("connect failed: " + t);
        }
    }

    /**
     * Adds already bonded trackers to the discovery list.
     *
     * A tracker that is connected to this headset stops advertising, so a scan
     * cannot see it; the bonded table is the only place it still shows up.
     */
    private static void seedBonded() {
        try {
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return;
            }
            java.util.Set<android.bluetooth.BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) {
                return;
            }
            for (android.bluetooth.BluetoothDevice dev : bonded) {
                String name = null;
                try {
                    name = dev.getName();
                } catch (Throwable ignored) {
                }
                if (name != null && name.startsWith("Pico Swift")) {
                    // Only record here; the GATT link is opened from a scan result,
                    // because a RemoteDevice handle connects with status 133.
                    remember(dev.getAddress(), name);
                }
            }
        } catch (Throwable t) {
            log("seed bonded failed: " + t);
        }
    }

    /** Calls IPeripheralScanCallback.onDeviceFound(present, device) for every tracker. */
    private static void pushScanResult(android.os.IBinder cb, boolean present) {
        java.util.List<String> macs;
        synchronized (sDevices) {
            macs = new java.util.ArrayList<>(sDevices.keySet());
        }
        for (String mac : macs) {
            Parcel p = Parcel.obtain();
            Parcel r = Parcel.obtain();
            try {
                // The callback stub validates the interface token against the
                // descriptor it attached, so the parcel must start with it.
                p.writeInterfaceToken("com.pvr.btperipheral.api.IPeripheralScanCallback");
                p.writeInt(present ? 1 : 0);
                p.writeString(mac);
                cb.transact(1, p, r, 0);
                log("pushed " + mac);
            } catch (Throwable t) {
                log("callback push failed: " + t);
            } finally {
                p.recycle();
                r.recycle();
            }
        }
    }

    private static int s16(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
        appendToFile(msg);
    }

    /**
     * Keeps a durable capture on shared storage so a short tracker wake window is
     * not lost when the log buffer rotates.
     */
    private static void appendToFile(String msg) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    "/sdcard/pmh_ble.log", true);
            String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS",
                    java.util.Locale.US).format(new java.util.Date())
                    + " " + msg + "\n";
            out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Remembers a discovered tracker and forwards it to the app's scan callback.
     */
    /** Entry point for PICO's own scan manager running in this process. */
    static void onNativeDevice(String mac, String name) {
        if (mac == null) {
            return;
        }
        remember(mac, name);
        PoseStore.setTrackers(snapshotDevices());
        log("pico-native device " + mac + " " + name);
        android.os.IBinder cb = sScanCallback;
        if (cb != null) {
            pushScanResult(cb, true);
        }
    }

    private static void remember(String mac, String name) {
        boolean added = false;
        synchronized (sDevices) {
            if (!sDevices.containsKey(mac)) {
                sDevices.put(mac, name == null ? "" : name);
                added = true;
            }
        }
        if (added) {
            // Keep the PoseStore slots in sync so left/right get their own slot.
            PoseStore.setTrackers(snapshotDevices());
            log("tracker remembered " + mac);
            android.os.IBinder cb = sScanCallback;
            if (cb != null) {
                pushScanResult(cb, true);
            }
        }
    }

    /**
     * Diagnostic BLE scan.
     *
     * Runs inside the tracker process, so it inherits the app's BLUETOOTH_SCAN
     * permission. Logs every advertisement so we can tell whether a first-gen
     * tracker is visible to Horizon OS at all, and with which name/UUIDs.
     */

        /** Trackers already bonded to this headset; reconnect without advertising. */
        private static final String[] KNOWN_MACS = {
            "B0:A3:F2:10:47:60",
            "B0:A3:F2:10:47:97",
        };

        static void directConnect(BluetoothAdapter adapter) {
            for (final String mac : KNOWN_MACS) {
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(mac);
                    if (device == null) {
                        continue;
                    }
                    log("gatt: direct connect attempt " + mac + " bond=" + device.getBondState());
                    TrackerLink.connect(device);
                } catch (Throwable t) {
                    log("gatt: direct connect failed " + mac + " " + t);
                }
            }
        }

    static final class BleProbe {
        private static boolean sStarted;
        private static int sFound;

        private static ScanCallback sCallback;
        private static BluetoothLeScanner sScanner;

        static void startOnce() {
            synchronized (BleProbe.class) {
                if (sStarted) {
                    return;
                }
                sStarted = true;
            }
            startScan();
        }

        static void startScan() {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    log("ble probe: adapter unavailable");
                    return;
                }
                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) {
                    log("ble probe: scanner unavailable");
                    return;
                }
                // Bonded trackers often stop advertising, so try a direct GATT
                // connection to the ones we already know before relying on scan.
                try {
                    directConnect(adapter);
                } catch (Throwable t) {
                    log("ble probe: direct connect failed " + t);
                }

                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                sScanner = scanner;
                sCallback = new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        if (result == null || result.getDevice() == null) {
                            return;
                        }
                        BluetoothDevice device = result.getDevice();
                        String name = null;
                        try {
                            name = device.getName();
                        } catch (Throwable ignored) {
                        }
                        boolean isSwift = name != null && name.startsWith("Pico Swift");
                        // Trackers already bonded to this headset may advertise
                        // without a name, so trust the bond state as well.
                        if (!isSwift && device.getBondState()
                                == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                            isSwift = true;
                        }
                        if (result.getScanRecord() != null
                                && result.getScanRecord().getServiceUuids() != null) {
                            for (android.os.ParcelUuid uuid
                                    : result.getScanRecord().getServiceUuids()) {
                                if (uuid.toString().toLowerCase().contains("feff")) {
                                    isSwift = true;
                                }
                            }
                        }
                        if (sFound < 40 && isSwift) {
                            ++sFound;
                            StringBuilder sb = new StringBuilder();
                            sb.append("adv mac=").append(device.getAddress());
                            if (name != null) {
                                sb.append(" name=").append(name);
                            }
                            sb.append(" rssi=").append(result.getRssi());
                            if (result.getScanRecord() != null) {
                                sb.append(" uuids=").append(result.getScanRecord().getServiceUuids());
                            }
                            log(sb.toString());
                        }
                        if (isSwift) {
                            remember(device.getAddress(), name);
                            // PICO's own stack drives pairing and data flow;
                            // our GATT probe stays as a fallback.
                            PicoNative.connect(device);
                            TrackerLink.connect(device);
                        }
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        log("ble probe: scan failed " + errorCode);
                    }
                };
                scanner.startScan(null, settings, sCallback);
                log("ble probe: scanning for 25s");
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (GattProbe.sGatt == null && sScanner != null
                                        && sCallback != null) {
                                    log("ble probe: restart");
                                    try {
                                        sScanner.stopScan(sCallback);
                                    } catch (Throwable ignored) {
                                    }
                                    startScan();
                                }
                            }
                        }, 27000L);
            } catch (Throwable t) {
                log("ble probe failed: " + t);
            }
        }
    }

    /**
     * Connects to one tracker and dumps its GATT table plus the first notifications.
     *
     * This is how the first-gen protocol gets documented: which service carries
     * data, which characteristic notifies, and what the initial packets look like.
     */
    static final class GattProbe {
        private static BluetoothGatt sGatt;
        /** IMU statistics: frames keep flowing after the raw log cap. */
        private static int sImuCount;
        private static long sLastImu;
        /** Address and battery of the tracker we are currently linked to. */
        private static volatile String sLinked;
        private static volatile int sBattery = -1;
        /** Orientation filter fed with every IMU frame. */
        private static final ImuFusion sFusion = new ImuFusion();
        private static long sLastSampleMs;
        private static boolean sConnected;
        private static int sPackets;


        private static boolean sNtfRequested;

        /**
         * The tracker exposes an ASCII command interface: reading ff02 answers
         * "ntf_enable", i.e. the client is expected to send that command to switch
         * notifications on. ff01 is the writable endpoint of that service.
         */
        static void requestNotifications(BluetoothGatt gatt) {
            log("gatt: requestNotifications entered (requested=" + sNtfRequested + ")");
            if (sNtfRequested) {
                return;
            }
            BluetoothGattCharacteristic target = null;
            for (android.bluetooth.BluetoothGattService service : gatt.getServices()) {
                if (service.getUuid().toString().toLowerCase().startsWith("02f00000")) {
                    for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                        if (ch.getUuid().toString().toLowerCase().endsWith("ff01")) {
                            target = ch;
                        }
                    }
                }
            }
            if (target == null) {
                log("gatt: ff01 not found");
                return;
            }
            sNtfRequested = true;
            startSwiftHandshake(gatt);
        }

        /**
         * PICO's own GATT protocol, taken from BluetoothPeripheralService.apk
         * (com.pvr.btperipheral.ble.BleCentralConnectManager):
         *
         *   service  0000fff0-...
         *   write    0783...5cba
         *   notify   0783...5cb8
         *
         * Every request starts with 0xAA:
         *   0xAA 01 00        get tracker info
         *   0xAA 02 01 00     enable IMU notifications
         *   0xAA 03 01/00     set master / slave
         *   0xAA 0A 00        battery
         */
        private static final byte[][] SWIFT_HANDSHAKE = {
            {(byte) 0xAA, 0x01, 0x00},
            {(byte) 0xAA, 0x02, 0x01, 0x00},
            {(byte) 0xAA, 0x03, 0x01},
            {(byte) 0xAA, 0x0A, 0x00},
        };

        static void startSwiftHandshake(final BluetoothGatt gatt) {
            BluetoothGattCharacteristic write = null;
            for (android.bluetooth.BluetoothGattService service : gatt.getServices()) {
                if (!service.getUuid().toString().toLowerCase().startsWith("0000fff0")) {
                    continue;
                }
                for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                    if (ch.getUuid().toString().toLowerCase().endsWith("5cba")) {
                        write = ch;
                    }
                }
            }
            if (write == null) {
                log("swift: 5cba not found");
                return;
            }
            log("swift: handshake start");
            stepSwift(gatt, write, 0);
        }

        static void stepSwift(final BluetoothGatt gatt,
                              final BluetoothGattCharacteristic write,
                              final int index) {
            if (index >= SWIFT_HANDSHAKE.length) {
                log("swift: handshake done");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : SWIFT_HANDSHAKE[index]) {
                sb.append(String.format("%02x", b));
            }
            log("swift: write 5cba " + sb);
            queueWrite(gatt, write, SWIFT_HANDSHAKE[index], false);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            stepSwift(gatt, write, index + 1);
                        }
                    }, 800L);
        }

        /**
         * Writes single-byte command ids 0x00..0x2F to the control endpoint.
         *
         * The radio protocol numbers its commands (1 = product info, 2 = IMU
         * samples, 19 = start IMU), and a one-byte write is what the endpoint
         * answered during the ASCII sweep.
         */
        static void startCommandSweep(final BluetoothGatt gatt,
                                      final BluetoothGattCharacteristic target) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            commandStep(gatt, target, 0);
                        }
                    }, 6000L);
        }

        static void commandStep(final BluetoothGatt gatt,
                                final BluetoothGattCharacteristic target,
                                final int cmd) {
            if (cmd > 0xFF) {
                log("gatt: command sweep done");
                startPairProbes(gatt, target, 0);
                return;
            }
            queueWrite(gatt, target, new byte[]{(byte) cmd}, false);
            log("gatt: cmd 0x" + Integer.toHexString(cmd));
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            commandStep(gatt, target, cmd + 1);
                        }
                    }, 260L);
        }

        /** Two-byte probes for the commands that answered during the id sweep. */
        private static final byte[][] PAIR_PROBES = {
            {0x02, 0x00}, {0x02, 0x01}, {0x02, 0x03},
            {0x13, 0x00}, {0x13, 0x01}, {0x13, 0x02},
            {0x00, 0x00}, {0x00, 0x01},
            {0x01, 0x00}, {0x01, 0x01},
            {0x11, 0x00}, {0x12, 0x00}, {0x14, 0x00},
        };

        static void startPairProbes(final BluetoothGatt gatt,
                                    final BluetoothGattCharacteristic target,
                                    final int index) {
            if (index >= PAIR_PROBES.length) {
                log("gatt: pair probes done");
                return;
            }
            queueWrite(gatt, target, PAIR_PROBES[index], false);
            StringBuilder pb = new StringBuilder();
            for (byte x : PAIR_PROBES[index]) {
                pb.append(String.format("%02x", x));
            }
            log("gatt: probe " + pb);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            startPairProbes(gatt, target, index + 1);
                        }
                    }, 500L);
        }

        /**
         * Frames shaped like the station firmware's tracker command builder:
         *   [len][cmd][flags|0x80][u32 value][payload...]
         * Command 5 is the control path that carries radio interval and IMU mode.
         */
        private static final byte[][] STREAM_FRAMES = {
            {0x07, 0x05, (byte) 0x80, 0x00, 0x00, 0x00, 0x00},
            {0x08, 0x05, (byte) 0x81, 0x01, 0x00, 0x00, 0x00, 0x01},
            {0x08, 0x05, (byte) 0x82, 0x02, 0x00, 0x00, 0x00, 0x02},
            {0x0B, 0x05, (byte) 0x83, 0x0A, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x01},
            {0x07, 0x00, (byte) 0x80, 0x01, 0x00, 0x00, 0x00},
            {0x09, 0x00, (byte) 0x81, 0x01, 0x00, 0x00, 0x00, 0x01, 0x01},
        };

        static void startStreamFrames(final BluetoothGatt gatt) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            streamFrame(gatt, 0);
                        }
                    }, 1500L);
        }

        static void streamFrame(final BluetoothGatt gatt, final int index) {
            if (index >= STREAM_FRAMES.length) {
                log("gatt: station-style sweep done");
                return;
            }
            BluetoothGattCharacteristic target = null;
            for (android.bluetooth.BluetoothGattService service : gatt.getServices()) {
                if (!service.getUuid().toString().toLowerCase().startsWith("0000fff0")) {
                    continue;
                }
                for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                    if (ch.getUuid().toString().toLowerCase().endsWith("5cb8")) {
                        target = ch;
                    }
                }
            }
            if (target != null) {
                log("gatt: stream frame " + index + " -> 5cb8");
                queueWrite(gatt, target, STREAM_FRAMES[index], false);
            }
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            streamFrame(gatt, index + 1);
                        }
                    }, 800L);
        }



        /**
         * Educated guesses at the binary framing used on the 0xFFF0 pipe.
         *
         * BLE serial bridges usually start with a magic byte plus a length, so the
         * list covers the common headers plus a TSYS frame (the magic the PICO
         * services use on Binder).
         */
        private static final byte[][] FRAMES = {
            {(byte) 0xA5, 0x00},
            {(byte) 0xA5, 0x01, 0x00},
            {(byte) 0xAA, 0x55},
            {0x01},
            {0x00},
            {0x02},
            {0x7E, 0x00},
            {'T', 'S', 'Y', 'S', 0x00, 0x00, 0x00, 0x00},
            {'A', 'T'},
            {'A', 'T', '+', 'S', 'T', 'A', 'R', 'T'},
            {(byte) 0xFE, 0x00},
        };

        static void binaryFrames(final BluetoothGatt gatt, final int index) {
            if (index >= FRAMES.length) {
                log("gatt: binary sweep done");
                return;
            }
            for (android.bluetooth.BluetoothGattService service : gatt.getServices()) {
                if (!service.getUuid().toString().toLowerCase().startsWith("0000fff0")) {
                    continue;
                }
                for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                    if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                            && (ch.getProperties()
                            & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                        continue;
                    }
                    queueWrite(gatt, ch, FRAMES[index], true);
                }
            }
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            binaryFrames(gatt, index + 1);
                        }
                    }, 900L);
        }

        /** Writes a command to the two characteristics of the 0xFFF0 service. */
        static void funnels(BluetoothGatt gatt, String command) {
            for (android.bluetooth.BluetoothGattService service : gatt.getServices()) {
                if (!service.getUuid().toString().toLowerCase().startsWith("0000fff0")) {
                    continue;
                }
                for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                    if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                            && (ch.getProperties()
                            & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                        continue;
                    }
                    queueWrite(gatt, ch, command.getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
                }
            }
        }

        private static final String[] PROBE_COMMANDS = {
            "data_enable", "data_start", "data_on",
            "imu_enable", "imu_start", "enable_imu",
            "sensor_enable", "sensors_enable",
            "raw_enable", "raw_start",
            "tracking_enable", "tracking_start",
            "stream_enable", "stream_on", "enable_stream",
            "enable_data", "swift_enable",
            "mag_enable", "quat_enable", "pose_enable",
            "get_data", "read_data", "request_data",
        };

        /** Sends one command, then schedules the next one from the probe list. */
        static void writeCommand(final BluetoothGatt gatt,
                                 final BluetoothGattCharacteristic target,
                                 final String command, final int index) {
            queueWrite(gatt, target, command.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
            if (index >= PROBE_COMMANDS.length) {
                binaryFrames(gatt, 0);
                return;
            }
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            writeCommand(gatt, target, PROBE_COMMANDS[index], index + 1);
                        }
                    }, 700L);
        }

        private static final java.util.ArrayDeque<Object[]> sWriteQueue =
                new java.util.ArrayDeque<>();
        private static BluetoothGatt sQueueGatt;

        /** Queues a write; the next one is issued from onCharacteristicWrite. */
        static void queueWrite(BluetoothGatt gatt, BluetoothGattCharacteristic ch,
                               byte[] payload, boolean noResponse) {
            synchronized (sWriteQueue) {
                sWriteQueue.add(new Object[]{ch, payload, noResponse});
            }
            sQueueGatt = gatt;
            drainWriteQueue(gatt);
        }

        static void drainWriteQueue(BluetoothGatt gatt) {
            Object[] item;
            synchronized (sWriteQueue) {
                item = sWriteQueue.poll();
            }
            if (item == null) {
                return;
            }
            BluetoothGattCharacteristic ch = (BluetoothGattCharacteristic) item[0];
            byte[] payload = (byte[]) item[1];
            boolean noResponse = (Boolean) item[2];
            try {
                ch.setWriteType(noResponse
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                ch.setValue(payload);
                boolean ok = gatt.writeCharacteristic(ch);
                log("gatt: queue write " + ch.getUuid() + " len=" + payload.length
                        + " ok=" + ok);
                if (!ok) {
                    synchronized (sWriteQueue) {
                        sWriteQueue.addFirst(item);
                    }
                }
            } catch (Throwable t) {
                log("gatt: queue write failed " + t);
            }
        }

        private static BluetoothGatt lastGatt;

        private static final java.util.ArrayDeque<BluetoothGattDescriptor> sDescQueue =
                new java.util.ArrayDeque<>();

        /** CCCD writes must be serialised or every write after the first is dropped. */
        static void writeNextDescriptor(BluetoothGatt gatt) {
            lastGatt = gatt;
            BluetoothGattDescriptor next;
            synchronized (sDescQueue) {
                next = sDescQueue.poll();
            }
            if (next == null) {
                log("gatt: descriptors done");
                requestNotifications(gatt);
                return;
            }
            try {
                boolean ok = gatt.writeDescriptor(next);
                if (!ok) {
                    log("gatt: descriptor write busy, retrying");
                    synchronized (sDescQueue) {
                        sDescQueue.addFirst(next);
                    }
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    writeNextDescriptor(lastGatt);
                                }
                            }, 400L);
                }
            } catch (Throwable t) {
                log("gatt: descriptor write failed " + t);
            }
        }

        private static final java.util.ArrayDeque<BluetoothGattCharacteristic> sReadQueue =
                new java.util.ArrayDeque<>();

        static void readNext(final BluetoothGatt gatt) {
            final BluetoothGattCharacteristic next;
            synchronized (sReadQueue) {
                next = sReadQueue.poll();
            }
            if (next == null) {
                log("gatt: reads done");
                requestNotifications(gatt);
                return;
            }
            // Descriptor writes are still in flight right after discovery; a read
            // issued then is rejected, so retry until the stack accepts it.
            final android.os.Handler handler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            for (int attempt = 0; attempt < 6; ++attempt) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            boolean ok = gatt.readCharacteristic(next);
                            if (!ok) {
                                log("gatt: read busy for " + next.getUuid());
                            }
                        } catch (Throwable t) {
                            log("gatt: read failed " + t);
                        }
                    }
                }, 400L * (attempt + 1));
            }
        }

        private static boolean sConnecting;

        static synchronized void connectOnce(BluetoothDevice device) {
            if (sConnected || sConnecting) {
                return;
            }
            sConnecting = true;
            try {
                log("gatt: connecting to " + device.getAddress()
                        + " bond=" + device.getBondState());
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    try {
                        device.createBond();
                        log("gatt: createBond requested");
                    } catch (Throwable t) {
                        log("gatt: createBond failed " + t);
                    }
                }
                android.content.Context context = null;
                try {
                    context = (android.content.Context) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null),
                            "currentApplication");
                } catch (Throwable ignored) {
                }
                sGatt = device.connectGatt(context, false, new Callback(),
                        BluetoothDevice.TRANSPORT_LE);
            } catch (Throwable t) {
                log("gatt: connect failed " + t);
            }
        }

        private static final class Callback extends BluetoothGattCallback {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                log("gatt: state=" + newState + " status=" + status);
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized (GattProbe.class) {
                        sConnected = false;
                        sConnecting = false;
                        sLinked = null;
                    }
                    try {
                        gatt.close();
                    } catch (Throwable ignored) {
                    }
                    log("gatt: disconnected, retrying in 3s");
                    final android.bluetooth.BluetoothDevice dev = gatt.getDevice();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (!sConnected && dev != null) {
                                        log("gatt: reconnect " + dev.getAddress());
                                        connectOnce(dev);
                                    }
                                }
                            }, 3000L);
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    synchronized (GattProbe.class) {
                        sConnected = true;
                        sConnecting = false;
                        try {
                            sLinked = gatt.getDevice().getAddress();
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        gatt.requestMtu(247);
                    } catch (Throwable ignored) {
                    }
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    } catch (Throwable ignored) {
                    }
                    gatt.discoverServices();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                log("gatt: services status=" + status);
                if (gatt.getServices() == null) {
                    return;
                }
                for (android.bluetooth.BluetoothGattService service : gatt.getServices()) {
                    log("gatt: service " + service.getUuid());
                    for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                        log("gatt:   char " + ch.getUuid() + " props=" + ch.getProperties());
                        for (BluetoothGattDescriptor d : ch.getDescriptors()) {
                            log("gatt:     desc " + d.getUuid());
                            if (d.getUuid().toString().toLowerCase().startsWith("00002901")) {
                                try {
                                    gatt.readDescriptor(d);
                                } catch (Throwable t) {
                                    log("gatt: desc read failed " + t);
                                }
                            }
                        }
                        int props = ch.getProperties();
                        if ((props & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                            sReadQueue.add(ch);
                        }
                        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            try {
                                gatt.setCharacteristicNotification(ch, true);
                                for (BluetoothGattDescriptor d : ch.getDescriptors()) {
                                    if ("00002902-0000-1000-8000-00805f9b34fb"
                                            .equalsIgnoreCase(d.getUuid().toString())) {
                                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                        sDescQueue.add(d);
                                    }
                                }
                            } catch (Throwable t) {
                                log("gatt: subscribe failed " + t);
                            }
                        }
                    }
                }
                writeNextDescriptor(gatt);
            }

            @Override
            public void onDescriptorRead(BluetoothGatt gatt,
                                         android.bluetooth.BluetoothGattDescriptor descriptor,
                                         int status, byte[] value) {
                StringBuilder sb = new StringBuilder();
                for (byte b : value) {
                    sb.append(String.format("%02x", b));
                }
                String text = null;
                try {
                    text = new String(value, "UTF-8");
                } catch (Throwable ignored) {
                }
                log("gatt: descRead " + descriptor.getCharacteristic().getUuid()
                        + " status=" + status + " hex=" + sb + " text=" + text);
                writeNextDescriptor(gatt);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic ch, int status) {
                log("gatt: write done " + ch.getUuid() + " status=" + status);
                drainWriteQueue(gatt);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt,
                                          android.bluetooth.BluetoothGattDescriptor descriptor,
                                          int status) {
                log("gatt: descriptor " + descriptor.getUuid() + " on "
                        + descriptor.getCharacteristic().getUuid() + " status=" + status);
                writeNextDescriptor(gatt);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt,
                                             BluetoothGattCharacteristic ch,
                                             byte[] value, int status) {
                StringBuilder sb = new StringBuilder();
                for (byte b : value) {
                    sb.append(String.format("%02x", b));
                }
                String text = null;
                try {
                    text = new String(value, "UTF-8");
                } catch (Throwable ignored) {
                }
                log("gatt: read " + ch.getUuid() + " status=" + status + " hex=" + sb
                        + " text=" + text);
                readNext(gatt);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic ch, byte[] value) {
                if (value != null && value.length >= 5 && value[0] == (byte) 0xBB
                        && value[1] == 0x0A) {
                    // bb 0A <len> <flags> <soc> ...  treat byte 4 as percent-ish
                    int soc = value[4] & 0xFF;
                    if (soc > 100) {
                        soc = 100;
                    }
                    sBattery = soc;
                    log("battery=" + soc);
                    return;
                }
                if (value != null && value.length == 27 && value[0] == (byte) 0xCC) {
                    ++sImuCount;
                    sLastImu = System.currentTimeMillis();
                    long now = android.os.SystemClock.elapsedRealtime();
                    float dt = sLastSampleMs == 0 ? 0.0f : (now - sLastSampleMs) / 1000.0f;
                    sLastSampleMs = now;
                    boolean fused = sFusion.update(
                            s16(value, 4), s16(value, 6), s16(value, 8),
                            s16(value, 10), s16(value, 12), s16(value, 14), dt);
                    if (fused) {
                        float[] q = sFusion.quaternion();
                        PoseStore.submit(sLinked, q[1], q[2], q[3], q[0]);
                    }
                    if (sImuCount <= 5 || sImuCount % 25 == 0) {
                        float[] e = sFusion.euler();
                        float[] q = sFusion.quaternion();
                        log("imu #" + sImuCount
                                + " gyro=" + s16(value, 4) + "," + s16(value, 6) + "," + s16(value, 8)
                                + " accel=" + s16(value, 10) + "," + s16(value, 12) + "," + s16(value, 14)
                                + " temp=" + s16(value, 24)
                                + (fused ? String.format(" q=%.3f,%.3f,%.3f,%.3f rpy=%.1f,%.1f,%.1f",
                                        q[0], q[1], q[2], q[3], e[0], e[1], e[2]) : " q=n/a"));
                    }
                    return;
                }
                if (sPackets >= 30) {
                    return;
                }
                ++sPackets;
                StringBuilder sb = new StringBuilder();
                for (byte b : value) {
                    sb.append(String.format("%02x", b));
                }
                log("gatt: notify " + ch.getUuid() + " len=" + value.length + " = " + sb
                        + (value.length > 4 ? "  <-- DATA" : ""));
            }

        }
    }
}
