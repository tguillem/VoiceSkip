/* SPDX-License-Identifier: GPL-3.0-or-later */

#define _GNU_SOURCE
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdbool.h>
#include <sys/sysinfo.h>
#include <string.h>
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
#include "whisper.h"
#include "stream.h"
#include "ggml.h"
#include "ggml-vulkan.h"

#define UNUSED(x) (void)(x)
#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))
#define TAG "JNI"

// #define EXTRA_LOGS

static jclass g_class_illegal_state;
static jclass g_class_illegal_argument;
static jclass g_class_out_of_memory;
static jfieldID g_field_mInstance;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void
whisper_android_log_callback(enum ggml_log_level level, const char *text, void *user_data)
{
    UNUSED(user_data);

    int android_log_level = ANDROID_LOG_UNKNOWN;
    switch (level)
    {
        case GGML_LOG_LEVEL_DEBUG:
#ifdef EXTRA_LOGS
            android_log_level = ANDROID_LOG_DEBUG;
#endif
            break;
        case GGML_LOG_LEVEL_INFO:
#ifdef EXTRA_LOGS
            android_log_level = ANDROID_LOG_INFO;
#endif
            break;
        case GGML_LOG_LEVEL_WARN:
            android_log_level = ANDROID_LOG_WARN;
            break;
        case GGML_LOG_LEVEL_ERROR:
            android_log_level = ANDROID_LOG_ERROR;
            break;
        default:
#ifdef EXTRA_LOGS
            android_log_level = ANDROID_LOG_VERBOSE;
#endif
            break;
    }

    if (android_log_level != ANDROID_LOG_UNKNOWN)
        __android_log_print(android_log_level, "whisper.cpp", "%s", text);
}

static inline bool
jni_check_exception(JNIEnv *env)
{
    if ((*env)->ExceptionCheck(env))
    {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return true;
    }
    return false;
}

static char*
jni_strdup(JNIEnv *env, jstring jstr)
{
    if (!jstr)
    {
        return NULL;
    }
    const char *chars = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (!chars)
    {
        return NULL;
    }
    char *result = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, jstr, chars);
    return result;
}

/* From libvlcjni - validates UTF-8 before calling NewStringUTF to prevent JNI abort */
static jstring
jni_new_string_utf(JNIEnv *env, const char *string)
{
    if (string == NULL)
        return NULL;
    for (int i = 0; string[i] != '\0'; ) {
        uint8_t lead = string[i++];
        uint8_t nbBytes;
        if ((lead & 0x80) == 0)
            continue;
        else if ((lead >> 5) == 0x06)
            nbBytes = 1;
        else if ((lead >> 4) == 0x0E)
            nbBytes = 2;
        else if ((lead >> 3) == 0x1E)
            nbBytes = 3;
        else {
            LOGE("Invalid UTF lead character\n");
            return NULL;
        }
        for (int j = 0; j < nbBytes && string[i] != '\0'; j++) {
            uint8_t byte = string[i++];
            if ((byte & 0x80) == 0) {
                LOGE("Invalid UTF byte\n");
                return NULL;
            }
        }
    }
    return (*env)->NewStringUTF(env, string);
}

typedef enum
{
    CMD_LOAD_MODEL,
    CMD_LOAD_SECOND_MODEL,
    CMD_START
} command_type;

struct model_load_args
{
    struct whisper_jni_context *ctx;
    char *model_path;
    char *vad_model_path;
    jobject asset_manager;
    bool use_gpu;
};

struct start_args
{
    int num_threads;
    char *language;
    bool translate;
    bool live;
    unsigned int session_id;
};

struct command_node
{
    command_type type;
    union
    {
        struct model_load_args load_model;
        struct start_args start;
    } args;
    struct command_node *next;
};

#define SLOT_MAIN   0
#define SLOT_SECOND 1

struct whisper_jni_context
{
    struct whisper_stream_slot slots[2];
    JavaVM *jvm;
    jobject java_context;
    jmethodID mid_on_loaded;
    jmethodID mid_on_progress;
    jmethodID mid_on_new_segment;
    jmethodID mid_on_stream_complete;
    jmethodID mid_on_error;
    jmethodID mid_read_audio;             /* int readAudio(float[] buffer, int maxSamples) */

    pthread_t worker_thread;
    struct command_node *queue_head;
    struct command_node *queue_tail;
    pthread_mutex_t mutex;
    pthread_cond_t worker_cond;

    atomic_uint session_id;
    unsigned int start_session_id;        /* session when CMD_START began */

    bool should_shutdown;
    bool use_gpu;
    atomic_int_fast64_t duration_samples;  /* 0 = unknown, no progress callbacks */
    atomic_int lang_override;              /* 0 = no override, >0 = lang_id to use */
};

static struct whisper_jni_context*
get_jni_context(JNIEnv *env, jobject thiz)
{
    jlong ptr = (*env)->GetLongField(env, thiz, g_field_mInstance);
    return (ptr == 0) ? NULL : (struct whisper_jni_context*)ptr;
}


static void
model_load_args_init(struct model_load_args *args)
{
    args->ctx = NULL;
    args->model_path = NULL;
    args->vad_model_path = NULL;
    args->asset_manager = NULL;
    args->use_gpu = false;
}

static void
model_load_args_clean(struct model_load_args *args, JNIEnv *env)
{
    free(args->model_path);
    free(args->vad_model_path);
    if (args->asset_manager && env)
    {
        (*env)->DeleteGlobalRef(env, args->asset_manager);
    }
}

static void
start_args_init(struct start_args *args)
{
    args->num_threads = 0;
    args->language = NULL;
    args->translate = false;
    args->live = false;
    args->session_id = 0;
}

static void
start_args_clean(struct start_args *args)
{
    free(args->language);
}

static void
report_error(JNIEnv *env, struct whisper_jni_context *ctx, const char *fmt, ...)
{
    char *error_msg = NULL;
    va_list args;
    va_start(args, fmt);
    int result = vasprintf(&error_msg, fmt, args);
    va_end(args);

    if (result < 0 || !error_msg)
    {
        jstring fallback_msg = (*env)->NewStringUTF(env,
            "Error occurred (failed to format message)");
        if (!fallback_msg)
        {
            return;
        }
        (*env)->CallVoidMethod(env, ctx->java_context, ctx->mid_on_error,
                               fallback_msg);
        jni_check_exception(env);
        (*env)->DeleteLocalRef(env, fallback_msg);
        return;
    }

    jstring jmsg = (*env)->NewStringUTF(env, error_msg);
    free(error_msg);

    if (!jmsg)
    {
        return;
    }

    (*env)->CallVoidMethod(env, ctx->java_context, ctx->mid_on_error, jmsg);
    jni_check_exception(env);
    (*env)->DeleteLocalRef(env, jmsg);
}


/* MUST be called with ctx->mutex held */
static struct command_node*
allocate_command_load(const struct model_load_args *args)
{
    struct command_node *node = malloc(sizeof *node);
    if (!node)
    {
        return NULL;
    }
    node->type = CMD_LOAD_MODEL;
    node->args.load_model = *args;
    node->next = NULL;
    return node;
}

static struct command_node*
allocate_command_start(const struct start_args *args)
{
    struct command_node *node = malloc(sizeof *node);
    if (!node)
    {
        return NULL;
    }
    node->type = CMD_START;
    node->args.start = *args;
    node->next = NULL;
    return node;
}

static void
enqueue_command_node(struct whisper_jni_context *ctx, struct command_node *node)
{
    if (!node) return;

    if (ctx->queue_tail)
    {
        ctx->queue_tail->next = node;
        ctx->queue_tail = node;
    }
    else
    {
        ctx->queue_head = ctx->queue_tail = node;
    }
}

static struct command_node*
dequeue_command(struct whisper_jni_context *ctx)
{
    struct command_node *node = ctx->queue_head;
    if (node)
    {
        ctx->queue_head = node->next;
        if (!ctx->queue_head)
        {
            ctx->queue_tail = NULL;
        }
    }
    return node;
}

static void
command_free(struct command_node *node, JNIEnv *env)
{
    switch (node->type)
    {
        case CMD_LOAD_MODEL:
        case CMD_LOAD_SECOND_MODEL:
            model_load_args_clean(&node->args.load_model, env);
            break;
        case CMD_START:
            start_args_clean(&node->args.start);
            break;
    }
    free(node);
}

static void
clear_command_queue(struct whisper_jni_context *ctx, JNIEnv *env)
{
    struct command_node *node;
    while ((node = dequeue_command(ctx)) != NULL)
    {
        command_free(node, env);
    }
}

static size_t
asset_read(void *ctx, void *output, size_t read_size)
{
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool
asset_is_eof(void *ctx)
{
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void
asset_close(void *ctx)
{
    AAsset_close((AAsset *) ctx);
}

static bool
is_gpu_blocklisted(const char *desc)
{
    size_t len = strlen(desc);

    /* Adreno 6xx-7xx series (tested until 730) cause VK_ERROR_DEVICE_LOST or
     * fail to link some  shaders */
    if (strncmp(desc, "Adreno", 6) == 0)
    {
        return true;
    }

    return false;
}

static void
load_model(struct whisper_jni_context *ctx, JNIEnv *env,
           struct model_load_args *args, size_t slot_idx)
{
    static const char *slot_names[] = { "ctx0", "ctx1" };
    struct whisper_stream_slot *slot = &ctx->slots[slot_idx];
    const char *slot_name = slot_names[slot_idx];

    if (slot->ctx)
    {
        LOGI("[%s] Freeing model", slot_name);
        whisper_free(slot->ctx);
        slot->ctx = NULL;
    }
    if (slot->vad_ctx)
    {
        whisper_vad_free(slot->vad_ctx);
        slot->vad_ctx = NULL;
    }

    if (!args->model_path)
    {
        LOGI("[%s] Unloaded", slot_name);
        return;
    }

    LOGI("[%s] Loading %s", slot_name, args->model_path);

    AAssetManager *asset_manager = AAssetManager_fromJava(env, args->asset_manager);
    if (!asset_manager)
    {
        report_error(env, ctx, "Failed to get AssetManager from Java");
        return;
    }

    AAsset *asset = AAssetManager_open(asset_manager, args->model_path,
                                       AASSET_MODE_STREAMING);
    if (!asset)
    {
        report_error(env, ctx,
            "Failed to open %s model '%s' from assets", slot_name, args->model_path);
        return;
    }

    whisper_model_loader loader = {
        .context = asset,
        .read = &asset_read,
        .eof = &asset_is_eof,
        .close = &asset_close
    };

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.flash_attn = cparams.use_gpu = args->use_gpu;
    cparams.gpu_device = 0;

    slot->ctx = whisper_init_with_params(&loader, cparams);
    if (!slot->ctx)
    {
        report_error(env, ctx,
            "Failed to load %s model '%s': initialization failed",
            slot_name, args->model_path);
        return;
    }

    if (args->vad_model_path)
    {
        AAsset *vad_asset = AAssetManager_open(asset_manager,
            args->vad_model_path, AASSET_MODE_STREAMING);
        if (!vad_asset)
        {
            report_error(env, ctx,
                "Failed to open VAD model '%s' from assets", args->vad_model_path);
            return;
        }

        whisper_model_loader vad_loader = {
            .context = vad_asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
        };

        struct whisper_vad_context_params vp = whisper_vad_default_context_params();
        vp.n_threads = 1;
        vp.use_gpu = false;

        LOGI("[%s] Loading VAD %s", slot_name, args->vad_model_path);
        slot->vad_ctx = whisper_vad_init_with_params(&vad_loader, vp);

        if (!slot->vad_ctx)
        {
            report_error(env, ctx,
                "Failed to load %s VAD model '%s': initialization failed",
                slot_name, args->vad_model_path);
            return;
        }
    }

    jstring gpu_desc = NULL;
    if (slot_idx == SLOT_MAIN)
    {
        char desc[256];
        ctx->use_gpu = false;
        if (args->use_gpu)
        {
            bool use_gpu = whisper_ctx_is_using_gpu(slot->ctx);
            int vk_device_count = ggml_backend_vk_get_device_count();
            if (use_gpu && vk_device_count > 0)
            {
                ggml_backend_vk_get_device_description(0, desc, sizeof(desc));
                if (!is_gpu_blocklisted(desc))
                {
                    gpu_desc = (*env)->NewStringUTF(env, desc);
                    ctx->use_gpu = true;
                }
                else
                {
                    LOGI("GPU blocklisted: %s", desc);
                }
            }
        }
    }

    (*env)->CallVoidMethod(env, ctx->java_context,
                           ctx->mid_on_loaded, (jint)slot_idx, gpu_desc);
    jni_check_exception(env);
    if (gpu_desc)
        (*env)->DeleteLocalRef(env, gpu_desc);

    LOGI("[%s] Loaded", slot_name);
}

static JNIEnv *
get_thread_env(struct whisper_jni_context *ctx)
{
    JNIEnv *env;
    jint status = (*ctx->jvm)->GetEnv(ctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED)
    {
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != JNI_OK)
            return NULL;
    }
    return env;
}

static int
jni_read_callback(float *samples, int n_samples_max, void *user_data)
{
    struct whisper_jni_context *ctx = user_data;
    JNIEnv *env = get_thread_env(ctx);
    if (!env)
        return -1;

    /* Check for stop (session changed) */
    if (ctx->start_session_id != atomic_load(&ctx->session_id))
        return 0;  /* EOF - stop requested */

    jfloatArray buffer = (*env)->NewFloatArray(env, n_samples_max);
    if (!buffer)
        return -1;

    jint n = (*env)->CallIntMethod(env, ctx->java_context,
                                   ctx->mid_read_audio, buffer, n_samples_max);
    if (jni_check_exception(env))
    {
        (*env)->DeleteLocalRef(env, buffer);
        return -1;
    }

    if (n > 0)
        (*env)->GetFloatArrayRegion(env, buffer, 0, n, samples);

    (*env)->DeleteLocalRef(env, buffer);
    return n;
}

static void
jni_segment_callback(struct whisper_context *wctx, int64_t t0, int64_t t1,
                     const char *text, void *user_data)
{
    UNUSED(wctx);

#ifdef EXTRA_LOGS
    int64_t ms0 = t0 * 10;
    int64_t ms1 = t1 * 10;
    LOGI("[%02d:%02d.%03d --> %02d:%02d.%03d]%s",
         (int)(ms0 / 60000), (int)((ms0 % 60000) / 1000), (int)(ms0 % 1000),
         (int)(ms1 / 60000), (int)((ms1 % 60000) / 1000), (int)(ms1 % 1000),
         text);
#endif

    struct whisper_jni_context *ctx = user_data;
    JNIEnv *env = get_thread_env(ctx);
    if (!env)
        return;

    /* Convert centiseconds to milliseconds */
    jlong start_ms = t0 * 10;
    jlong end_ms = t1 * 10;

    int lang_id = whisper_full_lang_id(ctx->slots[SLOT_MAIN].ctx);
    const char *lang_cstr = (lang_id >= 0) ? whisper_lang_str(lang_id) : NULL;
    jstring lang_str = lang_cstr ? (*env)->NewStringUTF(env, lang_cstr) : NULL;

    jstring text_str = jni_new_string_utf(env, text);
    if (text_str)
    {
        (*env)->CallVoidMethod(env, ctx->java_context,
                               ctx->mid_on_new_segment,
                               text_str, start_ms, end_ms, lang_str);
        jni_check_exception(env);
        (*env)->DeleteLocalRef(env, text_str);
    }

    if (lang_str)
        (*env)->DeleteLocalRef(env, lang_str);
}

static void
jni_progress_callback(int chunk_progress, int64_t samples_before,
                      int chunk_samples, void *user_data)
{
    struct whisper_jni_context *ctx = user_data;

    int64_t total = atomic_load(&ctx->duration_samples);
    if (total <= 0)
        return;

    JNIEnv *env = get_thread_env(ctx);
    if (!env)
        return;

    int64_t samples_done = samples_before + (chunk_progress * chunk_samples) / 100;
    int overall = (int)((samples_done * 100) / total);
    if (overall > 100)
        overall = 100;

    (*env)->CallVoidMethod(env, ctx->java_context,
                           ctx->mid_on_progress, overall);
    jni_check_exception(env);
}

static bool
whisper_abort_callback_impl(void *user_data)
{
    struct whisper_jni_context *ctx = user_data;
    unsigned int current_session = atomic_load(&ctx->session_id);
    return ctx->start_session_id != current_session;
}

static int
jni_language_callback(void *user_data)
{
    struct whisper_jni_context *ctx = user_data;
    return atomic_load(&ctx->lang_override);
}

static void
process_start_command(struct whisper_jni_context *ctx,
                      struct start_args *args, JNIEnv *env)
{
    if (!ctx->slots[SLOT_MAIN].ctx)
    {
        LOGE("Whisper context not initialized");
        report_error(env, ctx,
            "Model not loaded: whisper context not initialized");
        return;
    }

    unsigned int current_session = atomic_load(&ctx->session_id);
    if (args->session_id != current_session)
    {
        LOGI("Start session %u != current %u, discarding",
                 args->session_id, current_session);
        return;
    }
    atomic_store(&ctx->lang_override, -1);
    ctx->start_session_id = args->session_id;

    struct whisper_full_params wparams =
        whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.suppress_nst = true;
    wparams.translate = args->translate;
    wparams.language = args->language ? args->language : "auto";

    struct whisper_stream_params sparams = whisper_stream_default_params();
    sparams.read_callback = jni_read_callback;
    sparams.read_callback_user_data = ctx;
    sparams.segment_callback = jni_segment_callback;
    sparams.segment_callback_user_data = ctx;
    sparams.progress_callback = jni_progress_callback;
    sparams.progress_callback_user_data = ctx;
    sparams.language_callback = jni_language_callback;
    sparams.language_callback_user_data = ctx;
    sparams.abort_callback = whisper_abort_callback_impl;
    sparams.abort_callback_user_data = ctx;
    if (args->live)
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
    sparams.slots[0] = ctx->slots[SLOT_MAIN];
    sparams.slots[0].num_threads = ctx->use_gpu ? 1 : args->num_threads;
    sparams.slots[1] = ctx->slots[SLOT_SECOND];
    sparams.slots[1].num_threads = sparams.slots[1].ctx ? args->num_threads : 0;

    LOGI("Starting stream: ctx0=%s (%d threads), ctx1=%s (%d threads), "
         "lang=%s, live=%d",
         ctx->use_gpu ? "gpu" : "cpu", sparams.slots[0].num_threads,
         sparams.slots[1].ctx ? "cpu" : "none", sparams.slots[1].num_threads,
         args->language ? args->language : "auto",
         args->live);

    int result = whisper_stream_full(wparams, sparams);

    unsigned int session_after = atomic_load(&ctx->session_id);
    bool was_stopped = (ctx->start_session_id != session_after);

    LOGI("Stream finished: result=%d, stopped=%d", result, was_stopped);

    if (!was_stopped)
    {
        bool success = result == 0;
        (*env)->CallVoidMethod(env, ctx->java_context,
                               ctx->mid_on_stream_complete, success);
        jni_check_exception(env);
    }
}

static void*
worker_thread_func(void *arg)
{
    struct whisper_jni_context *ctx = arg;

    LOGI("Worker thread started");

    if (!ctx->jvm)
    {
        LOGE("JavaVM is null in worker thread");
        return NULL;
    }

    JNIEnv *env;
    jint attach_result = (*ctx->jvm)->AttachCurrentThread(ctx->jvm,
                                                          &env, NULL);
    if (attach_result != JNI_OK)
    {
        LOGE("Failed to attach worker thread");
        return NULL;
    }

    while (1)
    {
        pthread_mutex_lock(&ctx->mutex);
        while (!ctx->queue_head && !ctx->should_shutdown)
        {
            pthread_cond_wait(&ctx->worker_cond, &ctx->mutex);
        }

        if (ctx->should_shutdown)
        {
            pthread_mutex_unlock(&ctx->mutex);
            break;
        }

        struct command_node *node = dequeue_command(ctx);
        pthread_mutex_unlock(&ctx->mutex);

        if (!node)
        {
            continue;
        }

        switch (node->type)
        {
            case CMD_LOAD_MODEL:
                load_model(ctx, env, &node->args.load_model, SLOT_MAIN);
                break;
            case CMD_LOAD_SECOND_MODEL:
                load_model(ctx, env, &node->args.load_model, SLOT_SECOND);
                break;
            case CMD_START:
                process_start_command(ctx, &node->args.start, env);
                break;
        }

        command_free(node, env);
    }

    (*ctx->jvm)->DetachCurrentThread(ctx->jvm);

    LOGI("Worker thread finished");
    return NULL;
}

static jlong
nativeCreate(JNIEnv *env, jobject thiz)
{
    LOGI("Creating WhisperContext instance");

    struct whisper_jni_context *ctx = malloc(sizeof *ctx);
    if (!ctx)
    {
        return 0;
    }

    memset(ctx, 0, sizeof(*ctx));
    atomic_init(&ctx->lang_override, -1);

    if ((*env)->GetJavaVM(env, &ctx->jvm) != JNI_OK)
    {
        LOGE("Failed to get JavaVM");
        free(ctx);
        return 0;
    }

    ctx->java_context = (*env)->NewGlobalRef(env, thiz);
    if (!ctx->java_context)
    {
        LOGE("Failed to create GlobalRef for Java context");
        free(ctx);
        return 0;
    }

    jclass cls = (*env)->GetObjectClass(env, thiz);
    if (!cls)
    {
        LOGE("Failed to get object class");
        (*env)->DeleteGlobalRef(env, ctx->java_context);
        free(ctx);
        return 0;
    }

    #define CHECK_METHOD_LOOKUP(method_name) \
        if (!ctx->mid_##method_name || (*env)->ExceptionCheck(env)) \
        { \
            LOGE("Failed to find %s method", #method_name); \
            if ((*env)->ExceptionCheck(env)) \
            { \
                (*env)->ExceptionDescribe(env); \
                (*env)->ExceptionClear(env); \
            } \
            (*env)->DeleteLocalRef(env, cls); \
            (*env)->DeleteGlobalRef(env, ctx->java_context); \
            free(ctx); \
            return 0; \
        }

    ctx->mid_on_loaded = (*env)->GetMethodID(env, cls, "onLoaded",
        "(ILjava/lang/String;)V");
    CHECK_METHOD_LOOKUP(on_loaded);

    ctx->mid_on_progress = (*env)->GetMethodID(env, cls, "onProgress", "(I)V");
    CHECK_METHOD_LOOKUP(on_progress);

    ctx->mid_on_new_segment = (*env)->GetMethodID(env, cls, "onNewSegment",
        "(Ljava/lang/String;JJLjava/lang/String;)V");
    CHECK_METHOD_LOOKUP(on_new_segment);

    ctx->mid_on_stream_complete = (*env)->GetMethodID(env, cls,
        "onStreamComplete", "(Z)V");
    CHECK_METHOD_LOOKUP(on_stream_complete);

    ctx->mid_on_error = (*env)->GetMethodID(env, cls, "onError",
        "(Ljava/lang/String;)V");
    CHECK_METHOD_LOOKUP(on_error);

    ctx->mid_read_audio = (*env)->GetMethodID(env, cls, "readAudio",
        "([FI)I");
    CHECK_METHOD_LOOKUP(read_audio);

    #undef CHECK_METHOD_LOOKUP

    (*env)->DeleteLocalRef(env, cls);

    if (pthread_mutex_init(&ctx->mutex, NULL) != 0)
    {
        LOGE("Failed to initialize mutex");
        (*env)->DeleteGlobalRef(env, ctx->java_context);
        free(ctx);
        return 0;
    }

    if (pthread_cond_init(&ctx->worker_cond, NULL) != 0)
    {
        LOGE("Failed to initialize condition variable");
        pthread_mutex_destroy(&ctx->mutex);
        (*env)->DeleteGlobalRef(env, ctx->java_context);
        free(ctx);
        return 0;
    }

    ctx->queue_head = NULL;
    ctx->queue_tail = NULL;

    ctx->should_shutdown = false;
    int result = pthread_create(&ctx->worker_thread, NULL, worker_thread_func, ctx);
    if (result != 0)
    {
        LOGE("Failed to create worker thread: %d", result);
        pthread_mutex_destroy(&ctx->mutex);
        pthread_cond_destroy(&ctx->worker_cond);
        (*env)->DeleteGlobalRef(env, ctx->java_context);
        free(ctx);
        return 0;
    }

    LOGI("WhisperContext instance created: %p", ctx);
    LOGI("System info: %s", whisper_print_system_info());
    return (jlong)ctx;
}

static void
nativeLoadModel(JNIEnv *env, jobject thiz, jobject asset_manager,
                jstring model_path, jstring vad_model_path, jboolean use_gpu)
{
    struct whisper_jni_context *ctx = get_jni_context(env, thiz);
    if (!ctx)
    {
        (*env)->ThrowNew(env, g_class_illegal_state,
                         "WhisperContext not initialized");
        return;
    }

    if (!model_path || !asset_manager)
    {
        (*env)->ThrowNew(env, g_class_illegal_argument,
                         "model_path and asset_manager must not be null");
        return;
    }

    struct model_load_args args;
    model_load_args_init(&args);
    args.ctx = ctx;

    args.model_path = jni_strdup(env, model_path);
    if (!args.model_path)
    {
        (*env)->ThrowNew(env, g_class_out_of_memory,
                         "Failed to allocate memory for model path");
        return;
    }

    args.vad_model_path = jni_strdup(env, vad_model_path);

    args.asset_manager = (*env)->NewGlobalRef(env, asset_manager);
    if (!args.asset_manager)
    {
        (*env)->ThrowNew(env, g_class_out_of_memory,
                         "Failed to create global reference for asset manager");
        model_load_args_clean(&args, env);
        return;
    }

    args.use_gpu = use_gpu;

    struct command_node *cmd = allocate_command_load(&args);
    if (!cmd)
    {
        (*env)->ThrowNew(env, g_class_out_of_memory,
                         "Failed to allocate memory for command");
        model_load_args_clean(&args, env);
        return;
    }

    LOGI("Queuing model load command for: %s, VAD: %s, GPU: %s",
         args.model_path, args.vad_model_path ? args.vad_model_path : "none",
         use_gpu ? "enabled" : "disabled");

    pthread_mutex_lock(&ctx->mutex);
    enqueue_command_node(ctx, cmd);
    pthread_cond_signal(&ctx->worker_cond);
    pthread_mutex_unlock(&ctx->mutex);
}

static void
nativeLoadSecondModel(JNIEnv *env, jobject thiz, jobject asset_manager,
                      jstring model_path, jstring vad_model_path)
{
    struct whisper_jni_context *ctx = get_jni_context(env, thiz);
    if (!ctx)
    {
        (*env)->ThrowNew(env, g_class_illegal_state,
                         "WhisperContext not initialized");
        return;
    }

    /* NULL model_path = unload, but still need asset_manager for load */
    if (model_path && !asset_manager)
    {
        (*env)->ThrowNew(env, g_class_illegal_argument,
                         "asset_manager must not be null when loading");
        return;
    }

    struct model_load_args args;
    model_load_args_init(&args);
    args.ctx = ctx;

    if (model_path)
    {
        args.model_path = jni_strdup(env, model_path);
        if (!args.model_path)
        {
            (*env)->ThrowNew(env, g_class_out_of_memory,
                             "Failed to allocate memory for model path");
            return;
        }

        args.vad_model_path = jni_strdup(env, vad_model_path);

        args.asset_manager = (*env)->NewGlobalRef(env, asset_manager);
        if (!args.asset_manager)
        {
            (*env)->ThrowNew(env, g_class_out_of_memory,
                             "Failed to create global reference for asset manager");
            model_load_args_clean(&args, env);
            return;
        }
    }

    struct command_node *cmd = malloc(sizeof *cmd);
    if (!cmd)
    {
        (*env)->ThrowNew(env, g_class_out_of_memory,
                         "Failed to allocate memory for command");
        model_load_args_clean(&args, env);
        return;
    }
    cmd->type = CMD_LOAD_SECOND_MODEL;
    cmd->args.load_model = args;
    cmd->next = NULL;

    LOGI("Queuing second model %s command%s%s",
         model_path ? "load" : "unload",
         args.model_path ? " for: " : "",
         args.model_path ? args.model_path : "");

    pthread_mutex_lock(&ctx->mutex);
    enqueue_command_node(ctx, cmd);
    pthread_cond_signal(&ctx->worker_cond);
    pthread_mutex_unlock(&ctx->mutex);
}

static void
nativeStart(JNIEnv *env, jobject thiz, jint num_threads,
            jstring language, jboolean translate, jboolean live)
{
    struct whisper_jni_context *ctx = get_jni_context(env, thiz);
    if (!ctx)
    {
        (*env)->ThrowNew(env, g_class_illegal_state,
                         "WhisperContext not initialized");
        return;
    }

    if (num_threads < 1)
    {
        (*env)->ThrowNew(env, g_class_illegal_argument,
                         "num_threads must be >= 1");
        return;
    }

    struct start_args args;
    start_args_init(&args);

    if (language != NULL)
    {
        args.language = jni_strdup(env, language);
        if (!args.language)
        {
            (*env)->ThrowNew(env, g_class_out_of_memory,
                         "Failed to allocate memory for language string");
            return;
        }
    }

    args.num_threads = num_threads;
    args.translate = translate;
    args.live = live;
    args.session_id = atomic_load(&ctx->session_id);

    struct command_node *cmd = allocate_command_start(&args);
    if (!cmd)
    {
        (*env)->ThrowNew(env, g_class_out_of_memory,
                         "Failed to allocate memory for command");
        start_args_clean(&args);
        return;
    }

    LOGI("Queuing start command: threads=%d, lang=%s, translate=%d, session=%u, live=%d",
         num_threads, args.language ? args.language : "auto",
         translate, args.session_id, live);

    pthread_mutex_lock(&ctx->mutex);
    enqueue_command_node(ctx, cmd);
    pthread_cond_signal(&ctx->worker_cond);
    pthread_mutex_unlock(&ctx->mutex);
}

static void
nativeStop(JNIEnv *env, jobject thiz)
{
    struct whisper_jni_context *ctx = get_jni_context(env, thiz);
    if (!ctx)
    {
        LOGE("Invalid context");
        return;
    }

    LOGI("Stop - incrementing session");
    atomic_fetch_add(&ctx->session_id, 1);
}

static void
nativeSetDuration(JNIEnv *env, jobject thiz, jlong duration_ms)
{
    struct whisper_jni_context *ctx = get_jni_context(env, thiz);
    if (!ctx)
    {
        LOGE("Invalid context");
        return;
    }

    int64_t samples = (duration_ms * WHISPER_SAMPLE_RATE) / 1000;
    atomic_store(&ctx->duration_samples, samples);
    LOGI("Duration set: %lld ms (%lld samples)", (long long)duration_ms, (long long)samples);
}

static void
nativeUpdateLanguage(JNIEnv *env, jobject thiz, jstring language)
{
    struct whisper_jni_context *ctx = get_jni_context(env, thiz);
    if (!ctx)
    {
        LOGE("Invalid context");
        return;
    }

    int lang_id = -1;
    if (language)
    {
        const char *lang_str = (*env)->GetStringUTFChars(env, language, NULL);
        if (lang_str)
        {
            lang_id = whisper_lang_id(lang_str);
            LOGI("Language update: %s -> %d", lang_str, lang_id);
            (*env)->ReleaseStringUTFChars(env, language, lang_str);
        }
    }
    atomic_store(&ctx->lang_override, lang_id);
}

static void
nativeDestroy(JNIEnv *env, jobject thiz)
{
    struct whisper_jni_context *ctx = get_jni_context(env, thiz);
    if (!ctx)
    {
        LOGE("Invalid context");
        return;
    }

    LOGI("Destroying WhisperContext instance: %p", ctx);

    pthread_mutex_lock(&ctx->mutex);
    atomic_fetch_add(&ctx->session_id, 1);
    ctx->should_shutdown = true;
    pthread_cond_broadcast(&ctx->worker_cond);
    pthread_mutex_unlock(&ctx->mutex);

    LOGI("Waiting for worker thread to finish");
    pthread_join(ctx->worker_thread, NULL);
    LOGI("Worker thread finished");

    clear_command_queue(ctx, env);

    if (ctx->java_context)
    {
        (*env)->DeleteGlobalRef(env, ctx->java_context);
        ctx->java_context = NULL;
    }

    pthread_mutex_destroy(&ctx->mutex);
    pthread_cond_destroy(&ctx->worker_cond);
    for (size_t i = 0; i < 2; i++)
    {
        if (ctx->slots[i].vad_ctx)
            whisper_vad_free(ctx->slots[i].vad_ctx);
        if (ctx->slots[i].ctx)
            whisper_free(ctx->slots[i].ctx);
    }
    free(ctx);
    LOGI("WhisperContext instance destroyed");
}

JNIEXPORT jint
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    UNUSED(reserved);
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        return JNI_ERR;
    }

    jclass whisper_context_class = (*env)->FindClass(env,
        "com/voiceskip/whispercpp/whisper/WhisperContext");
    if (!whisper_context_class)
    {
        LOGE("JNI_OnLoad: Failed to find WhisperContext class");
        return JNI_ERR;
    }

    static const JNINativeMethod whisper_context_methods[] = {
        {"nativeCreate", "()J", (void*)nativeCreate},
        {"nativeLoadModel",
         "(Landroid/content/res/AssetManager;Ljava/lang/String;Ljava/lang/String;Z)V",
         (void*)nativeLoadModel},
        {"nativeLoadSecondModel",
         "(Landroid/content/res/AssetManager;Ljava/lang/String;Ljava/lang/String;)V",
         (void*)nativeLoadSecondModel},
        {"nativeStart", "(ILjava/lang/String;ZZ)V", (void*)nativeStart},
        {"nativeStop", "()V", (void*)nativeStop},
        {"nativeSetDuration", "(J)V", (void*)nativeSetDuration},
        {"nativeUpdateLanguage", "(Ljava/lang/String;)V", (void*)nativeUpdateLanguage},
        {"nativeDestroy", "()V", (void*)nativeDestroy},
    };

    if ((*env)->RegisterNatives(env, whisper_context_class,
                                 whisper_context_methods,
                                 ARRAY_SIZE(whisper_context_methods)) < 0)
    {
        LOGE("JNI_OnLoad: Failed to register WhisperContext native methods");
        (*env)->DeleteLocalRef(env, whisper_context_class);
        return JNI_ERR;
    }

    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (!cls) return JNI_ERR;
    g_class_illegal_state = (*env)->NewGlobalRef(env, cls);

    cls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (!cls) return JNI_ERR;
    g_class_illegal_argument = (*env)->NewGlobalRef(env, cls);

    cls = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
    if (!cls) return JNI_ERR;
    g_class_out_of_memory = (*env)->NewGlobalRef(env, cls);

    g_field_mInstance = (*env)->GetFieldID(env, whisper_context_class,
                                           "mInstance", "J");
    if (!g_field_mInstance)
    {
        (*env)->DeleteLocalRef(env, whisper_context_class);
        return JNI_ERR;
    }

    (*env)->DeleteLocalRef(env, whisper_context_class);

    whisper_log_set(whisper_android_log_callback, NULL);

    LOGI("JNI_OnLoad: Native methods registered successfully");
    return JNI_VERSION_1_6;
}

JNIEXPORT void
JNI_OnUnload(JavaVM *vm, void *reserved)
{
    UNUSED(reserved);
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        return;
    }

    if (g_class_illegal_state)
        (*env)->DeleteGlobalRef(env, g_class_illegal_state);
    if (g_class_illegal_argument)
        (*env)->DeleteGlobalRef(env, g_class_illegal_argument);
    if (g_class_out_of_memory)
        (*env)->DeleteGlobalRef(env, g_class_out_of_memory);

    LOGI("JNI_OnUnload: Library unloading");
}
