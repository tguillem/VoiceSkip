/* SPDX-License-Identifier: GPL-3.0-or-later */

#include <whisper.h>

#ifdef __cplusplus
extern "C" {
#endif

/* whisper_full() with backend exceptions contained. Returns whisper's own
 * result, or -1 when inference threw. */
int
voiceskip_whisper_full(struct whisper_context *ctx,
                       struct whisper_full_params params,
                       const float *samples, int n_samples);

#ifdef __cplusplus
}
#endif
