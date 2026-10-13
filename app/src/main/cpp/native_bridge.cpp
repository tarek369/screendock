/*
 * native_bridge.cpp — downward JNI surface (Java/Kotlin → native).
 *
 * Owns the server_ctx_t (raop_t + dnssd_t + android_callback_ctx_t) and exposes
 * the stable API in NativeBridge.kt. The handle passed across the JNI boundary
 * is a jlong cast of server_ctx_t*.
 *
 * Lifecycle (see docs/DESIGN.md §5 + UxPlay uxplay.cpp):
 *   nativeInit   → raop_init + callbacks wired (no keygen, no sockets yet)
 *   nativeStart  → raop_init2 (Ed25519 keygen + pairing + httpd), set ports,
 *                  raop_start_httpd (binds the real port), dnssd_init + set_pk
 *                  + register_* (builds TXT records), raop_set_dnssd.
 *                  Actual mDNS *advertising* is done in Kotlin (NsdManager);
 *                  the shim only builds TXT + the service name.
 *   nativeStop   → raop_stop_httpd + dnssd_unregister
 *   nativeDestroy→ raop_destroy + dnssd_destroy + free
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <stdio.h>
#include <android/log.h>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "global.h"
}
#include "android_raop_callbacks.h"
#include "android_event_channel.h"

/* ALAC software decoder (Apache-2.0, macosforge/alac) */
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

#define TAG "AirplayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" const char *android_dnssd_get_raop_name(dnssd_t *dnssd);

/* Per-server state. Outlives individual JNI calls. */
struct server_ctx {
    raop_t                 *raop;
    dnssd_t                *dnssd;
    raop_callbacks_t        callbacks;
    android_callback_ctx_t  cb_ctx;
    event_channel_t        *events;   /* receiver→sender touch backchannel */
    char                    hw_addr[6];
    char                    device_id[18];   /* "XX:XX:..." */
    char                   *key_file;
    char                   *name;           /* receiver display name (mDNS instance base) */
    bool                    started;
    bool                    raop_inited2;   /* raop_init2 succeeded (httpd exists) */
    bool                    pin_enabled;    /* PIN pairing required */
    int                     pin_fixed;      /* 0..9999 fixed PIN, -1 = random per pairing */
    bool                    hls_enabled;    /* advertise + accept AirPlay video (HLS) */
    bool                    h265_enabled;   /* advertise H.265 (features bit 42) */
};

#define DEFAULT_RECEIVER_NAME "ScreenDock"
#define MAX_RECEIVER_NAME_BYTES 63   /* keeps "<mac>@<name>._raop._tcp.local" inside the shim's buffer */

/* Apply the cached capability flags to a dnssd instance (features bits). Must run
 * before dnssd_register_* so the TXT records contain them. */
static void apply_feature_flags(struct server_ctx *ctx)
{
    if (!ctx->dnssd) return;
    /* Bit 42 = SupportsScreenMultiCodec (H.265). Bits 0 and 4 = AirPlay video /
     * HTTP live streaming (needed for the YouTube-style "cast video" button). */
    dnssd_set_airplay_features(ctx->dnssd, 42, ctx->h265_enabled ? 1 : 0);
    dnssd_set_airplay_features(ctx->dnssd, 0, ctx->hls_enabled ? 1 : 0);
    dnssd_set_airplay_features(ctx->dnssd, 4, ctx->hls_enabled ? 1 : 0);
}

static void free_server_ctx_strings(struct server_ctx *ctx)
{
    free(ctx->key_file); ctx->key_file = nullptr;
    free(ctx->name);     ctx->name = nullptr;
}

/* Forward UxPlay's internal (stderr) logs to logcat. Without this the RTSP
 * handshake is invisible on Android. Level is bumpable for protocol probes. */
static void android_log_forward(void *cls, int level, const char *msg)
{
    (void) cls;
    android_LogPriority prio = (level >= 7 /* LOGGER_DEBUG */) ? ANDROID_LOG_DEBUG
                               : (level >= 5 /* LOGGER_WARNING? */) ? ANDROID_LOG_INFO
                               : ANDROID_LOG_WARN;
    __android_log_print(prio, "AirplayNative", "%s", msg);
}

/* Build the UxPlay device-id string "XX:XX:..." from a 6-byte MAC. */
static void hwaddr_to_deviceid(const char hw[6], char out[18])
{
    snprintf(out, 18, "%02X:%02X:%02X:%02X:%02X:%02X",
             (unsigned char) hw[0], (unsigned char) hw[1], (unsigned char) hw[2],
             (unsigned char) hw[3], (unsigned char) hw[4], (unsigned char) hw[5]);
}

/* Parse a raw DNS TXT record (sequence of length-prefixed "key=value" entries)
 * into a Java HashMap<String,String>. Returns a local ref (caller deletes). */
static jobject parse_txt_to_map(JNIEnv *env, const char *txt, int len)
{
    jclass map_cls = env->FindClass("java/util/HashMap");
    jmethodID ctor = env->GetMethodID(map_cls, "<init>", "()V");
    jmethodID put  = env->GetMethodID(map_cls, "put",
                                      "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject map = env->NewObject(map_cls, ctor);

    int i = 0;
    while (i < len) {
        int entry_len = (unsigned char) txt[i];
        if (entry_len <= 0 || i + 1 + entry_len > len) break;
        const char *entry = txt + i + 1;
        int eq = -1;
        for (int j = 0; j < entry_len; j++) {
            if (entry[j] == '=') { eq = j; break; }
        }
        if (eq > 0) {
            jstring key = env->NewStringUTF(std::string(entry, eq).c_str());
            jstring val = env->NewStringUTF(std::string(entry + eq + 1, entry_len - eq - 1).c_str());
            env->CallObjectMethod(map, put, key, val);
            env->DeleteLocalRef(key);
            env->DeleteLocalRef(val);
        }
        i += 1 + entry_len;
    }
    env->DeleteLocalRef(map_cls);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return map;
}

/* =========================================================================
 *  Version + self-test (M0)
 *  ====================================================================== */

extern "C" JNIEXPORT jstring JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeGetVersion(JNIEnv *env, jobject)
{
    const char *v = "ScreenDock 0.3.1 (UxPlay core " GLOBAL_VERSION ")";
    return env->NewStringUTF(v);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeSelfTest(JNIEnv *env, jobject)
{
    /* Round-trip the mDNS shim (TXT construction + service-name build). We avoid
     * raop_init/destroy here because raop_destroy asserts httpd != NULL. */
    int err = 0;
    const char *name = "ScreenDock";
    dnssd_t *d = dnssd_init(name, (int) strlen(name), "\x2c\xf0\xa2\x25\x3a\x9e", 6, 0, &err);
    if (!d || err != DNSSD_ERROR_NOERROR) { LOGE("selfTest: dnssd_init err=%d", err); return JNI_FALSE; }
    if (dnssd_register_airplay(d, 7000) || dnssd_register_raop(d, 7000)) {
        LOGE("selfTest: register failed"); dnssd_destroy(d); return JNI_FALSE;
    }
    int rl = 0, al = 0;
    dnssd_get_raop_txt(d, &rl);
    dnssd_get_airplay_txt(d, &al);
    jboolean ok = (rl > 0 && al > 0) ? JNI_TRUE : JNI_FALSE;
    LOGI("nativeSelfTest: %s (raop=%dB airplay=%dB name=%s)",
         ok ? "OK" : "FAIL", rl, al, android_dnssd_get_raop_name(d));
    dnssd_destroy(d);
    return ok;
}

/* =========================================================================
 *  Init / destroy (handle lifecycle)
 *  ====================================================================== */

extern "C" JNIEXPORT jlong JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeInit(JNIEnv *env, jobject,
                                                        jobject callback_handler,
                                                        jbyteArray hw_addr,
                                                        jstring name, jstring key_file,
                                                        jboolean require_pin)
{
    if (!callback_handler || !hw_addr) { LOGE("nativeInit: null argument"); return 0; }

    struct server_ctx *ctx = (struct server_ctx *) calloc(1, sizeof(*ctx));
    if (!ctx) { LOGE("nativeInit: OOM"); return 0; }
    ctx->pin_fixed = -1;
    ctx->pin_enabled = (require_pin == JNI_TRUE);

    jsize n = env->GetArrayLength(hw_addr);
    if (n != 6) { LOGE("nativeInit: hw_addr must be 6 bytes (got %d)", n); free(ctx); return 0; }
    env->GetByteArrayRegion(hw_addr, 0, 6, (jbyte *) ctx->hw_addr);
    hwaddr_to_deviceid(ctx->hw_addr, ctx->device_id);

    const char *kf = key_file ? env->GetStringUTFChars(key_file, nullptr) : nullptr;
    if (kf) { ctx->key_file = strdup(kf); env->ReleaseStringUTFChars(key_file, kf); }

    /* Receiver display name -> mDNS instance name (applied at nativeStart). */
    const char *nm = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    if (nm && nm[0]) {
        size_t len = strlen(nm);
        if (len > MAX_RECEIVER_NAME_BYTES) {
            len = MAX_RECEIVER_NAME_BYTES;
            /* don't cut a UTF-8 sequence in half */
            while (len > 0 && ((unsigned char) nm[len] & 0xC0) == 0x80) len--;
        }
        ctx->name = strndup(nm, len);
    }
    if (nm) env->ReleaseStringUTFChars(name, nm);
    if (!ctx->name || !ctx->name[0]) { free(ctx->name); ctx->name = strdup(DEFAULT_RECEIVER_NAME); }

    if (android_callbacks_init(env, callback_handler, &ctx->cb_ctx) != 0) {
        /* callbacks_init already released the global ref */
        free_server_ctx_strings(ctx); free(ctx); return 0;
    }
    android_callbacks_fill(&ctx->callbacks);
    ctx->callbacks.cls = &ctx->cb_ctx;

    ctx->raop = raop_init(&ctx->callbacks);
    if (!ctx->raop) {
        LOGE("nativeInit: raop_init failed");
        android_callbacks_release(env, &ctx->cb_ctx);   /* don't leak the global ref */
        free_server_ctx_strings(ctx); free(ctx); return 0;
    }
    raop_set_log_callback(ctx->raop, android_log_forward, NULL);
    raop_set_log_level(ctx->raop, 6 /* LOGGER_INFO; 7=DEBUG dumps every RTSP plist but spams resends */);

    /* Display-cap defaults; nativeSetDisplaySize overrides these BEFORE
     * nativeStart (the service always applies settings first). Do NOT set
     * them in nativeStart — that would clobber the caller's values. */
    raop_set_plist(ctx->raop, "width", 1920);
    raop_set_plist(ctx->raop, "height", 1080);
    raop_set_plist(ctx->raop, "refreshRate", 60);
    raop_set_plist(ctx->raop, "maxFPS", 60);

    LOGI("nativeInit: ok device_id=%s name=%s", ctx->device_id, ctx->name);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeDestroy(JNIEnv *env, jobject, jlong handle)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx) return;
    if (ctx->started) raop_stop_httpd(ctx->raop);
    event_channel_stop(&ctx->events);
    /* raop_destroy asserts httpd != NULL, which only exists after raop_init2.
     * If start never got that far, the (small) raop_t is intentionally leaked
     * rather than aborting the process. */
    if (ctx->raop && ctx->raop_inited2) raop_destroy(ctx->raop);
    if (ctx->dnssd)  dnssd_destroy(ctx->dnssd);
    android_callbacks_release(env, &ctx->cb_ctx);
    free_server_ctx_strings(ctx);
    free(ctx);
}

/* =========================================================================
 *  Start / stop (the real server)
 *  ====================================================================== */

/* Undo a partially completed nativeStart so a later retry (or destroy) is clean. */
static void abort_start(struct server_ctx *ctx)
{
    if (ctx->raop_inited2) raop_stop_httpd(ctx->raop);
    event_channel_stop(&ctx->events);
    if (ctx->dnssd) { dnssd_destroy(ctx->dnssd); ctx->dnssd = nullptr; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeStart(JNIEnv *env, jobject, jlong handle, jint requested_port)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->raop) { LOGE("nativeStart: no ctx"); return -1; }
    if (ctx->started) { LOGW("nativeStart: already running"); return -1; }
    if (ctx->dnssd || ctx->events) abort_start(ctx);   /* leftovers from a failed attempt */

    /* 1. Ed25519 keygen + pairing + httpd init (once per handle). nohold=true so a
     *    3rd conn drops an existing one instead of being rejected (-DNOHOLD). */
    if (!ctx->raop_inited2) {
        if (raop_init2(ctx->raop, /*nohold*/ 1, ctx->device_id,
                       ctx->key_file ? ctx->key_file : "")) {
            LOGE("nativeStart: raop_init2 failed (keygen/pairing/httpd)");
            return -1;
        }
        ctx->raop_inited2 = true;
    }

    /* PIN + HLS switches live in raop's plist (UxPlay uxplay.cpp does the same):
     * pin value 0 = random PIN per pairing, n+10000 = fixed PIN n. */
    if (ctx->pin_enabled) {
        raop_set_plist(ctx->raop, "pin", ctx->pin_fixed >= 0 ? ctx->pin_fixed + 10000 : 0);
    }
    raop_set_plist(ctx->raop, "hls", ctx->hls_enabled ? 1 : 0);

    /* 2. Ports: let httpd bind any free TCP port for RAOP/AirPlay control, and
     *    fixed UDP ports for timing/control/data. UxPlay passes 0 = OS-assigned. */
    unsigned short tcp[2] = { (unsigned short) requested_port, 0 };
    unsigned short udp[3] = { 0, 0, 0 };
    raop_set_tcp_ports(ctx->raop, tcp);
    raop_set_udp_ports(ctx->raop, udp);

    /* 3. Bind + start the httpd thread. httpd_start returns: 1 = started,
     *    0 = already running, <0 = error. Treat only <0 as failure. */
    unsigned short bound_port = (unsigned short) requested_port;
    int httpd_rc = raop_start_httpd(ctx->raop, &bound_port);
    if (httpd_rc < 0) {
        LOGE("nativeStart: raop_start_httpd failed rc=%d", httpd_rc);
        return -1;
    }
    /* MUST follow raop_start_httpd: the HLS location URL embeds this port. */
    raop_set_port(ctx->raop, bound_port);

    /* 3b. Touch backchannel: listen BEFORE senders see the SETUP response so
     *     the advertised port is connectable immediately. */
    unsigned short event_port = 0;
    ctx->events = event_channel_start(&event_port);
    if (ctx->events && event_port > 0) {
        raop_set_event_port(ctx->raop, event_port);
        LOGI("nativeStart: event channel listening on TCP %u", event_port);
    } else {
        LOGW("nativeStart: event channel unavailable (touch-back disabled)");
    }

    /* 4. mDNS shim: init dnssd with the USER's device name, wire it to raop
     *    (which propagates the Ed25519 public key via dnssd_set_pk internally
     *    inside raop_set_dnssd), apply feature bits, then build TXT records.
     *    The shim does NO networking; NsdManager (Kotlin) advertises. Order
     *    matters: raop_set_dnssd and the feature bits must precede register_*. */
    int dnssd_err = 0;
    const char *nm = ctx->name ? ctx->name : DEFAULT_RECEIVER_NAME;
    ctx->dnssd = dnssd_init(nm, (int) strlen(nm), ctx->hw_addr, 6,
                            /*pin_pw*/ ctx->pin_enabled ? 1 : 0, &dnssd_err);
    if (!ctx->dnssd) {
        LOGE("nativeStart: dnssd_init err=%d", dnssd_err);
        abort_start(ctx);
        return -1;
    }

    raop_set_dnssd(ctx->raop, ctx->dnssd);   /* copies raop->pk_str into dnssd->pk */
    apply_feature_flags(ctx);

    if (dnssd_register_airplay(ctx->dnssd, bound_port) || dnssd_register_raop(ctx->dnssd, bound_port)) {
        LOGE("nativeStart: TXT construction failed");
        abort_start(ctx);
        return -1;
    }

    ctx->started = true;
    LOGI("nativeStart: listening on TCP %u (name=\"%s\" pin=%s hls=%d h265=%d)", bound_port, nm,
         ctx->pin_enabled ? (ctx->pin_fixed >= 0 ? "fixed" : "random") : "off",
         (int) ctx->hls_enabled, (int) ctx->h265_enabled);
    return (jint) bound_port;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeStop(JNIEnv *env, jobject, jlong handle)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->started) return;
    android_hls_abort(&ctx->cb_ctx);
    raop_stop_httpd(ctx->raop);
    event_channel_stop(&ctx->events);
    if (ctx->dnssd) { dnssd_unregister_raop(ctx->dnssd); dnssd_unregister_airplay(ctx->dnssd); }
    ctx->started = false;
    LOGI("nativeStop: stopped");
}

/* =========================================================================
 *  Display size + codec caps
 *  ====================================================================== */

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeSetDisplaySize(JNIEnv *, jobject, jlong handle,
                                                                  jint w, jint h, jint fps)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->raop) return;
    raop_set_plist(ctx->raop, "width", w);
    raop_set_plist(ctx->raop, "height", h);
    /* refreshRate = display capability (panel is 120Hz-class; never advertise
     * below 60), maxFPS = what we ask senders to cap at (30/60/120). */
    int refresh = (fps > 60) ? fps : 60;
    raop_set_plist(ctx->raop, "refreshRate", refresh);
    raop_set_plist(ctx->raop, "maxFPS", fps);
}

/* Re-run TXT construction after a capability change made while already started. */
static void refresh_txt_if_started(struct server_ctx *ctx)
{
    if (!ctx->started || !ctx->dnssd) return;
    unsigned short port = raop_get_port(ctx->raop);
    dnssd_register_airplay(ctx->dnssd, port);
    dnssd_register_raop(ctx->dnssd, port);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeSetH265Enabled(JNIEnv *, jobject, jlong handle, jboolean enabled)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx) return;
    /* Cached: the service calls this BEFORE nativeStart, when dnssd does not exist
     * yet — the flag is applied at start (previously this silently did nothing). */
    ctx->h265_enabled = (enabled == JNI_TRUE);
    ctx->cb_ctx.h265_enabled = ctx->h265_enabled;
    apply_feature_flags(ctx);
    refresh_txt_if_started(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeSetHlsEnabled(JNIEnv *, jobject, jlong handle, jboolean enabled)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx) return;
    ctx->hls_enabled = (enabled == JNI_TRUE);
    if (ctx->raop) raop_set_plist(ctx->raop, "hls", ctx->hls_enabled ? 1 : 0);
    apply_feature_flags(ctx);
    refresh_txt_if_started(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeSetPin(JNIEnv *env, jobject, jlong handle, jstring pin)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx) return;
    if (ctx->started) LOGW("nativeSetPin: server already started; takes effect on next start");
    const char *p = pin ? env->GetStringUTFChars(pin, nullptr) : nullptr;
    if (!p || !p[0]) {
        ctx->pin_enabled = false;
        ctx->pin_fixed = -1;
    } else if (strlen(p) == 4 && p[0] >= '0' && p[0] <= '9' && p[1] >= '0' && p[1] <= '9' &&
               p[2] >= '0' && p[2] <= '9' && p[3] >= '0' && p[3] <= '9') {
        ctx->pin_enabled = true;
        ctx->pin_fixed = atoi(p);
    } else {
        LOGE("nativeSetPin: PIN must be exactly 4 digits; PIN disabled");
        ctx->pin_enabled = false;
        ctx->pin_fixed = -1;
    }
    if (p) env->ReleaseStringUTFChars(pin, p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeUpdateHlsPlaybackInfo(JNIEnv *, jobject, jlong handle,
        jdouble duration, jdouble position, jfloat rate, jboolean ready)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx) return;
    android_hls_update_info(&ctx->cb_ctx, duration, position, rate, ready == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeSetCodecs(JNIEnv *, jobject, jlong handle,
                                                             jboolean alac, jboolean aac)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->dnssd) return;
    /* UxPlay numbering: 0=PCM,1=ALAC,2=AAC-LC,3=AAC-ELD. The shim rebuilds the
     * 'cn' TXT from RAOP_CN only; full per-codec selection is an M3 refinement.
     * For now this toggles the feature advertisement. */
    (void) alac; (void) aac;
    /* Re-register to refresh TXT if needed (shim's register rebuilds TXT). */
    unsigned short port = raop_get_port(ctx->raop);
    dnssd_unregister_raop(ctx->dnssd);
    dnssd_register_raop(ctx->dnssd, port);
}

/* =========================================================================
 *  TXT record getters + service name (for NsdManager)
 *  ====================================================================== */

extern "C" JNIEXPORT jobject JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeGetRaopTxtRecords(JNIEnv *env, jobject, jlong handle)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->dnssd) return env->NewObject(env->FindClass("java/util/HashMap"),
            env->GetMethodID(env->FindClass("java/util/HashMap"), "<init>", "()V"));
    int len = 0;
    const char *txt = dnssd_get_raop_txt(ctx->dnssd, &len);
    return parse_txt_to_map(env, txt, len);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeGetAirplayTxtRecords(JNIEnv *env, jobject, jlong handle)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->dnssd) return env->NewObject(env->FindClass("java/util/HashMap"),
            env->GetMethodID(env->FindClass("java/util/HashMap"), "<init>", "()V"));
    int len = 0;
    const char *txt = dnssd_get_airplay_txt(ctx->dnssd, &len);
    return parse_txt_to_map(env, txt, len);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeGetRaopServiceName(JNIEnv *env, jobject, jlong handle)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->dnssd) return env->NewStringUTF("");
    return env->NewStringUTF(android_dnssd_get_raop_name(ctx->dnssd));
}

/* =========================================================================
 *  Touch backchannel (receiver → sender HID injection)
 *  ====================================================================== */

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeSendTouchEvent(JNIEnv *, jobject,
                                                                  jlong handle, jint phase,
                                                                  jdouble x_norm, jdouble y_norm)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->events) return JNI_FALSE;
    if (x_norm < 0.0) x_norm = 0.0; if (x_norm > 1.0) x_norm = 1.0;
    if (y_norm < 0.0) y_norm = 0.0; if (y_norm > 1.0) y_norm = 1.0;
    return event_channel_send_touch(ctx->events, (int) phase,
                                    (double) x_norm, (double) y_norm,
                                    raop_get_session_uuid(ctx->raop)) == 0
           ? JNI_TRUE : JNI_FALSE;
}

/* =========================================================================
 *  Software ALAC decoder (M3 fallback)
 *
 *  Wraps Apple's open-source ALACDecoder (Apache-2.0, macosforge/alac) for the
 *  case where the device lacks a hardware ALAC MediaCodec. The AudioRenderer
 *  calls nativeAlacInit when it detects ct==2 (ALAC) and the HW codec fails.
 *
 *  Magic cookie layout (24-byte ALACSpecificConfig, big-endian multi-byte fields):
 *    frameLength(4) compatibleVersion(1) bitDepth(1) pb(1) mb(1) kb(1)
 *    numChannels(1) maxRun(2) maxFrameBytes(4) avgBitRate(4) sampleRate(4)
 *  ====================================================================== */

#define ALAC_MAX_FRAME_LENGTH 16384      /* real streams use 352 or 4096 */
#define ALAC_MAX_PACKET_BYTES (1 << 20)
#define ALAC_INPUT_PADDING    16         /* BitBuffer/ag_dec read a few bytes ahead */

/* PCM bytes per sample per channel as written by the decoder (20-bit uses 3). */
static inline uint32_t alac_bytes_per_sample(uint32_t bit_depth)
{
    return bit_depth <= 16 ? 2 : (bit_depth <= 24 ? 3 : 4);
}

/* Per-decoder state hung off the jlong handle. */
struct alac_ctx {
    ALACDecoder *decoder;
    uint32_t frame_length;
    uint8_t  num_channels;
    uint8_t  bit_depth;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeAlacInit(JNIEnv *, jobject,
                                                            jint frameLength, jint numChannels,
                                                            jint bitDepth, jint pb, jint mb, jint kb)
{
    /* Validate untrusted-ish params: they size the PCM buffer in nativeAlacDecode. */
    if (frameLength < 1 || frameLength > ALAC_MAX_FRAME_LENGTH ||
        numChannels < 1 || numChannels > 8 ||
        !(bitDepth == 16 || bitDepth == 20 || bitDepth == 24 || bitDepth == 32)) {
        LOGE("nativeAlacInit: rejected params frameLen=%d ch=%d bits=%d", frameLength, numChannels, bitDepth);
        return 0;
    }
    /* Build the 24-byte ALACSpecificConfig in big-endian. */
    unsigned char cookie[24];
    memset(cookie, 0, sizeof(cookie));
    /* frameLength (big-endian u32) */
    cookie[0] = (frameLength >> 24) & 0xFF;
    cookie[1] = (frameLength >> 16) & 0xFF;
    cookie[2] = (frameLength >>  8) & 0xFF;
    cookie[3] =  frameLength        & 0xFF;
    cookie[4] = 0;                  /* compatibleVersion */
    cookie[5] = (unsigned char) bitDepth;
    cookie[6] = (unsigned char) pb;
    cookie[7] = (unsigned char) mb;
    cookie[8] = (unsigned char) kb;
    cookie[9] = (unsigned char) numChannels;
    /* maxRun (u16 BE) at offset 10 — leave 0 */
    /* maxFrameBytes (u32 BE) at offset 12 — leave 0 */
    /* avgBitRate (u32 BE) at offset 16 — leave 0 */
    /* sampleRate (u32 BE) at offset 20 = 44100 = 0x0000AC44 */
    cookie[20] = 0x00; cookie[21] = 0x00; cookie[22] = 0xAC; cookie[23] = 0x44;

    alac_ctx *ctx = new alac_ctx();
    ctx->decoder = new ALACDecoder();
    ctx->frame_length = frameLength;
    ctx->num_channels = numChannels;
    ctx->bit_depth = bitDepth;

    int32_t rc = ctx->decoder->Init(cookie, sizeof(cookie));
    if (rc != 0 /* ALAC_noErr */) {
        LOGE("nativeAlacInit: ALACDecoder::Init failed rc=%d", rc);
        delete ctx->decoder;
        delete ctx;
        return 0;
    }
    LOGI("nativeAlacInit: ok (frameLen=%d ch=%d bits=%d)", frameLength, numChannels, bitDepth);
    return (jlong) ctx;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeAlacDecode(JNIEnv *env, jobject,
                                                              jlong handle, jbyteArray input)
{
    alac_ctx *ctx = (alac_ctx *) handle;
    if (!ctx || !ctx->decoder || !input) return env->NewByteArray(0);

    jsize in_len = env->GetArrayLength(input);
    if (in_len <= 0 || in_len > ALAC_MAX_PACKET_BYTES) {
        if (in_len > ALAC_MAX_PACKET_BYTES) LOGW("nativeAlacDecode: oversized packet %d dropped", in_len);
        return env->NewByteArray(0);
    }

    /* Copy into a zero-padded buffer: the bit reader peeks a few bytes past the
     * last data byte, and the packet comes from the network. */
    uint8_t *in_buf = (uint8_t *) calloc((size_t) in_len + ALAC_INPUT_PADDING, 1);
    uint32_t bps = alac_bytes_per_sample(ctx->bit_depth);
    uint32_t out_cap = ctx->frame_length * ctx->num_channels * bps;
    uint8_t *pcm = (uint8_t *) malloc(out_cap);
    if (!in_buf || !pcm) {
        free(in_buf); free(pcm);
        return env->NewByteArray(0);
    }
    env->GetByteArrayRegion(input, 0, in_len, (jbyte *) in_buf);

    BitBuffer bits;
    BitBufferInit(&bits, in_buf, (uint32_t) in_len);
    uint32_t out_samples = 0;
    int32_t rc = ctx->decoder->Decode(&bits, pcm, ctx->frame_length, ctx->num_channels, &out_samples);
    free(in_buf);

    if (rc != 0 || out_samples > ctx->frame_length) {
        LOGW("nativeAlacDecode: rejected frame rc=%d samples=%u (max %u)", rc, out_samples, ctx->frame_length);
        free(pcm);
        return env->NewByteArray(0);
    }

    uint32_t pcm_len = out_samples * ctx->num_channels * bps;   /* <= out_cap */
    jbyteArray result = env->NewByteArray((jsize) pcm_len);
    if (result && pcm_len > 0) env->SetByteArrayRegion(result, 0, (jsize) pcm_len, (jbyte *) pcm);
    free(pcm);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeAlacDestroy(JNIEnv *, jobject, jlong handle)
{
    alac_ctx *ctx = (alac_ctx *) handle;
    if (!ctx) return;
    delete ctx->decoder;
    delete ctx;
}
