/*
 * Optional low-latency PCM output for ScreenDock: TimelineBuffer (jitter cushion + sender
 * timeline sync) feeding an Oboe (AAudio / OpenSL ES) stream.
 *
 * Decoding is NOT done here: Kotlin (AudioRenderer) keeps decoding with MediaCodec / the
 * bundled ALAC decoder and pushes PCM16 stereo @ 44.1 kHz + the packet's NTP pts in via JNI.
 * This replaces only the AudioTrack + sleep-based pacing stage.
 *
 * Adapted from jqssun/android-airplay-server (GPL-3.0) audio_engine.cpp / audio_output.h /
 * timeline_buffer.h.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Threading: nativeAudioWrite/Start/Close are called by Kotlin serialized under
 * AudioRenderer.codecLock (the UxPlay audio thread, already JVM-attached). Oboe's data
 * and error threads are native-only and never call into JNI.
 */

#include <jni.h>

#include <cstdint>
#include <memory>
#include <new>
#include <vector>

#include "audio_log.h"
#include "audio_output.h"
#include "timeline_buffer.h"

namespace {

constexpr int kChannels = 2;
constexpr int kPercentilePct = 95;  // adaptive cushion targets the p95 of arrival jitter

struct OboeEngine {
    std::shared_ptr<TimelineBuffer> timeline;
    std::shared_ptr<AudioOutput> output;
    std::vector<int16_t> scratch;  // write() is serialized by the caller
};

}  // namespace

extern "C" {

/* Returns an opaque engine handle (0 = Oboe stream failed to open: caller falls back). */
JNIEXPORT jlong JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeAudioOpen(
        JNIEnv *, jobject, jint sampleRate, jint cushionMs, jint bufferFrames, jboolean lowLatency,
        jint deviceSampleRate, jint deviceFramesPerBurst)
{
    if (sampleRate <= 0) return 0;
    // OpenSL ES backend (API < 27) cannot discover these itself.
    if (deviceSampleRate > 0) oboe::DefaultStreamValues::SampleRate = deviceSampleRate;
    if (deviceFramesPerBurst > 0) oboe::DefaultStreamValues::FramesPerBurst = deviceFramesPerBurst;

    auto *e = new (std::nothrow) OboeEngine();
    if (!e) return 0;
    try {
        e->timeline = std::make_shared<TimelineBuffer>(sampleRate, kChannels,
                                                       cushionMs < 0 ? 0 : cushionMs, kPercentilePct);
        e->output = AudioOutput::create(sampleRate, kChannels, bufferFrames < 0 ? 0 : bufferFrames,
                                        lowLatency == JNI_TRUE, e->timeline);
        if (!e->output->start()) {
            OBOE_LOGE("Oboe output failed to start; falling back");
            delete e;
            return 0;
        }
    } catch (...) {
        OBOE_LOGE("Oboe engine init threw; falling back");
        delete e;
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

/* Push one PCM16 stereo chunk with its sender-timeline pts (ns; 0 = unsynced). */
JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeAudioWrite(
        JNIEnv *env, jobject, jlong handle, jbyteArray pcm, jint length, jlong ptsNs)
{
    auto *e = reinterpret_cast<OboeEngine *>(handle);
    if (!e || !pcm || length <= 0) return;
    const jsize cap = env->GetArrayLength(pcm);
    const jsize n = (length < cap ? length : cap) & ~3;  // whole stereo frames only
    if (n <= 0) return;
    const size_t samples = (size_t) n / sizeof(int16_t);
    if (e->scratch.size() < samples) e->scratch.resize(samples);
    env->GetByteArrayRegion(pcm, 0, n, reinterpret_cast<jbyte *>(e->scratch.data()));
    e->timeline->write(e->scratch.data(), samples, ptsNs);
}

/* Sender volume as linear gain (1 = unity). */
JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeAudioSetGain(JNIEnv *, jobject, jlong handle, jfloat gain)
{
    auto *e = reinterpret_cast<OboeEngine *>(handle);
    if (e) e->output->setClientGain(gain < 0.f ? 0.f : (gain > 1.f ? 1.f : gain));
}

/* Stops the stream and frees the engine. Must not race with nativeAudioWrite (caller serializes). */
JNIEXPORT void JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeAudioClose(JNIEnv *, jobject, jlong handle)
{
    auto *e = reinterpret_cast<OboeEngine *>(handle);
    if (!e) return;
    e->output->stop();  // closes the stream; late callbacks only touch shared_ptr-owned state
    delete e;
}

/* [backlogMs, cushionMs, trims, drops, silences, underruns, xruns] */
JNIEXPORT jintArray JNICALL
Java_com_cressu_screendock_bridge_NativeBridge_nativeAudioStats(JNIEnv *env, jobject, jlong handle)
{
    auto *e = reinterpret_cast<OboeEngine *>(handle);
    if (!e) return nullptr;
    const TimelineBuffer::Debug t = e->timeline->debugInfo();
    const AudioOutput::Debug o = e->output->debugInfo();
    const jint vals[7] = {t.backlogMs, t.tunedCushionMs, (jint) t.metrics.trims, (jint) t.metrics.drops,
                          (jint) t.metrics.silences, (jint) t.metrics.underruns, o.xrun};
    jintArray out = env->NewIntArray(7);
    if (out) env->SetIntArrayRegion(out, 0, 7, vals);
    return out;
}

}  // extern "C"
