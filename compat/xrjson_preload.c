// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of PMHBridge.
//
// libpmh_xrjson — points a title's OpenXR loader at the PMH facade.
//
// Quest does not read the Khronos active_runtime.json files, so a title's bundled
// loader always resolves to Meta's runtime and rejects PICO interaction profiles.
// The Khronos loader does honour XR_RUNTIME_JSON, so this preloaded library only
// sets that variable; nothing is intercepted and no library is replaced. The
// facade is then loaded as the runtime, exactly as a runtime plugin should be.

#define _GNU_SOURCE
#include <stdlib.h>

#define PMH_RUNTIME_JSON "/data/local/tmp/active_runtime.json"

__attribute__((constructor)) static void pmh_set_runtime_json(void) {
    setenv("XR_RUNTIME_JSON", PMH_RUNTIME_JSON, 1);
}
