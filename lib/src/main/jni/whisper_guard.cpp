/* SPDX-License-Identifier: GPL-3.0-or-later */

/* A driver that rejects a shader throws out of whisper_full(), which has C
 * linkage but is compiled as C++. stream.c is C with no handler above it, so
 * the throw would run to std::terminate. Catching it in the last C++ frame
 * before the C caller keeps that out of whisper.cpp itself, where it would be
 * one more patch to carry across every rebase. */

#include <exception>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "whisper_guard", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

#include "whisper_guard.h"

extern "C" int
voiceskip_whisper_full(struct whisper_context *ctx,
                       struct whisper_full_params params,
                       const float *samples, int n_samples)
try
{
    return whisper_full(ctx, params, samples, n_samples);
}
catch (const std::exception &e)
{
    LOGE("whisper_full: exception: %s\n", e.what());
    return -1;
}
catch (...)
{
    LOGE("whisper_full: unknown exception\n");
    return -1;
}
