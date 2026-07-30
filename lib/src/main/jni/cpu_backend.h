/* SPDX-License-Identifier: GPL-3.0-or-later */

#ifndef VOICESKIP_CPU_BACKEND_H
#define VOICESKIP_CPU_BACKEND_H

#define VOICESKIP_HWCAP_FPHP       0x00000200UL
#define VOICESKIP_HWCAP_ASIMDHP    0x00000400UL
#define VOICESKIP_HWCAP_ASIMDDP    0x00100000UL
#define VOICESKIP_HWCAP_REQUIRED   0x00100600UL

#define VOICESKIP_CPU_MODULE_BASELINE \
    "libggml-cpu-baseline.so"
#define VOICESKIP_CPU_MODULE_FP16_DOTPROD \
    "libggml-cpu-fp16-dotprod.so"

enum voiceskip_cpu_backend
{
    VOICESKIP_CPU_BACKEND_UNINITIALIZED,
    VOICESKIP_CPU_BACKEND_BASELINE,
    VOICESKIP_CPU_BACKEND_FP16_DOTPROD,
    VOICESKIP_CPU_BACKEND_FAILED
};

enum voiceskip_cpu_backend
voiceskip_cpu_backend_init(void);

const char *
voiceskip_cpu_backend_basename(enum voiceskip_cpu_backend backend);

#endif
