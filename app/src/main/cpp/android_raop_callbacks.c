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

#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "AirplayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Resolve the callback handler class once and cache every jmethodID.
 * Returns 0 on success. On failure the design says: fail fast now, not at
 * session time. */
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

    (*env)->DeleteLocalRef(env, cls);

    /* Verify every callback resolved. */
    if (!ctx->on_connection_init || !ctx->on_connection_destroy ||
        !ctx->on_connection_reset || !ctx->on_video_data || !ctx->on_video_size ||
        !ctx->on_audio_data || !ctx->on_audio_format || !ctx->on_volume_change ||
        !ctx->on_display_pin) {
        LOGE("callbacks_init: one or more RaopCallbackHandler methods not found "
             "(signature drift between Kotlin and C?)");
        return -1;
    }
    return 0;
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

/* ---- raop_callbacks_t implementations ---------------------------------- */

static void cb_conn_init(void *cls)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (env) (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_connection_init);
    LOGI("conn_init");
}

static void cb_conn_destroy(void *cls)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (env) (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_connection_destroy);
    LOGI("conn_destroy");
}

static void cb_conn_reset(void *cls, int reason)
{
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    JNIEnv *env = cb_get_env(ctx);
    if (env) (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_connection_reset, reason);
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
    if (env) (*env)->CallVoidMethod(env, ctx->callback_ref, ctx->on_volume_change, volume);
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
    android_callback_ctx_t *ctx = (android_callback_ctx_t *) cls;
    /* Surface as onVideoSize's codec dimension later; for M0 we just log. */
    LOGI("video_set_codec=%d", codec);
    (void) ctx;
    return 0;
}

static void cb_noop(void *cls) { (void) cls; }
static void cb_noop_reset(void *cls, int reason) { (void) cls; (void) reason; }
static void cb_noop_reset_type(void *cls, reset_type_t t) { (void) cls; (void) t; }

/* ---- optional callbacks UxPlay may invoke; safe v1 defaults --------------- */

static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen)
{ (void) cls; (void) buffer; (void) buflen; }

static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen)
{ (void) cls; (void) buffer; (void) buflen; }

static void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *header)
{ (void) cls; (void) dacp_id; (void) header; }

static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end)
{ (void) cls; (void) start; (void) curr; (void) end; }

static void cb_mirror_video_running(void *cls, bool running) { (void) cls; (void) running; }

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name,
                                     bool *admit)
{
    (void) cls; (void) deviceid; (void) model; (void) name;
    if (admit) *admit = true;   /* accept every sender (no allow-list in v1) */
}

static void cb_register_client(void *cls, const char *device_id, const char *pk, const char *name)
{ (void) cls; (void) device_id; (void) pk; (void) name; }   /* device registry is v2 */

static void cb_display_pin(void *cls, char *pin) { (void) cls; (void) pin; }   /* PIN is v2 */

static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id)
{ (void) cls; (void) active_remote; (void) dacp_id; }

static const char *cb_passwd(void *cls, int *len)
{ (void) cls; (void) len; return NULL; }   /* no password protection in v1 */

/* HLS video-URL playback is out of scope for v1 mirroring, but these endpoints
 * are dispatched UNGUARDED (lib/http_handlers.h), so provide valid replies. */
static void cb_on_video_play(void *cls, const char *location, float start_position)
{ (void) cls; (void) location; (void) start_position; }
static void cb_on_video_scrub(void *cls, float position) { (void) cls; (void) position; }
static void cb_on_video_rate(void *cls, float rate) { (void) cls; (void) rate; }
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info)
{
    (void) cls;
    /* Caller treats -1.0 duration/position as "playback info not available". */
    if (info) { info->duration = -1.0; info->position = -1.0; }
}
static float cb_on_video_playlist_remove(void *cls) { (void) cls; return 0.0f; }

void android_callbacks_fill(raop_callbacks_t *cbs)
{
    memset(cbs, 0, sizeof(*cbs));
    cbs->audio_process     = cb_audio_process;
    cbs->video_process     = cb_video_process;
    cbs->video_pause       = cb_noop;
    cbs->video_resume      = cb_noop;
    cbs->conn_feedback     = cb_noop;
    cbs->conn_reset        = cb_noop_reset;
    cbs->video_reset       = cb_noop_reset_type;
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
    cbs->on_video_stop                  = cb_noop;
    cbs->on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs->on_video_playlist_remove       = cb_on_video_playlist_remove;
    /* check_register stays NULL: it is NULL-guarded and NULL means "accept all
     * clients", which is the v1 behavior (device registry is v2/PIN). */
}
