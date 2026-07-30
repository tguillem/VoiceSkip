/* SPDX-License-Identifier: GPL-3.0-or-later */

#include <android/log.h>
#include <exception>

#include "backend_loader.h"
#include "ggml-backend.h"

#define TAG "JNI"

extern "C" bool
voiceskip_backend_load(const char *basename)
try
{
    return ggml_backend_load(basename) != nullptr;
}
catch (const std::exception &e)
{
    __android_log_print(ANDROID_LOG_ERROR, TAG,
                        "Backend load failed for %s: %s",
                        basename, e.what());
    return false;
}
catch (...)
{
    __android_log_print(ANDROID_LOG_ERROR, TAG,
                        "Backend load failed for %s: unknown exception",
                        basename);
    return false;
}
