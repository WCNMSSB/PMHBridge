// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
//
// libpmh_openxr_preload — redirects a title's OpenXR loader to the PMH bridge.
//
// Quest does not use the Khronos active_runtime.json mechanism: a title's bundled
// libopenxr_loader.so talks to Meta's runtime through runtimeipcbroker, so PMH's
// PICO->Meta facade never runs and PICO controller profiles stay unsupported.
//
// Preloading this library intercepts dlopen and hands back the facade instead of
// the bundled loader. The facade exposes xrGetInstanceProcAddr, xrCreateInstance
// and the loader negotiation entry points, which is everything Unity asks for.

#define _GNU_SOURCE
#include <dlfcn.h>
#include <string.h>

static void *(*g_real_dlopen)(const char *, int);
static void *(*g_real_dlopen_ext)(const char *, int, const void *);

#define PMH_BRIDGE "/data/local/tmp/libpico_meta_bridge.so"
#define PMH_BACKEND "/data/local/tmp/libmeta_xrloader.so"
#define PMH_LIBCPP "/data/local/tmp/libc++.so"

static int wants_loader(const char *name) {
    return name != NULL && strstr(name, "openxr_loader") != NULL;
}

// The facade links against Meta's loader by absolute path; that path lives outside
// the app namespace, so the copy staged in /data/local/tmp is redirected instead.
static const char *redirect(const char *name) {
    if (name == NULL) {
        return NULL;
    }
    if (strstr(name, "openxr_loader") != NULL) {
        return PMH_BRIDGE;
    }
    if (strstr(name, "libmeta_xrloader") != NULL) {
        return PMH_BACKEND;
    }
    // The staged Meta loader is linked against the platform libc++; the app
    // namespace does not expose it, so the staged copy is used instead.
    if (strstr(name, "libc++.so") != NULL) {
        return PMH_LIBCPP;
    }
    return NULL;
}

void *dlopen(const char *name, int flag) {
    if (g_real_dlopen == NULL) {
        g_real_dlopen = (void *(*)(const char *, int)) dlsym(RTLD_NEXT, "dlopen");
    }
    const char *target = redirect(name);
    if (target != NULL) {
        return g_real_dlopen(target, flag);
    }
    return g_real_dlopen(name, flag);
}

void *android_dlopen_ext(const char *name, int flag, const void *info) {
    if (g_real_dlopen_ext == NULL) {
        g_real_dlopen_ext = (void *(*)(const char *, int, const void *))
                dlsym(RTLD_NEXT, "android_dlopen_ext");
    }
    const char *target = redirect(name);
    if (target != NULL) {
        return g_real_dlopen_ext(target, flag, info);
    }
    return g_real_dlopen_ext(name, flag, info);
}
