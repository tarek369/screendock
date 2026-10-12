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

// it should only start and stop the media_data_store that handles all HLS transactions, without
// otherwise participating in them.  

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <time.h>

#include "raop.h"
#include "airplay_video.h"
#include "hls_playlist.h"

typedef enum playlist_type_e {
    NONE,
    VOD,
    EVENT
} playlist_type_t;

struct media_item_s {
    char *uri;
    char *playlist;
    int num;
    int count;
    float duration;
    bool endlist;
    playlist_type_t playlist_type;
    int hls_version;
    int media_sequence;
};

struct airplay_video_s {
    raop_t *raop;
    char *apple_session_id;
    char *playback_uuid;
    char *uri_prefix;
    char *local_uri_prefix;
    char *playback_location;
    char *language_name;
    char *language_code;
    const char *lang;
    int next_uri;
    int FCUP_RequestID;
    float start_position_seconds;
    float resume_position_seconds;
    playback_info_t *playback_info;
    char *master_playlist;
    media_item_t *media_data_store;
    int num_uri;
    /* live playlists: index of the media playlist being re-requested from the sender (-1: none) */
    int refresh_index;
    time_t refresh_started;
    time_t last_refresh;
};

//  initialize airplay_video service.
airplay_video_t *airplay_video_init(raop_t *raop, unsigned short http_port, const char *lang) {
    char uri[] = "http://localhost:";
    char port[6] = { '\0' };
    if (!raop) {
        return NULL;
    }

    /* calloc guarantees that the 36-character strings apple_session_id and 
       playback_uuid are null-terminated */
    airplay_video_t *airplay_video =  (airplay_video_t *) calloc(1, sizeof(airplay_video_t));

    if (!airplay_video) {
        return NULL;
    }

    airplay_video->lang = lang;
     /* create local_uri_prefix string */
    snprintf(port, sizeof(port), "%u", http_port);
    size_t len = strlen(uri) + strlen(port);
    airplay_video->local_uri_prefix = (char *) calloc (len + 1, sizeof(char));
    if (!airplay_video->local_uri_prefix) {
        free(airplay_video);
        return NULL;
    }
    strcat(airplay_video->local_uri_prefix, uri);
    strcat(airplay_video->local_uri_prefix, port);

    airplay_video->raop = raop;
    airplay_video->FCUP_RequestID = 0;
    airplay_video->apple_session_id = NULL;
    airplay_video->start_position_seconds = 0.0f;
    airplay_video->playback_uuid = NULL;
    airplay_video->uri_prefix = NULL;
    airplay_video->playback_location = NULL;
    airplay_video->language_code = NULL;
    airplay_video->language_name = NULL;
    airplay_video->media_data_store = NULL;
    airplay_video->master_playlist = NULL;
    airplay_video->num_uri = 0;
    airplay_video->next_uri = 0;
    airplay_video->refresh_index = -1;
    airplay_video->refresh_started = 0;
    airplay_video->last_refresh = 0;
    return airplay_video;
}

// destroy the airplay_video service
void
airplay_video_destroy(airplay_video_t *airplay_video) {
    if (!airplay_video) {
        return;
    }
    if (airplay_video->apple_session_id) {
        free(airplay_video->apple_session_id);
    }
    if (airplay_video->playback_uuid) {
        free(airplay_video->playback_uuid);
    }
    if (airplay_video->uri_prefix) {
        free(airplay_video->uri_prefix);
    }
    if (airplay_video->local_uri_prefix) {
        free(airplay_video->local_uri_prefix);
    }
    if (airplay_video->playback_location) {
        free(airplay_video->playback_location);
    }
    if (airplay_video->language_name) {
        free(airplay_video->language_name);
    }
    if (airplay_video->language_code) {
       free(airplay_video->language_code);
    }
    if (airplay_video->media_data_store) {
        destroy_media_data_store(airplay_video);
    }
    if (airplay_video->master_playlist){
        free (airplay_video->master_playlist);
    }
    free (airplay_video);
}

/* Replace *dst by a copy of the first len bytes of src.  Returns 0, or -1 (and leaves
   *dst untouched) if src is NULL, len is 0 or exceeds max_len, or allocation fails. */
static int replace_string(char **dst, const char *src, size_t len, size_t max_len) {
    if (!src || len == 0 || len > max_len) {
        return -1;
    }
    char *str = (char *) calloc(len + 1, sizeof(char));
    if (!str) {
        return -1;
    }
    memcpy(str, src, len);
    str[strnlen(str, len)] = '\0';
    free(*dst);
    *dst = str;
    return 0;
}

#define MAX_ID_LEN 64
#define MAX_URL_LEN 8192
#define MAX_NAME_LEN 256

int set_apple_session_id(airplay_video_t *airplay_video, const char *apple_session_id, size_t len) {
    return airplay_video ? replace_string(&airplay_video->apple_session_id, apple_session_id, len, MAX_ID_LEN) : -1;
}

int set_playback_uuid(airplay_video_t *airplay_video, const char *playback_uuid, size_t len) {
    return airplay_video ? replace_string(&airplay_video->playback_uuid, playback_uuid, len, MAX_ID_LEN) : -1;
}

int set_uri_prefix(airplay_video_t *airplay_video, const char *uri_prefix, size_t len) {
    return airplay_video ? replace_string(&airplay_video->uri_prefix, uri_prefix, len, MAX_URL_LEN) : -1;
}

int set_playback_location(airplay_video_t *airplay_video, const char *location, size_t len) {
    return airplay_video ? replace_string(&airplay_video->playback_location, location, len, MAX_URL_LEN) : -1;
}

int set_language_name(airplay_video_t *airplay_video, const char *language_name, size_t len) {
    return airplay_video ? replace_string(&airplay_video->language_name, language_name, len, MAX_NAME_LEN) : -1;
}

int set_language_code(airplay_video_t *airplay_video, const char *language_code, size_t len) {
    return airplay_video ? replace_string(&airplay_video->language_code, language_code, len, MAX_NAME_LEN) : -1;
}

const char *get_apple_session_id(airplay_video_t *airplay_video) {
    if (!airplay_video || !airplay_video->apple_session_id) {
        return NULL;
    }
    return airplay_video->apple_session_id;
}

float get_duration(airplay_video_t *airplay_video) {
    if (!airplay_video || !airplay_video->media_data_store || !airplay_video->media_data_store->duration) {
        return 0.0f;
    }
    return airplay_video->media_data_store->duration;
}

float get_start_position_seconds(airplay_video_t *airplay_video) {
    return airplay_video->start_position_seconds;
}

float get_resume_position_seconds(airplay_video_t *airplay_video) {
    return airplay_video->resume_position_seconds;
}

void set_start_position_seconds(airplay_video_t *airplay_video, float start_position_seconds) {
    airplay_video->start_position_seconds = start_position_seconds;
}

void set_resume_position_seconds(airplay_video_t *airplay_video, float resume_position_seconds) {
    airplay_video->resume_position_seconds = resume_position_seconds;
}

const char *get_playback_uuid(airplay_video_t *airplay_video) {
    return (const char *) (!airplay_video ? NULL : airplay_video->playback_uuid); 
}

const char *get_playback_location(airplay_video_t *airplay_video) {
    return (const char *) (!airplay_video ? NULL : airplay_video->playback_location); 
}

const char *get_uri_prefix(airplay_video_t *airplay_video) {
    return (const char *) airplay_video->uri_prefix;
}

const char *get_language_name(airplay_video_t *airplay_video) {
    return (const char *)airplay_video->language_name;
}

const char *get_language_code(airplay_video_t *airplay_video) {
    return (const char *) airplay_video->language_code;
}

char *get_uri_local_prefix(airplay_video_t *airplay_video) {
    return airplay_video->local_uri_prefix;
}

int get_next_FCUP_RequestID(airplay_video_t *airplay_video) {    
    return ++(airplay_video->FCUP_RequestID);
}

void  set_next_media_uri_id(airplay_video_t *airplay_video, int num) {
    airplay_video->next_uri = num;
}

int get_next_media_uri_id(airplay_video_t *airplay_video) {
    return airplay_video->next_uri;
}

void store_master_playlist(airplay_video_t *airplay_video, char *master_playlist) {
    if (airplay_video->master_playlist) {
        free (airplay_video->master_playlist);
    }
    airplay_video->master_playlist = master_playlist;
}

/* Pick the audio language in a master playlist that offers several; returns the (possibly
   new) master playlist, taking ownership of master_playlist.  Never fails: on any problem
   the original playlist is returned unchanged. */
char *select_master_playlist_language(airplay_video_t *airplay_video, char *master_playlist) {
    char *new_master = NULL, *code = NULL, *name = NULL;
    if (!master_playlist || !airplay_video) {
        return master_playlist;
    }
    int ret = hls_select_language(master_playlist, airplay_video->language_name, airplay_video->lang,
                                  &new_master, &code, &name);
    if (ret != 1) {
        return master_playlist;
    }
    /* update stored language code, name if changed */
    if (!airplay_video->language_name || strcmp(airplay_video->language_name, name)) {
        set_language_name(airplay_video, name, strlen(name));
    }
    if (!airplay_video->language_code || strcmp(airplay_video->language_code, code)) {
        set_language_code(airplay_video, code, strlen(code));
    }
    free(code);
    free(name);
    free(master_playlist);
    return new_master;
}

char *get_master_playlist(airplay_video_t *airplay_video) {
    return  airplay_video->master_playlist;
}

/* media_data_store */

int get_num_media_uri(airplay_video_t *airplay_video) {
    return airplay_video->num_uri;
}

void destroy_media_data_store(airplay_video_t *airplay_video) {
    media_item_t *media_data_store = airplay_video->media_data_store; 
    if (media_data_store) {
        for (int i = 0; i < airplay_video->num_uri ; i ++ ) {
            free(media_data_store[i].uri);
            free(media_data_store[i].playlist);
        }
    }
    free (media_data_store);
    airplay_video->media_data_store = NULL;
    airplay_video->num_uri = 0;
    airplay_video->refresh_index = -1;
}

/* takes ownership of the strings in uri_list (but not of the array itself), also on failure */
int create_media_data_store(airplay_video_t * airplay_video, char ** uri_list, int num_uri) {  
    destroy_media_data_store(airplay_video);
    if (num_uri <= 0 || !uri_list) {
        return 0;
    }
    media_item_t *media_data_store = calloc((size_t) num_uri, sizeof(media_item_t));
    if (!media_data_store) {
        for (int i = 0; i < num_uri; i++) {
            free(uri_list[i]);
        }
        return -1;
    }
    for (int i = 0; i < num_uri; i++) {
        media_data_store[i].uri = uri_list[i];
        media_data_store[i].playlist = NULL;
        media_data_store[i].num = i;
        media_data_store[i].count = 0;
        media_data_store[i].duration = 0;
        media_data_store[i].endlist = false;
        media_data_store[i].playlist_type = NONE;
        media_data_store[i].hls_version = 0;
        media_data_store[i].media_sequence = 0;
    }
    airplay_video->media_data_store = media_data_store;
    airplay_video->num_uri = num_uri;
    return 0;
}

static int parse_media_playlist(media_item_t *media_item) {
    const char *ptr = media_item->playlist;
    char extm3u[] = "#EXTM3U";
    char extinf[] = "#EXTINF:";
    char extx[] = "#EXT-X-";
    char playlist_type[] = "PLAYLIST-TYPE:";
    char version[] = "VERSION:";
    char media_sequence[] = "MEDIA-SEQUENCE:";
    if (!ptr) {
        return -1;
    }
    ptr = strstr(ptr, extm3u);
    if (!ptr) {
        return -1;
    }
    ptr++;
    while (ptr) {
        const char *ptr1 = NULL;
        ptr = strstr(ptr, "#EXT");
        if (!ptr || !memcmp(ptr, extinf, strlen(extinf))) {
            break;
        }
        ptr = strstr(ptr, extx);
        if (!ptr) {
            break;
        }
        if ((ptr1 = strstr(ptr, playlist_type))) {
            ptr1 += strlen(playlist_type);
            if (!memcmp(ptr1,"VOD", strlen("VOD"))) {
                media_item->playlist_type = VOD;
            } else if (!memcmp(ptr1,"EVENT", strlen("EVENT"))) {
                media_item->playlist_type = EVENT;
            }
            ptr1 = NULL;
        }
        if ((ptr1 = strstr(ptr, version))) {
            char *endptr = NULL;
            ptr1 += strlen(version);
            media_item->hls_version = (int) strtol(ptr1, &endptr, 10);
            ptr1 = NULL;
        }
        if ((ptr1 = strstr(ptr, media_sequence))) {
            char *endptr = NULL;
            ptr1 += strlen(media_sequence);
            media_item->media_sequence = (int) strtol(ptr1, &endptr, 10);
            ptr1 = NULL;
        }
        ptr += strlen(extx);
    }
    return 0;
}

/* Store the media playlist received for the uri with index num.  Takes ownership of
   media_playlist when it returns 0 or 1 (the caller must free it on a negative return).
   returns: 0 stored, 1 duplicate of an earlier identical playlist (freed), -1 bad index, -2 already stored */
int store_media_playlist(airplay_video_t *airplay_video, char * media_playlist, int *count, float *duration, bool *endlist, int num) {
    media_item_t *media_data_store = airplay_video->media_data_store;
    if (!media_data_store || num < 0 ||  num >= airplay_video->num_uri) {
        return -1;
    } else if (media_data_store[num].playlist) {
        return -2;
    }
    /* dont store duplicate media paylists (same uri, identical content) */
    for (int i = 0; i < num ; i++) {
        if (media_data_store[i].playlist && media_data_store[i].uri && media_data_store[num].uri &&
            strcmp(media_data_store[i].uri, media_data_store[num].uri) == 0 &&
            strcmp(media_data_store[i].playlist, media_playlist) == 0) {
            media_data_store[num].num = i;
            free (media_playlist);
            return 1;
        }
    }
    media_item_t *media_item = &media_data_store[num];
    media_item->playlist = media_playlist;
    media_item->count = *count;
    media_item->duration = *duration;
    media_item->endlist = *endlist;
    parse_media_playlist(media_item);
    return 0;
}

/* resolve uri (as requested by the media player) to the canonical store index, or -1 */
static int find_media_index(airplay_video_t *airplay_video, const char *uri) {
    media_item_t *media_data_store = airplay_video->media_data_store;
    if (!media_data_store || !uri) {
        return -1;
    }
    for (int i = 0; i < airplay_video->num_uri; i++) {
        if (media_data_store[i].uri && strstr(media_data_store[i].uri, uri)) {
            int canonical = media_data_store[i].num;
            if (canonical < 0 || canonical >= airplay_video->num_uri) {
                return -1;
            }
            return canonical;
        }
    }
    return -1;
}

char * get_media_playlist(airplay_video_t *airplay_video, int *count, float *duration, const char *uri) {
    int idx = find_media_index(airplay_video, uri);
    if (idx < 0) {
        return NULL;
    }
    *count = airplay_video->media_data_store[idx].count;
    *duration = airplay_video->media_data_store[idx].duration;
    return airplay_video->media_data_store[idx].playlist;
}

char * get_media_uri_by_num(airplay_video_t *airplay_video, int num) {
    media_item_t * media_data_store = airplay_video->media_data_store;
    if (media_data_store && num >= 0 && num < airplay_video->num_uri) {
        return  media_data_store[num].uri;
    }
    return NULL;
}

/* ---- live playlists (no #EXT-X-ENDLIST) ----
   The sender (not the Android player) is the origin of all playlists and only hands them over
   when asked via an "FCUP" request on the reverse channel; that request is asynchronous.  For a
   live stream the player polls the media playlist, so each poll (rate limited) triggers one FCUP
   re-request.  The poll itself is answered immediately with the newest stored copy; the refreshed
   copy arrives in a later POST /action and replaces the stored one. */
#define LIVE_REFRESH_MIN_INTERVAL_SEC 1
#define LIVE_REFRESH_TIMEOUT_SEC 10

int airplay_video_live_refresh_begin(airplay_video_t *airplay_video, const char *uri) {
    int idx = find_media_index(airplay_video, uri);
    time_t now = time(NULL);
    if (idx < 0) {
        return -1;
    }
    media_item_t *item = &airplay_video->media_data_store[idx];
    if (!item->playlist || item->endlist) {
        return -1;
    }
    if (airplay_video->refresh_index >= 0 &&
        now - airplay_video->refresh_started < LIVE_REFRESH_TIMEOUT_SEC) {
        return -1;   /* a request is still outstanding */
    }
    if (now - airplay_video->last_refresh < LIVE_REFRESH_MIN_INTERVAL_SEC) {
        return -1;
    }
    airplay_video->refresh_index = idx;
    airplay_video->refresh_started = now;
    airplay_video->last_refresh = now;
    return idx;
}

void airplay_video_live_refresh_cancel(airplay_video_t *airplay_video) {
    airplay_video->refresh_index = -1;
}

int airplay_video_live_refresh_pending(airplay_video_t *airplay_video) {
    return airplay_video->refresh_index;
}

/* Replace the stored playlist of the pending refresh with the one just received.
   Takes ownership of media_playlist (freed on failure too).  Returns 0 on success. */
int airplay_video_live_refresh_complete(airplay_video_t *airplay_video, char *media_playlist) {
    int idx = airplay_video->refresh_index;
    airplay_video->refresh_index = -1;
    if (idx < 0 || idx >= airplay_video->num_uri || !media_playlist) {
        free(media_playlist);
        return -1;
    }
    media_item_t *item = &airplay_video->media_data_store[idx];
    int count = 0;
    float duration = 0.0f;
    bool endlist = false;
    count = hls_analyze_media_playlist(media_playlist, &duration, &endlist);
    free(item->playlist);
    item->playlist = media_playlist;
    item->count = count;
    item->duration = duration;
    item->endlist = endlist;
    parse_media_playlist(item);
    return 0;
}

/* thin wrappers kept for the existing API */
int analyze_media_playlist(char *playlist, float *duration, bool *endlist) {
    return hls_analyze_media_playlist(playlist, duration, endlist);
}

int create_media_uri_table(const char *url_prefix, const char *master_playlist_data,
                           int datalen, char ***media_uri_table, int *num_uri) {
    (void) datalen;
    return hls_create_media_uri_table(url_prefix, master_playlist_data, media_uri_table, num_uri);
}

/* Adjust uri prefixes in the Master Playlist, for sending to the Media Player.
   Returns a new string (or NULL on failure). */
char *adjust_master_playlist (char *fcup_response_data, int fcup_response_datalen,
                              const char *uri_prefix, char *uri_local_prefix) {
    if (fcup_response_datalen < 0) {
        return NULL;
    }
    return hls_adjust_master_playlist(fcup_response_data, (size_t) fcup_response_datalen,
                                      uri_prefix, uri_local_prefix);
}

char *adjust_yt_condensed_playlist(const char *media_playlist) {
    return hls_expand_condensed_playlist(media_playlist);
}
