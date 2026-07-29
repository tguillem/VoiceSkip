/* SPDX-License-Identifier: GPL-3.0-or-later */

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Describes the GPU whisper would select, without building a context.
 * Returns false when there is no usable GPU. */
bool
voiceskip_probe_gpu(char *desc, size_t desc_size);

#ifdef __cplusplus
}
#endif
