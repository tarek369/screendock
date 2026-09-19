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

void android_callbacks_fill(raop_callbacks_t *cbs);

#ifdef __cplusplus
}
#endif
#endif
