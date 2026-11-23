// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
//
// Minimal libdl_android.so replacement.
//
// PICO's libvndksupport.so is linked against bionic's private libdl_android.so and
// uses exactly one symbol from it:
//
//   android_get_exported_namespace@LIBDL_ANDROID
//
// Horizon keeps libdl_android inside the runtime APEX and out of app linker
// namespaces, so the whole VNDK chain fails with "libdl_android.so not found".
// Returning NULL here makes libvndksupport fall back to the default namespace,
// which is enough for the graphics HIDL libs this port needs.

#include <stddef.h>

__attribute__((visibility("default")))
void *android_get_exported_namespace(const char *name) {
    (void) name;
    return NULL;
}
