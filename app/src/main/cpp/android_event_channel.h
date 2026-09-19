/*
 * android_event_channel.h — receiver→sender touch-event backchannel.
 *
 * AirPlay receivers can inject HID/touch input into the sender: the receiver
 * advertises an `eventPort` in the RTSP SETUP response; the sender opens a TCP
 * connection to it; the receiver then pushes reverse-HTTP `POST /event`
 * requests (binary plist touch data) over that connection. This module owns
 * the listener socket and the send path; UxPlay core advertises the port via
 * raop_set_event_port() (Android-port patch).
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef AIRPLAY_ANDROID_EVENT_CHANNEL_H
#define AIRPLAY_ANDROID_EVENT_CHANNEL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct event_channel_s event_channel_t;

/* Bind + listen (pass *port == 0 for OS-assigned; returns the actual port).
 * Spawns the accept/drain thread. Returns NULL on failure. */
event_channel_t *event_channel_start(unsigned short *port);

/* Send one touch event. phase: 0 = touchStart, 1 = touchMove, 2 = touchEnd.
 * x/y are normalized [0,1] over the sender's screen, origin top-left.
 * Returns 0 on success, -1 if no sender connection or send failed. */
int event_channel_send_touch(event_channel_t *ch, int phase, double x, double y,
                             const char *session_uuid);

/* Stop the thread and release everything. Handles *ch == NULL. */
void event_channel_stop(event_channel_t **ch);

#ifdef __cplusplus
}
#endif
#endif
