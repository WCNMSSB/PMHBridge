// PMHBridge: make PICO Connect believe two PICO controllers exist so its
// battery thread forwards Quest controller levels to the PC client.
//
// Layout facts recovered from the 10.6.6 build:
//   vector<ControllerData>            { begin, end, capacity }   (24 bytes)
//   sizeof(ControllerData)            0x38
//   ControllerData::ControllerButton  at +0x8
//     side_type                       +0x10   (int, ps_common::SideType)
//     active                          +0x14   (bool)
//   battery percent                   +0x30   (int)
//
// ps_engine::PxrControllerModuleImpl::GetControllerData(vector&) returns bool
// and fills the vector. On Quest it dereferences a null controller provider and
// segfaults, so its prologue is replaced with a jump to the shim below.

#include <android/log.h>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "PmhCtrlShim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* The loader swap rewrites a string inside libstreaming_server.so, which
 * shifts every symbol. Patch whichever of the two known layouts matches the
 * function prologue that is actually present in the mapped image. */
#define GET_CONTROLLER_DATA_OFFSET_A 0x540DFCULL
#define GET_CONTROLLER_DATA_OFFSET_B 0x4D1304ULL
static const uint8_t kPrologue[8] = {0x01, 0x08, 0x01, 0xad,
                                     0x22, 0x05, 0x40, 0xad};
#define CONTROLLER_DATA_SIZE 0x38
#define MAX_CONTROLLERS 2
#define BATTERY_JSON "/data/local/tmp/pmh_battery.json"

typedef struct {
    void *begin;
    void *end;
    void *cap;
} FakeVector;

static int read_levels(int *levels, int max) {
    FILE *f = fopen(BATTERY_JSON, "r");
    if (f == NULL) {
        return 0;
    }
    char buf[512];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    buf[n] = '\0';

    int found = 0;
    const char *p = buf;
    while (found < max) {
        const char *colon = strchr(p, ':');
        if (colon == NULL) {
            break;
        }
        int value = 0;
        int digits = 0;
        const char *q = colon + 1;
        while (*q != '\0' && *q != ',' && *q != '}') {
            if (*q >= '0' && *q <= '9') {
                value = value * 10 + (*q - '0');
                ++digits;
            }
            ++q;
        }
        if (digits > 0) {
            levels[found++] = value;
        }
        p = q;
    }
    return found;
}

static int shim_get_controller_data(void *self, FakeVector *out) {
    (void)self;
    if (out == NULL) {
        return 0;
    }

    int levels[MAX_CONTROLLERS] = {0, 0};
    int count = read_levels(levels, MAX_CONTROLLERS);
    if (count <= 0) {
        return 0;
    }

    /* libc++ is not in the app's global namespace here; operator delete frees
     * through free(), so plain malloc keeps the vector consistent. */
    uint8_t *data = (uint8_t *)malloc(CONTROLLER_DATA_SIZE * MAX_CONTROLLERS);
    if (data == NULL) {
        return 0;
    }
    memset(data, 0, CONTROLLER_DATA_SIZE * MAX_CONTROLLERS);

    for (int i = 0; i < count; ++i) {
        uint8_t *slot = data + i * CONTROLLER_DATA_SIZE;
        *(int32_t *)(slot + 0x10) = i;  /* SideType: 0 = left, 1 = right */
        *(uint8_t *)(slot + 0x14) = 1;  /* active */
        *(int32_t *)(slot + 0x30) = levels[i];
    }

    out->begin = data;
    out->end = data + count * CONTROLLER_DATA_SIZE;
    out->cap = out->end;
    LOGI("reported %d controllers: %d%%, %d%%", count, levels[0], levels[1]);
    return 1;
}

static uintptr_t g_bases[4];
static int g_base_count;

static int find_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    (void)data;
    if (info->dlpi_name != NULL &&
        strstr(info->dlpi_name, "libstreaming_server.so") != NULL) {
        if (g_base_count < (int)(sizeof(g_bases) / sizeof(g_bases[0]))) {
            g_bases[g_base_count++] = (uintptr_t)info->dlpi_addr;
        }
    }
    return 0;
}

static int patch_at(uintptr_t target) {
    long page = sysconf(_SC_PAGESIZE);
    void *page_addr = (void *)(target & ~((uintptr_t)page - 1));
    if (mprotect(page_addr, (size_t)page * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return 0;
    }
    uint32_t *code = (uint32_t *)target;
    code[0] = 0x58000050u;
    code[1] = 0xD61F0200u;
    *(uint64_t *)(target + 8) = (uint64_t)(uintptr_t)shim_get_controller_data;
    __builtin___clear_cache((char *)target, (char *)(target + 16));
    return 1;
}

static uintptr_t addr_for_file_offset(uintptr_t base, uint64_t file_off, int *ok) {
    const Elf64_Ehdr *eh = (const Elf64_Ehdr *)base;
    if (eh->e_ident[EI_MAG0] != ELFMAG0 || eh->e_ident[EI_MAG1] != ELFMAG1 ||
        eh->e_ident[EI_MAG2] != ELFMAG2 || eh->e_ident[EI_MAG3] != ELFMAG3) {
        return 0;
    }
    const Elf64_Phdr *ph = (const Elf64_Phdr *)(base + eh->e_phoff);
    for (int i = 0; i < eh->e_phnum; ++i) {
        if (ph[i].p_type != PT_LOAD) {
            continue;
        }
        if (file_off < ph[i].p_offset || file_off >= ph[i].p_offset + ph[i].p_filesz) {
            continue;
        }
        *ok = 1;
        return base + ph[i].p_vaddr + (file_off - ph[i].p_offset);
    }
    return 0;
}

static int try_patch(void) {
    g_base_count = 0;
    dl_iterate_phdr(find_cb, NULL);
    if (g_base_count == 0) {
        return 0;
    }
    int patched = 0;
    for (int i = 0; i < g_base_count; ++i) {
        const uint64_t offsets[2] = {GET_CONTROLLER_DATA_OFFSET_A,
                                     GET_CONTROLLER_DATA_OFFSET_B};
        for (int c = 0; c < 2; ++c) {
            int ok = 0;
            uintptr_t target = addr_for_file_offset(g_bases[i], offsets[c], &ok);
            if (!ok || target == 0) {
                continue;
            }
            if (memcmp((const void *)target, kPrologue, sizeof(kPrologue)) != 0) {
                LOGI("prologue mismatch base=%p off=%#llx first=%02x%02x%02x%02x",
                     (void *)g_bases[i], (unsigned long long)offsets[c],
                     ((const uint8_t *)target)[0], ((const uint8_t *)target)[1],
                     ((const uint8_t *)target)[2], ((const uint8_t *)target)[3]);
                continue;
            }
            if (patch_at(target)) {
                LOGI("hooked GetControllerData at %p (base %p off %#llx)", (void *)target,
                     (void *)g_bases[i], (unsigned long long)offsets[c]);
                ++patched;
            }
        }
    }
    return patched > 0;
}

static void *watch_thread(void *arg) {
    (void)arg;
    for (int i = 0; i < 300; ++i) {
        if (try_patch()) {
            return NULL;
        }
        usleep(200 * 1000);
    }
    LOGI("gave up waiting for libstreaming_server.so");
    return NULL;
}

__attribute__((constructor)) static void pmh_ctrl_shim_init(void) {
    /* Route the app's OpenXR loader to the PICO->Meta facade, which advertises
     * the PICO extensions the native streaming code probes. */
    if (try_patch()) {
        return;
    }
    pthread_t t;
    pthread_create(&t, NULL, watch_thread, NULL);
    pthread_detach(t);
}
