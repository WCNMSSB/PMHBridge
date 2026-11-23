/* Minimal OpenXR probe: initialise the loader through the proc-addr chain,
 * create an instance, then ask for a body tracker so PMHBridge's layer can be
 * observed intercepting the call. */
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "PmhXrLayerTest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

typedef int32_t XrResult;
typedef uint64_t XrInstance;
typedef uint64_t XrVersion;
typedef uint64_t XrBodyTrackerFB;
typedef uint64_t XrSpace;

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

typedef struct XrBodyTrackerCreateInfoFB {
    int32_t type;
    const void *next;
    int32_t jointSet; /* XrBodyJointSetFB */
} XrBodyTrackerCreateInfoFB;

#define XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR 1000000000
#define XR_TYPE_INSTANCE_CREATE_INFO 3
#define XR_TYPE_BODY_TRACKER_CREATE_INFO_FB 1000181000
#define XR_MAKE_VERSION(major, minor, patch) \
    ((((uint64_t)(major)) << 48) | (((uint64_t)(minor)) << 32) | ((uint64_t)(patch)))

JNIEXPORT jstring JNICALL
Java_com_pmh_xrlayertest_MainActivity_probe(JNIEnv *env, jclass clazz, jobject context) {
    static char out[512];
    out[0] = '\0';
    (void)clazz;

    void *loader = dlopen("/system/lib64/libopenxr_loader.so", RTLD_NOW);
    if (loader == NULL) {
        snprintf(out, sizeof(out), "dlopen: %s", dlerror());
        LOGI("%s", out);
        return (*env)->NewStringUTF(env, out);
    }

    typedef XrResult (*PFN_get)(XrInstance, const char *, void **);
    PFN_get get = (PFN_get)dlsym(loader, "xrGetInstanceProcAddr");
    if (get == NULL) {
        snprintf(out, sizeof(out), "no xrGetInstanceProcAddr");
        LOGI("%s", out);
        return (*env)->NewStringUTF(env, out);
    }

    /* am start does not give us the VR environment, so point the loader at the
     * runtime manifest and the API layer directory ourselves. */
    setenv("XR_RUNTIME_JSON", "/apex/com.meta.xr/active_runtime.aarch64.json", 1);
    setenv("XR_API_LAYER_PATH", "/system/etc/openxr/1/api_layers", 1);
    setenv("XR_ENABLE_API_LAYERS", "XR_APILAYER_PMH_tracker_bridge", 1);
    LOGI("env set: runtime json + layer path");

    typedef XrResult (*PFN_init)(const XrLoaderInitInfoAndroidKHR *);
    void *initFn = NULL;
    get((XrInstance)0, "xrInitializeLoaderKHR", &initFn);
    if (initFn != NULL) {
        JavaVM *vm = NULL;
        (*env)->GetJavaVM(env, &vm);
        XrLoaderInitInfoAndroidKHR info;
        memset(&info, 0, sizeof(info));
        info.type = XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR;
        info.applicationVM = vm;
        info.applicationContext = context;
        XrResult ir = ((PFN_init)initFn)(&info);
        LOGI("xrInitializeLoaderKHR -> %d", ir);
        snprintf(out, sizeof(out), "init=%d", ir);
    }

    typedef XrResult (*PFN_create)(const XrInstanceCreateInfo *, XrInstance *);
    void *createFn = NULL;
    get((XrInstance)0, "xrCreateInstance", &createFn);
    if (createFn == NULL) {
        strncat(out, " no xrCreateInstance", sizeof(out) - strlen(out) - 1);
        return (*env)->NewStringUTF(env, out);
    }
    XrInstanceCreateInfo ci;
    memset(&ci, 0, sizeof(ci));
    ci.type = XR_TYPE_INSTANCE_CREATE_INFO;
    strncpy(ci.applicationInfo.applicationName, "PMHLayerTest",
            sizeof(ci.applicationInfo.applicationName) - 1);
    ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    XrInstance instance = 0;
    XrResult r = ((PFN_create)createFn)(&ci, &instance);
    LOGI("xrCreateInstance -> %d instance=%llu", r, (unsigned long long)instance);
    char tail[96];
    snprintf(tail, sizeof(tail), " create=%d", r);
    strncat(out, tail, sizeof(out) - strlen(out) - 1);
    if (r != 0 || instance == 0) {
        return (*env)->NewStringUTF(env, out);
    }

    /* Body tracker creation goes through PMHBridge's layer. */
    typedef XrResult (*PFN_getproc)(XrInstance, const char *, void **);
    void *bodyCreateFn = NULL;
    get(instance, "xrCreateBodyTrackerFB", &bodyCreateFn);
    LOGI("xrCreateBodyTrackerFB ptr=%p", bodyCreateFn);
    if (bodyCreateFn != NULL) {
        typedef XrResult (*PFN_body_create)(XrInstance, const XrBodyTrackerCreateInfoFB *,
                                            XrBodyTrackerFB *);
        XrBodyTrackerCreateInfoFB bc;
        memset(&bc, 0, sizeof(bc));
        bc.type = XR_TYPE_BODY_TRACKER_CREATE_INFO_FB;
        bc.jointSet = 1; /* XR_BODY_JOINT_SET_DEFAULT_FB */
        XrBodyTrackerFB tracker = 0;
        XrResult br = ((PFN_body_create)bodyCreateFn)(instance, &bc, &tracker);
        LOGI("body tracker -> %d tracker=%llu", br, (unsigned long long)tracker);
        snprintf(tail, sizeof(tail), " body=%d", br);
        strncat(out, tail, sizeof(out) - strlen(out) - 1);
    }
    return (*env)->NewStringUTF(env, out);
}
