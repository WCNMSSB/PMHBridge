package com.pmh.bridge.lsp;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * One live GATT link per tracker.
 *
 * PICO's protocol is small enough to speak directly: subscribe to the notify
 * characteristic, run the captured handshake, then parse the 27-byte IMU frames
 * into quaternions. Because left and right legs must stream at the same time,
 * every tracker gets its own instance of this class instead of the old global
 * singleton.
 */
final class TrackerLink extends BluetoothGattCallback {

    private static final String TAG = "PmhTrackerLink";

    static final String SVC_FFF0 = "0000fff0-0000-1000-8000-00805f9b34fb";
    static final String CHR_WRITE = "0783b03e-8535-b5a0-7140-a304d2495cba";
    static final String CHR_NOTIFY = "0783b03e-8535-b5a0-7140-a304d2495cb8";
    static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";

    private static final Map<String, TrackerLink> LINKS = new ConcurrentHashMap<>();

    private final String mac;
    private final BluetoothDevice device;
    private final ImuFusion fusion = new ImuFusion();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChr;
    private long lastSampleMs;
    private int frames;
    private boolean handshakeStarted;

    private TrackerLink(BluetoothDevice device) {
        this.device = device;
        this.mac = device.getAddress();
    }

    static void connect(BluetoothDevice device) {
        if (device == null || device.getAddress() == null) {
            return;
        }
        String mac = device.getAddress();
        TrackerLink existing = LINKS.get(mac);
        if (existing != null) {
            return;
        }
        TrackerLink link = new TrackerLink(device);
        LINKS.put(mac, link);
        link.open();
    }

    static int linkCount() {
        return LINKS.size();
    }

    private void open() {
        try {
            Context context = PeripheralServiceStub.appContext();
            if (context == null) {
                log("no context for " + mac);
                LINKS.remove(mac);
                return;
            }
            log("connecting " + mac);
            // autoConnect=true lets the stack attach to a bonded tracker as soon
            // as it is reachable, without waiting for an advertisement.
            gatt = device.connectGatt(context, true, this, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            log("connect failed " + mac + ": " + t);
            LINKS.remove(mac);
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
        log("state " + mac + " -> " + newState + " status=" + status);
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            PeripheralServiceStub.onLinkConnected(mac);
            try {
                g.requestMtu(247);
            } catch (Throwable ignored) {
            }
            g.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            PeripheralServiceStub.onLinkDisconnected(mac);
            try {
                g.close();
            } catch (Throwable ignored) {
            }
            LINKS.remove(mac);
            // Bonded trackers stop advertising, so keep retrying for a while.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    connect(device);
                }
            }, 5000L);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt g, int status) {
        BluetoothGattService svc = g.getService(java.util.UUID.fromString(SVC_FFF0));
        if (svc == null) {
            for (BluetoothGattService s : g.getServices()) {
                if (s.getUuid().toString().toLowerCase(Locale.US).startsWith("0000fff0")) {
                    svc = s;
                }
            }
        }
        if (svc == null) {
            log("fff0 missing on " + mac);
            return;
        }
        for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
            String uuid = ch.getUuid().toString().toLowerCase(Locale.US);
            if (uuid.endsWith("5cba")) {
                writeChr = ch;
            }
            if (uuid.endsWith("5cb8")) {
                g.setCharacteristicNotification(ch, true);
                BluetoothGattDescriptor cccd = ch.getDescriptor(java.util.UUID.fromString(CCCD));
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
            }
        }
        log("services ready on " + mac + " write=" + (writeChr != null));
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
        // The CCCD uuid is 00002902-0000-1000-8000-00805f9b34fb, so match the
        // short id anywhere in the string rather than at the end.
        if (descriptor.getUuid().toString().toLowerCase(Locale.US).contains("2902")) {
            log("cccd written on " + mac + " status=" + status);
            startHandshake();
        }
    }

    private void startHandshake() {
        if (handshakeStarted || writeChr == null) {
            return;
        }
        handshakeStarted = true;
        byte[] bind = new byte[10];
        bind[0] = (byte) 0xAA;
        bind[1] = 0x03;
        bind[2] = 0x00;
        String[] parts = mac.split(":");
        for (int i = 0; i < 6 && i < parts.length; ++i) {
            bind[3 + i] = (byte) Integer.parseInt(parts[i], 16);
        }
        bind[9] = 0x01;
        byte[][] frames = {
            {(byte) 0xAA, 0x01, 0x00},
            {(byte) 0xAA, 0x0A, 0x00},
            bind,
            {(byte) 0xAA, 0x0B, 0x63, 0x00},
            {(byte) 0xAA, 0x02, 0x00, 0x00},
            {(byte) 0xAA, 0x0B, 0x07, 0x00},
            {(byte) 0xAA, 0x02, 0x01, 0x00},
            {(byte) 0xAA, 0x0B, 0x00, 0x00},
        };
        stepHandshake(frames, 0);
    }

    private void stepHandshake(final byte[][] frames, final int index) {
        if (index >= frames.length) {
            log("handshake done " + mac);
            return;
        }
        writeChr.setValue(frames[index]);
        writeChr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        gatt.writeCharacteristic(writeChr);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stepHandshake(frames, index + 1);
            }
        }, 700L);
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
        if (value == null || value.length != 27 || value[0] != (byte) 0xCC) {
            return;
        }
        ++frames;
        long now = android.os.SystemClock.elapsedRealtime();
        float dt = lastSampleMs == 0 ? 0.0f : (now - lastSampleMs) / 1000.0f;
        lastSampleMs = now;
        boolean fused = fusion.update(s16(value, 4), s16(value, 6), s16(value, 8),
                s16(value, 10), s16(value, 12), s16(value, 14), dt);
        if (fused) {
            float[] q = fusion.quaternion();
            PoseStore.submit(mac, q[1], q[2], q[3], q[0]);
        }
        if (frames <= 3 || frames % 150 == 0) {
            float[] e = fusion.euler();
            log(String.format(Locale.US, "imu %s #%d rpy=(%.1f,%.1f,%.1f)",
                    mac, frames, e[0], e[1], e[2]));
        }
    }

    private static int s16(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
