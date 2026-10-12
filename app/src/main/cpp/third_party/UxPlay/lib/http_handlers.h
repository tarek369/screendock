/**
 * Copyright (c) 2024 fduncanh
 * All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 */

/* this file is part of raop.c and should not be included in any other file */

/* Robustness notes (ScreenDock): every value in these handlers originates from the sender
 * (or anyone on the LAN who can reach the port), so nothing here may assert()/exit().
 * Malformed input is answered with an HTTP error, allocation failures abort only the
 * current request. */

#include <math.h>
#include "airplay_video.h"
#include "hls_playlist.h"
#include "fcup_request.h"

#define HLS_MAX_FCUP_RESPONSE_BYTES (32 * 1024 * 1024)
#define HLS_MAX_SESSION_ID_LEN 64

/* true if any header (name or value) of the request contains text */
static bool
request_headers_contain(http_request_t *request, const char *text) {
    char *header_str = NULL;
    http_request_get_header_string(request, &header_str);
    bool found = header_str && strstr(header_str, text);
    free(header_str);
    return found;
}

static void
*hls_get_current_video(raop_t *raop) {
    if (raop->current_video < 0 || raop->current_video >= MAX_AIRPLAY_VIDEO ||
        !raop->airplay_video[raop->current_video]) {
        logger_log(raop->logger, LOGGER_ERR, "hls_get_current_video: failed to identify current_playlist");
        return NULL;
    }
    return (void *) raop->airplay_video[raop->current_video];
}

static int
get_playlist_by_uuid(raop_t *raop, const char *uuid) {
    if (!uuid) {
        return -1;
    }
    for (int i = 0 ;i < MAX_AIRPLAY_VIDEO; i++) {
        const char *other = raop->airplay_video[i] ? get_playback_uuid(raop->airplay_video[i]) : NULL;
        if (other && !strcmp(uuid, other)) {
            return i;
        }
    }
    return -1;
}

/* parse "...?name=value" (as in /scrub?position=12.5 and /rate?value=1.0) */
static bool
parse_query_number(const char *url, double *value) {
    const char *query = url ? strchr(url, '?') : NULL;
    const char *eq = query ? strchr(query, '=') : NULL;
    if (!eq) {
        return false;
    }
    char *end = NULL;
    double v = strtod(eq + 1, &end);
    if (end == eq + 1 || !isfinite(v)) {
        return false;
    }
    *value = v;
    return true;
}

static void
http_handler_server_info(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                         char **response_data, int *response_datalen)  {

    raop_t *raop = conn->raop;
    if (!raop->dnssd) {
        logger_log(raop->logger, LOGGER_ERR, "server-info requested before dnssd initialised");
        http_response_init(response, "HTTP/1.1", 503, "Service Unavailable");
        return;
    }
    int hw_addr_raw_len = 0;
    const char *hw_addr_raw = dnssd_get_hw_addr(raop->dnssd, &hw_addr_raw_len);
    if (!hw_addr_raw || hw_addr_raw_len <= 0) {
        http_response_init(response, "HTTP/1.1", 503, "Service Unavailable");
        return;
    }

    char *hw_addr = calloc(1, 3 * hw_addr_raw_len);
    if (!hw_addr) {
        http_response_init(response, "HTTP/1.1", 500, "Internal Server Error");
        return;
    }
    utils_hwaddr_airplay(hw_addr, 3 * hw_addr_raw_len, hw_addr_raw, hw_addr_raw_len);

    plist_t r_node = plist_new_dict();

    /* first 12 AirPlay features bits (R to L): 0x27F = 0010 0111 1111
     * Only bits 0-6 and bit 9  are set:
     * 0. video supported
     * 1. photo supported
     * 2. video protected wirh FairPlay DRM
     * 3. volume control supported for video
     * 4. HLS supported
     * 5. slideshow supported
     * 6. (unknown)
     * 9. audio supported.
     */
    plist_t features_node = plist_new_uint(0x27F); 
    plist_dict_set_item(r_node, "features", features_node);

    plist_t mac_address_node = plist_new_string(hw_addr);
    plist_dict_set_item(r_node, "macAddress", mac_address_node);

    plist_t model_node = plist_new_string(GLOBAL_MODEL);
    plist_dict_set_item(r_node, "model", model_node);

    plist_t os_build_node = plist_new_string("12B435");
    plist_dict_set_item(r_node, "osBuildVersion", os_build_node);

    plist_t protovers_node = plist_new_string("1.0");
    plist_dict_set_item(r_node, "protovers", protovers_node);

    plist_t source_version_node = plist_new_string(GLOBAL_VERSION);
    plist_dict_set_item(r_node, "srcvers", source_version_node);

    plist_t vv_node = plist_new_uint(strtol(AIRPLAY_VV, NULL, 10));
    plist_dict_set_item(r_node, "vv", vv_node);

    plist_t device_id_node = plist_new_string(hw_addr);
    plist_dict_set_item(r_node, "deviceid", device_id_node);

    plist_to_xml(r_node, response_data, (uint32_t *) response_datalen);

    /* last character (at *response_data[response_datalen - 1]) is  0x0a = '\n'
     * (*response_data[response_datalen] is '\0').
     * apsdk removes the last "\n" by overwriting it with '\0', and reducing response_datalen by 1. 
     * TODO: check if this is necessary  */
    
    plist_free(r_node);
    http_response_add_header(response, "Content-Type", "text/x-apple-plist+xml");
    free(hw_addr);
}    

static void
http_handler_scrub(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                   char **response_data, int *response_datalen) {
    raop_t *raop = conn->raop;
    const char *url = http_request_get_url(request);
    double value = 0.0;
    float scrub_position = 0.0f;
    if (parse_query_number(url, &value) && value >= 0.0) {
        scrub_position = (float) value;
        logger_log(raop->logger, LOGGER_DEBUG, "http_handler_scrub: got position = %.6f", scrub_position);
    }
    if (raop->callbacks.on_video_scrub) {
        raop->callbacks.on_video_scrub(raop->callbacks.cls, scrub_position);
    }
}

static void
http_handler_rate(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                  char **response_data, int *response_datalen) {

    raop_t *raop = conn->raop;
    const char *url = http_request_get_url(request);
    double value = 0.0;
    float rate_value = 0.0f;
    if (parse_query_number(url, &value)) {
        rate_value = (float) value;
        logger_log(raop->logger, LOGGER_DEBUG, "http_handler_rate: got rate = %.6f", rate_value);
    }
    if (raop->callbacks.on_video_rate) {
        raop->callbacks.on_video_rate(raop->callbacks.cls, rate_value);
    }
}

static void
http_handler_stop(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                  char **response_data, int *response_datalen) {

    raop_t *raop = conn->raop;
    logger_log(raop->logger, LOGGER_INFO, "client HTTP request POST stop");

    if (raop->callbacks.on_video_stop) {
        raop->callbacks.on_video_stop(raop->callbacks.cls);
    }
}

/* Extract the first MediaSelectionOptionsName / ...UnicodeLanguageIdentifier strings from the
   "value" array of a selectedMediaArray property plist.  Returned strings (libplist-owned
   allocations) must be freed with plist_mem_free. */
static void
extract_media_selection(plist_t value_array, char **language_name, char **language_code) {
    *language_name = NULL;
    *language_code = NULL;
    uint32_t count = plist_array_get_size(value_array);
    for (uint32_t i = 0; i < count && !(*language_name && *language_code); i++) {
        plist_t item = plist_array_get_item(value_array, i);   /* owned by the array: never free */
        if (!PLIST_IS_DICT(item)) {
            continue;
        }
        if (!*language_name) {
            plist_t node = plist_dict_get_item(item, "MediaSelectionOptionsName");
            if (PLIST_IS_STRING(node)) {
                plist_get_string_val(node, language_name);
            }
        }
        if (!*language_code) {
            plist_t node = plist_dict_get_item(item, "MediaSelectionOptionsUnicodeLanguageIdentifier");
            if (PLIST_IS_STRING(node)) {
                plist_get_string_val(node, language_code);
            }
        }
    }
}

/* handles PUT /setProperty http requests from Client to Server */

static void
http_handler_set_property(raop_conn_t *conn,
                          http_request_t *request, http_response_t *response,
                          char **response_data, int *response_datalen) {

    raop_t *raop = conn->raop;
    const char *url = http_request_get_url(request);
    const char *property = url + strlen("/setProperty?");
    logger_log(raop->logger, LOGGER_DEBUG, "http_handler_set_property: %s", property);


    /*  actionAtItemEnd:  values:  
                  0: advance (advance to next item, if there is one)
                  1: pause   (pause playing)
                  2: none    (do nothing)             

        reverseEndTime   (only used when rate < 0) time at which reverse playback ends
        forwardEndTime   (only used when rate > 0) time at which reverse playback ends
        selectedMediaArray contains plist with language choice:
    */

    airplay_video_t *airplay_video = (airplay_video_t *) hls_get_current_video(raop);
    if (!airplay_video) {
        goto post_error;
    }
    if (!strcmp(property, "selectedMediaArray")) {
        /* verify that this request contains a binary plist*/
        int request_datalen = 0;
        if (!request_headers_contain(request, "apple-binary-plist")) {
            logger_log(raop->logger, LOGGER_DEBUG, "POST /setProperty?selectedMediaArray"
                       "does not provide an apple-binary-plist");
            goto post_error;
        }

        const char *request_data = http_request_get_data(request, &request_datalen);
        plist_t req_root_node = NULL;
        if (request_data && request_datalen > 0) {
            plist_from_bin(request_data, request_datalen, &req_root_node);
        }
        plist_t req_value_node = PLIST_IS_DICT(req_root_node) ? plist_dict_get_item(req_root_node, "value") : NULL;

        if (!req_value_node || !PLIST_IS_ARRAY(req_value_node)) {	  
            logger_log(raop->logger, LOGGER_INFO, "POST /setProperty?selectedMediaArray"
                   " did not provide expected plist from client");
            plist_free(req_root_node);   /* NULL-safe */
            goto post_error;
        }

        char *language_name = NULL;
        char *language_code = NULL;
        extract_media_selection(req_value_node, &language_name, &language_code);
        plist_free(req_root_node);
        if (language_code && language_name &&
            set_language_code(airplay_video, language_code, strlen(language_code)) == 0 &&
            set_language_name(airplay_video, language_name, strlen(language_name)) == 0) {
            logger_log(raop->logger, LOGGER_INFO, "stored language from MediaSelectionOptions: %s \"%s\"",
                       get_language_code(airplay_video), get_language_name(airplay_video));
        }
        plist_mem_free(language_name);
        plist_mem_free(language_code);
    } else if (!strcmp(property, "reverseEndTime") ||
        !strcmp(property, "forwardEndTime") ||
        !strcmp(property, "actionAtItemEnd")) {
        logger_log(raop->logger, LOGGER_DEBUG, "property %s is known but unhandled", property);

        plist_t errResponse = plist_new_dict();
        plist_t errCode = plist_new_uint(0);
        plist_dict_set_item(errResponse, "errorCode", errCode);
        plist_to_xml(errResponse, response_data, (uint32_t *) response_datalen);
        plist_free(errResponse);
        http_response_add_header(response, "Content-Type", "text/x-apple-plist+xml");
    } else {
        logger_log(raop->logger, LOGGER_DEBUG, "property %s is unknown, unhandled", property);      
        goto post_error;
    }
    return;
 post_error:
    http_response_add_header(response, "Content-Length", "0");
}

/* handles GET /getProperty http requests from Client to Server.  (not implemented) */

static void
http_handler_get_property(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                          char **response_data, int *response_datalen) {
    raop_t *raop = conn->raop;
    const char *url = http_request_get_url(request);
    const char *property = url + strlen("getProperty?");
    logger_log(raop->logger, LOGGER_DEBUG, "http_handler_get_property: %s (unhandled)", property);
}

/* this request (for a variant FairPlay decryption)  cannot be handled  by UxPlay */
static void
http_handler_fpsetup2(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                      char **response_data, int *response_datalen) {
    raop_t *raop = conn->raop;
    logger_log(raop->logger, LOGGER_WARNING, "client HTTP request POST fp-setup2 is unhandled");
    http_response_add_header(response, "Content-Type", "application/x-apple-binary-plist");
    int req_datalen = 0;
    const unsigned char *req_data = (unsigned char *) http_request_get_data(request, &req_datalen);
    if (req_data && req_datalen > 4) {
        logger_log(raop->logger, LOGGER_ERR, "only FairPlay version 0x03 is implemented, version is 0x%2.2x",
                   req_data[4]);
    }
    http_response_init(response, "HTTP/1.1", 421, "Misdirected Request");
}

// called by http_handler_playback_info while preparing response to a GET /playback_info request from the client.

typedef struct time_range_s {
    double start;
    double duration;
} time_range_t;

void time_range_to_plist(void *time_ranges, const int n_time_ranges,
		         plist_t time_ranges_node) {
    time_range_t *tr = (time_range_t *) time_ranges;
    for (int i = 0 ; i < n_time_ranges; i++) {
        plist_t time_range_node = plist_new_dict();
        plist_t duration_node = plist_new_real(tr[i].duration);
        plist_dict_set_item(time_range_node, "duration", duration_node);
        plist_t start_node = plist_new_real(tr[i].start);
        plist_dict_set_item(time_range_node, "start", start_node);
        plist_array_append_item(time_ranges_node, time_range_node);
    }
}

// called by http_handler_playback_info while preparing response to a GET /playback_info request from the client.

int create_playback_info_plist_xml(playback_info_t *playback_info, char **plist_xml) {

    plist_t res_root_node = plist_new_dict();

    plist_t duration_node = plist_new_real(playback_info->duration);
    plist_dict_set_item(res_root_node, "duration", duration_node);

    plist_t position_node = plist_new_real(playback_info->position);
    plist_dict_set_item(res_root_node, "position", position_node);

    plist_t rate_node = plist_new_real(playback_info->rate);
    plist_dict_set_item(res_root_node, "rate", rate_node);

    /* should these be int or bool? */
    plist_t ready_to_play_node = plist_new_uint(playback_info->ready_to_play);
    plist_dict_set_item(res_root_node, "readyToPlay", ready_to_play_node);

    plist_t playback_buffer_empty_node = plist_new_uint(playback_info->playback_buffer_empty);
    plist_dict_set_item(res_root_node, "playbackBufferEmpty", playback_buffer_empty_node);

    plist_t playback_buffer_full_node = plist_new_uint(playback_info->playback_buffer_full);
    plist_dict_set_item(res_root_node, "playbackBufferFull", playback_buffer_full_node);

    plist_t playback_likely_to_keep_up_node = plist_new_uint(playback_info->playback_likely_to_keep_up);
    plist_dict_set_item(res_root_node, "playbackLikelyToKeepUp", playback_likely_to_keep_up_node);

    plist_t loaded_time_ranges_node = plist_new_array();
    time_range_to_plist(playback_info->loadedTimeRanges, playback_info->num_loaded_time_ranges,
                        loaded_time_ranges_node);
    plist_dict_set_item(res_root_node, "loadedTimeRanges", loaded_time_ranges_node);

    plist_t seekable_time_ranges_node = plist_new_array();
    time_range_to_plist(playback_info->seekableTimeRanges, playback_info->num_seekable_time_ranges,
                        seekable_time_ranges_node);
    plist_dict_set_item(res_root_node, "seekableTimeRanges", seekable_time_ranges_node);

    int len = 0;
    plist_to_xml(res_root_node, plist_xml, (uint32_t *) &len);
    /* plist_xml is null-terminated, last character is '/n' */

    plist_free(res_root_node);

    return len;
}

/* this handles requests from the Client  for "Playback information" while the Media is playing on the 
   Media Player.  (The Server gets this information by monitoring the Media Player). The Client could use 
   the information to e.g. update  the slider it shows with progress to the player (0%-100%). 
   It does not affect playing of the Media*/

static void
http_handler_playback_info(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
    raop_t *raop = conn->raop;
    playback_info_t playback_info;
    memset(&playback_info, 0, sizeof(playback_info));
    playback_info.duration = -1.0;
    playback_info.position = -1.0;

    if (!raop->callbacks.on_video_acquire_playback_info) {
        return;
    }
    raop->callbacks.on_video_acquire_playback_info(raop->callbacks.cls, &playback_info);
    if (playback_info.duration == -1.0) {
        /* video has finished, reset */
        logger_log(raop->logger, LOGGER_DEBUG, "playback_info not available (finishing)");
        http_response_set_disconnect(response,1);
        raop->callbacks.video_reset(raop->callbacks.cls, RESET_TYPE_HLS_SHUTDOWN);
        return;
    } else if (playback_info.position == -1.0) {
        logger_log(raop->logger, LOGGER_DEBUG, "playback_info not available");
        return;
    }      

    playback_info.num_loaded_time_ranges = 1; 
    time_range_t time_ranges_loaded[1];
    time_ranges_loaded[0].start = playback_info.position;
    time_ranges_loaded[0].duration = playback_info.duration - playback_info.position;
    playback_info.loadedTimeRanges = (void *) &time_ranges_loaded;

    playback_info.num_seekable_time_ranges = 1;
    time_range_t time_ranges_seekable[1];
    time_ranges_seekable[0].start = playback_info.seek_start;
    time_ranges_seekable[0].duration = playback_info.seek_duration;
    playback_info.seekableTimeRanges = (void *) &time_ranges_seekable;

    *response_datalen =  create_playback_info_plist_xml(&playback_info, response_data);
    http_response_add_header(response, "Content-Type", "text/x-apple-plist+xml");
}

/* this handles the POST /reverse request from Client to Server on a AirPlay http channel to "Upgrade" 
   to "PTTH/1.0" Reverse HTTP protocol proposed in 2009 Internet-Draft 

          https://datatracker.ietf.org/doc/id/draft-lentczner-rhttp-00.txt .  

   After the Upgrade the channel becomes a reverse http "AirPlay (reversed)" channel for
   http requests from Server to Client.
  */

static void
http_handler_reverse(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                     char **response_data, int *response_datalen) {

    raop_t *raop = conn->raop;
    /* get http socket for send */
    int socket_fd = httpd_get_connection_socket (raop->httpd, (void *) conn);
    if (socket_fd < 0) {
        logger_log(raop->logger, LOGGER_ERR, "fcup_request failed to retrieve socket_fd from httpd");
        /* shut down connection? */
    }
    
    const char *purpose = http_request_get_header(request, "X-Apple-Purpose");
    const char *connection = http_request_get_header(request, "Connection");
    const char *upgrade = http_request_get_header(request, "Upgrade");
    logger_log(raop->logger, LOGGER_INFO, "client requested reverse connection: %s; purpose: %s  \"%s\"",
               connection ? connection : "(none)", upgrade ? upgrade : "(none)", purpose ? purpose : "(none)");

    httpd_set_connection_type(raop->httpd, (void *) conn, CONNECTION_TYPE_PTTH);
    int type_PTTH = httpd_count_connection_type(raop->httpd, CONNECTION_TYPE_PTTH);

    if (type_PTTH == 1) {
        logger_log(raop->logger, LOGGER_DEBUG, "will use socket %d for %s connections", socket_fd,
                   purpose ? purpose : "(none)");
        http_response_init(response, "HTTP/1.1", 101, "Switching Protocols");
        http_response_add_header(response, "Connection", "Upgrade");
        http_response_add_header(response, "Upgrade", "PTTH/1.0");
    } else {
        logger_log(raop->logger, LOGGER_ERR, "multiple TPPH connections (%d) are forbidden", type_PTTH );
    }    
}

/* Copy the FCUP_Response_Data of an "unhandledURLResponse" into a NUL-terminated string
   (length-limited; the data comes from the sender). Returns NULL on failure. */
static char *
fcup_response_playlist(plist_t data_node) {
    uint64_t data_len = 0;
#ifdef PLIST_210
    const char *data = plist_get_data_ptr(data_node, &data_len);
#else
    char *data = NULL;
    plist_get_data_val(data_node, &data, &data_len);
#endif
    char *playlist = NULL;
    if (data && data_len > 0 && data_len <= HLS_MAX_FCUP_RESPONSE_BYTES) {
        playlist = (char *) malloc((size_t) data_len + 1);
        if (playlist) {
            memcpy(playlist, data, (size_t) data_len);
            playlist[data_len] = '\0';
        }
    }
#ifndef PLIST_210
    plist_mem_free(data);
#endif
    return playlist;
}

/* Handle the sender's reply (POST /action "unhandledURLResponse") to an FCUP request.
   Returns 0 if handled (or ignorable), -1 if the response is malformed. */
static int
handle_fcup_response(raop_conn_t *conn, airplay_video_t *airplay_video, const char *apple_session_id,
                     plist_t params_node) {
    raop_t *raop = conn->raop;
    plist_t url_node = plist_dict_get_item(params_node, "FCUP_Response_URL");
    plist_t data_node = plist_dict_get_item(params_node, "FCUP_Response_Data");
    if (!PLIST_IS_STRING(url_node) || !PLIST_IS_DATA(data_node)) {
        return -1;
    }
    char *fcup_response_url = NULL;
    plist_get_string_val(url_node, &fcup_response_url);
    if (!fcup_response_url) {
        return -1;
    }
    logger_log(raop->logger, LOGGER_DEBUG, "FCUP_Response_URL =  %s", fcup_response_url);

    char *playlist = fcup_response_playlist(data_node);
    if (!playlist) {
        plist_mem_free(fcup_response_url);
        return -1;
    }
    int playlist_len = (int) strlen(playlist);
    if (logger_get_level(raop->logger) >= LOGGER_DEBUG) {
        logger_log(raop->logger, LOGGER_DEBUG, "begin FCUP Response data:\n%s\nend FCUP Response data", playlist);
    }

    int status = 0;
    if (strstr(fcup_response_url, "/master.m3u8")) {
        /* this is a master playlist */
        const char *uri_prefix = get_uri_prefix(airplay_video);
        char ** uri_list = NULL;
        int num_uri = 0;
        char *uri_local_prefix = get_uri_local_prefix(airplay_video);
        char *new_master = NULL;
        if (!uri_prefix || !uri_local_prefix) {
            status = -1;
            free(playlist);
            goto done;
        }
        playlist = select_master_playlist_language(airplay_video, playlist);
        playlist_len = (int) strlen(playlist);
        int rc = create_media_uri_table(uri_prefix, playlist, playlist_len, &uri_list, &num_uri);
        new_master = adjust_master_playlist(playlist, playlist_len, uri_prefix, uri_local_prefix);
        free(playlist);
        if (rc < 0 || !new_master || num_uri <= 0) {
            logger_log(raop->logger, LOGGER_ERR, "unusable master playlist from client (%d media playlists)", num_uri);
            free(new_master);
            for (int i = 0; i < num_uri; i++) {
                free(uri_list[i]);
            }
            free(uri_list);
            status = -1;
            goto done;
        }
        store_master_playlist(airplay_video, new_master);
        int ret = create_media_data_store(airplay_video, uri_list, num_uri);   /* takes the uri strings */
        free(uri_list);
        if (ret < 0) {
            status = -1;
            goto done;
        }
        set_next_media_uri_id(airplay_video, 0);
    } else {
        /* this is a media playlist */
        int pending = airplay_video_live_refresh_pending(airplay_video);
        const char *pending_uri = pending >= 0 ? get_media_uri_by_num(airplay_video, pending) : NULL;
        if (pending_uri && !strcmp(pending_uri, fcup_response_url)) {
            /* answer to a live-playlist refresh request: replace the stored playlist, no state change */
            airplay_video_live_refresh_complete(airplay_video, playlist);
            logger_log(raop->logger, LOGGER_DEBUG, "refreshed live media playlist %s", fcup_response_url);
            goto done;
        }
        float duration = 0.0f;
        bool endlist = false;
        int count = analyze_media_playlist(playlist, &duration, &endlist);
        int uri_num = get_next_media_uri_id(airplay_video);
        --uri_num;    // (next num is current num + 1)
        int ret = store_media_playlist(airplay_video, playlist, &count, &duration, &endlist, uri_num);
        if (ret == 1) {
            logger_log(raop->logger, LOGGER_DEBUG,"media_playlist is a duplicate: do not store");
        } else if (ret < 0) {
            free(playlist);   /* not stored: still owned by us */
            if (ret == -1) {
                logger_log(raop->logger, LOGGER_ERR, "media playlist %s received out of sequence", fcup_response_url);
                status = -1;
                goto done;
            }
        } else if (count) {
            logger_log(raop->logger, LOGGER_DEBUG,
                       "\n%s:\nreceived media playlist has %5d chunks, total duration %9.3f secs%s\n",
                        fcup_response_url, count, duration, endlist ? "" : " (live: no ENDLIST)");
        }
    }

    {
        int num_uri = get_num_media_uri(airplay_video);
        int uri_num = get_next_media_uri_id(airplay_video);
        if (uri_num <  num_uri) {
            if (fcup_request((void *) conn, get_media_uri_by_num(airplay_video, uri_num),
                             apple_session_id, get_next_FCUP_RequestID(airplay_video)) < 0) {
                status = -1;
                goto done;
            }
            set_next_media_uri_id(airplay_video, ++uri_num);
        } else if (raop->callbacks.on_video_play) {
            raop->callbacks.on_video_play(raop->callbacks.cls,
                                          get_playback_location(airplay_video),
                                          get_start_position_seconds(airplay_video));
        }
    }
done:
    plist_mem_free(fcup_response_url);
    return status;
}

/* the POST /action request from Client to Server on the AirPlay http channel follows a POST /event "FCUP Request"
 from Server to Client on the reverse http channel, for a HLS playlist (first the Master Playlist, then the Media Playlists
 listed in the Master Playlist.     The POST /action request contains the playlist requested by the Server in
 the preceding "FCUP Request".   The FCUP Request sequence  continues until all Media Playlists have been obtained by the Server */ 

static void
http_handler_action(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                    char **response_data, int *response_datalen) {

    raop_t *raop = conn->raop;
    airplay_video_t *airplay_video = (airplay_video_t *) hls_get_current_video(raop);
    plist_t req_root_node = NULL;
    char *type = NULL;

    if (!airplay_video) {
        goto post_action_error;
    }

    const char* session_id = http_request_get_header(request, "X-Apple-Session-ID");
    if (!session_id) {
        logger_log(raop->logger, LOGGER_ERR, "Play request had no X-Apple-Session-ID");
        goto post_action_error;
    }    
    const char *apple_session_id = get_apple_session_id(airplay_video);
    if (!apple_session_id || strcmp(session_id, apple_session_id)){
        logger_log(raop->logger, LOGGER_ERR, "X-Apple-Session-ID has changed:\n  was:\"%s\"\n  now:\"%s\"",
                   apple_session_id ? apple_session_id : "(none)", session_id);
        goto post_action_error;
    }

    /* verify that this request contains a binary plist*/
    if (!request_headers_contain(request, "apple-binary-plist")) {
        logger_log(raop->logger, LOGGER_INFO, "POST /action: did not receive expected plist from client");	
        goto post_action_error;
    }

    /* extract the root_node  plist */
    int request_datalen = 0;
    const char *request_data = http_request_get_data(request, &request_datalen);
    if (!request_data || request_datalen <= 0) {
        logger_log(raop->logger, LOGGER_INFO, "POST /action: did not receive expected plist from client");	
        goto post_action_error;
    }
    plist_from_bin(request_data, request_datalen, &req_root_node);
    if (!PLIST_IS_DICT(req_root_node)) {
        goto post_action_error;
    }

    /* determine type of data */
    plist_t req_type_node = plist_dict_get_item(req_root_node, "type");
    if (!PLIST_IS_STRING(req_type_node)) {
        goto post_action_error;
    }
    
    /* three possible types are known: 
       playlistRemove
       playlistAdd
       unhandledURLRespone
*/
    plist_get_string_val(req_type_node, &type);
    if (!type) {
        goto post_action_error;
    }
    logger_log(raop->logger, LOGGER_DEBUG, "action type is %s", type);
    /* check that plist structure is as expected*/
    plist_t req_params_node = plist_dict_get_item(req_root_node, "params");
    if (!PLIST_IS_DICT (req_params_node)) {
        goto post_action_error;
    }
    if (!strcmp(type,"playlistRemove")) {
        plist_t req_params_item_node = plist_dict_get_item(req_params_node, "item");
        if (!PLIST_IS_DICT (req_params_item_node)) {
            goto post_action_error;
        }
        plist_t req_params_item_uuid_node = plist_dict_get_item(req_params_item_node, "uuid");
        char* remove_uuid = NULL;
        if (PLIST_IS_STRING(req_params_item_uuid_node)) {
            plist_get_string_val(req_params_item_uuid_node, &remove_uuid);
        }
        if (!remove_uuid) {
            goto post_action_error;
        }
        int id  =  get_playlist_by_uuid(raop, remove_uuid);
        if (id >= 0 && id == raop->current_video) {
            raop->current_video = -1;
            float position = raop->callbacks.on_video_playlist_remove ?
                             raop->callbacks.on_video_playlist_remove(raop->callbacks.cls) : 0.0f;
            /* keep the playlist (until space is needed for another one) in case its playback_uuid is re-requested
               the video will then be restarted at its previous position */
            set_resume_position_seconds(airplay_video, position);
        } else {
            logger_log(raop->logger, LOGGER_WARNING, "playlistRemove uuid %s does not match current_video\n", remove_uuid);
        }
        plist_mem_free (remove_uuid);

    } else if (!strcmp(type, "playlistInsert")) {
        logger_log(raop->logger, LOGGER_INFO, "action type playlistInsert (start playback)");
        plist_t req_params_item_node = plist_dict_get_item(req_params_node, "item");
        if (!PLIST_IS_DICT (req_params_item_node)) {
            goto post_action_error;
        }
        plist_t req_params_item_uuid_node = plist_dict_get_item(req_params_item_node, "uuid");
        char* insert_uuid = NULL;
        if (PLIST_IS_STRING(req_params_item_uuid_node)) {
            plist_get_string_val(req_params_item_uuid_node, &insert_uuid);
        }
        if (insert_uuid) {
            int id  =  get_playlist_by_uuid(raop, insert_uuid);
            if (id >= 0) {
                logger_log(raop->logger, LOGGER_INFO, "playlistInsert uuid %s is stored at airplay_video[%d]", insert_uuid, id);
            } else {
                logger_log(raop->logger, LOGGER_INFO, "playlistInsert uuid %s is not a stored playlist", insert_uuid);
            }
            plist_mem_free(insert_uuid);
        }
        logger_log(raop->logger, LOGGER_ERR, "FIXME: playlistInsert is not yet implemented");

    } else if (!strcmp(type, "unhandledURLResponse")) {   
        if (handle_fcup_response(conn, airplay_video, apple_session_id, req_params_node) < 0) {
            goto post_action_error;
        }
    } else {
        logger_log(raop->logger, LOGGER_INFO, "unknown action type (unhandled)"); 
    }
    plist_mem_free(type);
    plist_free(req_root_node);
    return;

 post_action_error:;
    http_response_init(response, "HTTP/1.1", 400, "Bad Request");
    plist_mem_free(type);
    if (req_root_node)  {
        plist_free(req_root_node);
    }

}

/* The parts of a /play request that matter to us, from either body encoding */
typedef struct {
    char *uuid;               /* playback uuid (binary plist) or the session id (text/parameters) */
    char *location;           /* Content-Location */
    double start_seconds;
    bool text_mode;
} play_request_t;

static void
play_request_free(play_request_t *req) {
    plist_mem_free(req->uuid);
    plist_mem_free(req->location);
    memset(req, 0, sizeof(*req));
}

static char *
dup_string(const char *s) {
    size_t n = strlen(s);
    char *c = (char *) malloc(n + 1);
    if (c) {
        memcpy(c, s, n + 1);
    }
    return c;
}

/* /play body: binary plist (dict with uuid, Content-Location, Start-Position-Seconds, ...) or the
   older "text/parameters" form ("Content-Location: <url>\nStart-Position: <fraction>\n") which
   some macOS versions send.  Returns 0 on success. */
static int
parse_play_request(raop_t *raop, http_request_t *request, const char *apple_session_id,
                   play_request_t *out) {
    memset(out, 0, sizeof(*out));
    out->start_seconds = 0.0;
    int datalen = 0;
    const char *data = http_request_get_data(request, &datalen);
    if (!data || datalen <= 0) {
        return -1;
    }
    if (request_headers_contain(request, "x-apple-binary-plist")) {
        plist_t root = NULL;
        plist_from_bin(data, datalen, &root);
        if (!PLIST_IS_DICT(root)) {
            plist_free(root);
            return -1;
        }
        plist_t uuid_node = plist_dict_get_item(root, "uuid");
        plist_t loc_node = plist_dict_get_item(root, "Content-Location");
        if (!PLIST_IS_STRING(uuid_node) || !PLIST_IS_STRING(loc_node)) {
            plist_free(root);
            return -1;
        }
        plist_get_string_val(uuid_node, &out->uuid);
        plist_get_string_val(loc_node, &out->location);

        plist_t proc_node = plist_dict_get_item(root, "clientProcName");
        char *proc = NULL;
        if (PLIST_IS_STRING(proc_node)) {
            plist_get_string_val(proc_node, &proc);
        }
        if (proc && !strstr("YouTube;", proc)) {
            logger_log(raop->logger, LOGGER_WARNING, "Unsupported HLS streaming format: clientProcName %s not found "
                       "in supported list: YouTube;", proc);
        }
        plist_mem_free(proc);

        plist_t start_node = plist_dict_get_item(root, "Start-Position-Seconds");
        if (PLIST_IS_REAL(start_node)) {
            double v = 0.0;
            plist_get_real_val(start_node, &v);
            out->start_seconds = (isfinite(v) && v > 0.0) ? v : 0.0;
        } else if (!start_node) {
            logger_log(raop->logger, LOGGER_INFO, "No Start-Position-Seconds in Play request");
        }
        plist_free(root);
    } else {
        hls_text_params_t params;
        if (hls_parse_text_parameters(data, (size_t) datalen, &params) < 0 || !params.location) {
            logger_log(raop->logger, LOGGER_ERR, "Play request Content is neither binary plist nor text/parameters");
            return -1;
        }
        out->text_mode = true;
        out->location = params.location;   /* malloc'd; released with free == plist_mem_free */
        out->uuid = dup_string(apple_session_id);
        /* Start-Position is a fraction of the (still unknown) duration: only an absolute value can be honoured */
        if (params.start_seconds > 0.0) {
            out->start_seconds = params.start_seconds;
        } else if (params.start_fraction > 1.0) {
            out->start_seconds = params.start_fraction;
        }
        logger_log(raop->logger, LOGGER_INFO, "Play request in text/parameters form (start %.3f s)", out->start_seconds);
    }
    return out->uuid && out->location ? 0 : -1;
}

/* index of a free airplay_video slot; evicts a stored (non-current) one if all are in use */
static int
claim_video_slot(raop_t *raop) {
    for (int i = 0; i < MAX_AIRPLAY_VIDEO; i++) {
        if (!raop->airplay_video[i]) {
            return i;
        }
    }
    int victim = raop->current_video >= 0 ? (raop->current_video + 1) % (int) MAX_AIRPLAY_VIDEO : 0;
    logger_log(raop->logger, LOGGER_INFO, "deleting playlist playback_uuid %s duration (seconds) %f",
               get_playback_uuid(raop->airplay_video[victim]), get_duration(raop->airplay_video[victim]));
    raop_destroy_airplay_video(raop, victim);
    return victim;
}

/* The POST /play request from the Client to Server on the AirPlay http channel contains (among other information)
   the "Content Location" that specifies the HLS Playlists for the video to be streamed, as well as the video 
   "start position in seconds".   Once this request is received by the Sever, the Server sends a POST /event
   "FCUP Request" request to the Client on the reverse http channel, to request the HLS Master Playlist */

static void
http_handler_play(raop_conn_t *conn, http_request_t *request, http_response_t *response,
                      char **response_data, int *response_datalen) {
    raop_t *raop = conn->raop;
    play_request_t play;
    memset(&play, 0, sizeof(play));
    airplay_video_t *airplay_video = NULL;
    int new_id = -1;
    
    logger_log(raop->logger, LOGGER_DEBUG, "http_handler_play");

    const char* apple_session_id = http_request_get_header(request, "X-Apple-Session-ID");
    if (!apple_session_id || !*apple_session_id || strlen(apple_session_id) > HLS_MAX_SESSION_ID_LEN) {
        logger_log(raop->logger, LOGGER_ERR, "Play request had no (valid) X-Apple-Session-ID");
        goto play_error;
    }
    if (parse_play_request(raop, request, apple_session_id, &play) < 0) {
        goto play_error;
    }

    int id = get_playlist_by_uuid(raop, play.uuid);
    if (id >= 0 && play.text_mode) {
        /* text/parameters has no per-item uuid: a new /play is always a new item */
        raop_destroy_airplay_video(raop, id);
        id = -1;
    }

    /* check if playlist is already downloaded and stored (may have been interrupted by advertisements ) */
    if (id >= 0) {
        airplay_video = raop->airplay_video[id];
        if (!get_playback_location(airplay_video) || set_apple_session_id(airplay_video, apple_session_id,
                                                                          strlen(apple_session_id)) < 0) {
            raop_destroy_airplay_video(raop, id);
            airplay_video = NULL;
            goto play_error;
        }
        raop->current_video = id;
        float resume_pos = get_resume_position_seconds(airplay_video);
        float start_pos = get_start_position_seconds(airplay_video);
        play_request_free(&play);
        if (raop->callbacks.on_video_play) {
            raop->callbacks.on_video_play(raop->callbacks.cls,
                                          get_playback_location(airplay_video),
                                          resume_pos > start_pos ? resume_pos : start_pos);
        }
        return;
    }

    /* we only support HLS if the playback location is terminated by "/master.m3u8" */
    const char *uri_suffix = strstr(play.location, "/master.m3u8");
    if (!uri_suffix) { 
        logger_log(raop->logger, LOGGER_ERR, "Content-Location has unsupported form:\n%s\n", play.location);	    
        goto play_error;
    }
    
    /* initialize a new playlist (airplay_video structure) */
    /* first delete any short stored playlists (probably advertisements */
    for (int i = 0; i < MAX_AIRPLAY_VIDEO; i++) {
        if (raop->airplay_video[i]) {
            float duration = get_duration(raop->airplay_video[i]); 
            if (duration < (float) MIN_STORED_AIRPLAY_VIDEO_DURATION_SECONDS ) { //likely to be an advertisement
                logger_log(raop->logger, LOGGER_INFO,
                          "deleting playlist playback_uuid %s duration (seconds) %f",
                           get_playback_uuid(raop->airplay_video[i]), duration);
                raop_destroy_airplay_video(raop, i);
            }
        }
    }

    new_id = claim_video_slot(raop);
    raop->airplay_video[new_id] = airplay_video_init(raop, raop->port, raop->lang);
    airplay_video = raop->airplay_video[new_id];
    if (!airplay_video) {
        logger_log(raop->logger, LOGGER_ERR, "http_handler_play: out of memory");
        new_id = -1;
        goto play_error;
    }
    raop->current_video = new_id;

    {
        size_t prefix_len = (size_t) (uri_suffix - play.location);
        const char *local_prefix = get_uri_local_prefix(airplay_video);
        size_t len = strlen(local_prefix) + strlen(uri_suffix);
        char *location = (char *) calloc(len + 1, sizeof(char));
        if (!location) {
            goto play_error;
        }
        strcat(location, local_prefix);
        strcat(location, uri_suffix);
        int ok = set_playback_location(airplay_video, location, strlen(location));
        free(location);
        if (ok < 0 || set_uri_prefix(airplay_video, play.location, prefix_len) < 0) {
            logger_log(raop->logger, LOGGER_ERR, "Content-Location is empty or too long");
            goto play_error;
        }
    }
    if (set_apple_session_id(airplay_video, apple_session_id, strlen(apple_session_id)) < 0 ||
        set_playback_uuid(airplay_video, play.uuid, strlen(play.uuid)) < 0) {
        goto play_error;
    }
    set_start_position_seconds(airplay_video, (float) play.start_seconds);
    set_next_media_uri_id(airplay_video, 0);

    /* ensure that space will always be available for adding future playlists */
    {
        bool full = true;
        for (int i = 0; i < MAX_AIRPLAY_VIDEO; i++) {
            if (!raop->airplay_video[i]) {
                full = false;
                break;
            }
        }
        if (full) {
            int next = (new_id + 1) % (int) MAX_AIRPLAY_VIDEO;
            logger_log(raop->logger, LOGGER_INFO,
                       "deleting playlist playback_uuid %s duration (seconds) %f",
                       get_playback_uuid(raop->airplay_video[next]),
                       get_duration(raop->airplay_video[next]));
            raop_destroy_airplay_video(raop, next);
        }
    }

    if (fcup_request((void *) conn, play.location, apple_session_id, get_next_FCUP_RequestID(airplay_video)) < 0) {
        goto play_error;
    }

    play_request_free(&play);
    return;

 play_error:;
    play_request_free(&play);
    if (new_id >= 0) {
        /* do not leave a half-initialised item behind */
        raop_destroy_airplay_video(raop, new_id);
    }
    logger_log(raop->logger, LOGGER_ERR, "Could not find valid Plist Data for POST/play request, Unhandled");
    http_response_init(response, "HTTP/1.1", 400, "Bad Request");
    http_response_set_disconnect(response, 1);
    if (raop->callbacks.conn_reset) {
        raop->callbacks.conn_reset(raop->callbacks.cls, 2);
    }
}

/* the HLS handler handles http requests GET /[uri] on the HLS channel from the media player to the Server, asking for
   (adjusted) copies of Playlists: first the Master Playlist  (adjusted to change the uri prefix to
   "http://localhost:[port]/.......m3u8"), then the Media Playlists that the media player wishes to use.  
   If the client supplied Media playlists with the "YT-EXT-CONDENSED-URI" header, these must be adjusted into
   the standard uncondensed form before sending with the response.    The uri in the request is  the uri for the
   Media Playlist, taken from the Master Playlist, with the uri prefix removed.  

   Live streams (media playlist without #EXT-X-ENDLIST): the stored copy is a snapshot, so every poll
   by the player (at most once a second) additionally triggers an FCUP re-request of that playlist
   from the sender; the refreshed copy replaces the snapshot when it arrives (see handle_fcup_response)
   and is served to the next poll.  The player therefore sees a live playlist that lags the sender by
   about one polling interval.
*/ 

static void
http_handler_hls(raop_conn_t *conn,  http_request_t *request, http_response_t *response,
                 char **response_data, int *response_datalen) {
    raop_t *raop = conn->raop;
    if (raop->current_video == -1) {
        logger_log(raop->logger, LOGGER_ERR,"airplay_video playlist  not found");
        http_response_init(response, "HTTP/1.1", 404, "Not Found");
        return;
    }
    const char *method = http_request_get_method(request);
    const char *url = http_request_get_url(request);    
    if (!method || strcmp(method, "GET") || !url) {
        http_response_init(response, "HTTP/1.1", 405, "Method Not Allowed");
        return;
    }
    const char* upgrade = http_request_get_header(request, "Upgrade");
    if (upgrade) {
        //don't accept Upgrade: h2c request ?
        char *header_str = NULL;
        http_request_get_header_string(request, &header_str);
        logger_log(raop->logger, LOGGER_INFO,
                   "%s\nhls upgrade request declined", header_str ? header_str : "");
        free (header_str);
        return;
    }
    airplay_video_t *airplay_video = (airplay_video_t *) hls_get_current_video(raop);
    if (!airplay_video) {
        http_response_init(response, "HTTP/1.1", 404, "Not Found");
        return;
    }
    if (!strcmp(url, "/master.m3u8")){
        char * master_playlist  = get_master_playlist(airplay_video);
        if (master_playlist) {
            size_t len = strlen(master_playlist);
            char * data = (char *) malloc(len + 1);
            if (!data) {
                http_response_init(response, "HTTP/1.1", 500, "Internal Server Error");
                return;
            }
            memcpy(data, master_playlist, len);
            data[len] = '\0';
            *response_data = data;
            *response_datalen = (int ) len;
        } else {
            logger_log(raop->logger, LOGGER_ERR,"requested master playlist %s not found", url); 
        }

    } else {
        int chunks = 0;
        float duration = 0.0f;
        char *media_playlist = get_media_playlist(airplay_video, &chunks, &duration, url);
        if (media_playlist) {
            char *data  = adjust_yt_condensed_playlist(media_playlist);
            if (!data) {
                http_response_init(response, "HTTP/1.1", 500, "Internal Server Error");
                return;
            }
            *response_data = data;
            *response_datalen = (int) strlen(data);
            logger_log(raop->logger, LOGGER_INFO,
                       "Requested media_playlist %s has %5d chunks, total duration %9.3f secs", url, chunks, duration); 
            /* live playlist: ask the sender for a newer copy (answered asynchronously) */
            int live_idx = airplay_video_live_refresh_begin(airplay_video, url);
            if (live_idx >= 0) {
                logger_log(raop->logger, LOGGER_DEBUG, "live playlist %s: requesting refresh from sender", url);
                if (fcup_request((void *) conn, get_media_uri_by_num(airplay_video, live_idx),
                                 get_apple_session_id(airplay_video),
                                 get_next_FCUP_RequestID(airplay_video)) < 0) {
                    airplay_video_live_refresh_cancel(airplay_video);
                }
            }
        } else {
            logger_log(raop->logger, LOGGER_ERR,"requested media playlist %s not found", url); 
        }
	    
    }

    http_response_add_header(response, "Access-Control-Allow-Headers", "Content-type");
    http_response_add_header(response, "Access-Control-Allow-Origin", "*");
    const char *date = NULL;
    date = gmt_time_string();
    http_response_add_header(response, "Date", date);
    if (*response_datalen > 0) {
        http_response_add_header(response, "Content-Type", "application/x-mpegURL; charset=utf-8");
    } else if (*response_datalen == 0) {
        http_response_init(response, "HTTP/1.1", 404, "Not Found");
    }
}
