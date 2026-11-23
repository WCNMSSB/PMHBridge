package com.pmh.bridge.lsp;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Publishes the tracked orientations where the OpenXR layer can read them.
 *
 * Up to five trackers are supported: slot 0 is the left leg, slot 1 the right,
 * slots 2/3 the hips and slot 4 the spine. The
 * slot is assigned by sorted MAC address so it stays stable across runs; the
 * layer applies slot 0 to the left leg joints and slot 1 to the right.
 *
 * Layout (little endian): "PMH1" magic, version, tracker count, then count ×
 * 4 floats (x, y, z, w).
 */
public final class PoseStore {

    private static final String TAG = "PmhPose";
    private static final int MAGIC = 0x31484D50; // "PMH1"
    private static final long MIN_INTERVAL_MS = 50L;
    private static final int MAX_TRACKERS = 5;

    private static File sFile;
    private static long sLastWrite;
    private static final float[][] sQuats = new float[MAX_TRACKERS][4];
    private static final boolean[] sValid = new boolean[MAX_TRACKERS];
    private static String[] sMacs = new String[0];

    private PoseStore() {
    }

    private static File file() {
        // Shared with the OpenXR layer running in other app processes.
        if (sFile == null) {
            File shared = new File("/data/local/tmp/pmh_pose.bin");
            if (shared.canWrite() || !shared.exists()) {
                sFile = shared;
            }
        }
        if (sFile != null) {
            return sFile;
        }
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            if (app != null) {
                File dir = (File) app.getClass().getMethod("getFilesDir").invoke(app);
                sFile = new File(dir, "pmh_pose.bin");
            }
        } catch (Throwable t) {
            Log.i(TAG, "no files dir: " + t);
        }
        return sFile;
    }

    /** Slot for a tracker MAC: 0 for the first (left), 1 for the second. */
    private static int slotFor(String mac) {
        if (mac == null) {
            return 0;
        }
        synchronized (PoseStore.class) {
            if (sMacs.length == 0) {
                return 0;
            }
            for (int i = 0; i < sMacs.length && i < MAX_TRACKERS; ++i) {
                if (mac.equals(sMacs[i])) {
                    return i;
                }
            }
            return 0;
        }
    }

    /** Registers the trackers we know about so slots stay deterministic. */
    public static synchronized void setTrackers(java.util.List<String> macs) {
        java.util.List<String> sorted = new java.util.ArrayList<>(macs);
        java.util.Collections.sort(sorted);
        sMacs = sorted.toArray(new String[0]);
    }

    /** Publishes one tracker quaternion (x, y, z, w). Rate limited to 20 Hz. */
    public static void submit(String mac, float x, float y, float z, float w) {
        int slot = slotFor(mac);
        if (slot >= MAX_TRACKERS) {
            return;
        }
        synchronized (PoseStore.class) {
            sQuats[slot][0] = x;
            sQuats[slot][1] = y;
            sQuats[slot][2] = z;
            sQuats[slot][3] = w;
            sValid[slot] = true;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastWrite < MIN_INTERVAL_MS) {
            return;
        }
        sLastWrite = now;
        write();
    }

    private static synchronized void write() {
        File target = file();
        if (target == null) {
            return;
        }
        int count = 0;
        for (int i = 0; i < MAX_TRACKERS; ++i) {
            if (sValid[i]) {
                ++count;
            }
        }
        if (count == 0) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(12 + 16 * count).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(1);
        buffer.putInt(count);
        for (int i = 0; i < MAX_TRACKERS; ++i) {
            if (!sValid[i]) {
                continue;
            }
            buffer.putFloat(sQuats[i][0]);
            buffer.putFloat(sQuats[i][1]);
            buffer.putFloat(sQuats[i][2]);
            buffer.putFloat(sQuats[i][3]);
        }
        try {
            FileOutputStream out = new FileOutputStream(target);
            out.write(buffer.array());
            out.close();
        } catch (Throwable t) {
            Log.i(TAG, "write failed: " + t);
        }
    }
}
