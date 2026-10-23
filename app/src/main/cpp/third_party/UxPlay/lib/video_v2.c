/* See video_v2.h. Every input is sender-controlled: nothing here may assert()/exit(). */
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <plist/plist.h>
#include "video_v2.h"
#include "hls_playlist.h"

#define MIME_BPLIST "application/x-apple-binary-plist"

static bool starts_with(const char *s, const char *prefix) {
    return s && !strncmp(s, prefix, strlen(prefix));
}

bool video_v2_route(const char *method, const char *url) {
    if (!method || !url) return false;
    if (!strcmp(method, "POST")) {
        return !strcmp(url, "/play") || !strcmp(url, "/stop") || !strcmp(url, "/action") ||
               starts_with(url, "/rate?") || starts_with(url, "/scrub?") ||
               starts_with(url, "/getProperty?") || starts_with(url, "/setProperty?");
    }
    if (!strcmp(method, "GET")) {
        return !strcmp(url, "/playback-info") || !strcmp(url, "/scrub") || starts_with(url, "/scrub?");
    }
    /* NB: the vendored llhttp refuses PUT on an RTSP/1.0 request line (HPE_INVALID_CONSTANT), so a
     * PUT never gets here today; POST is accepted for the same property commands. */
    if (!strcmp(method, "PUT")) {
        return starts_with(url, "/setProperty?");
    }
    return false;
}

/* ---- helpers ------------------------------------------------------------------------ */

static void fail(http_request_t *request, http_response_t *response, int code, const char *msg) {
    const char *protocol = http_request_get_protocol(request);
    http_response_init(response, protocol ? protocol : "RTSP/1.0", code, msg);
}

/* "...?name=value" -> value */
static bool query_number(const char *url, double *value) {
    const char *query = url ? strchr(url, '?') : NULL;
    const char *eq = query ? strchr(query, '=') : NULL;
    if (!eq) return false;
    char *end = NULL;
    double v = strtod(eq + 1, &end);
    if (end == eq + 1 || !isfinite(v)) return false;
    *value = v;
    return true;
}

static bool node_number(plist_t node, double *out) {
    if (PLIST_IS_REAL(node)) {
        plist_get_real_val(node, out);
    } else if (PLIST_IS_UINT(node)) {
        uint64_t u = 0;
        plist_get_uint_val(node, &u);
        *out = (double) u;
    } else {
        return false;
    }
    return isfinite(*out);
}

static bool node_string(plist_t node, char *buf, size_t cap) {
    if (!PLIST_IS_STRING(node)) return false;
    char *s = NULL;
    plist_get_string_val(node, &s);
    if (!s) return false;
    bool ok = strlen(s) < cap;
    if (ok) memcpy(buf, s, strlen(s) + 1);
    plist_mem_free(s);
    return ok;
}

/* Encode seconds/fraction into the on_video_play float (see header). */
static float encode_start(double seconds, double fraction) {
    if (seconds > 0.0) return (float) seconds;
    if (fraction > 1.0) return (float) fraction;      /* some senders put seconds in Start-Position */
    if (fraction > 0.0) return (float) -fraction;
    return 0.0f;
}

typedef struct {
    char location[VIDEO_V2_MAX_LOCATION];
    char uuid[VIDEO_V2_MAX_UUID];
    float start;
} play_item_t;

static bool location_ok(const char *loc) {
    return starts_with(loc, "http://") || starts_with(loc, "https://");
}

static void copy_session_uuid(play_item_t *out, const char *session_id) {
    if (!out->uuid[0] && session_id && strlen(session_id) < sizeof(out->uuid)) {
        memcpy(out->uuid, session_id, strlen(session_id) + 1);
    }
}

/* item dict: Content-Location, uuid, Start-Position (fraction) / Start-Position-Seconds */
static int parse_item(plist_t dict, const char *session_id, play_item_t *out) {
    memset(out, 0, sizeof(*out));
    if (!PLIST_IS_DICT(dict)) return -1;
    if (!node_string(plist_dict_get_item(dict, "Content-Location"), out->location, sizeof(out->location)) ||
        !location_ok(out->location)) {
        return -1;
    }
    node_string(plist_dict_get_item(dict, "uuid"), out->uuid, sizeof(out->uuid));
    copy_session_uuid(out, session_id);
    double secs = 0.0, frac = 0.0;
    node_number(plist_dict_get_item(dict, "Start-Position-Seconds"), &secs);
    node_number(plist_dict_get_item(dict, "Start-Position"), &frac);
    out->start = encode_start(secs, frac);
    return 0;
}

/* /play body: binary or XML plist, or legacy "text/parameters" */
static int parse_play_body(const char *data, int len, const char *session_id, play_item_t *out) {
    if (!data || len <= 0) return -1;
    plist_t root = NULL;
    if (plist_from_memory(data, (uint32_t) len, &root, NULL) == PLIST_ERR_SUCCESS && PLIST_IS_DICT(root)) {
        int rc = parse_item(root, session_id, out);
        plist_free(root);
        return rc;
    }
    plist_free(root);   /* e.g. libplist's lenient parsers may accept text/parameters as a non-dict */
    hls_text_params_t p;
    if (hls_parse_text_parameters(data, (size_t) len, &p) < 0 || !p.location) return -1;
    memset(out, 0, sizeof(*out));
    int rc = -1;
    if (strlen(p.location) < sizeof(out->location) && location_ok(p.location)) {
        memcpy(out->location, p.location, strlen(p.location) + 1);
        copy_session_uuid(out, session_id);
        out->start = encode_start(p.start_seconds, p.start_fraction);
        rc = 0;
    }
    free(p.location);
    return rc;
}

static void start_item(video_v2_t *st, const raop_callbacks_t *cb, logger_t *logger, const play_item_t *item) {
    logger_log(logger, LOGGER_INFO, "AirPlay video V2: play %s (start %.3f%s)", item->location,
               fabs((double) item->start), item->start < 0 ? " fraction" : " s");
    st->started = true;
    snprintf(st->uuid, sizeof(st->uuid), "%s", item->uuid);
    if (cb->on_video_play) cb->on_video_play(cb->cls, item->location, item->start);
}

static void reply_plist(http_response_t *response, plist_t root, char **data, int *len) {
    char *bin = NULL;
    uint32_t n = 0;
    plist_to_bin(root, &bin, &n);
    if (bin && n > 0) {
        *data = bin;
        *len = (int) n;
        http_response_add_header(response, "Content-Type", MIME_BPLIST);
    } else if (bin) {
        plist_mem_free(bin);
    }
    plist_free(root);
}

static plist_t time_range(double start, double duration) {
    plist_t d = plist_new_dict();
    plist_dict_set_item(d, "start", plist_new_real(start));
    plist_dict_set_item(d, "duration", plist_new_real(duration));
    return d;
}

static void acquire_info(const raop_callbacks_t *cb, playback_info_t *info) {
    memset(info, 0, sizeof(*info));
    info->duration = -1.0;
    info->position = -1.0;
    if (cb->on_video_acquire_playback_info) {
        cb->on_video_acquire_playback_info(cb->cls, info);
    }
}

/* ---- handlers ----------------------------------------------------------------------- */

static void handle_playback_info(video_v2_t *st, const raop_callbacks_t *cb, http_response_t *response,
                                 char **data, int *len) {
    playback_info_t info;
    acquire_info(cb, &info);
    plist_t root = plist_new_dict();
    if (info.duration < 0.0 || info.position < 0.0) {
        /* nothing playing: idle, or the item ended / failed on its own */
        plist_dict_set_item(root, "readyToPlay", plist_new_bool(0));
        plist_dict_set_item(root, "duration", plist_new_real(0.0));
        plist_dict_set_item(root, "position", plist_new_real(0.0));
        plist_dict_set_item(root, "rate", plist_new_real(0.0));
        if (st->started) {
            st->started = false;
            plist_t err = plist_new_dict();
            plist_dict_set_item(err, "code", plist_new_int(-1));
            plist_dict_set_item(err, "domain", plist_new_string("ScreenDock"));
            plist_dict_set_item(err, "localizedDescription", plist_new_string("Playback ended or failed"));
            plist_dict_set_item(root, "error", err);
        }
        reply_plist(response, root, data, len);
        return;
    }
    double loaded_dur = info.duration > info.position ? info.duration - info.position : 0.0;
    plist_dict_set_item(root, "duration", plist_new_real(info.duration));
    plist_dict_set_item(root, "position", plist_new_real(info.position));
    plist_dict_set_item(root, "rate", plist_new_real((double) info.rate));
    plist_dict_set_item(root, "readyToPlay", plist_new_bool(info.ready_to_play ? 1 : 0));
    plist_dict_set_item(root, "playbackBufferEmpty", plist_new_bool(info.playback_buffer_empty ? 1 : 0));
    plist_dict_set_item(root, "playbackBufferFull", plist_new_bool(info.playback_buffer_full ? 1 : 0));
    plist_dict_set_item(root, "playbackLikelyToKeepUp", plist_new_bool(info.playback_likely_to_keep_up ? 1 : 0));
    plist_t loaded = plist_new_array();
    plist_array_append_item(loaded, time_range(info.position, loaded_dur));
    plist_dict_set_item(root, "loadedTimeRanges", loaded);
    plist_t seekable = plist_new_array();
    plist_array_append_item(seekable, time_range(info.seek_start, info.seek_duration));
    plist_dict_set_item(root, "seekableTimeRanges", seekable);
    reply_plist(response, root, data, len);
}

static void handle_get_scrub(const raop_callbacks_t *cb, http_response_t *response, char **data, int *len) {
    playback_info_t info;
    acquire_info(cb, &info);
    double dur = info.duration > 0.0 ? info.duration : 0.0;
    double pos = info.position > 0.0 ? info.position : 0.0;
    char buf[96];
    int n = snprintf(buf, sizeof(buf), "duration: %f\nposition: %f\n", dur, pos);
    if (n <= 0 || n >= (int) sizeof(buf)) return;
    char *copy = (char *) malloc((size_t) n);
    if (!copy) return;
    memcpy(copy, buf, (size_t) n);
    *data = copy;
    *len = n;
    http_response_add_header(response, "Content-Type", "text/parameters");
}

static void handle_action(video_v2_t *st, const raop_callbacks_t *cb, logger_t *logger,
                          http_request_t *request, http_response_t *response) {
    int len = 0;
    const char *data = http_request_get_data(request, &len);
    plist_t root = NULL;
    if (!data || len <= 0 || plist_from_memory(data, (uint32_t) len, &root, NULL) != PLIST_ERR_SUCCESS ||
        !PLIST_IS_DICT(root)) {
        plist_free(root);
        fail(request, response, 400, "Bad Request");
        return;
    }
    char type[48] = {0};
    if (!node_string(plist_dict_get_item(root, "type"), type, sizeof(type))) {
        plist_free(root);
        fail(request, response, 400, "Bad Request");
        return;
    }
    /* the item sits under "params" (AirPlay-1 form) or directly at the top level */
    plist_t params = plist_dict_get_item(root, "params");
    plist_t item = PLIST_IS_DICT(params) ? plist_dict_get_item(params, "item") : NULL;
    if (!item) item = plist_dict_get_item(root, "item");

    if (!strcmp(type, "playlistInsert")) {
        play_item_t it;
        if (parse_item(item, http_request_get_header(request, "X-Apple-Session-ID"), &it) < 0) {
            logger_log(logger, LOGGER_ERR, "playlistInsert without a usable item (Content-Location)");
            fail(request, response, 400, "Bad Request");
        } else {
            start_item(st, cb, logger, &it);
        }
    } else if (!strcmp(type, "playlistRemove")) {
        char uuid[VIDEO_V2_MAX_UUID] = {0};
        bool have = PLIST_IS_DICT(item) && node_string(plist_dict_get_item(item, "uuid"), uuid, sizeof(uuid));
        if (st->started && (!have || !strcmp(uuid, st->uuid))) {
            st->started = false;
            if (cb->on_video_playlist_remove) {
                cb->on_video_playlist_remove(cb->cls);
            } else if (cb->on_video_stop) {
                cb->on_video_stop(cb->cls);
            }
        }
    } else {
        logger_log(logger, LOGGER_DEBUG, "AirPlay video V2: ignoring /action type %s", type);
    }
    plist_free(root);
}

void video_v2_dispatch(video_v2_t *st, const raop_callbacks_t *cb, logger_t *logger, bool enabled,
                       http_request_t *request, http_response_t *response,
                       char **response_data, int *response_datalen) {
    const char *method = http_request_get_method(request);
    const char *url = http_request_get_url(request);
    if (!st || !cb || !method || !url) {
        fail(request, response, 500, "Internal Server Error");
        return;
    }
    if (!enabled) {
        logger_log(logger, LOGGER_INFO, "AirPlay video V2 request %s %s refused: video playback disabled", method, url);
        fail(request, response, 501, "Not Implemented");
        return;
    }

    if (!strcmp(url, "/play")) {
        int len = 0;
        const char *data = http_request_get_data(request, &len);
        play_item_t it;
        if (parse_play_body(data, len, http_request_get_header(request, "X-Apple-Session-ID"), &it) < 0) {
            logger_log(logger, LOGGER_ERR, "AirPlay video V2: /play without a usable Content-Location");
            fail(request, response, 400, "Bad Request");
        } else {
            start_item(st, cb, logger, &it);
        }
    } else if (!strcmp(url, "/playback-info")) {
        handle_playback_info(st, cb, response, response_data, response_datalen);
    } else if (starts_with(url, "/rate?")) {
        double v = 0.0;
        if (!query_number(url, &v)) {
            fail(request, response, 400, "Bad Request");
        } else if (cb->on_video_rate) {
            cb->on_video_rate(cb->cls, (float) v);
        }
    } else if (!strcmp(method, "GET") && starts_with(url, "/scrub")) {
        handle_get_scrub(cb, response, response_data, response_datalen);
    } else if (starts_with(url, "/scrub?")) {
        double v = 0.0;
        if (!query_number(url, &v) || v < 0.0) {
            fail(request, response, 400, "Bad Request");
        } else if (cb->on_video_scrub) {
            cb->on_video_scrub(cb->cls, (float) v);
        }
    } else if (!strcmp(url, "/stop")) {
        st->started = false;
        if (cb->on_video_stop) cb->on_video_stop(cb->cls);
    } else if (starts_with(url, "/setProperty?") || starts_with(url, "/getProperty?")) {
        plist_t root = plist_new_dict();
        plist_dict_set_item(root, "errorCode", plist_new_uint(0));
        const char *name = strchr(url, '?') + 1;
        if (!strcmp(name, "playbackAccessLog") || !strcmp(name, "playbackErrorLog")) {
            plist_dict_set_item(root, "value", plist_new_array());
        }
        reply_plist(response, root, response_data, response_datalen);
    } else if (!strcmp(url, "/action")) {
        handle_action(st, cb, logger, request, response);
    } else {
        fail(request, response, 501, "Not Implemented");
    }
}
