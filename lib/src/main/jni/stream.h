/* SPDX-License-Identifier: GPL-3.0-or-later */

#include <stdbool.h>
#include <whisper.h>

/* Stream read callback - returns samples read (>0), 0 for EOF, negative for error */
typedef int (*whisper_stream_read_callback)(float *samples,
                                            int n_samples_max,
                                            void *user_data);

/* Segment callback - timestamps in centiseconds, adjusted for chunk offset */
typedef void (*whisper_stream_segment_callback)(struct whisper_context *ctx,
                                                int64_t t0, int64_t t1,
                                                const char *text,
                                                void *user_data);

/* Progress callback - chunk_progress is 0-100 within current chunk */
typedef void (*whisper_stream_progress_callback)(int chunk_progress,
                                                 int64_t samples_before,
                                                 int chunk_samples,
                                                 void *user_data);

/* Language callback - returns lang_id to use (0 for auto-detect/no override) */
typedef int (*whisper_stream_language_callback)(void *user_data);

/* Abort callback - returns true to request abort */
typedef bool (*whisper_stream_abort_callback)(void *user_data);

struct whisper_stream_slot
{
    struct whisper_context *ctx;
    struct whisper_vad_context *vad_ctx;
    int num_threads;
};

struct whisper_stream_params
{
    struct whisper_stream_slot slots[2];  /* [1] NULL ctx for single-context mode */

    int   min_chunk_ms;
    /* extra time to search for silence, past min_chunk_ms */
    int   chunk_extend_ms;
    int   overlap_ms;
    int   min_silence_ms;

    float vad_threshold;

    whisper_stream_read_callback read_callback;
    void *read_callback_user_data;

    whisper_stream_segment_callback segment_callback;
    void *segment_callback_user_data;

    whisper_stream_progress_callback progress_callback;
    void *progress_callback_user_data;

    whisper_stream_language_callback language_callback;
    void *language_callback_user_data;

    whisper_stream_abort_callback abort_callback;
    void *abort_callback_user_data;
};

struct whisper_stream_params
whisper_stream_default_params(void);

/* Process audio in chunks, splitting at silence boundaries.
 * stream_params.ctx1 enables parallel processing (NULL for single context).
 * Returns 0 on success, negative on error. */
int
whisper_stream_full(struct whisper_full_params params,
                    struct whisper_stream_params stream_params);
