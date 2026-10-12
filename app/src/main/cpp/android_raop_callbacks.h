/*
 * android_raop_callbacks.h — native → JVM callback wiring for UxPlay's raop_callbacks_t.
 *
 * Contract is frozen (see docs/DESIGN.md §5.2). JNI descriptors here are
 * load-bearing: they must match RaopCallbackHandler.kt exactly.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef AIRPLAY_ANDROID_RAOP_CALLBACKS_H
#define AIRPLAY_ANDROID_RAOP_CALLBACKS_H

#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include "raop.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Per-server context holding the global ref + cached method IDs. Allocated in
 * nativeInit and freed in nativeDestroy. */
typedef struct android_callback_ctx_s {
    JavaVM  *jvm;
    jobject  callback_ref;   /* global ref to RaopCallbackHandler instance */

    /* cached method IDs (lookup once in init) */
    jmethodID on_connection_init;
    jmethodID on_connection_destroy;
    jmethodID on_connection_reset;
    jmethodID on_video_data;
    jmethodID on_video_size;
    jmethodID on_audio_data;
    jmethodID on_audio_format;
    jmethodID on_volume_change;
    jmethodID on_display_pin;
    /* v2 contract (see PLAN.md / RaopCallbackHandler.kt) */
    jmethodID on_client_request;      /* (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z */
    jmethodID on_audio_metadata;      /* (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V */
    jmethodID on_cover_art;           /* ([B)V */
    jmethodID on_audio_progress;      /* (FF)V */
    jmethodID on_hls_play;            /* (Ljava/lang/String;F)V */
    jmethodID on_hls_stop;            /* ()V */
    jmethodID on_hls_scrub;           /* (F)V */
    jmethodID on_hls_rate;            /* (F)V */

    /* HLS playback snapshot: pushed from Kotlin (android_hls_update_info) and
     * read by UxPlay's httpd thread in on_video_acquire_playback_info, so the
     * httpd thread never makes a JNI call for the 1 Hz /playback-info poll. */
    pthread_mutex_t hls_mu;
    pthread_cond_t  hls_cv;      /* signalled when the player reports ready */
    bool   hls_mu_ready;         /* mutex/cond initialised */
    bool   hls_active;           /* between on_video_play and stop */
    bool   hls_ready;
    double hls_duration;         /* seconds; <0 = playback finished/unavailable */
    double hls_position;
    float  hls_rate;

    /* v1 settings knobs (PIN wired in v2) */
    bool h265_enabled;

    /* Last AirPlay volume (dB) set by the sender; returned to /info queries.
     * calloc-zeroed = 0.0 = full volume, matching upstream UxPlay's default. */
    double volume_db;
} android_callback_ctx_t;

/* Populate a raop_callbacks_t. cls/userdata is the android_callback_ctx_t.
 * Returns 0 on success, nonzero if the RaopCallbackHandler class shape does not
 * match this file (fail fast at M0 rather than at session time). */
int android_callbacks_init(JNIEnv *env, jobject callback_handler,
                           android_callback_ctx_t *ctx);

/* Push the player's state (any thread). duration<0 = finished. */
/* Reset HLS state and wake any waiter (call at stop). */
void android_hls_abort(android_callback_ctx_t *ctx);
void android_hls_update_info(android_callback_ctx_t *ctx, double duration,
                             double position, float rate, bool ready);

void android_callbacks_fill(raop_callbacks_t *cbs);

/* Delete the global ref held by ctx (safe to call twice / on a zeroed ctx). */
void android_callbacks_release(JNIEnv *env, android_callback_ctx_t *ctx);

/* Parse a DMAP "mlit" listing item buffer (as sent in SET_PARAMETER
 * application/x-dmap-tagged). Writes malloc'd UTF-8 strings (or NULL) for
 * minm (title), asar (artist), asal (album); caller frees. Returns 0 when the
 * buffer was well-formed, -1 otherwise. Pure function: unit-testable on host. */
int android_dmap_parse(const unsigned char *buf, int len,
                       char **title, char **artist, char **album);

#ifdef __cplusplus
}
#endif
#endif
