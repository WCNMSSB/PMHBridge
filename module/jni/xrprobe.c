#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "PmhXrProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

typedef int32_t XrResult;
typedef uint64_t XrInstance;
typedef uint64_t XrVersion;

typedef struct XrLoaderInitInfoAndroidKHR {
    int32_t type;
    const void *next;
    void *applicationVM;
    void *applicationContext;
} XrLoaderInitInfoAndroidKHR;

typedef struct XrApplicationInfo {
    char applicationName[128];
    uint32_t applicationVersion;
    char engineName[128];
    uint32_t engineVersion;
    XrVersion apiVersion;
} XrApplicationInfo;

typedef struct XrInstanceCreateInfo {
    int32_t type;
    const void *next;
    uint64_t createFlags;
    XrApplicationInfo applicationInfo;
    uint32_t enabledApiLayerCount;
    const char *const *enabledApiLayerNames;
    uint32_t enabledExtensionCount;
    const char *const *enabledExtensionNames;
} XrInstanceCreateInfo;

#define XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR 1000000000
#define XR_TYPE_INSTANCE_CREATE_INFO 3
#define XR_MAKE_VERSION(major, minor, patch) \
    ((((uint64_t)(major)) << 48) | (((uint64_t)(minor)) << 32) | ((uint64_t)(patch)))

/*
 * Runs inside a host app (com.pvr.swift) because Horizon OS will not launch our
 * own test activity. Initialises the OpenXR loader with the app's JavaVM and
 * context, then creates an instance so implicit API layers get negotiated.
 */

/* ---- leg IK self-test ------------------------------------------------ */

static void rotate(const float q[4], const float v[3], float out[3]) {
    float x = q[0], y = q[1], z = q[2], w = q[3];
    float tx = 2.0f * (y * v[2] - z * v[1]);
    float ty = 2.0f * (z * v[0] - x * v[2]);
    float tz = 2.0f * (x * v[1] - y * v[0]);
    out[0] = v[0] + w * tx + (y * tz - z * ty);
    out[1] = v[1] + w * ty + (z * tx - x * tz);
    out[2] = v[2] + w * tz + (x * ty - y * tx);
}

/* Thigh and shin lengths of an average adult, metres. */
#define THIGH_M 0.42f
#define SHIN_M 0.40f

static void leg_ik(const float hip[3], const float thighQ[4], const float shinQ[4],
                   float knee[3], float ankle[3]) {
    float down[3] = {0.0f, -THIGH_M, 0.0f};
    float rotated[3];
    rotate(thighQ, down, rotated);
    for (int i = 0; i < 3; ++i) {
        knee[i] = hip[i] + rotated[i];
    }
    rotate(shinQ, down, rotated);
    ankle[0] = knee[0];
    ankle[1] = knee[1];
    ankle[2] = knee[2];
    float shinDown[3] = {0.0f, -SHIN_M, 0.0f};
    rotate(shinQ, shinDown, rotated);
    for (int i = 0; i < 3; ++i) {
        ankle[i] += rotated[i];
    }
}

static void selftest_ik(void) {
    float hip[3] = {0.12f, 0.95f, 0.0f};
    float identity[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    float knee[3], ankle[3];
    leg_ik(hip, identity, identity, knee, ankle);
    LOGI("ik rest: knee=(%.3f,%.3f,%.3f) ankle=(%.3f,%.3f,%.3f)",
         knee[0], knee[1], knee[2], ankle[0], ankle[1], ankle[2]);

    /* 30 degrees around X: the leg must swing backwards in Z. */
    float angle = 30.0f * 3.14159265f / 180.0f;
    float q30[4] = {0.2588190f, 0.0f, 0.0f, 0.9659258f};
    (void)angle;
    leg_ik(hip, q30, q30, knee, ankle);
    LOGI("ik 30deg: knee=(%.3f,%.3f,%.3f) ankle=(%.3f,%.3f,%.3f)",
         knee[0], knee[1], knee[2], ankle[0], ankle[1], ankle[2]);
}

JNIEXPORT jstring JNICALL
Java_com_pmh_bridge_lsp_XrProbe_probe(JNIEnv *env, jclass clazz, jobject context) {
    static char out[512];
    out[0] = '\0';
    (void)clazz;

    /* First: can this process even load our layer library? */
    selftest_ik();

    void *layer = dlopen("/system/lib64/libpmh_openxr_layer.so", RTLD_NOW);
    if (layer == NULL) {
        LOGI("layer dlopen failed: %s", dlerror());
    } else {
        void *sym = dlsym(layer, "xrNegotiateLoaderApiLayerInterface");
        LOGI("layer dlopen ok, negotiate=%p", sym);
    }

    /* Write a synthetic pose for the host process, then let the layer's own
     * pose path consume it. This validates the whole channel without needing a
     * tracker to be awake. */
    {
        char cmdline[256];
        int fd = open("/proc/self/cmdline", O_RDONLY);
        if (fd >= 0) {
            ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
            close(fd);
            if (n > 0) {
                cmdline[n] = '\0';
                char *colon = strchr(cmdline, ':');
                if (colon) {
                    *colon = '\0';
                }
                char path[512];
                snprintf(path, sizeof(path), "/data/user/0/%s/files/pmh_pose.bin", cmdline);
                FILE *f = fopen(path, "wb");
                if (f != NULL) {
                    uint32_t header[3] = {0x31484D50u, 1, 1};
                    float q[4] = {0.2588190f, 0.0f, 0.0f, 0.9659258f};
                    fwrite(header, sizeof(header), 1, f);
                    fwrite(q, sizeof(q), 1, f);
                    fclose(f);
                    LOGI("wrote synthetic pose to %s", path);
                } else {
                    LOGI("pose write failed: %s", strerror(errno));
                }
            }
        }
    }

    if (layer != NULL) {
        typedef void (*PFN_selftest)(void);
        PFN_selftest st = (PFN_selftest)dlsym(layer, "pmh_layer_selftest");
        LOGI("layer selftest symbol=%p", st);
        if (st != NULL) {
            st();
        }
    }

    /* Check that the layer hands out our body-tracking wrappers. */
    if (layer != NULL) {
        typedef XrResult (*PFN_layer_gipa)(XrInstance, const char *, void **);
        PFN_layer_gipa lgipa = (PFN_layer_gipa)dlsym(layer, "xrGetInstanceProcAddr");
        if (lgipa != NULL) {
            void *createFn = NULL;
            void *locateFn = NULL;
            XrResult r1 = lgipa((XrInstance)0, "xrCreateBodyTrackerFB", &createFn);
            XrResult r2 = lgipa((XrInstance)0, "xrLocateBodyJointsFB", &locateFn);
            LOGI("layer wrappers create=%d/%p locate=%d/%p", r1, createFn, r2, locateFn);
        }
    }

    /* Validate the layer's negotiate entry point directly. */
    if (layer != NULL) {
        typedef XrResult (*PFN_negotiate)(const void *, const char *, void *);
        PFN_negotiate negotiate =
                (PFN_negotiate)dlsym(layer, "xrNegotiateLoaderApiLayerInterface");
        if (negotiate != NULL) {
            struct {
                int32_t structType;
                uint32_t structVersion;
                size_t structSize;
                uint32_t minInterfaceVersion;
                uint32_t maxInterfaceVersion;
                uint64_t minApiVersion;
                uint64_t maxApiVersion;
            } loaderInfo = {1, 1, sizeof(loaderInfo), 1, 1, 0, 0};
            unsigned char request[64];
            memset(request, 0, sizeof(request));
            XrResult nr = negotiate(&loaderInfo, "XR_APILAYER_PMH_tracker_bridge", request);
            LOGI("negotiate -> %d", nr);
        }
    }

    void *loader = dlopen("/system/lib64/libopenxr_loader.so", RTLD_NOW);
    if (loader == NULL) {
        snprintf(out, sizeof(out), "dlopen failed: %s", dlerror());
        LOGI("%s", out);
        return (*env)->NewStringUTF(env, out);
    }

    /* The loader only exports xrCreateInstance/xrGetInstanceProcAddr; the KHR
     * initialiser must be fetched through the proc-addr chain. */
    typedef XrResult (*PFN_get)(XrInstance, const char *, void **);
    PFN_get get = (PFN_get)dlsym(loader, "xrGetInstanceProcAddr");
    typedef XrResult (*PFN_init)(const XrLoaderInitInfoAndroidKHR *);
    PFN_init init = NULL;
    if (get != NULL) {
        void *fn = NULL;
        get((XrInstance)0, "xrInitializeLoaderKHR", &fn);
        init = (PFN_init)fn;
    }
    if (init == NULL) {
        snprintf(out, sizeof(out), "xrInitializeLoaderKHR missing");
        LOGI("%s", out);
        return (*env)->NewStringUTF(env, out);
    }

    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);

    XrLoaderInitInfoAndroidKHR initInfo;
    memset(&initInfo, 0, sizeof(initInfo));
    initInfo.type = XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR;
    initInfo.applicationVM = vm;
    initInfo.applicationContext = context;

    XrResult r = init(&initInfo);
    LOGI("xrInitializeLoaderKHR -> %d", r);
    snprintf(out, sizeof(out), "init=%d", r);

    typedef XrResult (*PFN_create)(const XrInstanceCreateInfo *, XrInstance *);
    PFN_create create = (PFN_create)dlsym(loader, "xrCreateInstance");
    if (create != NULL) {
        XrInstanceCreateInfo ci;
        memset(&ci, 0, sizeof(ci));
        ci.type = XR_TYPE_INSTANCE_CREATE_INFO;
        strncpy(ci.applicationInfo.applicationName, "PMHProbe",
                sizeof(ci.applicationInfo.applicationName) - 1);
        ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
        XrInstance instance = 0;
        r = create(&ci, &instance);
        LOGI("xrCreateInstance -> %d instance=%llu", r, (unsigned long long)instance);
        char tail[128];
        snprintf(tail, sizeof(tail), " create=%d", r);
        strncat(out, tail, sizeof(out) - strlen(out) - 1);
    }
    return (*env)->NewStringUTF(env, out);
}
