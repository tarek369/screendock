/*
 * android_raop_callbacks.c — implements UxPlay's raop_callbacks_t, forwarding
 * decoded (plaintext) video NALUs and audio frames plus lifecycle events up to
 * the JVM's RaopCallbackHandler.
 *
 * Threading (see docs/DESIGN.md §8): every callback here runs on a UxPlay
 * pthread (httpd / rtp_video / rtp_audio / ntp). We AttachCurrentThread on
 * demand and MUST clear any pending exception before the next JNI call, or a
 * stale exception will abort the process on the next NewByteArray.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "android_raop_callbacks.h"

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

#define TAG "AirplayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define AIRPLAY_RTP_RATE 44100.0f   /* RAOP audio RTP clock (Hz) */

/* Resolve the callback handler class once and cache every jmethodID.
 * Returns 0 on success. On failure the design says: fail fast now, not at
 * session time. On ANY failure the global ref is released again (no leak). */
int android_callbacks_init(JNIEnv *env, jobject callback_handler,
                           android_callback_ctx_t *ctx)
{
    memset(ctx, 0, sizeof(*ctx));
    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->callback_ref = (*env)->NewGlobalRef(env, callback_handler);
    if (!ctx->callback_ref) {
        LOGE("callbacks_init: NewGlobalRef failed");
        return -1;
    }

    jclass cls = (*env)->GetObjectClass(env, callback_handler);
    if (!cls) {
        LOGE("callbacks_init: cannot resolve RaopCallbackHandler class");
        android_callbacks_release(env, ctx);
        return -1;
    }

    /* Descriptors MUST match RaopCallbackHandler.kt. */
    ctx->on_connection_init    = (*env)->GetMethodID(env, cls, "onConnectionInit",   "()V");
    ctx->on_connection_destroy = (*env)->GetMethodID(env, cls, "onConnectionDestroy","()V");
    ctx->on_connection_reset   = (*env)->GetMethodID(env, cls, "onConnectionReset",  "(I)V");
    ctx->on_video_data         = (*env)->GetMethodID(env, cls, "onVideoData",        "([BJZ)V");
    ctx->on_video_size         = (*env)->GetMethodID(env, cls, "onVideoSize",        "(FFFF)V");
    ctx->on_audio_data         = (*env)->GetMethodID(env, cls, "onAudioData",        "([BIJI)V");
    ctx->on_audio_format       = (*env)->GetMethodID(env, cls, "onAudioFormat",      "(IIZ)V");
    ctx->on_volume_change      = (*env)->GetMethodID(env, cls, "onVolumeChange",     "(F)V");
    ctx->on_display_pin        = (*env)->GetMethodID(env, cls, "onDisplayPin",       "(Ljava/lang/String;)V");
    ctx->on_client_request     = (*env)->GetMethodID(env, cls, "onClientRequest",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    ctx->on_audio_metadata     = (*env)->GetMethodID(env, cls, "onAudioMetadata",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    ctx->on_cover_art          = (*env)->GetMethodID(env, cls, "onCoverArt",         "([B)V");
    ctx->on_audio_progress     = (*env)->GetMethodID(env, cls, "onAudioProgress",    "(FF)V");
    ctx->on_hls_play           = (*env)->GetMethodID(env, cls, "onHlsPlay",          "(Ljava/lang/String;F)V");
    ctx->on_hls_stop           = (*env)->GetMethodID(env, cls, "onHlsStop",          "()V");
    ctx->on_hls_scrub          = (*env)->GetMethodID(env, cls, "onHlsScrub",         "(F)V");
    ctx->on_hls_rate           = (*env)->GetMethodID(env, cls, "onHlsRate",          "(F)V");

    (*env)->DeleteLocalRef(env, cls);

    /* Verify every callback resolved. A failed GetMethodID leaves a pending
     * NoSuchMethodError — clear it so the caller's next JNI call is legal. */
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (!ctx->on_connection_init || !ctx->on_connection_destroy ||
        !ctx->on_connection_reset || !ctx->on_video_data || !ctx->on_video_size ||
        !ctx->on_audio_data || !ctx->on_audio_format || !ctx->on_volume_change ||
        !ctx->on_display_pin || !ctx->on_client_request || !ctx->on_audio_metadata ||
        !ctx->on_cover_art || !ctx->on_audio_progress || !ctx->on_hls_play ||
        !ctx->on_hls_stop || !ctx->on_hls_scrub || !ctx->on_hls_rate) {
        LOGE("callbacks_init: one or more RaopCallbackHandler methods not found "
             "(signature drift between Kotlin and C?)");
        android_callbacks_release(env, ctx);
        return -1;
    }

    pthread_mutex_init(&ctx->hls_mu, NULL);
    pthread_condattr_t ca;
    pthread_condattr_init(&ca);
    pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
    pthread_cond_init(&ctx->hls_cv, &ca);
    pthread_condattr_destroy(&ca);
    ctx->hls_mu_ready = true;
    ctx->hls_duration = -1.0;
    return 0;
}

void android_callbacks_release(JNIEnv *env, android_callback_ctx_t *ctx)
{
    if (ctx && ctx->callback_ref) {
        (*env)->DeleteGlobalRef(env, ctx->callback_ref);
        ctx->callback_ref = NULL;
    }
    if (ctx && ctx->hls_mu_ready) {
        pthread_cond_destroy(&ctx->hls_cv);
        pthread_mutex_destroy(&ctx->hls_mu);
        ctx->hls_mu_ready = false;
    }
}

/* ---- thread attach / detach ------------------------------------------------
 * UxPlay's pthreads are not JVM threads. We attach them lazily on first
 * up-call. An attached native thread MUST be detached before it exits or ART
 * aborts ("Native thread exited without calling DetachCurrentThread"). A
 * pthread key whose destructor runs at thread exit does the detach. */
static pthread_key_t  g_detach_key;
static pthread_once_t g_detach_once = PTHREAD_ONCE_INIT;

static void detach_on_thread_exit(void *vm_ptr)
{
    JavaVM *vm = (JavaVM *) vm_ptr;
    if (vm) (*vm)->DetachCurrentThread(vm);
}

static void make_detach_key(void)
{
    if (pthread_key_create(&g_detach_key, detach_on_thread_exit) != 0) {
        LOGE("pthread_key_create failed; native threads will not be detached");
    }
}

/* Attach (if needed) and return a JNIEnv for the current native thread.
 * Clears any pending exception so a subsequent NewByteArray won't abort. */
static JNIEnv *cb_get_env(android_callback_ctx_t *ctx)
{
    JNIEnv *env = NULL;
    JavaVMAttachArgs aargs = { JNI_VERSION_1_6, "UxPlayCallback", NULL };
    jint rc = (*ctx->jvm)->GetEnv(ctx->jvm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        rc = (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, &aargs);
        if (rc != JNI_OK) {
            LOGE("cb_get_env: AttachCurrentThread failed rc=%d", rc);
            return NULL;
        }
        pthread_once(&g_detach_once, make_detach_key);
        /* Non-NULL value arms the destructor for this thread. */
        pthread_setspecific(g_detach_key, ctx->jvm);
    } else if (rc != JNI_OK) {
        return NULL;
    }
    /* Critical (§5.4): a stale pending exception makes the next JNI call fatal. */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);   /* to logcat */
        (*env)->ExceptionClear(env);
    }
    return env;
}

/* Call after EVERY up-call: an exception thrown by Kotlin must not leak into
 * the next JNI call on this thread. Returns true if one was pending. */
static bool cb_check(JNIEnv *env, const char *what)
{
    if ((*env)->ExceptionCheck(env)) {
        LOGE("exception in up-call %s", what);
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return true;
    }
    return false;
}

/* UTF-8 (possibly invalid, possibly 4-byte) -> java.lang.String. NewStringUTF
 * wants *modified* UTF-8 and CheckJNI aborts on anything else, so decode to
 * UTF-16 ourselves; invalid sequences become U+FFFD. Returns NULL for NULL. */
static jstring cb_new_string(JNIEnv *env, const char *s)
{
    if (!s) return NULL;
    size_t n = strlen(s);
    jchar *out = (jchar *) malloc((n + 1) * sizeof(jchar));
    if (!out) return NULL;
    size_t o = 0;
    const unsigned char *p = (const unsigned char *) s;
    size_t i = 0;
    while (i < n) {
        unsigned c = p[i];
        uint32_t cp = 0xFFFD;
        size_t need = 0;
        if (c < 0x80)               { cp = c; need = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; need = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; need = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; need = 3; }
        else                         { cp = 0xFFFD; need = 0; }
        if (need > 0 && i + need > n - 1) { cp = 0xFFFD; need = 0; }   /* truncated */
        if (need > 0) {
            int ok = 1;
            for (size_t k = 1; k <= need; k++) {
                if ((p[i + k] & 0xC0) != 0x80) { ok = 0; break; }
                cp = (cp << 6) | (p[i + k] & 0x3F);
            }
            if (!ok || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) { cp = 0xFFFD; need = 0; }
        }
        i += 1 + need;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out[o++] = (jchar) (0xD800 + (cp >> 10));
            out[o++] = (jchar) (0xDC00 + (cp & 0x3FF));
        } else {
            out[o++] = (jchar) cp;
        }
    }
    jstring js = (*env)->NewString(env, out, (jsize) o);
    free(out);
    if (!js) (*env)->ExceptionClear(env);
    return js;
}

/* ---- raop_callbacks_t implementations ---------------------------------- */

static void cb_conn_init(void *cls)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (env) { (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_connection_init); cb_check(env, "onConnectionInit"); }
    LOGI("conn_init");
}

static void cb_conn_destroy(void *cls)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (env) { (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_connection_destroy); cb_check(env, "onConnectionDestroy"); }
    LOGI("conn_destroy");
}

static void cb_conn_reset(void *cls, int reason)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (env) { (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_connection_reset, reason); cb_check(env, "onConnectionReset"); }
    LOGW("conn_reset reason=%d", reason);
}

static void cb_conn_teardown(void *cls, bool *teardown_96, bool *teardown_110)
{
    /* v1: tear down both audio (96) and video (110) on teardown. */
    (void) cls;
    if (teardown_96)  *teardown_96  = true;
    if (teardown_110) *teardown_110 = true;
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *vdata)
{
    (void) ntp;
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (!env || vdata->data_len <= 0) return;

    /* v1 marshalling: byte[] copy (DESIGN §5.3). Cheap enough at mirroring bitrates;
     * switch to DirectByteBuffer only if M4 profiling shows it on HEVC. */
    jbyteArray arr = (*env)->NewByteArray(env, vdata->data_len);
    if (!arr) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return;
    }
    (*env)->SetByteArrayRegion(env, arr, 0, vdata->data_len, (jbyte *) vdata->data);
    (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_video_data, arr,
                           (jlong) vdata->ntp_time_remote, (jboolean) vdata->is_h265);
    cb_check(env, "onVideoData");
    (*env)->DeleteLocalRef(env, arr);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *adata)
{
    (void) ntp;
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (!env || adata->data_len <= 0) return;

    jbyteArray arr = (*env)->NewByteArray(env, adata->data_len);
    if (!arr) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return;
    }
    (*env)->SetByteArrayRegion(env, arr, 0, adata->data_len, (jbyte *) adata->data);
    (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_audio_data, arr,
                           (jint) adata->ct, (jlong) adata->ntp_time_remote,
                           (jint) adata->seqnum);
    cb_check(env, "onAudioData");
    (*env)->DeleteLocalRef(env, arr);
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                                bool *using_screen, bool *is_media, uint64_t *audio_format)
{
    /* No-op defaults; the actual AAC/ALAC choice is driven by the first
     * onAudioData's ct, and we surface it via onAudioFormat from the
     * SETUP path later. Keeps UxPlay happy in M0/M1. */
    (void) cls; (void) ct; (void) spf; (void) using_screen;
    (void) is_media; (void) audio_format;
}

static void cb_audio_set_volume(void *cls, float volume)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    ctx->volume_db = volume;   /* raw AirPlay dB {-30:0} or -144 mute */
    JNIEnv *env = cb_get_env(ctx);
    if (env) { (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_volume_change, volume); cb_check(env, "onVolumeChange"); }
}

/* raop_handler_info calls this UNGUARDED on every /info request that has a
 * CSeq (raop_handlers.h) — a NULL here segfaults the app on the first real
 * sender. Returns the current AirPlay volume in dB, as upstream does. */
static double cb_audio_set_client_volume(void *cls)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    return ctx->volume_db;
}

static void cb_video_report_size(void *cls, float *w_src, float *h_src, float *w, float *h)
{
    (void) cls; (void) w_src; (void) h_src; (void) w; (void) h;
    /* VideoRenderer will own geometry in M2; UxPlay just needs a valid ptr. */
}

static int cb_video_set_codec(void *cls, video_codec_t codec)
{
    (void) cls;
    LOGI("video_set_codec=%d", codec);
    return 0;
}

static void cb_noop(void *cls) { (void) cls; }

/* ---- HLS helpers -------------------------------------------------------- */

static void hls_call_void(android_callback_ctx_t *ctx, jmethodID mid, const char *what)
{
    JNIEnv *env = cb_get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_ref, mid);
    cb_check(env, what);
}

static void hls_mark_stopped(android_callback_ctx_t *ctx)
{
    pthread_mutex_lock(&ctx->hls_mu);
    ctx->hls_active = false;
    ctx->hls_ready = false;
    ctx->hls_duration = -1.0;
    ctx->hls_position = -1.0;
    pthread_cond_broadcast(&ctx->hls_cv);
    pthread_mutex_unlock(&ctx->hls_mu);
}

static void cb_video_reset(void *cls, reset_type_t t)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    switch (t) {
        case RESET_TYPE_NOHOLD:        /* new sender takes over */
        case RESET_TYPE_HLS_SHUTDOWN:  /* sender closed the HLS connection */
        case RESET_TYPE_HLS_EOS:       /* playback ended */
            LOGI("video_reset type=%d -> HLS stop", t);
            hls_mark_stopped(ctx);
            hls_call_void(ctx, ctx->on_hls_stop, "onHlsStop");
            break;
        default:
            break;   /* RTP shutdown / on_video_play / rtp->hls teardown: nothing to do */
    }
}

/* Must NOT block: this runs on UxPlay's single httpd thread, which also has to
 * serve the local player's GET /master.m3u8. Until Kotlin pushes the first
 * playback info, playback-info reports ready=false / duration 0. */
static void cb_on_video_play(void *cls, const char *location, float start_position)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (!env || !location) return;
    LOGI("on_video_play location=%s start=%.2f", location, start_position);

    pthread_mutex_lock(&ctx->hls_mu);
    ctx->hls_active = true;
    ctx->hls_ready = false;
    ctx->hls_duration = 0.0;
    ctx->hls_position = start_position > 0 ? start_position : 0.0;
    ctx->hls_rate = 0.0f;
    pthread_mutex_unlock(&ctx->hls_mu);

    jstring jloc = cb_new_string(env, location);
    if (!jloc) { hls_mark_stopped(ctx); return; }
    (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_hls_play, jloc, (jfloat) start_position);
    cb_check(env, "onHlsPlay");
    (*env)->DeleteLocalRef(env, jloc);
}

static void cb_on_video_scrub(void *cls, float position)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (!env) return;
    pthread_mutex_lock(&ctx->hls_mu);
    ctx->hls_position = position;   /* optimistic until the next player push */
    pthread_mutex_unlock(&ctx->hls_mu);
    (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_hls_scrub, position);
    cb_check(env, "onHlsScrub");
}

static void cb_on_video_rate(void *cls, float rate)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_hls_rate, rate);
    cb_check(env, "onHlsRate");
}

static void cb_on_video_stop(void *cls)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    hls_mark_stopped(ctx);
    hls_call_void(ctx, ctx->on_hls_stop, "onHlsStop");
}

static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    if (!info) return;
    pthread_mutex_lock(&ctx->hls_mu);
    double dur = ctx->hls_active ? ctx->hls_duration : -1.0;
    double pos = ctx->hls_active ? ctx->hls_position : -1.0;
    info->rate = ctx->hls_rate;
    info->ready_to_play = ctx->hls_ready;
    pthread_mutex_unlock(&ctx->hls_mu);
    /* The caller treats duration == -1.0 as "finished / not available". */
    info->duration = dur < 0 ? -1.0 : dur;
    info->position = pos < 0 ? -1.0 : pos;
    info->seek_start = 0.0;
    info->seek_duration = dur > 0 ? dur : 0.0;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    info->playback_likely_to_keep_up = true;
}

/* Sender removed the item (e.g. ad boundary or navigated away). Return the
 * current position so UxPlay can resume there on a later re-request, and stop
 * the local player. */
static float cb_on_video_playlist_remove(void *cls)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    pthread_mutex_lock(&ctx->hls_mu);
    float pos = ctx->hls_position > 0 ? (float) ctx->hls_position : 0.0f;
    pthread_mutex_unlock(&ctx->hls_mu);
    hls_mark_stopped(ctx);
    hls_call_void(ctx, ctx->on_hls_stop, "onHlsStop");
    return pos;
}

void android_hls_abort(android_callback_ctx_t *ctx)
{
    if (!ctx || !ctx->hls_mu_ready) return;
    hls_mark_stopped(ctx);
}

void android_hls_update_info(android_callback_ctx_t *ctx, double duration,
                             double position, float rate, bool ready)
{
    if (!ctx || !ctx->hls_mu_ready) return;
    pthread_mutex_lock(&ctx->hls_mu);
    if (ctx->hls_active) {
        ctx->hls_duration = duration;
        ctx->hls_position = position;
        ctx->hls_rate = rate;
        ctx->hls_ready = ready;
        if (ready && duration > 0.0) pthread_cond_broadcast(&ctx->hls_cv);
    }
    pthread_mutex_unlock(&ctx->hls_mu);
}

/* ---- audio metadata / cover art / progress -------------------------------- */

#define DMAP_STR_MAX 1024

static char *dmap_dup(const unsigned char *p, int len)
{
    if (len > DMAP_STR_MAX) len = DMAP_STR_MAX;
    char *s = (char *) malloc((size_t) len + 1);
    if (!s) return NULL;
    memcpy(s, p, (size_t) len);
    s[len] = '\0';
    return s;
}

int android_dmap_parse(const unsigned char *buf, int len,
                      char **title, char **artist, char **album)
{
    *title = *artist = *album = NULL;
    if (!buf || len < 8) return -1;
    if (memcmp(buf, "mlit", 4) != 0) return -1;
    int total = (int) (((uint32_t) buf[4] << 24) | ((uint32_t) buf[5] << 16) |
                       ((uint32_t) buf[6] << 8)  |  (uint32_t) buf[7]);
    int remain = len - 8;
    if (total < 0 || total > remain) return -1;
    const unsigned char *p = buf + 8;
    remain = total;
    while (remain >= 8) {
        const unsigned char *tag = p;
        int ilen = (int) (((uint32_t) p[4] << 24) | ((uint32_t) p[5] << 16) |
                          ((uint32_t) p[6] << 8)  |  (uint32_t) p[7]);
        p += 8; remain -= 8;
        if (ilen < 0 || ilen > remain) return -1;
        if (!memcmp(tag, "minm", 4) && !*title)  *title  = dmap_dup(p, ilen);
        else if (!memcmp(tag, "asar", 4) && !*artist) *artist = dmap_dup(p, ilen);
        else if (!memcmp(tag, "asal", 4) && !*album)  *album  = dmap_dup(p, ilen);
        p += ilen; remain -= ilen;
    }
    return 0;
}

static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    char *t, *a, *al;
    if (android_dmap_parse((const unsigned char *) buffer, buflen, &t, &a, &al) != 0) {
        LOGW("audio_set_metadata: malformed DMAP (len=%d)", buflen);
        free(t); free(a); free(al);
        return;
    }
    JNIEnv *env = cb_get_env(ctx);
    if (env) {
        jstring jt = cb_new_string(env, t), ja = cb_new_string(env, a), jal = cb_new_string(env, al);
        (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_audio_metadata, jt, ja, jal);
        cb_check(env, "onAudioMetadata");
        if (jt) (*env)->DeleteLocalRef(env, jt);
        if (ja) (*env)->DeleteLocalRef(env, ja);
        if (jal) (*env)->DeleteLocalRef(env, jal);
    }
    free(t); free(a); free(al);
}

static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    if (!buffer || buflen <= 0) return;
    JNIEnv *env = cb_get_env(ctx);
    if (!env) return;
    jbyteArray arr = (*env)->NewByteArray(env, buflen);
    if (!arr) { cb_check(env, "NewByteArray(coverart)"); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, buflen, (const jbyte *) buffer);
    (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_cover_art, arr);
    cb_check(env, "onCoverArt");
    (*env)->DeleteLocalRef(env, arr);
}

static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    if (!start || !curr || !end) return;
    /* RTP timestamps wrap at 2^32: unsigned subtraction is wrap-safe. */
    uint32_t pos_ticks = (uint32_t) (*curr - *start);
    uint32_t dur_ticks = (uint32_t) (*end - *start);
    float pos = (float) pos_ticks / AIRPLAY_RTP_RATE;
    float dur = (float) dur_ticks / AIRPLAY_RTP_RATE;
    JNIEnv *env = cb_get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_audio_progress, pos, dur);
    cb_check(env, "onAudioProgress");
}

static void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *header)
{ (void) cls; (void) dacp_id; (void) header; }

static void cb_mirror_video_running(void *cls, bool running) { (void) cls; (void) running; }

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name,
                                     bool *admit)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    if (admit) *admit = true;
    JNIEnv *env = cb_get_env(ctx);
    if (!env || !admit) return;
    jstring jd = cb_new_string(env, deviceid ? deviceid : "");
    jstring jm = cb_new_string(env, model ? model : "");
    jstring jn = cb_new_string(env, name ? name : "");
    /* Runs on the httpd thread: Kotlin may block here (e.g. waiting for the
     * user's approve/deny answer). Exceptions or errors fail OPEN (admit). */
    jboolean ok = (*env)->CallBooleanMethod(env, ctx->callback_ref, ctx->on_client_request, jd, jm, jn);
    if (cb_check(env, "onClientRequest")) ok = JNI_TRUE;
    *admit = (ok == JNI_TRUE);
    LOGI("client request device=%s name=%s -> %s", deviceid ? deviceid : "?",
         name ? name : "?", *admit ? "admit" : "REFUSE");
    if (jd) (*env)->DeleteLocalRef(env, jd);
    if (jm) (*env)->DeleteLocalRef(env, jm);
    if (jn) (*env)->DeleteLocalRef(env, jn);
}

static void cb_register_client(void *cls, const char *device_id, const char *pk, const char *name)
{ (void) cls; (void) device_id; (void) pk; (void) name; }   /* device registry is v2 */

static void cb_display_pin(void *cls, char *pin)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    if (!pin) return;
    JNIEnv *env = cb_get_env(ctx);
    if (!env) return;
    jstring jp = cb_new_string(env, pin);
    if (!jp) return;
    (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_display_pin, jp);
    cb_check(env, "onDisplayPin");
    (*env)->DeleteLocalRef(env, jp);
}

static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id)
{ (void) cls; (void) active_remote; (void) dacp_id; }

static const char *cb_passwd(void *cls, int *len)
{ (void) cls; if (len) *len = 0; return NULL; }   /* PIN mode uses pair-setup-pin, not a password */

void android_callbacks_fill(raop_callbacks_t *cbs)
{
    memset(cbs, 0, sizeof(*cbs));
    cbs->audio_process     = cb_audio_process;
    cbs->video_process     = cb_video_process;
    cbs->video_pause       = cb_noop;
    cbs->video_resume      = cb_noop;
    cbs->conn_feedback     = cb_noop;
    cbs->conn_reset        = cb_conn_reset;
    cbs->video_reset       = cb_video_reset;
    cbs->conn_init         = cb_conn_init;
    cbs->conn_destroy      = cb_conn_destroy;
    cbs->conn_teardown     = cb_conn_teardown;
    cbs->audio_flush       = cb_noop;
    cbs->video_flush       = cb_noop;
    cbs->audio_set_client_volume = cb_audio_set_client_volume;
    cbs->audio_set_volume  = cb_audio_set_volume;
    cbs->audio_set_metadata       = cb_audio_set_metadata;
    cbs->audio_set_coverart       = cb_audio_set_coverart;
    cbs->audio_stop_coverart_rendering = cb_noop;
    cbs->audio_remote_control_id  = cb_audio_remote_control_id;
    cbs->audio_set_progress       = cb_audio_set_progress;
    cbs->audio_get_format  = cb_audio_get_format;
    cbs->video_report_size = cb_video_report_size;
    cbs->video_set_codec   = cb_video_set_codec;
    cbs->mirror_video_running    = cb_mirror_video_running;
    cbs->report_client_request   = cb_report_client_request;
    cbs->register_client         = cb_register_client;
    cbs->display_pin             = cb_display_pin;
    cbs->export_dacp             = cb_export_dacp;
    cbs->passwd                  = cb_passwd;
    cbs->on_video_play                  = cb_on_video_play;
    cbs->on_video_scrub                 = cb_on_video_scrub;
    cbs->on_video_rate                  = cb_on_video_rate;
    cbs->on_video_stop                  = cb_on_video_stop;
    cbs->on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs->on_video_playlist_remove       = cb_on_video_playlist_remove;
    /* check_register stays NULL: it is NULL-guarded and NULL means "accept all
     * clients" (device registry is v2). */
}
