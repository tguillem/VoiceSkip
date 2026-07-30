/* SPDX-License-Identifier: GPL-3.0-or-later */

#include <errno.h>
#include <pthread.h>
#include <sys/auxv.h>

#include "backend_loader.h"
#include "cpu_backend.h"

static pthread_once_t backend_once = PTHREAD_ONCE_INIT;
static enum voiceskip_cpu_backend selected_backend =
    VOICESKIP_CPU_BACKEND_UNINITIALIZED;

static bool
optimized_backend_eligible(void)
{
    errno = 0;
    unsigned long hwcap = getauxval(AT_HWCAP);
    if (errno != 0)
    {
        return false;
    }

    return (hwcap & VOICESKIP_HWCAP_REQUIRED) ==
        VOICESKIP_HWCAP_REQUIRED;
}

static enum voiceskip_cpu_backend
select_backend(void)
{
    if (optimized_backend_eligible() &&
        voiceskip_backend_load(VOICESKIP_CPU_MODULE_FP16_DOTPROD))
    {
        return VOICESKIP_CPU_BACKEND_FP16_DOTPROD;
    }

    if (voiceskip_backend_load(VOICESKIP_CPU_MODULE_BASELINE))
    {
        return VOICESKIP_CPU_BACKEND_BASELINE;
    }

    return VOICESKIP_CPU_BACKEND_FAILED;
}

static void
initialize_backend(void)
{
    selected_backend = select_backend();
}

enum voiceskip_cpu_backend
voiceskip_cpu_backend_init(void)
{
    if (pthread_once(&backend_once, initialize_backend) != 0)
    {
        return VOICESKIP_CPU_BACKEND_FAILED;
    }

    return selected_backend;
}

const char *
voiceskip_cpu_backend_basename(enum voiceskip_cpu_backend backend)
{
    switch (backend)
    {
        case VOICESKIP_CPU_BACKEND_BASELINE:
            return VOICESKIP_CPU_MODULE_BASELINE;
        case VOICESKIP_CPU_BACKEND_FP16_DOTPROD:
            return VOICESKIP_CPU_MODULE_FP16_DOTPROD;
        case VOICESKIP_CPU_BACKEND_UNINITIALIZED:
        case VOICESKIP_CPU_BACKEND_FAILED:
            return NULL;
    }

    return NULL;
}
