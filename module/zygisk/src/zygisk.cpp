// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
//
// Zygisk probe for PMHBridge.
//
// Purpose of this first stage is reconnaissance only:
//   * report the process we are injected into
//   * log every AServiceManager_getService() lookup and whether it resolved,
//     so the PICO controller service name becomes known
//   * verify /dev/input/event4 can be opened and produce events
//   * report whether vrapi_Initialize() works inside an app process
//
// The controller bridge itself is built on top of these answers.

#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <sys/sysmacros.h>
#include <stdio.h>
#include <fcntl.h>
#include <linux/input.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#include "zygisk.hpp"

#define LOG_TAG "PmhZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

volatile bool g_started = false;

static int find_vr_evdev(char *out, size_t out_len) {
    for (int i = 0; i < 32; ++i) {
        snprintf(out, out_len, "/dev/input/event%d", i);
        int fd = open(out, O_RDONLY | O_NONBLOCK);
        if (fd < 0) {
            continue;
        }
        struct input_absinfo abs_x;
        bool vr = ioctl(fd, EVIOCGABS(ABS_X), &abs_x) == 0
                && ioctl(fd, EVIOCGABS(ABS_RZ), &abs_x) == 0;
        char name[64] = {0};
        ioctl(fd, EVIOCGNAME(sizeof(name)), name);
        close(fd);
        if (vr) {
            LOGI("evdev: candidate %s name=\"%s\"", out, name);
            return i;
        }
    }
    return -1;
}

void *evdev_probe(void *) {
    char path[64];
    int index = find_vr_evdev(path, sizeof(path));
    if (index < 0) {
        LOGI("evdev: no VR peripheral found");
        return nullptr;
    }
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGI("evdev: open failed (%d)", errno);
        return nullptr;
    }
    LOGI("evdev: opened %s", path);
    int logged = 0;
    while (logged < 40) {
        struct input_event ev;
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n != (ssize_t) sizeof(ev)) {
            usleep(5000);
            continue;
        }
        if (ev.type == EV_KEY || ev.type == EV_ABS) {
            LOGI("evdev: type=%u code=%u value=%d", ev.type, ev.code, ev.value);
            ++logged;
        }
    }
    close(fd);
    return nullptr;
}

void start_evdev_probe() {
    pthread_t thread;
    if (pthread_create(&thread, nullptr, evdev_probe, nullptr) == 0) {
        pthread_detach(thread);
    }
}


// VrApi ships inside the title's own APK, so the real path is read from its maps
// instead of assuming a system location.
static char g_vrapi_path[PATH_MAX];

static const char *find_mapped_library(const char *basename) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        return nullptr;
    }
    char line[512];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        char *slash = strstr(line, "/");
        if (slash == nullptr || strstr(slash, basename) == nullptr) {
            continue;
        }
        char *nl = strchr(slash, '\n');
        if (nl != nullptr) {
            *nl = '\0';
        }
        strncpy(g_vrapi_path, slash, sizeof(g_vrapi_path) - 1);
        fclose(maps);
        return g_vrapi_path;
    }
    fclose(maps);
    return nullptr;
}


void *vrapi_retry(void *) {
    for (int i = 0; i < 50; ++i) {
        const char *path = find_mapped_library("libvrapi.so");
        if (path != nullptr) {
            LOGI("vrapi: mapped at %s", path);
            void *handle = dlopen(path, RTLD_NOW);
            if (handle != nullptr) {
                auto init = reinterpret_cast<int (*)(void *, void *, void *)>(
                        dlsym(handle, "vrapi_Initialize"));
                LOGI("vrapi: handle=%p initialize=%p", handle, init);
                return nullptr;
            }
            LOGI("vrapi: dlopen mapped path failed (%s)", dlerror());
        }
        usleep(200 * 1000);
    }
    LOGI("vrapi: never mapped");
    return nullptr;
}


// Hide the PICO XR backend from multi-backend titles so Unity falls back to its
// own OpenXR provider, which is what gives Quest native controller tracking.
// The libraries are pulled in by native dlopen(), so this has to happen in the
// PLT of the calling module rather than through a Java hook.
static void *(*g_orig_dlopen)(const char *, int);
static void *(*g_orig_dlopen_ext)(const char *, int, const android_dlextinfo *);

static bool hide_target(const char *name) {
    if (name == nullptr) {
        return false;
    }
    // Only Unity's PICO XR provider is hidden. Pillar libraries such as pxr_api
    // must stay loadable or the title cannot start at all.
    return strstr(name, "libPxrPlatform.so") != nullptr;
}

static void *pmh_dlopen(const char *name, int flag) {
    if (hide_target(name)) {
        LOGI("dlopen: hid %s", name);
        return nullptr;
    }
    return g_orig_dlopen(name, flag);
}

static void *pmh_dlopen_ext(const char *name, int flag, const android_dlextinfo *info) {
    if (hide_target(name)) {
        LOGI("dlopen_ext: hid %s", name);
        return nullptr;
    }
    return g_orig_dlopen_ext(name, flag, info);
}

static void install_dlopen_hooks(zygisk::Api *api) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        return;
    }
    char line[512];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (strstr(line, "libil2cpp.so") == nullptr || strstr(line, "r-xp") == nullptr) {
            continue;
        }
        unsigned long start, end, offset;
        char perms[8], dev[16];
        unsigned long inode;
        if (sscanf(line, "%lx-%lx %7s %lx %15s %lu", &start, &end, perms, &offset, dev, &inode) != 6) {
            continue;
        }
        unsigned int major, minor;
        sscanf(dev, "%x:%x", &major, &minor);
        dev_t device = makedev(major, minor);
        api->pltHookRegister(device, inode, "dlopen", (void *) pmh_dlopen, (void **) &g_orig_dlopen);
        api->pltHookRegister(device, inode, "android_dlopen_ext",
                             (void *) pmh_dlopen_ext, (void **) &g_orig_dlopen_ext);
        api->pltHookCommit();
        LOGI("dlopen hooks installed for libil2cpp.so");
        break;
    }
    fclose(maps);
}


static zygisk::Api *g_api;

// libil2cpp.so only appears after the app starts running, so the PLT hooks are
// installed from a retry loop rather than during process specialization.
static void *dlopen_hook_retry(void *) {
    for (int i = 0; i < 100; ++i) {
        if (g_api != nullptr) {
            install_dlopen_hooks(g_api);
        }
        usleep(200 * 1000);
    }
    return nullptr;
}

static void start_dlopen_hook_retry() {
    pthread_t thread;
    if (pthread_create(&thread, nullptr, dlopen_hook_retry, nullptr) == 0) {
        pthread_detach(thread);
    }
}


// Probe whether an app process may reach Quest's own controller tracking service.
// A non-null binder means poses can be read without going through VrApi.
typedef void *(*GetServiceFn)(const char *);

static void *tracking_probe(void *) {
    // libbinder_ndk is a platform library and is not part of the NDK stub set,
    // so the entry point is resolved at runtime instead of at link time.
    auto get_service = reinterpret_cast<GetServiceFn>(
            dlsym(RTLD_DEFAULT, "AServiceManager_getService"));
    if (get_service == nullptr) {
        LOGI("tracking service: AServiceManager_getService unavailable");
        return nullptr;
    }
    void *binder = get_service(
            "oculus.internal.tracking.IControllerTrackingService/default");
    LOGI("tracking service: %p", binder);
    return nullptr;
}

static void start_tracking_probe() {
    pthread_t thread;
    if (pthread_create(&thread, nullptr, tracking_probe, nullptr) == 0) {
        pthread_detach(thread);
    }
}

void vrapi_probe() {
    const char *path = find_mapped_library("libvrapi.so");
    if (path == nullptr) {
        path = "libvrapi.so";
    }
    void *handle = dlopen(path, RTLD_NOW);
    if (handle == nullptr) {
        LOGI("vrapi: dlopen failed (%s)", dlerror());
        return;
    }
    auto init = reinterpret_cast<int (*)(void *, void *, void *)>(
            dlsym(handle, "vrapi_Initialize"));
    LOGI("vrapi: handle=%p initialize=%p", handle, init);
}

}  // namespace

class PmhZygisk : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        g_api = api;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (g_started) {
            return;
        }
        if (!is_target(env_, args)) {
            return;
        }
        g_started = true;
        LOGI("zygisk: injected");
        start_evdev_probe();
        start_tracking_probe();
        start_dlopen_hook_retry();
    }


    // Only PXR titles are probed. Injecting into every process made com.oculus.vrshell
    // crash, which hangs the whole headset, so anything else returns immediately.
    static bool is_target(JNIEnv *env, zygisk::AppSpecializeArgs *args) {
        if (env == nullptr || args == nullptr || args->nice_name == nullptr) {
            return false;
        }
        const char *name = env->GetStringUTFChars(args->nice_name, nullptr);
        if (name == nullptr) {
            return false;
        }
        bool hit = strstr(name, "com.Pico.RealmDream") != nullptr
                || strstr(name, "com.vrchat.android") != nullptr
                || strstr(name, "com.picoxr.ControllerTest") != nullptr;
        LOGI("zygisk: process %s -> %s", name, hit ? "target" : "skip");
        env->ReleaseStringUTFChars(args->nice_name, name);
        return hit;
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs * /*args*/) override {
        LOGI("zygisk: postAppSpecialize");
        pthread_t thread;
        if (pthread_create(&thread, nullptr, vrapi_retry, nullptr) == 0) {
            pthread_detach(thread);
        }
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(PmhZygisk)
