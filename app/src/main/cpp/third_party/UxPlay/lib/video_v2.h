/*
 * AirPlay Video V2 command handling on the RTSP (RAOP) connection.
 *
 * A V2 sender (macOS QuickTime / modern iOS "send video to TV") first pairs on the RTSP
 * connection and then issues its video commands on that SAME connection, framed as
 * "RTSP/1.0" with a CSeq:
 *     POST /play  GET /playback-info  POST /rate?value=  POST|GET /scrub  POST /stop
 *     PUT /setProperty?x  POST /getProperty?x  POST /action (playlistInsert/playlistRemove)
 * This module answers them without any dependency on raop.c internals (so it can be
 * unit-tested on the host, see app/src/test/native/test_video_v2.c).  The AirPlay-1
 * (YouTube / HLS-proxy) path in http_handlers.h is NOT touched.
 *
 * Start position convention passed to raop_callbacks_t::on_video_play (frozen JNI contract
 * (Ljava/lang/String;F)V): value >= 0 is an absolute position in SECONDS; a value < 0 is a
 * FRACTION of the (not yet known) duration encoded as -fraction, e.g. -0.25 = 25 %.
 *
 * Licensed under the GNU Lesser General Public License v2.1 or later.
 */
#ifndef VIDEO_V2_H
#define VIDEO_V2_H

#include <stdbool.h>
#include "raop.h"
#include "http_request.h"
#include "http_response.h"
#include "logger.h"

#define VIDEO_V2_MAX_LOCATION 2048
#define VIDEO_V2_MAX_UUID 128

typedef struct video_v2_s {
    bool started;                      /* an item was started and has not been stopped/removed */
    char uuid[VIDEO_V2_MAX_UUID];      /* uuid of the current item ("" if the sender gave none) */
} video_v2_t;

/* true if method+url is one of the V2 video commands listed above. */
bool video_v2_route(const char *method, const char *url);

/* Handle one routed request. `enabled` mirrors raop->hls_support (video playback on/off):
 * when false every command is answered 501.  On return the response status/headers are set
 * (response was already initialised with the request's protocol by the caller); *response_data
 * is a malloc'd buffer (or NULL) that the caller frees. */
void video_v2_dispatch(video_v2_t *state, const raop_callbacks_t *callbacks, logger_t *logger,
                       bool enabled, http_request_t *request, http_response_t *response,
                       char **response_data, int *response_datalen);

#endif
