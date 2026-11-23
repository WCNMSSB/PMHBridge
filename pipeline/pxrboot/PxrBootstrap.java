// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
//
// Boots PICO's XR runtime with the vsync shim already loaded.
//
// The shim (libpxrvsync.so) has to be present in the process before the
// compositor opens /sys/class/drm/sde-crtc-0/vsync_event, and Horizon's linker
// namespaces refuse to dlopen it from /data/local/tmp. Loading it from the APK's
// own lib directory works, so this subclass of PICO's Application loads it first
// and then lets the original Application run.

package com.pmh.pxrboot;

import com.pico.xr.openxr_runtime.XRRuntimeApplication;

public class PxrBootstrap extends XRRuntimeApplication {

    static {
        try {
            System.loadLibrary("pxrvsync");
            android.util.Log.i("PmhPxrBoot", "libpxrvsync loaded");
        } catch (Throwable t) {
            android.util.Log.e("PmhPxrBoot", "libpxrvsync load failed: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        android.util.Log.i("PmhPxrBoot", "XRRuntimeApplication started with vsync shim");
    }
}
