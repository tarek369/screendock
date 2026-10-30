/*
 * Adapted from jqssun/android-airplay-server (GPL-3.0); replaces its LogSink with
 * plain logcat output (tag AirplayOboe).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef AUDIO_LOG_H
#define AUDIO_LOG_H

#include <android/log.h>

#define OBOE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AirplayOboe", __VA_ARGS__)
#define OBOE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AirplayOboe", __VA_ARGS__)

#endif  // AUDIO_LOG_H
