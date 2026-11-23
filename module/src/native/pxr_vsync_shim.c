// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
//
// PICO's compositor (libpxrcompositor.so) takes its vsync from a Qualcomm DRM
// node: /sys/class/drm/sde-crtc-0/vsync_event. Horizon OS has no such node, so
// the compositor logs
//
//   PxrCompositor: GetVsyncEvent ERROR ,should be here only once
//   PXRSDK_FT ERROR wait vsync for 8ms
//   MilliSecondsToNextVSync(): Error! VSync time is in the future by ...
//
// and never submits a frame. This shim makes the node appear to exist and feeds
// synthetic vsync timestamps at the panel rate, which is enough for the
// compositor's timing loop to advance.
//
// Build:
//   clang --target=aarch64-linux-android21 -shared -fPIC -O2 \
//         -o libpxrvsync.so pxr_vsync_shim.c -ldl -llog
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <android/log.h>

#define LOG_TAG "PmhVsync"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>
#include <link.h>
#include <dirent.h>
#include <linux/input.h>
#include <sys/ioctl.h>

#define VSYNC_PATH "vsync_event"
#define MAX_FDS 64
#define VSYNC_HZ 72

static int g_pipe[2] = {-1, -1};
static int g_shim_fds[MAX_FDS];
static int g_fd_count = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_started = 0;

typedef int (*open_fn)(const char *, int, ...);
typedef int (*openat_fn)(int, const char *, int, ...);
typedef ssize_t (*read_fn)(int, void *, size_t);
typedef int (*close_fn)(int);
typedef int (*access_fn)(const char *, int);
typedef int (*stat_fn)(const char *, struct stat *);


typedef void *(*dlopen_fn)(const char *, int);
typedef void *(*android_dlopen_ext_fn)(const char *, int, const void *);

static dlopen_fn real_dlopen;
static android_dlopen_ext_fn real_android_dlopen_ext;
static char g_libdir[PATH_MAX];
static void init_libdir(void);
static void start_evdev_reader(void);
static void preload_vndk(void);
static void *try_bundled(const char *name, int flags);

static open_fn real_open;
static openat_fn real_openat;
static read_fn real_read;
typedef FILE *(*fopen_fn)(const char *, const char *);
typedef size_t (*fread_fn)(void *, size_t, size_t, FILE *);
static fopen_fn real_fopen;
static fopen_fn real_fopen64;
static fread_fn real_fread;
typedef int (*fclose_fn)(FILE *);
static fclose_fn real_fclose;
#define MAX_FILES 8
static FILE *g_vsync_files[MAX_FILES];
static int g_vsync_file_count;
static close_fn real_close;
static access_fn real_access;
static stat_fn real_stat;

static int is_vsync_path(const char *path) {
    if (path != NULL && (strstr(path, "vsync") != NULL || strstr(path, "drm") != NULL)) {
        LOGI("probe %s", path);
    }
    return path != NULL && strstr(path, VSYNC_PATH) != NULL;
}

static void remember_fd(int fd) {
    pthread_mutex_lock(&g_lock);
    if (g_fd_count < MAX_FDS) {
        g_shim_fds[g_fd_count++] = fd;
    }
    pthread_mutex_unlock(&g_lock);
}

static int forget_fd(int fd) {
    int found = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_fd_count; ++i) {
        if (g_shim_fds[i] == fd) {
            g_shim_fds[i] = g_shim_fds[--g_fd_count];
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return found;
}

static int is_shim_fd(int fd) {
    int found = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_fd_count; ++i) {
        if (g_shim_fds[i] == fd) {
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return found;
}

/** Writes one 8-byte monotonic timestamp per vsync period into the pipe. */
static void *vsync_thread(void *arg) {
    (void) arg;
    const long period_ns = 1000000000L / VSYNC_HZ;
    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    while (1) {
        next.tv_nsec += period_ns;
        while (next.tv_nsec >= 1000000000L) {
            next.tv_nsec -= 1000000000L;
            next.tv_sec += 1;
        }
        int rc;
        do {
            rc = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
        } while (rc == EINTR);

        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t stamp = (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
        ssize_t ignored = write(g_pipe[1], &stamp, sizeof(stamp));
        (void) ignored;
    }
    return NULL;
}

__attribute__((constructor)) static void shim_init(void) {
    LOGI("shim constructor pid=%d", getpid());
    {
        FILE *marker = fopen("/data/local/tmp/pmh_vsync_loaded.txt", "a");
        if (marker) {
            fprintf(marker, "pid=%d\n", getpid());
            fclose(marker);
        }
    }
    // The runtime dlopens this shim locally, so its symbols never interpose on
    // later libraries. Promoting it to the global scope makes the compositor bind
    // its open/fread calls here instead of to libc.
    {
        Dl_info info;
        if (dladdr((void *) &shim_init, &info) != 0 && info.dli_fname != NULL) {
            void *self = dlopen(info.dli_fname, RTLD_NOW | RTLD_GLOBAL);
            LOGI("self promote %s -> %p", info.dli_fname, self);
        }
    }

    real_open = (open_fn) dlsym(RTLD_NEXT, "open");
    real_openat = (openat_fn) dlsym(RTLD_NEXT, "openat");
    real_read = (read_fn) dlsym(RTLD_NEXT, "read");
    real_fopen = (fopen_fn) dlsym(RTLD_NEXT, "fopen");
    real_fopen64 = (fopen_fn) dlsym(RTLD_NEXT, "fopen64");
    real_fread = (fread_fn) dlsym(RTLD_NEXT, "fread");
    real_fclose = (fclose_fn) dlsym(RTLD_NEXT, "fclose");
    real_close = (close_fn) dlsym(RTLD_NEXT, "close");
    real_access = (access_fn) dlsym(RTLD_NEXT, "access");
    real_stat = (stat_fn) dlsym(RTLD_NEXT, "stat");
    {
        FILE *f = fopen("/data/user/0/com.pico.xr.openxr_runtime/cache/pmh_shim.log", "a");
        if (f) { fprintf(f, "constructor pid=%d\n", getpid()); fclose(f); }
    }
    real_dlopen = (dlopen_fn) dlsym(RTLD_NEXT, "dlopen");
    real_android_dlopen_ext = (android_dlopen_ext_fn) dlsym(RTLD_NEXT, "android_dlopen_ext");
    init_libdir();

    /* Preload VNDK libs that Android refuses to extract (their file names do not
     * start with "lib", so the installer skips them). They are shipped renamed as
     * libpmhvndk_*.so and registered with RTLD_GLOBAL so DT_NEEDED lookups by
     * their real soname succeed. */
    preload_vndk();
    start_evdev_reader();

    if (pipe(g_pipe) != 0) {
        return;
    }
    fcntl(g_pipe[0], F_SETFL, O_NONBLOCK);
    pthread_t thread;
    if (pthread_create(&thread, NULL, vsync_thread, NULL) == 0) {
        pthread_detach(thread);
        g_started = 1;
    }
}

static void remember_file(FILE *f) {
    pthread_mutex_lock(&g_lock);
    if (g_vsync_file_count < MAX_FILES) {
        g_vsync_files[g_vsync_file_count++] = f;
    }
    pthread_mutex_unlock(&g_lock);
}

static int is_vsync_file(FILE *f) {
    int found = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_vsync_file_count; ++i) {
        if (g_vsync_files[i] == f) {
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return found;
}

FILE *fopen(const char *path, const char *mode) {
    FILE *f = real_fopen ? real_fopen(path, mode) : NULL;
    if (f != NULL && is_vsync_path(path)) {
        remember_file(f);
    }
    return f;
}

FILE *fopen64(const char *path, const char *mode) {
    fopen_fn open_impl = real_fopen64 ? real_fopen64 : real_fopen;
    FILE *f = open_impl ? open_impl(path, mode) : NULL;
    if (f != NULL && is_vsync_path(path)) {
        remember_file(f);
    }
    return f;
}

static void forget_file(FILE *f) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_vsync_file_count; ++i) {
        if (g_vsync_files[i] == f) {
            g_vsync_files[i] = g_vsync_files[--g_vsync_file_count];
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
}

/** Serves binary vsync stamps to stdio readers, which bypass open()/read(). */
size_t fread(void *ptr, size_t size, size_t nmemb, FILE *stream) {
    if (stream != NULL && is_vsync_file(stream)) {
        size_t want = size * nmemb;
        if (want < sizeof(int64_t) || size == 0) {
            return 0;
        }
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t stamp = (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
        memcpy(ptr, &stamp, sizeof(stamp));
        return sizeof(stamp) / size;
    }
    return real_fread ? real_fread(ptr, size, nmemb, stream) : 0;
}

size_t fread_unlocked(void *ptr, size_t size, size_t nmemb, FILE *stream) {
    if (stream != NULL && is_vsync_file(stream)) {
        size_t want = size * nmemb;
        if (want < sizeof(int64_t) || size == 0) {
            return 0;
        }
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t stamp = (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
        memcpy(ptr, &stamp, sizeof(stamp));
        return sizeof(stamp) / size;
    }
    typedef size_t (*fru_fn)(void *, size_t, size_t, FILE *);
    static fru_fn real_fru;
    if (real_fru == NULL) {
        real_fru = (fru_fn) dlsym(RTLD_NEXT, "fread_unlocked");
    }
    return real_fru ? real_fru(ptr, size, nmemb, stream) : 0;
}

int fclose(FILE *stream) {
    // A closed handle must leave the table, otherwise a recycled FILE* would be
    // served fake timestamps and corrupt unrelated reads.
    forget_file(stream);
    return real_fclose ? real_fclose(stream) : 0;
}

int open(const char *path, int flags, ...) {
    if (is_vsync_path(path) && real_open != NULL) {
        int fd = dup(g_pipe[0]);
        if (fd >= 0) {
            remember_fd(fd);
            return fd;
        }
    }
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t) va_arg(args, int);
        va_end(args);
    }
    return real_open ? real_open(path, flags, mode) : -1;
}

int open64(const char *path, int flags, ...) {
    if (is_vsync_path(path)) {
        int fd = dup(g_pipe[0]);
        if (fd >= 0) {
            remember_fd(fd);
            return fd;
        }
    }
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t) va_arg(args, int);
        va_end(args);
    }
    return real_open ? real_open(path, flags, mode) : -1;
}

int openat(int dirfd, const char *path, int flags, ...) {
    if (is_vsync_path(path)) {
        int fd = dup(g_pipe[0]);
        if (fd >= 0) {
            remember_fd(fd);
            return fd;
        }
    }
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t) va_arg(args, int);
        va_end(args);
    }
    return real_openat ? real_openat(dirfd, path, flags, mode) : -1;
}

ssize_t read(int fd, void *buffer, size_t count) {
    if (is_shim_fd(fd)) {
        // The compositor reads a binary 64-bit timestamp, not the text form the
        // stock Qualcomm node prints; feeding it text aborts pxr_initialize.
        if (count < sizeof(int64_t)) {
            return -1;
        }
        int64_t stamp = 0;
        ssize_t n = real_read ? real_read(fd, &stamp, sizeof(stamp)) : -1;
        if (n != (ssize_t) sizeof(stamp)) {
            return 0;
        }
        memcpy(buffer, &stamp, sizeof(stamp));
        return sizeof(stamp);
    }
    return real_read ? real_read(fd, buffer, count) : -1;
}

int close(int fd) {
    forget_fd(fd);
    return real_close ? real_close(fd) : 0;
}

int access(const char *path, int mode) {
    if (is_vsync_path(path)) {
        return 0;
    }
    return real_access ? real_access(path, mode) : -1;
}

int stat(const char *path, struct stat *buf) {
    if (is_vsync_path(path)) {
        if (buf != NULL) {
            memset(buf, 0, sizeof(*buf));
            buf->st_mode = S_IFREG | 0444;
            buf->st_size = 8;
        }
        return 0;
    }
    return real_stat ? real_stat(path, buf) : -1;
}

int lstat(const char *path, struct stat *buf) {
    return stat(path, buf);
}



/* ---------------------------------------------------------------------------
 * Controller button source.
 *
 * Quest exposes each controller as an evdev node (/dev/input/event4 is the RUBY
 * controller) carrying KEY and ABS events, but no 6DoF pose - poses only exist
 * inside VrApi. Buttons, triggers and sticks are enough to make PXR titles react,
 * so they are polled here and written to a small state file that the PICO input
 * service can map.
 * ------------------------------------------------------------------------- */
#define PXR_INPUT_LOG "/data/local/tmp/pxr_input.log"
#define PXR_EVDEV "/dev/input/event4"

static void *evdev_thread(void *arg) {
    (void) arg;
    int fd = open(PXR_EVDEV, O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        return NULL;
    }
    FILE *log = fopen(PXR_INPUT_LOG, "a");
    if (log != NULL) {
        fprintf(log, "evdev opened\n");
        fclose(log);
    }
    int logged = 0;
    while (1) {
        struct input_event ev;
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n != (ssize_t) sizeof(ev)) {
            usleep(2000);
            continue;
        }
        if (ev.type != EV_KEY && ev.type != EV_ABS) {
            continue;
        }
        if (logged > 4000) {
            continue;
        }
        FILE *f = fopen(PXR_INPUT_LOG, "a");
        if (f != NULL) {
            fprintf(f, "type=%u code=%u value=%d\n", ev.type, ev.code, ev.value);
            fclose(f);
            ++logged;
        }
    }
    return NULL;
}

static void start_evdev_reader(void) {
    pthread_t thread;
    if (pthread_create(&thread, NULL, evdev_thread, NULL) == 0) {
        pthread_detach(thread);
    }
}

/* ---------------------------------------------------------------------------
 * dlopen redirector.
 *
 * PICO's runtime dlopen()s VNDK/private libs that Horizon keeps out of an app's
 * linker namespace ("dlopen failed: library X not found"), even though X ships
 * inside the APK. Intercepting dlopen lets the request fall back to the copy the
 * APK already carries.
 * ------------------------------------------------------------------------- */

static void init_libdir(void) {
    Dl_info info;
    if (dladdr((void *) init_libdir, &info) != 0 && info.dli_fname != NULL) {
        snprintf(g_libdir, sizeof(g_libdir), "%s", info.dli_fname);
        char *slash = strrchr(g_libdir, '/');
        if (slash != NULL) {
            *slash = '\0';
        }
        LOGI("libdir=%s", g_libdir);
    }
}

static void log_line(const char *fmt, const char *arg) {
    FILE *f = fopen("/data/user/0/com.pico.xr.openxr_runtime/cache/pmh_shim.log", "a");
    if (f) { fprintf(f, fmt, arg); fclose(f); }
}


static void preload_one(const char *name) {
    if (g_libdir[0] == '\0' || name == NULL) {
        return;
    }
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", g_libdir, name);
    if (access(path, R_OK) != 0) {
        return;
    }
    dlerror();
    void *handle = real_dlopen ? real_dlopen(path, RTLD_NOW | RTLD_GLOBAL) : NULL;
    const char *err = handle ? NULL : dlerror();
    char msg[PATH_MAX + 256];
    snprintf(msg, sizeof(msg), "preload %s -> %p %s", name, handle, err ? err : "");
    FILE *f = fopen("/data/user/0/com.pico.xr.openxr_runtime/cache/pmh_shim.log", "a");
    if (f) {
        fprintf(f, "%s\n", msg);
        fclose(f);
    }
    LOGW("%s", msg);
}

/* Ordered preload: the V4 graphics lib needs the V2 common lib loaded first, and
 * both are shipped renamed because the installer skips names not starting with
 * "lib". */
static void preload_vndk(void) {
    if (g_libdir[0] == '\0') {
        return;
    }
    /* Multi-pass: a lib whose DT_NEEDED sibling is not loaded yet fails on the
     * first pass and succeeds on a later one, so repeat until nothing changes. */
    for (int pass = 0; pass < 6; ++pass) {
        DIR *dir = opendir(g_libdir);
        if (dir == NULL) {
            return;
        }
        struct dirent *entry;
        while ((entry = readdir(dir)) != NULL) {
            if (strncmp(entry->d_name, "libpmhvndk", 10) != 0) {
                continue;
            }
            preload_one(entry->d_name);
        }
        closedir(dir);
    }
}

static void *try_bundled(const char *name, int flags) {
    log_line("miss %s\n", name ? name : "(null)");
    if (g_libdir[0] == '\0' || name == NULL || strchr(name, '/') != NULL) {
        return NULL;
    }
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", g_libdir, name);
    if (access(path, R_OK) != 0) {
        return NULL;
    }
    LOGW("redirecting %s -> %s", name, path);
    return real_dlopen ? real_dlopen(path, flags) : NULL;
}

void *dlopen(const char *name, int flags) {
    void *handle = real_dlopen ? real_dlopen(name, flags) : NULL;
    if (handle != NULL) {
        return handle;
    }
    void *fallback = try_bundled(name, flags);
    if (fallback != NULL) {
        return fallback;
    }
    return NULL;
}

void *android_dlopen_ext(const char *name, int flags, const void *extinfo) {
    void *handle = real_android_dlopen_ext
        ? real_android_dlopen_ext(name, flags, extinfo) : NULL;
    if (handle != NULL) {
        return handle;
    }
    void *fallback = try_bundled(name, flags);
    if (fallback != NULL) {
        return fallback;
    }
    return NULL;
}

/* Horizon's libbinder lacks this PICO binder callback, which blocks the runtime's
 * tracking client from loading. A no-op definition satisfies the linker. */
int pmh_train_on_transact(unsigned int a, void *b, void *c, unsigned int d)
        __asm__("_ZN7android22BnTrainArrviesCallback10onTransactEjRKNS_6ParcelEPS1_j");
int pmh_train_on_transact(unsigned int a, void *b, void *c, unsigned int d) {
    (void) a; (void) b; (void) c; (void) d;
    return 0;
}

