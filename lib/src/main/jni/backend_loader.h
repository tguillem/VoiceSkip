/* SPDX-License-Identifier: GPL-3.0-or-later */

#ifndef VOICESKIP_BACKEND_LOADER_H
#define VOICESKIP_BACKEND_LOADER_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool
voiceskip_backend_load(const char *basename);

#ifdef __cplusplus
}
#endif

#endif
