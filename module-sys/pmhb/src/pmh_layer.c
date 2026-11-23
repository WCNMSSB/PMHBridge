/*
 * PMHBridge OpenXR implicit API layer.
 *
 * Purpose: prove that Horizon OS's OpenXR loader picks up an implicit layer
 * installed under /system/etc/openxr/1/api_layers/implicit.d, and provide the
 * hook point where tracker poses produced by PMHBridge can later be injected
 * into XR_META_body_tracking / XR_FB_body_tracking calls.
 *
 * It is a pure pass-through: every call is forwarded to the next layer or the
 * runtime untouched.
 */
#include <android/log.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#define PMH_POSE_MAGIC 0x31484D50u
static void pose_path(char *out, size_t size);
#define PMH_MAX_TRACKERS 5

typedef struct PmhJoint PmhJoint;
static void pmh_apply_pose(PmhJoint *joints, uint32_t count);

#define LOG_TAG "PMHXRLayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define XR_SUCCESS 0
#define XR_ERROR_INITIALIZATION_FAILED (-1)
#define XR_MAX_API_LAYER_NAME_SIZE 256
#define XR_MAX_API_LAYER_PATH_SIZE 4096
#define XR_API_LAYER_MAX_SETTINGS_PATH_SIZE 512

typedef int32_t XrResult;
typedef uint64_t XrInstance;
typedef uint64_t XrVersion;

typedef struct XrInstanceCreateInfo XrInstanceCreateInfo;

typedef XrResult (*PFN_xrVoidFunction)(void);

typedef XrResult (*PFN_xrGetInstanceProcAddr)(XrInstance instance, const char *name,
                                              void **function);

typedef struct XrApiLayerNextInfo {
    int32_t structType;
    uint32_t structVersion;
    size_t structSize;
    char layerName[XR_MAX_API_LAYER_NAME_SIZE];
    PFN_xrGetInstanceProcAddr nextGetInstanceProcAddr;
    void *nextCreateApiLayerInstance;
    struct XrApiLayerNextInfo *next;
} XrApiLayerNextInfo;

typedef struct XrApiLayerCreateInfo {
    int32_t structType;
    uint32_t structVersion;
    size_t structSize;
    void *loaderInstance;
    char settings_file_location[XR_API_LAYER_MAX_SETTINGS_PATH_SIZE];
    XrApiLayerNextInfo *nextInfo;
} XrApiLayerCreateInfo;

typedef XrResult (*PFN_xrCreateApiLayerInstance)(const XrInstanceCreateInfo *info,
                                                 const XrApiLayerCreateInfo *layerInfo,
                                                 XrInstance *instance);

typedef struct XrNegotiateLoaderInfo {
    int32_t structType;
    uint32_t structVersion;
    size_t structSize;
    uint32_t minInterfaceVersion;
    uint32_t maxInterfaceVersion;
    XrVersion minApiVersion;
    XrVersion maxApiVersion;
} XrNegotiateLoaderInfo;

typedef struct XrNegotiateApiLayerRequest {
    int32_t structType;
    uint32_t structVersion;
    size_t structSize;
    uint32_t layerInterfaceVersion;
    XrVersion layerApiVersion;
    PFN_xrGetInstanceProcAddr getInstanceProcAddr;
    PFN_xrCreateApiLayerInstance createApiLayerInstance;
} XrNegotiateApiLayerRequest;

static PFN_xrGetInstanceProcAddr g_nextGetInstanceProcAddr;
static PFN_xrCreateApiLayerInstance g_nextCreateApiLayerInstance;

/* ---- XR_FB_body_tracking interception -------------------------------- */

typedef uint64_t XrBodyTrackerFB;
typedef uint64_t XrSpace;

typedef struct XrBodyJointsLocateInfoFB {
    int32_t type;
    const void *next;
    XrSpace baseSpace;
    int64_t time;
} XrBodyJointsLocateInfoFB;

typedef struct XrBodyJointLocationsFB {
    int32_t type;
    const void *next;
    uint32_t isActive;
    float confidence;
    uint32_t jointCount;
    const void *jointLocations;
    uint32_t skeletonChangedCount;
    int64_t time;
} XrBodyJointLocationsFB;

typedef XrResult (*PFN_create_body_tracker)(XrInstance, const void *, XrBodyTrackerFB *);
typedef XrResult (*PFN_locate_body_joints)(XrBodyTrackerFB, const XrBodyJointsLocateInfoFB *,
                                           XrBodyJointLocationsFB *);

static PFN_create_body_tracker g_nextCreateBodyTracker;
static PFN_locate_body_joints g_nextLocateBodyJoints;

static XrResult layer_create_body_tracker(XrInstance instance, const void *createInfo,
                                          XrBodyTrackerFB *tracker) {
    if (g_nextCreateBodyTracker == NULL) {
        void *fn = NULL;
        g_nextGetInstanceProcAddr(instance, "xrCreateBodyTrackerFB", &fn);
        g_nextCreateBodyTracker = (PFN_create_body_tracker)fn;
    }
    if (g_nextCreateBodyTracker == NULL) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    XrResult r = g_nextCreateBodyTracker(instance, createInfo, tracker);
    LOGI("xrCreateBodyTrackerFB -> %d tracker=%llu", r, (unsigned long long)(tracker ? *tracker : 0));
    return r;
}

static XrResult layer_locate_body_joints(XrBodyTrackerFB tracker,
                                         const XrBodyJointsLocateInfoFB *info,
                                         XrBodyJointLocationsFB *locations) {
    if (g_nextLocateBodyJoints == NULL) {
        void *fn = NULL;
        g_nextGetInstanceProcAddr(0, "xrLocateBodyJointsFB", &fn);
        g_nextLocateBodyJoints = (PFN_locate_body_joints)fn;
    }
    if (g_nextLocateBodyJoints == NULL) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    XrResult r = g_nextLocateBodyJoints(tracker, info, locations);
    if (locations != NULL) {
        if (r == 0 && locations->isActive && locations->jointLocations != NULL) {
            pmh_apply_pose((PmhJoint *)locations->jointLocations, locations->jointCount);
        }
        LOGI("xrLocateBodyJointsFB -> %d active=%u joints=%u conf=%.2f", r,
             locations->isActive, locations->jointCount, locations->confidence);
    }
    return r;
}

/* ---- pose driven leg joints ------------------------------------------ */

struct PmhJoint {
    float q[4]; /* x, y, z, w */
    float p[3]; /* position, metres */
    uint64_t flags;
};

#define PMH_THIGH_M 0.40f
#define PMH_SHIN_M 0.39f

static void pmh_rotate(const float q[4], const float v[3], float out[3]) {
    float x = q[0], y = q[1], z = q[2], w = q[3];
    float tx = 2.0f * (y * v[2] - z * v[1]);
    float ty = 2.0f * (z * v[0] - x * v[2]);
    float tz = 2.0f * (x * v[1] - y * v[0]);
    out[0] = v[0] + w * tx + (y * tz - z * ty);
    out[1] = v[1] + w * ty + (z * tx - x * tz);
    out[2] = v[2] + w * tz + (x * ty - y * tx);
}

/** Reads quaternion `index` from the shared pose file; 0 when unavailable. */
static int pmh_read_pose_n(int index, float q[4]) {
    if (index < 0 || index >= PMH_MAX_TRACKERS) {
        return 0;
    }
    const char *candidates[2] = {"/data/local/tmp/pmh_pose.bin", NULL};
    char fallback[512];
    pose_path(fallback, sizeof(fallback));
    candidates[1] = fallback[0] ? fallback : NULL;

    int fd = -1;
    for (int i = 0; i < 2 && fd < 0; ++i) {
        if (candidates[i] != NULL) {
            fd = open(candidates[i], O_RDONLY);
        }
    }
    if (fd < 0) {
        return 0;
    }
    uint32_t header[3] = {0, 0, 0};
    float values[4] = {0, 0, 0, 1};
    int ok = 0;
    if (read(fd, header, sizeof(header)) == (ssize_t)sizeof(header) &&
        header[0] == PMH_POSE_MAGIC && (int)header[2] > index) {
        off_t offset = (off_t)sizeof(header) + (off_t)index * (off_t)sizeof(values);
        if (lseek(fd, offset, SEEK_SET) == offset &&
            read(fd, values, sizeof(values)) == (ssize_t)sizeof(values)) {
            q[0] = values[0];
            q[1] = values[1];
            q[2] = values[2];
            q[3] = values[3];
            ok = 1;
        }
    }
    close(fd);
    return ok;
}

/* XR_FB_body_tracking joint indices used here. */
#define PMH_JOINT_LEFT_HIP 1
#define PMH_JOINT_RIGHT_HIP 2
#define PMH_JOINT_LEFT_UPPER_LEG 15
#define PMH_JOINT_LEFT_LOWER_LEG 16
#define PMH_JOINT_RIGHT_UPPER_LEG 19
#define PMH_JOINT_RIGHT_LOWER_LEG 20
#define PMH_JOINT_SPINE_MIDDLE 4
#define PMH_JOINT_COUNT 23

/**
 * Writes body joints from up to five tracker orientations.
 *
 * Slot 0 drives the left leg (15/16), slot 1 the right leg (19/20), slots 2/3
 * rotate the hips (1/2) and slot 4 the spine (4). Positions come from the hip
 * plus a forward-kinematics step along the tracked thigh direction.
 */
static void pmh_apply_pose(PmhJoint *joints, uint32_t count) {
    if (joints == NULL || count < PMH_JOINT_COUNT) {
        return;
    }

    float quats[PMH_MAX_TRACKERS][4];
    int have[PMH_MAX_TRACKERS];
    for (int i = 0; i < PMH_MAX_TRACKERS; ++i) {
        have[i] = pmh_read_pose_n(i, quats[i]);
    }
    if (!have[0] && !have[1] && !have[2] && !have[3] && !have[4]) {
        return;
    }

    static const int hipJoint[2] = {PMH_JOINT_LEFT_HIP, PMH_JOINT_RIGHT_HIP};
    static const int upperJoint[2] = {PMH_JOINT_LEFT_UPPER_LEG, PMH_JOINT_RIGHT_UPPER_LEG};
    static const int lowerJoint[2] = {PMH_JOINT_LEFT_LOWER_LEG, PMH_JOINT_RIGHT_LOWER_LEG};

    for (int leg = 0; leg < 2; ++leg) {
        int slot = leg;
        if (!have[slot]) {
            slot = have[0] ? 0 : (have[1] ? 1 : -1);
        }
        if (slot < 0) {
            continue;
        }
        const float *legQ = quats[slot];
        float hip[3] = {joints[hipJoint[leg]].p[0], joints[hipJoint[leg]].p[1],
                        joints[hipJoint[leg]].p[2]};
        float down[3] = {0.0f, -PMH_THIGH_M, 0.0f};
        float rotated[3];
        pmh_rotate(legQ, down, rotated);

        PmhJoint *upper = &joints[upperJoint[leg]];
        PmhJoint *lower = &joints[lowerJoint[leg]];
        upper->p[0] = hip[0];
        upper->p[1] = hip[1];
        upper->p[2] = hip[2];
        for (int i = 0; i < 4; ++i) {
            upper->q[i] = legQ[i];
        }
        lower->p[0] = hip[0] + rotated[0];
        lower->p[1] = hip[1] + rotated[1];
        lower->p[2] = hip[2] + rotated[2];
        for (int i = 0; i < 4; ++i) {
            lower->q[i] = legQ[i];
        }
        LOGI("leg %d slot=%d upper=(%.3f,%.3f,%.3f) lower=(%.3f,%.3f,%.3f)", leg, slot,
             upper->p[0], upper->p[1], upper->p[2], lower->p[0], lower->p[1], lower->p[2]);
    }

    /* Extra trackers only rotate; hips keep their positions. */
    if (have[2]) {
        for (int i = 0; i < 4; ++i) {
            joints[PMH_JOINT_LEFT_HIP].q[i] = quats[2][i];
        }
        LOGI("hip left rotated by slot 2");
    }
    if (have[3]) {
        for (int i = 0; i < 4; ++i) {
            joints[PMH_JOINT_RIGHT_HIP].q[i] = quats[3][i];
        }
        LOGI("hip right rotated by slot 3");
    }
    if (have[4]) {
        for (int i = 0; i < 4; ++i) {
            joints[PMH_JOINT_SPINE_MIDDLE].q[i] = quats[4][i];
        }
        LOGI("spine rotated by slot 4");
    }
}

/** Exported so the probe can exercise the pose path without a real app. */
__attribute__((visibility("default"))) void pmh_layer_selftest(void) {
    PmhJoint joints[PMH_JOINT_COUNT];
    memset(joints, 0, sizeof(joints));
    joints[PMH_JOINT_LEFT_HIP].p[0] = 0.09f;
    joints[PMH_JOINT_LEFT_HIP].p[1] = 0.95f;
    joints[PMH_JOINT_RIGHT_HIP].p[0] = -0.09f;
    joints[PMH_JOINT_RIGHT_HIP].p[1] = 0.95f;
    float selfQ[4] = {0, 0, 0, 1};
    LOGI("selftest pose available=%d", pmh_read_pose_n(0, selfQ));
    pmh_apply_pose(joints, PMH_JOINT_COUNT);
    LOGI("selftest done");
}

/* XR_FB_body_tracking skeleton path: some apps read bones instead of joints. */
typedef struct XrBodySkeletonJointFB {
    int32_t joint;
    int32_t parentJoint;
    float orientation[4]; /* x, y, z, w */
    float position[3];
} XrBodySkeletonJointFB;

typedef struct XrBodySkeletonFB {
    int32_t type;
    const void *next;
    uint32_t jointCount;
    XrBodySkeletonJointFB *joints;
} XrBodySkeletonFB;

typedef XrResult (*PFN_get_body_skeleton)(XrBodyTrackerFB, XrBodySkeletonFB *);

static PFN_get_body_skeleton g_nextGetBodySkeleton;

static XrResult layer_get_body_skeleton(XrBodyTrackerFB tracker, XrBodySkeletonFB *skeleton) {
    if (g_nextGetBodySkeleton == NULL) {
        void *fn = NULL;
        g_nextGetInstanceProcAddr(0, "xrGetBodySkeletonFB", &fn);
        g_nextGetBodySkeleton = (PFN_get_body_skeleton)fn;
    }
    if (g_nextGetBodySkeleton == NULL) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    XrResult r = g_nextGetBodySkeleton(tracker, skeleton);
    if (r == 0 && skeleton != NULL && skeleton->joints != NULL) {
        float q[4];
        if (pmh_read_pose_n(0, q)) {
            for (uint32_t i = 0; i < skeleton->jointCount; ++i) {
                XrBodySkeletonJointFB *j = &skeleton->joints[i];
                if (j->joint == 15 || j->joint == 16 || j->joint == 19 || j->joint == 20) {
                    for (int k = 0; k < 4; ++k) {
                        j->orientation[k] = q[k];
                    }
                }
            }
        }
        LOGI("xrGetBodySkeletonFB -> %d joints=%u", r, skeleton->jointCount);
    }
    return r;
}

static XrResult layer_get_instance_proc_addr(XrInstance instance, const char *name,
                                             void **function) {
    if (name == NULL) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    if (strcmp(name, "xrGetInstanceProcAddr") == 0) {
        *function = (void *)layer_get_instance_proc_addr;
        return XR_SUCCESS;
    }
    if (strcmp(name, "xrCreateBodyTrackerFB") == 0) {
        *function = (void *)layer_create_body_tracker;
        return XR_SUCCESS;
    }
    if (strcmp(name, "xrLocateBodyJointsFB") == 0) {
        *function = (void *)layer_locate_body_joints;
        return XR_SUCCESS;
    }
    if (strcmp(name, "xrGetBodySkeletonFB") == 0) {
        *function = (void *)layer_get_body_skeleton;
        return XR_SUCCESS;
    }
    /* Body tracking entry points are what PMHBridge will eventually rewrite. */
    if (strstr(name, "BodyTracking") != NULL || strstr(name, "body_tracking") != NULL ||
        strstr(name, "BodyJoints") != NULL) {
        LOGI("app asked for %s", name);
    }
    if (g_nextGetInstanceProcAddr == NULL) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    return g_nextGetInstanceProcAddr(instance, name, function);
}

/*
 * Pose channel.
 *
 * PMHBridge's module runs inside this same app process and writes the latest
 * tracker quaternion to <app files>/pmh_pose.bin. The layer reads it here and,
 * once body tracking interception lands, feeds it into the runtime calls.
 *
 * Layout (little endian): "PMH1" magic, version, tracker count, then per
 * tracker: 4 floats (x, y, z, w).
 */
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>



static void pose_path(char *out, size_t size) {
    char cmdline[256];
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) {
        out[0] = '\0';
        return;
    }
    ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
    close(fd);
    if (n <= 0) {
        out[0] = '\0';
        return;
    }
    cmdline[n] = '\0';
    /* Process name may be "pkg" or "pkg:service"; keep the package part. */
    char *colon = strchr(cmdline, ':');
    if (colon != NULL) {
        *colon = '\0';
    }
    snprintf(out, size, "/data/user/0/%s/files/pmh_pose.bin", cmdline);
}

static void log_pose_state(void) {
    char path[512];
    pose_path(path, sizeof(path));
    if (path[0] == '\0') {
        LOGI("pose: cannot resolve process name");
        return;
    }
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        LOGI("pose: %s not present yet", path);
        return;
    }
    uint32_t header[3] = {0, 0, 0};
    ssize_t n = read(fd, header, sizeof(header));
    close(fd);
    if (n != (ssize_t)sizeof(header) || header[0] != PMH_POSE_MAGIC) {
        LOGI("pose: %s has no valid header", path);
        return;
    }
    LOGI("pose: %s v%u trackers=%u", path, header[1], header[2]);
}

static XrResult layer_create_api_layer_instance(const XrInstanceCreateInfo *info,
                                                const XrApiLayerCreateInfo *layerInfo,
                                                XrInstance *instance) {
    if (layerInfo == NULL || layerInfo->nextInfo == NULL) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    XrApiLayerNextInfo *next = layerInfo->nextInfo;
    g_nextGetInstanceProcAddr = next->nextGetInstanceProcAddr;
    g_nextCreateApiLayerInstance = (PFN_xrCreateApiLayerInstance)next->nextCreateApiLayerInstance;

    LOGI("instance created through PMHBridge layer");
    log_pose_state();

    if (g_nextCreateApiLayerInstance == NULL) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    XrApiLayerCreateInfo forwarded = *layerInfo;
    forwarded.nextInfo = next->next;
    return g_nextCreateApiLayerInstance(info, &forwarded, instance);
}

__attribute__((visibility("default"))) XrResult
xrNegotiateLoaderApiLayerInterface(const XrNegotiateLoaderInfo *loaderInfo,
                                  const char *layerName,
                                  XrNegotiateApiLayerRequest *apiLayerRequest) {
    if (loaderInfo == NULL || apiLayerRequest == NULL) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    LOGI("negotiating layer %s (loader %u..%u)", layerName ? layerName : "?",
         loaderInfo->minInterfaceVersion, loaderInfo->maxInterfaceVersion);

    apiLayerRequest->layerInterfaceVersion = 1;
    apiLayerRequest->layerApiVersion = loaderInfo->maxApiVersion;
    apiLayerRequest->getInstanceProcAddr = layer_get_instance_proc_addr;
    apiLayerRequest->createApiLayerInstance = layer_create_api_layer_instance;
    return XR_SUCCESS;
}
