/* SPDX-License-Identifier: GPL-3.0-or-later */

#include "stream.h"

#include <assert.h>
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "whisper_stream"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGW(...) fprintf(stderr, __VA_ARGS__)
#endif

#define TCTX_LOGI(tctx, fmt, ...) LOGI("[ctx%d] " fmt, (tctx)->parity, ##__VA_ARGS__)
#define TCTX_LOGW(tctx, fmt, ...) LOGW("[ctx%d] " fmt, (tctx)->parity, ##__VA_ARGS__)

#define MIN(a, b) ((a) < (b) ? (a) : (b))

struct chunk_info
{
    int chunk_samples;
    int actual_chunk_samples;
    int overlap_offset;
    int64_t time_offset;
};

struct common_ctx
{
    whisper_stream_read_callback read_cb;
    void *read_cb_user_data;

    pthread_mutex_t mutex;
    pthread_cond_t cond;

    int next_chunk_idx;
    int64_t total_samples_read;
    bool eof;
    atomic_bool abort;
    bool single_thread;

    int overlap_samples;
    int min_chunk_samples;
    int max_chunk_samples;
    int min_silence_ms;

    float vad_threshold;

    int max_ctx_tokens;

    struct whisper_full_params params;

    float *read_buffer;
    int read_buffer_len;
    int buffer_size;

    whisper_stream_progress_callback progress_cb;
    void *progress_cb_user_data;

    whisper_stream_language_callback language_cb;
    void *language_cb_user_data;

    whisper_stream_abort_callback abort_cb;
    void *abort_cb_user_data;

    atomic_uintptr_t progress_reporter;
};

struct thread_ctx
{
    struct common_ctx *cctx;
    struct thread_ctx *other_tctx;
    struct whisper_context *ctx;
    struct whisper_vad_context *vad_ctx;
    float *buffer;
    int parity;
    int num_threads;

    whisper_token *tokens;
    int n_tokens;
    int lang_id;
    bool context_ready;

    int64_t time_offset;
    int64_t output_start;
    int64_t last_t1;
    whisper_stream_segment_callback segment_cb;
    void *segment_cb_user_data;

    int64_t samples_before_chunk;
    int chunk_samples;
};


static bool
stream_abort_callback(void *user_data)
{
    struct common_ctx *cctx = user_data;
    if (atomic_load(&cctx->abort))
        return true;
    if (cctx->abort_cb && cctx->abort_cb(cctx->abort_cb_user_data))
        return true;
    return false;
}

static void
stream_segment_callback(struct whisper_context *ctx, struct whisper_state *state,
                        int n_new, void *user_data)
{
    (void)state;
    struct thread_ctx *tctx = user_data;

    if (!tctx->segment_cb)
        return;

    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = n_segments - n_new; i < n_segments; i++)
    {
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) + tctx->time_offset;
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) + tctx->time_offset;

        /* Clip to chunk boundaries to prevent timestamp overlap between chunks */
        if (t0 < tctx->output_start)
            t0 = tctx->output_start;

        int64_t chunk_end = tctx->time_offset
                          + (tctx->chunk_samples * 100) / WHISPER_SAMPLE_RATE;
        if (t1 > chunk_end)
            t1 = chunk_end;

        if (t0 < tctx->last_t1)
            t0 = tctx->last_t1;
        if (t0 >= t1)
            continue;

        tctx->segment_cb(ctx, t0, t1, whisper_full_get_segment_text(ctx, i),
                         tctx->segment_cb_user_data);
        tctx->last_t1 = t1;
    }
}

static void
stream_progress_callback(struct whisper_context *ctx, struct whisper_state *state,
                         int progress, void *user_data)
{
    (void)ctx;
    (void)state;
    struct thread_ctx *tctx = user_data;
    struct common_ctx *cctx = tctx->cctx;

    /* Only report progress from the earlier whisper_full context */
    if ((uintptr_t)tctx != atomic_load(&cctx->progress_reporter))
        return;

    if (cctx->progress_cb)
        cctx->progress_cb(progress, tctx->samples_before_chunk,
                          tctx->chunk_samples, cctx->progress_cb_user_data);
}

static int
copy_tokens(struct thread_ctx *tctx, whisper_token *tokens_out,
            int max_tokens, int *lang_id_out)
{
    struct common_ctx *cctx = tctx->cctx;
    int n = MIN(tctx->n_tokens, max_tokens);

    int lang_id = tctx->lang_id;
    if (cctx->language_cb)
    {
        int override = cctx->language_cb(cctx->language_cb_user_data);
        if (override != -1 && override != lang_id)
        {
            lang_id = override;
            /* No context if lang changed */
            n = 0;
        }
    }

    if (n > 0)
        memcpy(tokens_out, tctx->tokens, n * sizeof *tokens_out);
    *lang_id_out = lang_id;

    TCTX_LOGI(tctx, "context_callback: lang: %d tokens: %d\n", lang_id, n);
    return n;
}

static int
stream_context_callback(struct whisper_context *ctx, struct whisper_state *state,
                        whisper_token *tokens_out, int max_tokens,
                        int *lang_id_out, void *user_data)
{
    (void)ctx;
    (void)state;
    struct thread_ctx *tctx = user_data;
    struct common_ctx *cctx = tctx->cctx;

    if (cctx->single_thread)
    {
        return copy_tokens(tctx, tokens_out, max_tokens, lang_id_out);
    }

    pthread_mutex_lock(&cctx->mutex);
    while (!tctx->context_ready && !atomic_load(&cctx->abort))
        pthread_cond_wait(&cctx->cond, &cctx->mutex);

    tctx->context_ready = false;

    if (atomic_load(&cctx->abort))
    {
        TCTX_LOGI(tctx, "context_callback: aborted\n");
        pthread_mutex_unlock(&cctx->mutex);
        return 0;
    }

    int n = copy_tokens(tctx, tokens_out, max_tokens, lang_id_out);

    pthread_mutex_unlock(&cctx->mutex);
    return n;
}

static struct whisper_vad_segments*
detect_vad_segments(struct thread_ctx *tctx, float *audio, int len)
{
    if (len <= 0 || !whisper_vad_detect_speech(tctx->vad_ctx, audio, len))
        return NULL;

    struct whisper_vad_params vad_params = whisper_vad_default_params();
    /* use default threshold for chunk detection */
    vad_params.min_silence_duration_ms = tctx->cctx->min_silence_ms;
    vad_params.max_speech_duration_s = len / (float) WHISPER_SAMPLE_RATE;
    struct whisper_vad_segments *segs =
        whisper_vad_segments_from_probs(tctx->vad_ctx, vad_params);

    if (segs && whisper_vad_segments_n_segments(segs) == 0)
    {
        whisper_vad_free_segments(segs);
        return NULL;
    }

    return segs;
}

static int
check_gap(int64_t gap_start_cs, int64_t gap_end_cs,
          int64_t range_start_cs, int64_t range_end_cs, int min_silence_ms)
{
    int gap_ms = (int)((gap_end_cs - gap_start_cs) * 10);
    if (gap_ms < min_silence_ms)
        return -1;
    if (gap_start_cs >= range_end_cs || gap_end_cs <= range_start_cs)
        return -1;

    int64_t gap_middle_cs = (gap_start_cs + gap_end_cs) / 2;
    if (gap_middle_cs < range_start_cs)
        gap_middle_cs = range_start_cs;
    if (gap_middle_cs > range_end_cs)
        gap_middle_cs = range_end_cs;
    int result_samples = (int)(gap_middle_cs * WHISPER_SAMPLE_RATE / 100);

    return result_samples;
}

static int
find_silence_in_segments(struct whisper_vad_segments *segs,
                         int range_start_samples, int range_end_samples,
                         int min_silence_ms, int vad_offset)
{
    if (!segs)
        return -1;

    const int n_segs = whisper_vad_segments_n_segments(segs);
    if (n_segs == 0)
        return -1;

    int64_t vad_offset_cs = (int64_t)vad_offset * 100 / WHISPER_SAMPLE_RATE;
    int64_t range_start_cs = (int64_t)range_start_samples * 100 / WHISPER_SAMPLE_RATE;
    int64_t range_end_cs = (int64_t)range_end_samples * 100 / WHISPER_SAMPLE_RATE;

    for (int i = 0; i < n_segs - 1; i++)
    {
        int64_t gap_start = whisper_vad_segments_get_segment_t1(segs, i)
                          + vad_offset_cs;
        int64_t gap_end = whisper_vad_segments_get_segment_t0(segs, i + 1)
                        + vad_offset_cs;

        if (gap_end <= range_start_cs)
            continue;
        if (gap_start >= range_end_cs)
            break;

        int pos = check_gap(gap_start, gap_end, range_start_cs, range_end_cs,
                            min_silence_ms);
        if (pos >= 0)
            return pos;
    }

    int64_t last_end = whisper_vad_segments_get_segment_t1(segs, n_segs - 1)
                     + vad_offset_cs;
    return check_gap(last_end, range_end_cs, range_start_cs, range_end_cs,
                     min_silence_ms);
}

static void
pass_context(struct thread_ctx *tctx)
{
    struct common_ctx *cctx = tctx->cctx;
    struct thread_ctx *dst = cctx->single_thread ? tctx : tctx->other_tctx;

    atomic_store(&cctx->progress_reporter, (uintptr_t)dst);

    if (!cctx->single_thread)
        pthread_mutex_lock(&cctx->mutex);

    int n = whisper_full_get_prompt_past(tctx->ctx, dst->tokens,
                                         cctx->max_ctx_tokens);

    dst->n_tokens = n;
    dst->lang_id = whisper_full_lang_id(tctx->ctx);
    dst->context_ready = true;

    if (!cctx->single_thread)
    {
        pthread_cond_signal(&cctx->cond);
        pthread_mutex_unlock(&cctx->mutex);
    }
}

static int
find_chunk_boundary(struct thread_ctx *tctx, int available,
                    struct whisper_vad_segments *vad_segs, int vad_offset,
                    int *silence_found)
{
    struct common_ctx *cctx = tctx->cctx;
    int search_start = cctx->min_chunk_samples;
    int search_end = MIN(cctx->max_chunk_samples, available);

    *silence_found = 0;

    if (search_start >= search_end)
        return search_end;

    if (!vad_segs)
        return search_start;

    int silence_pos = find_silence_in_segments(
        vad_segs, search_start, search_end, cctx->min_silence_ms, vad_offset);

    if (silence_pos > 0)
    {
        *silence_found = cctx->min_silence_ms;
        return silence_pos;
    }

    return search_end;
}

static struct chunk_info
make_chunk_info(struct common_ctx *cctx, int chunk_samples, int buffer_len,
                int overlap_offset, int64_t total_samples, bool eof)
{
    struct chunk_info info;

    info.chunk_samples = chunk_samples;
    if (eof && buffer_len - info.chunk_samples < cctx->min_chunk_samples)
        info.chunk_samples = buffer_len;

    info.overlap_offset = overlap_offset;
    info.actual_chunk_samples = info.chunk_samples + overlap_offset;
    info.time_offset = 100 * (total_samples - overlap_offset) / WHISPER_SAMPLE_RATE;

    return info;
}

static int
fill_read_buffer(struct common_ctx *cctx, int target_len, bool *eof)
{
    float *buffer = cctx->read_buffer;
    int buffer_len = cctx->read_buffer_len;

    while (buffer_len < target_len && !*eof)
    {
        int n_read = cctx->read_cb(buffer + buffer_len,
                                   cctx->buffer_size - buffer_len,
                                   cctx->read_cb_user_data);
        if (n_read <= 0)
        {
            *eof = true;
            break;
        }
        buffer_len += n_read;
    }
    cctx->read_buffer_len = buffer_len;
    return buffer_len;
}

static void
set_eof(struct common_ctx *cctx, bool and_abort)
{
    pthread_mutex_lock(&cctx->mutex);
    cctx->eof = true;
    if (and_abort)
        atomic_store(&cctx->abort, true);
    pthread_cond_signal(&cctx->cond);
    pthread_mutex_unlock(&cctx->mutex);
}

static int
wait_for_turn(struct thread_ctx *tctx, int *chunk_idx_out,
              int64_t *total_samples_out)
{
    struct common_ctx *cctx = tctx->cctx;

    if (cctx->single_thread)
    {
        if (cctx->eof)
            return -1;

        *chunk_idx_out = cctx->next_chunk_idx;
        *total_samples_out = cctx->total_samples_read;
        return cctx->read_buffer_len;
    }

    pthread_mutex_lock(&cctx->mutex);
    while ((cctx->next_chunk_idx % 2 != tctx->parity) && !cctx->eof)
        pthread_cond_wait(&cctx->cond, &cctx->mutex);

    if (cctx->eof)
    {
        pthread_mutex_unlock(&cctx->mutex);
        return -1;
    }

    *chunk_idx_out = cctx->next_chunk_idx;
    *total_samples_out = cctx->total_samples_read;
    int len = cctx->read_buffer_len;
    pthread_mutex_unlock(&cctx->mutex);
    return len;
}

static void
handoff_to_next(struct common_ctx *cctx, struct chunk_info *ci,
                int64_t total_samples, int chunk_idx, bool is_eof)
{
    if (!cctx->single_thread)
        pthread_mutex_lock(&cctx->mutex);

    int keep_start = ci->actual_chunk_samples - cctx->overlap_samples;
    if (keep_start < 0)
        keep_start = 0;
    int keep_len = cctx->read_buffer_len - keep_start;
    if (keep_len > 0)
    {
        memmove(cctx->read_buffer, cctx->read_buffer + keep_start,
                keep_len * sizeof *cctx->read_buffer);
        cctx->read_buffer_len = keep_len;
    }
    else
    {
        cctx->read_buffer_len = 0;
    }

    cctx->total_samples_read = total_samples + ci->chunk_samples;
    cctx->next_chunk_idx = chunk_idx + 1;
    /* Only overlap data remains - nothing new to transcribe */
    if (is_eof && keep_len <= cctx->overlap_samples)
        cctx->eof = true;

    if (!cctx->single_thread)
    {
        pthread_cond_signal(&cctx->cond);
        pthread_mutex_unlock(&cctx->mutex);
    }
}

static int
process_one_chunk(struct thread_ctx *tctx)
{
    struct common_ctx *cctx = tctx->cctx;
    int target_len = cctx->max_chunk_samples + cctx->overlap_samples;

    int chunk_idx;
    int64_t total_samples;

    if (wait_for_turn(tctx, &chunk_idx, &total_samples) < 0)
        return -1;

    int overlap_offset = (chunk_idx > 0) ? cctx->overlap_samples : 0;
    bool eof = false;
    int buffer_len;
    struct whisper_vad_segments *vad_segs = NULL;
    int found_boundary = -1;

    buffer_len = fill_read_buffer(cctx, target_len, &eof);

    int vad_start = 0;
    if (tctx->vad_ctx)
    {
        /* Start VAD 5s before search range to establish state */
        int margin = 5 * WHISPER_SAMPLE_RATE;
        vad_start = cctx->min_chunk_samples - margin;
        if (vad_start < 0)
            vad_start = 0;

        int available = buffer_len - overlap_offset;
        if (vad_start < available)
        {
            int vad_len = MIN(available - vad_start, cctx->max_chunk_samples - vad_start);
            if (vad_len > 0)
                vad_segs = detect_vad_segments(tctx,
                    cctx->read_buffer + overlap_offset + vad_start, vad_len);
        }
    }

    if (buffer_len > overlap_offset)
    {
        int available = buffer_len - overlap_offset;
        int silence_found;
        found_boundary = find_chunk_boundary(tctx, available, vad_segs,
                                             vad_start, &silence_found);
        if (silence_found > 0)
            TCTX_LOGI(tctx, "silence >=%dms at %dms\n", silence_found,
                      (found_boundary * 1000) / WHISPER_SAMPLE_RATE);
        else if (available > cctx->min_chunk_samples)
            TCTX_LOGW(tctx, "no silence in %d-%dms, splitting at max\n",
                      (cctx->min_chunk_samples * 1000) / WHISPER_SAMPLE_RATE,
                      (available * 1000) / WHISPER_SAMPLE_RATE);
        else
            TCTX_LOGI(tctx, "using remaining %dms\n",
                      (available * 1000) / WHISPER_SAMPLE_RATE);
    }

    if (vad_segs)
        whisper_vad_free_segments(vad_segs);

    if (buffer_len <= overlap_offset)
    {
        set_eof(cctx, false);
        return -1;
    }

    struct chunk_info ci = make_chunk_info(cctx, found_boundary,
                                           buffer_len - overlap_offset,
                                           overlap_offset, total_samples, eof);

    TCTX_LOGI(tctx, "chunk %d: %dms + %dms overlap, offset %lldms, "
              "buf_len=%d keep_start=%d total=%lld\n",
              chunk_idx,
              (ci.chunk_samples * 1000) / WHISPER_SAMPLE_RATE,
              (ci.overlap_offset * 1000) / WHISPER_SAMPLE_RATE,
              (long long)(ci.time_offset * 10),
              cctx->read_buffer_len,
              ci.actual_chunk_samples - cctx->overlap_samples,
              (long long)total_samples);

    memcpy(tctx->buffer, cctx->read_buffer,
           ci.actual_chunk_samples * sizeof *tctx->buffer);

    tctx->samples_before_chunk = total_samples;
    tctx->chunk_samples = ci.chunk_samples;

    handoff_to_next(cctx, &ci, total_samples, chunk_idx, eof);

    tctx->time_offset = ci.time_offset;
    tctx->output_start = ci.time_offset
                       + (ci.overlap_offset * 100) / WHISPER_SAMPLE_RATE;

    struct whisper_full_params params = cctx->params;
    params.n_threads = tctx->num_threads;
    params.duration_ms = (ci.actual_chunk_samples * 1000) / WHISPER_SAMPLE_RATE;
    params.new_segment_callback = stream_segment_callback;
    params.new_segment_callback_user_data = tctx;
    params.progress_callback = stream_progress_callback;
    params.progress_callback_user_data = tctx;
    params.abort_callback = stream_abort_callback;
    params.abort_callback_user_data = cctx;
    params.no_context = true;  /* context provided via callback */

    if (ci.overlap_offset > 0)
        params.offset_ms = (ci.overlap_offset * 1000) / WHISPER_SAMPLE_RATE;

    if (chunk_idx > 0)
    {
        params.context_callback = stream_context_callback;
        params.context_callback_user_data = tctx;
    }

    params.vad_params.threshold = tctx->cctx->vad_threshold;
    params.vad_params.min_silence_duration_ms = tctx->cctx->min_silence_ms;
    whisper_set_vad_context(tctx->ctx, tctx->vad_ctx);
    params.vad = true;

    TCTX_LOGI(tctx, "chunk %d: start\n", chunk_idx);
    int ret = whisper_full(tctx->ctx, params, tctx->buffer,
                           ci.actual_chunk_samples);
    TCTX_LOGI(tctx, "chunk %d: done: %d\n", chunk_idx, ret);

    bool aborted = false;
    if (cctx->abort_cb != NULL && cctx->abort_cb(cctx->abort_cb_user_data))
        aborted = true;

    if (ret != 0 || aborted)
    {
        set_eof(cctx, true);
        return ret;
    }

    pass_context(tctx);

    return 0;
}

static void *
worker_thread_func(void *arg)
{
    struct thread_ctx *tctx = arg;

    while (process_one_chunk(tctx) == 0)
        ;

    return NULL;
}

static int
init_thread_ctx(struct thread_ctx *tctx, struct common_ctx *cctx,
                struct whisper_stream_slot *slot, int parity)
{
    tctx->buffer = malloc(cctx->buffer_size * sizeof *tctx->buffer);
    if (!tctx->buffer)
        return -1;

    tctx->tokens = malloc(cctx->max_ctx_tokens * sizeof *tctx->tokens);
    if (!tctx->tokens)
    {
        free(tctx->buffer);
        return -1;
    }

    tctx->cctx = cctx;
    tctx->other_tctx = NULL;
    tctx->ctx = slot->ctx;
    tctx->vad_ctx = slot->vad_ctx;
    tctx->parity = parity;
    tctx->num_threads = slot->num_threads;
    tctx->n_tokens = 0;
    tctx->lang_id = -1;
    tctx->context_ready = false;
    tctx->time_offset = 0;
    tctx->output_start = 0;
    tctx->last_t1 = 0;
    tctx->segment_cb = NULL;
    tctx->segment_cb_user_data = NULL;

    return 0;
}

static void
cleanup_thread_ctx(struct thread_ctx *tctx)
{
    free(tctx->tokens);
    free(tctx->buffer);
}

static int
init_common_ctx(struct common_ctx *cctx,
                struct whisper_full_params params,
                struct whisper_stream_params *sparams,
                int max_ctx_tokens,
                int overlap_samples, int min_chunk_samples,
                int max_chunk_samples, bool single_thread)
{
    cctx->next_chunk_idx = 0;
    cctx->total_samples_read = 0;
    cctx->eof = false;
    atomic_init(&cctx->abort, false);
    cctx->single_thread = single_thread;

    cctx->vad_threshold = sparams->vad_threshold;

    pthread_mutex_init(&cctx->mutex, NULL);
    pthread_cond_init(&cctx->cond, NULL);

    cctx->read_cb = sparams->read_callback;
    cctx->read_cb_user_data = sparams->read_callback_user_data;
    cctx->overlap_samples = overlap_samples;
    cctx->min_chunk_samples = min_chunk_samples;
    cctx->max_chunk_samples = max_chunk_samples;
    cctx->min_silence_ms = sparams->min_silence_ms;
    cctx->max_ctx_tokens = max_ctx_tokens;
    cctx->params = params;

    cctx->buffer_size = max_chunk_samples + overlap_samples;
    cctx->read_buffer = malloc(cctx->buffer_size * sizeof *cctx->read_buffer);
    if (!cctx->read_buffer)
    {
        pthread_mutex_destroy(&cctx->mutex);
        pthread_cond_destroy(&cctx->cond);
        return -1;
    }
    cctx->read_buffer_len = 0;

    cctx->progress_cb = sparams->progress_callback;
    cctx->progress_cb_user_data = sparams->progress_callback_user_data;

    cctx->language_cb = sparams->language_callback;
    cctx->language_cb_user_data = sparams->language_callback_user_data;

    cctx->abort_cb = sparams->abort_callback;
    cctx->abort_cb_user_data = sparams->abort_callback_user_data;

    return 0;
}

static void
cleanup_common_ctx(struct common_ctx *cctx)
{
    free(cctx->read_buffer);
    pthread_mutex_destroy(&cctx->mutex);
    pthread_cond_destroy(&cctx->cond);
}

struct whisper_stream_params
whisper_stream_default_params(void)
{
    struct whisper_stream_params params;
    params.slots[0].ctx = NULL;
    params.slots[0].vad_ctx = NULL;
    params.slots[0].num_threads = 1;
    params.slots[1].ctx = NULL;
    params.slots[1].vad_ctx = NULL;
    params.slots[1].num_threads = 8;
    params.min_chunk_ms = 30000;
    params.chunk_extend_ms = 20000;
    params.overlap_ms = 300;
    params.min_silence_ms = 300;
    params.vad_threshold = 0.5f;
    params.read_callback = NULL;
    params.read_callback_user_data = NULL;
    params.segment_callback = NULL;
    params.segment_callback_user_data = NULL;
    params.progress_callback = NULL;
    params.progress_callback_user_data = NULL;
    params.language_callback = NULL;
    params.language_callback_user_data = NULL;
    params.abort_callback = NULL;
    params.abort_callback_user_data = NULL;
    return params;
}

int
whisper_stream_full(struct whisper_full_params params,
                    struct whisper_stream_params stream_params)
{
    assert(stream_params.slots[0].ctx);
    assert(stream_params.read_callback);
    assert(stream_params.min_chunk_ms > 0);
    assert(stream_params.overlap_ms >= 0 &&
           stream_params.overlap_ms < stream_params.min_chunk_ms);

    const bool dual = (stream_params.slots[1].ctx != NULL);
    const int min_chunk_samples =
        (WHISPER_SAMPLE_RATE * stream_params.min_chunk_ms) / 1000;
    const int max_chunk_samples = min_chunk_samples +
        (WHISPER_SAMPLE_RATE * stream_params.chunk_extend_ms) / 1000;
    const int overlap_samples =
        (WHISPER_SAMPLE_RATE * stream_params.overlap_ms) / 1000;
    const int max_ctx_tokens = whisper_n_text_ctx(stream_params.slots[0].ctx) / 2;

    struct common_ctx cctx;
    struct thread_ctx tctx0, tctx1;
    pthread_t worker_thread;

    if (init_common_ctx(&cctx, params, &stream_params, max_ctx_tokens,
                        overlap_samples, min_chunk_samples, max_chunk_samples,
                        !dual) != 0)
        return -1;

    if (init_thread_ctx(&tctx0, &cctx, &stream_params.slots[0], 0) != 0)
    {
        cleanup_common_ctx(&cctx);
        return -1;
    }
    tctx0.segment_cb = stream_params.segment_callback;
    tctx0.segment_cb_user_data = stream_params.segment_callback_user_data;

    atomic_init(&cctx.progress_reporter, (uintptr_t)&tctx0);

    if (dual)
    {
        if (init_thread_ctx(&tctx1, &cctx, &stream_params.slots[1], 1) != 0)
        {
            cleanup_thread_ctx(&tctx0);
            cleanup_common_ctx(&cctx);
            return -1;
        }
        tctx1.segment_cb = stream_params.segment_callback;
        tctx1.segment_cb_user_data = stream_params.segment_callback_user_data;

        tctx0.other_tctx = &tctx1;
        tctx1.other_tctx = &tctx0;

        pthread_create(&worker_thread, NULL, worker_thread_func, &tctx1);
    }

    while (process_one_chunk(&tctx0) == 0)
        ;

    if (dual)
    {
        pthread_join(worker_thread, NULL);
        cleanup_thread_ctx(&tctx1);
    }
    cleanup_thread_ctx(&tctx0);
    int ret = atomic_load(&cctx.abort) ? -1 : 0;
    cleanup_common_ctx(&cctx);

    return ret;
}
