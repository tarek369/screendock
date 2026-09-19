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
    bool                    started;
};

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
    const char *v = "ScreenDock 0.1.0 (UxPlay core " GLOBAL_VERSION ")";
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
    (void) name; (void) require_pin;   /* name is applied at nativeStart (settings); pin is v2 */

    struct server_ctx *ctx = (struct server_ctx *) calloc(1, sizeof(*ctx));
    if (!ctx) { LOGE("nativeInit: OOM"); return 0; }

    jsize n = env->GetArrayLength(hw_addr);
    if (n != 6) { LOGE("nativeInit: hw_addr must be 6 bytes (got %d)", n); free(ctx); return 0; }
    env->GetByteArrayRegion(hw_addr, 0, 6, (jbyte *) ctx->hw_addr);
    hwaddr_to_deviceid(ctx->hw_addr, ctx->device_id);

    const char *kf = key_file ? env->GetStringUTFChars(key_file, nullptr) : nullptr;
    if (kf) { ctx->key_file = strdup(kf); env->ReleaseStringUTFChars(key_file, kf); }

    if (android_callbacks_init(env, callback_handler, &ctx->cb_ctx) != 0) {
        free(ctx->key_file); free(ctx); return 0;
    }
    android_callbacks_fill(&ctx->callbacks);
    ctx->callbacks.cls = &ctx->cb_ctx;

    ctx->raop = raop_init(&ctx->callbacks);
    if (!ctx->raop) { LOGE("nativeInit: raop_init failed"); free(ctx->key_file); free(ctx); return 0; }
    raop_set_log_callback(ctx->raop, android_log_forward, NULL);
    raop_set_log_level(ctx->raop, 6 /* LOGGER_INFO; 7=DEBUG dumps every RTSP plist but spams resends */);

    /* Display-cap defaults; nativeSetDisplaySize overrides these BEFORE
     * nativeStart (the service always applies settings first). Do NOT set
     * them in nativeStart — that would clobber the caller's values. */
    raop_set_plist(ctx->raop, "width", 1920);
    raop_set_plist(ctx->raop, "height", 1080);
    raop_set_plist(ctx->raop, "refreshRate", 60);
    raop_set_plist(ctx->raop, "maxFPS", 60);

    LOGI("nativeInit: ok device_id=%s", ctx->device_id);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeDestroy(JNIEnv *env, jobject, jlong handle)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx) return;
    if (ctx->started) raop_stop_httpd(ctx->raop);
    event_channel_stop(&ctx->events);
    if (ctx->raop)   raop_destroy(ctx->raop);    /* also stops httpd (safe if NULL) */
    if (ctx->dnssd)  dnssd_destroy(ctx->dnssd);
    if (ctx->cb_ctx.callback_ref) env->DeleteGlobalRef(ctx->cb_ctx.callback_ref);
    free(ctx->key_file);
    free(ctx);
}

/* =========================================================================
 *  Start / stop (the real server)
 *  ====================================================================== */

extern "C" JNIEXPORT jint JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeStart(JNIEnv *env, jobject, jlong handle, jint requested_port)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->raop) { LOGE("nativeStart: no ctx"); return -1; }
    if (ctx->started) { LOGW("nativeStart: already running"); return -1; }

    /* 1. Ed25519 keygen + pairing + httpd init. nohold=true so a 3rd conn drops
     *    an existing one instead of being rejected (matches -DNOHOLD). */
    if (raop_init2(ctx->raop, /*nohold*/ 1, ctx->device_id,
                   ctx->key_file ? ctx->key_file : "")) {
        LOGE("nativeStart: raop_init2 failed (keygen/pairing/httpd)");
        return -1;
    }

    /* Display caps were already set via nativeSetDisplaySize (or the
     * nativeInit defaults); re-setting them here would clobber user settings. */

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

    /* 4. mDNS shim: init dnssd, wire it to raop (which propagates the Ed25519
     *    public key via dnssd_set_pk internally inside raop_set_dnssd), then
     *    build TXT records. The shim does NO networking; NsdManager (Kotlin)
     *    advertises. Order matters: raop_set_dnssd must run BEFORE register_*
     *    so the 'pk' TXT entry is populated when TXT is built. */
    int dnssd_err = 0;
    ctx->dnssd = dnssd_init("ScreenDock", (int) strlen("ScreenDock"),
                             ctx->hw_addr, 6, /*pin_pw*/ 0, &dnssd_err);
    if (!ctx->dnssd) { LOGE("nativeStart: dnssd_init err=%d", dnssd_err); return -1; }

    raop_set_dnssd(ctx->raop, ctx->dnssd);   /* copies raop->pk_str into dnssd->pk */

    dnssd_register_airplay(ctx->dnssd, bound_port);
    dnssd_register_raop(ctx->dnssd, bound_port);

    ctx->started = true;
    LOGI("nativeStart: listening on TCP %u", bound_port);
    return (jint) bound_port;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeStop(JNIEnv *env, jobject, jlong handle)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->started) return;
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

extern "C" JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeSetH265Enabled(JNIEnv *, jobject, jlong handle, jboolean enabled)
{
    struct server_ctx *ctx = (struct server_ctx *) handle;
    if (!ctx || !ctx->dnssd) return;
    /* Bit 42 = SupportsScreenMultiCodec (advertises H.265 capability). */
    dnssd_set_airplay_features(ctx->dnssd, 42, enabled ? 1 : 0);
    ctx->cb_ctx.h265_enabled = enabled;
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
    if (!ctx || !ctx->decoder) return env->NewByteArray(0);

    jsize in_len = env->GetArrayLength(input);
    if (in_len <= 0) return env->NewByteArray(0);

    /* Get the compressed ALAC frame bytes. */
    jbyte *in_buf = env->GetByteArrayElements(input, nullptr);
    if (!in_buf) return env->NewByteArray(0);

    /* Allocate the PCM output buffer. */
    uint32_t bytes_per_sample = ctx->bit_depth / 8;
    uint32_t out_cap = ctx->frame_length * ctx->num_channels * bytes_per_sample;
    uint8_t *pcm = (uint8_t *) malloc(out_cap);
    if (!pcm) {
        env->ReleaseByteArrayElements(input, in_buf, JNI_ABORT);
        return env->NewByteArray(0);
    }

    /* Init the bit reader and decode. */
    BitBuffer bits;
    BitBufferInit(&bits, (uint8_t *) in_buf, (uint32_t) in_len);
    uint32_t out_samples = 0;
    ctx->decoder->Decode(&bits, pcm, ctx->frame_length, ctx->num_channels, &out_samples);

    env->ReleaseByteArrayElements(input, in_buf, JNI_ABORT);

    uint32_t pcm_len = out_samples * ctx->num_channels * bytes_per_sample;
    if (pcm_len > out_cap) pcm_len = out_cap;

    jbyteArray result = env->NewByteArray(pcm_len);
    if (pcm_len > 0) env->SetByteArrayRegion(result, 0, pcm_len, (jbyte *) pcm);
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
