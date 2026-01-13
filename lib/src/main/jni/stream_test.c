/* SPDX-License-Identifier: GPL-3.0-or-later */

#include "stream.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>

#include <getopt.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static atomic_bool *g_abort_ptr = NULL;

static void
sigint_handler(int sig)
{
    (void)sig;
    if (g_abort_ptr)
        atomic_store(g_abort_ptr, true);
}

static bool
abort_cb(void *user_data)
{
    atomic_bool *abort = user_data;
    return atomic_load(abort);
}

struct decode_ctx
{
    AVFormatContext *fmt_ctx;
    AVCodecContext *codec_ctx;
    AVPacket *pkt;
    AVFrame *frame;
    SwrContext *swr_ctx;
    float *samples;
    int sample_count;
    int sample_capacity;
};

static int
ensure_capacity(struct decode_ctx *d, int needed)
{
    if (d->sample_count + needed <= d->sample_capacity)
        return 0;

    int new_cap = d->sample_capacity ? d->sample_capacity * 2 : 1024 * 1024;
    while (new_cap < d->sample_count + needed)
        new_cap *= 2;
    float *tmp = realloc(d->samples, new_cap * sizeof(float));
    if (!tmp)
        return -1;
    d->samples = tmp;
    d->sample_capacity = new_cap;
    return 0;
}

static int
drain_frames(struct decode_ctx *d)
{
    while (avcodec_receive_frame(d->codec_ctx, d->frame) >= 0)
    {
        int out_samples = swr_get_out_samples(d->swr_ctx, d->frame->nb_samples);
        if (ensure_capacity(d, out_samples) < 0)
        {
            av_frame_unref(d->frame);
            return -1;
        }

        uint8_t *out_buf = (uint8_t *)(d->samples + d->sample_count);
        int converted = swr_convert(d->swr_ctx, &out_buf, out_samples,
                                    (const uint8_t **)d->frame->extended_data,
                                    d->frame->nb_samples);
        if (converted > 0)
            d->sample_count += converted;

        av_frame_unref(d->frame);
    }
    return 0;
}

static int
flush_resampler(struct decode_ctx *d)
{
    while (swr_get_delay(d->swr_ctx, WHISPER_SAMPLE_RATE) > 0)
    {
        if (ensure_capacity(d, 1024) < 0)
            return -1;
        uint8_t *out_buf = (uint8_t *)(d->samples + d->sample_count);
        int converted = swr_convert(d->swr_ctx, &out_buf, 1024, NULL, 0);
        if (converted <= 0)
            break;
        d->sample_count += converted;
    }
    return 0;
}

static void
decode_ctx_free(struct decode_ctx *d)
{
    free(d->samples);
    av_frame_free(&d->frame);
    av_packet_free(&d->pkt);
    swr_free(&d->swr_ctx);
    avcodec_free_context(&d->codec_ctx);
    avformat_close_input(&d->fmt_ctx);
}

static float *
read_avcodec(const char *path, int *n_samples)
{
    struct decode_ctx d = {0};

    if (avformat_open_input(&d.fmt_ctx, path, NULL, NULL) < 0)
    {
        fprintf(stderr, "Failed to open: %s\n", path);
        return NULL;
    }

    if (avformat_find_stream_info(d.fmt_ctx, NULL) < 0)
    {
        fprintf(stderr, "Failed to find stream info\n");
        goto cleanup;
    }

    int stream_idx = av_find_best_stream(d.fmt_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream_idx < 0)
    {
        fprintf(stderr, "No audio stream found\n");
        goto cleanup;
    }

    AVStream *stream = d.fmt_ctx->streams[stream_idx];
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec)
    {
        fprintf(stderr, "Unsupported codec\n");
        goto cleanup;
    }

    d.codec_ctx = avcodec_alloc_context3(codec);
    if (!d.codec_ctx)
        goto cleanup;

    if (avcodec_parameters_to_context(d.codec_ctx, stream->codecpar) < 0)
        goto cleanup;

    d.codec_ctx->thread_count = 1;

    if (avcodec_open2(d.codec_ctx, codec, NULL) < 0)
    {
        fprintf(stderr, "Failed to open codec\n");
        goto cleanup;
    }

    AVChannelLayout out_layout = AV_CHANNEL_LAYOUT_MONO;
    if (swr_alloc_set_opts2(&d.swr_ctx,
                            &out_layout, AV_SAMPLE_FMT_FLT, WHISPER_SAMPLE_RATE,
                            &d.codec_ctx->ch_layout, d.codec_ctx->sample_fmt, d.codec_ctx->sample_rate,
                            0, NULL) < 0)
        goto cleanup;

    if (swr_init(d.swr_ctx) < 0)
    {
        fprintf(stderr, "Failed to init resampler\n");
        goto cleanup;
    }

    d.pkt = av_packet_alloc();
    d.frame = av_frame_alloc();
    if (!d.pkt || !d.frame)
        goto cleanup;

    while (av_read_frame(d.fmt_ctx, d.pkt) >= 0)
    {
        if (d.pkt->stream_index != stream_idx)
        {
            av_packet_unref(d.pkt);
            continue;
        }

        if (avcodec_send_packet(d.codec_ctx, d.pkt) < 0)
        {
            av_packet_unref(d.pkt);
            continue;
        }

        if (drain_frames(&d) < 0)
            goto cleanup;

        av_packet_unref(d.pkt);
    }

    /* Flush decoder */
    avcodec_send_packet(d.codec_ctx, NULL);
    if (drain_frames(&d) < 0)
        goto cleanup;

    if (flush_resampler(&d) < 0)
        goto cleanup;

    *n_samples = d.sample_count;

    float *samples = d.samples;
    d.samples = NULL;
    decode_ctx_free(&d);
    return samples;

cleanup:
    decode_ctx_free(&d);
    *n_samples = 0;
    return NULL;
}

struct file_ctx
{
    const float *samples;
    int n_samples;
    int pos;
};

static int
file_read_cb(float *out, int n_max, void *user_data)
{
    struct file_ctx *ctx = user_data;
    int remaining = ctx->n_samples - ctx->pos;
    int n = (n_max < remaining) ? n_max : remaining;
    if (n > 0)
    {
        memcpy(out, ctx->samples + ctx->pos, n * sizeof(float));
        ctx->pos += n;
    }
    return n;
}

static void
segment_cb(struct whisper_context *ctx, int64_t t0, int64_t t1,
           const char *text, void *user_data)
{
    (void)ctx;
    (void)user_data;
    int64_t ms0 = t0 * 10;
    int64_t ms1 = t1 * 10;
    printf("[%02d:%02d.%03d --> %02d:%02d.%03d]%s\n",
           (int)(ms0 / 60000), (int)((ms0 % 60000) / 1000), (int)(ms0 % 1000),
           (int)(ms1 / 60000), (int)((ms1 % 60000) / 1000), (int)(ms1 % 1000),
           text);
    fflush(stdout);
}

static void
log_disable(enum ggml_log_level level, const char *text, void *user_data)
{
    (void)level;
    (void)text;
    (void)user_data;
}

static void
usage(const char *prog)
{
    fprintf(stderr, "Usage: %s -m model -f file [options]\n", prog);
    fprintf(stderr, "  -m, --model PATH      Whisper model\n");
    fprintf(stderr, "  -f, --file PATH       Input audio/video file\n");
    fprintf(stderr, "  -s, --stream N        Stream contexts (1 or 2)\n");
    fprintf(stderr, "  -l, --language LANG   Language (default: en)\n");
    fprintf(stderr, "  -t, --threads N       Thread count\n");
    fprintf(stderr, "      --no-gpu          Disable GPU\n");
    fprintf(stderr, "  -v, --vad-model PATH  VAD model\n");
    fprintf(stderr, "  -d, --debug           Enable debug output\n");
    fprintf(stderr, "  -L, --live            Live mode (5s min, 10s extend, 200ms silence)\n");
}

static int
init_context(const char *model_path, const char *vad_model, bool use_gpu,
             struct whisper_context **out_ctx,
             struct whisper_vad_context **out_vad)
{
    *out_ctx = NULL;
    *out_vad = NULL;

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.flash_attn = cparams.use_gpu = use_gpu;

    *out_ctx = whisper_init_from_file_with_params(model_path, cparams);
    if (!*out_ctx)
    {
        fprintf(stderr, "Failed to load model\n");
        return -1;
    }

    if (vad_model)
    {
        struct whisper_vad_context_params vp = whisper_vad_default_context_params();
        vp.n_threads = 1;
        vp.use_gpu = false;
        *out_vad = whisper_vad_init_from_file_with_params(vad_model, vp);
        if (!*out_vad)
        {
            fprintf(stderr, "Failed to load VAD model: %s\n", vad_model);
            whisper_free(*out_ctx);
            *out_ctx = NULL;
            return -1;
        }
    }

    return 0;
}

int
main(int argc, char **argv)
{
    const char *model_path = NULL;
    const char *file_path = NULL;
    const char *vad_model = NULL;
    const char *language = NULL;
    int stream_ctx = 1;
    int n_threads = 4;
    bool use_gpu = true;
    bool debug = false;
    bool live = false;

    static struct option long_opts[] = {
        {"model",     required_argument, 0, 'm'},
        {"file",      required_argument, 0, 'f'},
        {"stream",    required_argument, 0, 's'},
        {"language",  required_argument, 0, 'l'},
        {"threads",   required_argument, 0, 't'},
        {"no-gpu",    no_argument,       0, 'g'},
        {"vad-model", required_argument, 0, 'v'},
        {"debug",     no_argument,       0, 'd'},
        {"live",      no_argument,       0, 'L'},
        {0, 0, 0, 0}
    };

    int opt;
    while ((opt = getopt_long(argc, argv, "m:f:s:l:t:v:dL", long_opts, NULL)) != -1)
    {
        switch (opt)
        {
        case 'm': model_path = optarg; break;
        case 'f': file_path = optarg; break;
        case 's': stream_ctx = atoi(optarg); break;
        case 'l': language = optarg; break;
        case 't': n_threads = atoi(optarg); break;
        case 'g': use_gpu = false; break;
        case 'v': vad_model = optarg; break;
        case 'd': debug = true; break;
        case 'L': live = true; break;
        default:
            usage(argv[0]);
            return 1;
        }
    }

    if (!model_path || !file_path)
    {
        usage(argv[0]);
        return 1;
    }

    if (stream_ctx < 1 || stream_ctx > 2)
    {
        fprintf(stderr, "stream must be 1 or 2\n");
        return 1;
    }

    if (!debug)
        whisper_log_set(log_disable, NULL);

    int ret = 1;
    float *samples = NULL;
    struct whisper_context *ctx0 = NULL;
    struct whisper_context *ctx1 = NULL;
    struct whisper_vad_context *vad_ctx = NULL;
    struct whisper_vad_context *vad_ctx1 = NULL;

    int n_samples;
    samples = read_avcodec(file_path, &n_samples);
    if (!samples)
        goto cleanup;

    fprintf(stderr, "Loaded %d samples (%.1fs)\n",
            n_samples, (float)n_samples / WHISPER_SAMPLE_RATE);

    if (init_context(model_path, vad_model, use_gpu, &ctx0, &vad_ctx) < 0)
        goto cleanup;

    if (stream_ctx == 2)
    {
        if (init_context(model_path, vad_model, false, &ctx1, &vad_ctx1) < 0)
            goto cleanup;
    }

    struct file_ctx fctx = { samples, n_samples, 0 };

    atomic_bool abort_flag = false;
    g_abort_ptr = &abort_flag;
    signal(SIGINT, sigint_handler);

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language = language;
    wparams.suppress_nst = true;

    struct whisper_stream_params sparams = whisper_stream_default_params();
    sparams.read_callback = file_read_cb;
    sparams.read_callback_user_data = &fctx;
    sparams.segment_callback = segment_cb;
    sparams.segment_callback_user_data = NULL;
    sparams.abort_callback = abort_cb;
    sparams.abort_callback_user_data = &abort_flag;
    sparams.slots[0].ctx = ctx0;
    sparams.slots[0].vad_ctx = vad_ctx;
    sparams.slots[0].num_threads = n_threads;
    sparams.slots[1].ctx = ctx1;
    sparams.slots[1].vad_ctx = vad_ctx1;
    sparams.slots[1].num_threads = n_threads;
    if (live)
    {
        sparams.vad_threshold = 0.5;
        sparams.min_chunk_ms = 10000;
        sparams.chunk_extend_ms = 20000;
    }
    else
    {
        sparams.vad_threshold = 0.25;
        sparams.min_chunk_ms = 30000;
        sparams.chunk_extend_ms = 30000;
    }

    ret = whisper_stream_full(wparams, sparams);

cleanup:
    if (vad_ctx)
        whisper_vad_free(vad_ctx);
    if (vad_ctx1)
        whisper_vad_free(vad_ctx1);
    if (ctx0)
        whisper_free(ctx0);
    if (ctx1)
        whisper_free(ctx1);
    free(samples);

    return ret;
}
