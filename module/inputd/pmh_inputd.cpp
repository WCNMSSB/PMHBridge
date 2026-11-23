// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
//
// pmh_inputd — PICO controller service for Horizon OS.
//
// The PICO XR runtime talks to its controllers through an AIDL service:
//
//   instance name : pxrcontrollerservice
//   descriptor    : PXR.IPxrControllerService
//   listener      : PXR.IControllerServiceListener
//
// Neither exists on Quest, so PXR titles never receive controller data. This
// daemon registers a stand-in service under the expected instance name.
//
// Stage 1: register the service and log every incoming transaction code with its
// payload size. That maps codes to methods without guessing, which is what the
// real implementation needs before it can answer with poses.
//
// The NDK ships libbinder_ndk but not binder_manager.h, so the few entry points
// used here are declared locally and resolved from the shared library.

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <linux/input.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define LOG_TAG "PmhInputd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ---- minimal libbinder_ndk surface -----------------------------------------

struct AIBinder;
struct AIBinder_Class;
struct AParcel;
using transaction_code_t = uint32_t;
using binder_status_t = int32_t;

constexpr binder_status_t STATUS_OK = 0;

extern "C" {
typedef AIBinder_Class *(*define_fn)(const char *, void *(*)(void *),
                                     void (*)(void *),
                                     binder_status_t (*)(AIBinder *, transaction_code_t,
                                                         const AParcel *, AParcel *));
typedef AIBinder *(*new_fn)(const AIBinder_Class *, void *);
typedef int32_t (*write_int32_fn)(AParcel *, int32_t);
typedef int32_t (*read_int32_fn)(const AParcel *, int32_t *);
typedef binder_status_t (*read_binder_fn)(const AParcel *, AIBinder **);
typedef void (*binder_dec_fn)(AIBinder **);
typedef binder_status_t (*prepare_fn)(AIBinder *, AParcel **);
typedef binder_status_t (*transact_fn)(AIBinder *, transaction_code_t, AParcel **,
                                       AParcel **, uint32_t);
typedef void (*binder_free_fn)(AIBinder *);
typedef void (*binder_inc_fn)(AIBinder *);
typedef void (*parcel_delete_fn)(AParcel *);
typedef binder_status_t (*read_string_fn)(const AParcel *, char *, int32_t *);
typedef binder_status_t (*add_service_fn)(AIBinder *, const char *);
typedef void (*join_pool_fn)(void);
}

// The NDK stub library only carries a subset of libbinder_ndk, so the registry and
// thread pool entry points are resolved from the platform library at runtime.
static define_fn g_define;
static new_fn g_new;
static add_service_fn g_add_service;
static join_pool_fn g_join_pool;
static write_int32_fn g_write_int32;
static read_int32_fn g_read_int32;
static read_binder_fn g_read_binder;
static binder_dec_fn g_binder_dec;
static prepare_fn g_prepare;
static transact_fn g_transact;
static binder_free_fn g_binder_free;
static binder_inc_fn g_binder_inc;
static parcel_delete_fn g_parcel_delete;
static read_string_fn g_read_string;
typedef int32_t (*write_string_fn)(AParcel *, const char *, int32_t);
static write_string_fn g_write_string;
static AIBinder *g_session = nullptr;
static pthread_t g_session_thread;

static bool load_binder() {
    void *handle = dlopen("libbinder_ndk.so", RTLD_NOW);
    if (handle == nullptr) {
        LOGI("dlopen libbinder_ndk failed: %s", dlerror());
        return false;
    }
    g_define = reinterpret_cast<define_fn>(dlsym(handle, "AIBinder_Class_define"));
    g_new = reinterpret_cast<new_fn>(dlsym(handle, "AIBinder_new"));
    g_add_service = reinterpret_cast<add_service_fn>(dlsym(handle, "AServiceManager_addService"));
    g_join_pool = reinterpret_cast<join_pool_fn>(dlsym(handle, "ABinderProcess_joinThreadPool"));
    g_write_int32 = reinterpret_cast<write_int32_fn>(dlsym(handle, "AParcel_writeInt32"));
    g_read_int32 = reinterpret_cast<read_int32_fn>(dlsym(handle, "AParcel_readInt32"));
    g_read_binder = reinterpret_cast<read_binder_fn>(dlsym(handle, "AParcel_readStrongBinder"));
    g_binder_dec = reinterpret_cast<binder_dec_fn>(
            dlsym(handle, "AIBinder_decStrong"));
    g_prepare = reinterpret_cast<prepare_fn>(dlsym(handle, "AIBinder_prepareTransaction"));
    g_transact = reinterpret_cast<transact_fn>(dlsym(handle, "AIBinder_transact"));
    g_binder_free = reinterpret_cast<binder_free_fn>(dlsym(handle, "AIBinder_free"));
    g_binder_inc = reinterpret_cast<binder_inc_fn>(dlsym(handle, "AIBinder_incStrong"));
    g_parcel_delete = reinterpret_cast<parcel_delete_fn>(dlsym(handle, "AParcel_delete"));
    g_read_string = reinterpret_cast<read_string_fn>(dlsym(handle, "AParcel_readString"));
    g_write_string = reinterpret_cast<write_string_fn>(dlsym(handle, "AParcel_writeString"));
    if (g_define == nullptr || g_new == nullptr || g_add_service == nullptr
            || g_join_pool == nullptr) {
        LOGI("missing binder symbols");
        return false;
    }
    return true;
}

namespace {

constexpr char kInstanceName[] = "pxrcontrollerservice";
constexpr char kDescriptor[] = "PXR.IPxrControllerService";

uint32_t g_frame = 0;

binder_status_t on_transact(AIBinder *, transaction_code_t code,
                            const AParcel *, AParcel *) {
    // Generic stubs stay silent: writing payloads for unknown contracts disturbed
    // the runtime. The services it really needs have dedicated handlers.
    return STATUS_OK;
}

// api_layer is a Java AIDL interface; each getter needs the shape its client
// parses, otherwise obfuscated object reads corrupt binder refcounts.
/** Sends one one-way style callback to the title's session binder. */
static void notify_session(int32_t code, int32_t value) {
    AIBinder *session = g_session;
    if (session == nullptr || g_prepare == nullptr || g_transact == nullptr
            || g_write_int32 == nullptr) {
        return;
    }
    AParcel *data = nullptr;
    if (g_prepare(session, &data) != STATUS_OK || data == nullptr) {
        return;
    }
    g_write_int32(data, value);
    AParcel *reply = nullptr;
    binder_status_t st = g_transact(session, static_cast<transaction_code_t>(code),
                                    &data, &reply, 0);
    if (data != nullptr && g_parcel_delete != nullptr) {
        g_parcel_delete(data);
    }
    if (reply != nullptr && g_parcel_delete != nullptr) {
        g_parcel_delete(reply);
    }
    LOGI("session notify code=%d value=%d -> %d", code, value, st);
}

static void *session_notifier(void *) {
    // Give the title time to finish its own start-up before the visibility
    // callbacks arrive, mirroring how the PICO framework sequences them.
    sleep(3);
    notify_session(107, 1);   // XR runtime state: running
    sleep(1);
    notify_session(102, 1);   // showing 3D app: true
    sleep(1);
    notify_session(101, 0);   // seethrough state: off
    notify_session(105, 0);   // running 2D app: none
    return nullptr;
}

static binder_status_t on_transact_api_layer(AIBinder *, transaction_code_t code,
                                             const AParcel *in, AParcel *reply) {
    if (g_write_int32 == nullptr || reply == nullptr) {
        return STATUS_OK;
    }
    LOGI("api_layer code=%u", static_cast<unsigned>(code));
    g_write_int32(reply, 0);
    switch (code) {
        case 4:   // getShowing3dApp -> String
            if (g_write_string != nullptr) {
                g_write_string(reply, "com.Pico.RealmDream", -1);
            } else {
                g_write_int32(reply, -1);
            }
            break;
        case 6:   // getRunning2dAppData -> String
        case 15:  // getTopAppOnDefaultDisplayForNative -> String
        case 19:  // getSettingsString -> String
            g_write_int32(reply, -1);
            break;
        case 1:   // getScreenState -> boolean
        case 2:   // isInputMethodShowing -> boolean
            g_write_int32(reply, 1);
            break;
        case 3:   // getSeethroughState -> int
        case 5:   // getXrRuntimeDisplayState -> int
        case 16:  // getPowerState -> int
        case 17:  // getSettingsInt -> int
            g_write_int32(reply, 0);
            break;
        case 7:   // getTopAppOnDefaultDisplay -> ComponentName
            if (g_write_string != nullptr) {
                g_write_string(reply, "com.Pico.RealmDream", -1);
                g_write_string(reply, "com.unity3d.player.UnityPlayerActivity", -1);
            } else {
                g_write_int32(reply, 0);
            }
            break;
        default:  // void methods (registerAppSession and friends)
            break;
    }
    return STATUS_OK;
}


#define PMH_VSYNC_FIFO "/data/local/tmp/pmh_vsync_fifo"

/** Publishes a monotonic 8-byte timestamp at the panel rate. */
static void *vsync_publisher(void *) {
    // A regular file can be bind mounted over the DRM node, unlike a FIFO, and
    // rewriting it in place keeps the timestamp fresh for every read.
    int fd = open(PMH_VSYNC_FIFO, O_RDWR | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) {
        LOGI("vsync file open failed");
        return nullptr;
    }
    int64_t zero = 0;
    (void) !pwrite(fd, &zero, sizeof(zero), 0);
    LOGI("vsync file ready");
    const long period = 1000000000L / 72;
    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    while (true) {
        next.tv_nsec += period;
        while (next.tv_nsec >= 1000000000L) {
            next.tv_nsec -= 1000000000L;
            ++next.tv_sec;
        }
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t stamp = (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
        (void) !pwrite(fd, &stamp, sizeof(stamp), 0);
        struct timespec wait = next;
        while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &wait, nullptr) == EINTR) {
        }
    }
    return nullptr;
}

void *evdev_reader(void *) {
    char path[64];
    int fd = -1;
    for (int i = 0; i < 32; ++i) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int candidate = open(path, O_RDONLY | O_NONBLOCK);
        if (candidate < 0) {
            continue;
        }
        struct input_absinfo info;
        bool controller = ioctl(candidate, EVIOCGABS(ABS_X), &info) == 0
                && ioctl(candidate, EVIOCGABS(ABS_RZ), &info) == 0;
        if (!controller) {
            close(candidate);
            continue;
        }
        fd = candidate;
        LOGI("evdev: using %s", path);
        break;
    }
    if (fd < 0) {
        LOGI("evdev: no controller node found");
        return nullptr;
    }
    while (true) {
        struct input_event ev;
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n != static_cast<ssize_t>(sizeof(ev))) {
            usleep(2000);
            continue;
        }
        if (ev.type == EV_KEY || ev.type == EV_ABS) {
            LOGI("evdev: type=%u code=%u value=%d", ev.type, ev.code, ev.value);
        }
    }
    return nullptr;
}

void *frame_ticker(void *) {
    while (true) {
        ++g_frame;
        usleep(11000);
    }
    return nullptr;
}

static void *on_create(void *args) {
    return args;
}

static void on_destroy(void *) {
}

}  // namespace

int main() {
    if (!load_binder()) {
        return 1;
    }
    LOGI("binder symbols: define=%p new=%p add=%p join=%p",
         reinterpret_cast<void *>(g_define), reinterpret_cast<void *>(g_new),
         reinterpret_cast<void *>(g_add_service), reinterpret_cast<void *>(g_join_pool));
    AIBinder_Class *clazz = g_define(kDescriptor, on_create, on_destroy, on_transact);
    if (clazz == nullptr) {
        LOGI("AIBinder_Class_define failed");
        return 1;
    }
    AIBinder *binder = g_new(clazz, nullptr);
    if (binder == nullptr) {
        LOGI("AIBinder_new failed");
        return 1;
    }
    // PICO titles and the runtime block on a handful of PICO system services that
    // Horizon does not provide. Permissive stubs answer every transaction so that
    // start-up can continue instead of waiting forever.
    static const char *kStubs[] = {
            "ConfigurationService",
            "pxr_notification",
            "api_layer",
    };
    for (const char *name : kStubs) {
        if (strcmp(name, "api_layer") == 0) {
            AIBinder_Class *clazz = g_define("com.pico.api.app.IApiLayer",
                                             on_create, on_destroy,
                                             on_transact_api_layer);
            AIBinder *stub = clazz ? g_new(clazz, nullptr) : nullptr;
            if (stub != nullptr) {
                LOGI("addService(%s) -> %d", name, g_add_service(stub, name));
            }
            continue;
        }
        AIBinder_Class *clazz = g_define(name, on_create, on_destroy, on_transact);
        AIBinder *stub = clazz ? g_new(clazz, nullptr) : nullptr;
        if (stub != nullptr) {
            LOGI("addService(%s) -> %d", name, g_add_service(stub, name));
        }
    }

    binder_status_t status = g_add_service(binder, kInstanceName);
    LOGI("addService(%s) -> %d", kInstanceName, status);
    if (status != STATUS_OK) {
        return 1;
    }

    pthread_t evdev_thread;
    pthread_t vsync_thread;
    pthread_create(&vsync_thread, nullptr, vsync_publisher, nullptr);
    pthread_detach(vsync_thread);
    pthread_create(&evdev_thread, nullptr, evdev_reader, nullptr);
    pthread_detach(evdev_thread);

    pthread_t ticker;
    pthread_create(&ticker, nullptr, frame_ticker, nullptr);
    pthread_detach(ticker);

    LOGI("pmh_inputd running");
    g_join_pool();
    return 0;
}
