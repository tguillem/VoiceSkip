/* SPDX-License-Identifier: GPL-3.0-or-later */

/* The blocklist has to choose the backend before the whisper context exists,
 * which means asking ggml about the device from jni.c. Those queries look like
 * C but reach vk::SystemError on an unusable loader, and the backend registry
 * re-runs its constructor after a failed one, so the throw can resurface on a
 * later call. jni.c is C with no handler above it, so such a throw would reach
 * std::terminate. Answer in a bool instead. */

#include <android/log.h>
#include <exception>
#include <cstdio>

#include "ggml-backend.h"
#include "gpu_probe.h"

#define TAG "JNI"

extern "C" bool
voiceskip_probe_gpu(char *desc, size_t desc_size)
try
{
    /* Mirrors whisper_backend_init_gpu(): whisper takes the first GPU/IGPU
     * device in registry order, so the blocklist must inspect that same
     * device rather than the Vulkan backend's own device numbering. */
    for (size_t i = 0; i < ggml_backend_dev_count(); i++)
    {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);

        if (type != GGML_BACKEND_DEVICE_TYPE_GPU &&
            type != GGML_BACKEND_DEVICE_TYPE_IGPU)
        {
            continue;
        }

        const char *description = ggml_backend_dev_description(dev);
        snprintf(desc, desc_size, "%s", description ? description : "");
        return true;
    }

    return false;
}
catch (const std::exception &e)
{
    __android_log_print(ANDROID_LOG_ERROR, TAG,
                        "GPU probe failed: %s", e.what());
    return false;
}
catch (...)
{
    __android_log_print(ANDROID_LOG_ERROR, TAG,
                        "GPU probe failed: unknown exception");
    return false;
}
