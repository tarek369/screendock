/*
 * HLS playlist helpers for the AirPlay-video (HLS) path.
 *
 * Pure string functions with no dependency on the rest of UxPlay, so that they
 * can be unit tested on the host (see app/src/test/native).  Every length that
 * originates from the sender is bounds checked; failures are reported through
 * return values, never assert()/exit().
 *
 * Licensed under the GNU Lesser General Public License v2.1 or later
 * (same as the UxPlay code this was split out of).
 */
#ifndef HLS_PLAYLIST_H
#define HLS_PLAYLIST_H

#include <stddef.h>
#include <stdbool.h>

/* hard caps on sender-controlled sizes */
#define HLS_MAX_PLAYLIST_BYTES (32u * 1024u * 1024u)
#define HLS_MAX_MEDIA_URIS 512

/* Replace every occurrence of uri_prefix in the master playlist by local_prefix.
 * Returns a new malloc'd, NUL-terminated string (caller frees), or NULL on error. */
char *hls_adjust_master_playlist(const char *data, size_t datalen,
                                 const char *uri_prefix, const char *local_prefix);

/* Copy a media playlist; if it carries the "#YT-EXT-CONDENSED-URL" header, expand it
 * into the full format.  A malformed condensed playlist is returned unexpanded (plain
 * copy) rather than treated as fatal.  Returns malloc'd string, NULL only on OOM. */
char *hls_expand_condensed_playlist(const char *playlist);

/* Number of #EXTINF chunks, summed duration; *endlist set if "#EXT-X-ENDLIST" present
 * (false => live / event playlist that the sender keeps appending to). */
int hls_analyze_media_playlist(const char *playlist, float *duration, bool *endlist);

/* Table of the media-playlist URIs (those starting with url_prefix and ending in
 * "m3u8") listed in a master playlist.  Returns 0 on success (table may be empty:
 * *num_uri == 0, *table == NULL), <0 on error.  On success each entry and the table
 * are malloc'd and owned by the caller. */
int hls_create_media_uri_table(const char *url_prefix, const char *master,
                               char ***table, int *num_uri);

/* Select one audio language in a master playlist that offers several
 * (YouTube "#EXT-X-MEDIA:URI=... LANGUAGE=... YT-EXT-AUDIO-CONTENT-ID=" lines).
 * Returns 0 if the playlist offers no language choice (outputs untouched),
 * 1 if filtered (*out_master, *out_code, *out_name are malloc'd), <0 on error. */
int hls_select_language(const char *master, const char *want_name, const char *pref_langs,
                        char **out_master, char **out_code, char **out_name);

/* Parameters of an AirPlay "text/parameters" /play body, e.g.
 *     Content-Location: http://host/path/master.m3u8
 *     Start-Position: 0.25
 * Keys are matched case-insensitively; unknown keys are ignored.  Absent numeric values are
 * reported as a negative number.  Start-Position is a *fraction* (0..1) of the item's duration,
 * Start-Position-Seconds is absolute.  body need not be NUL-terminated.
 * Returns 0 on success (location may still be NULL if absent; malloc'd, caller frees), <0 on error. */
typedef struct {
    char *location;
    double start_fraction;
    double start_seconds;
} hls_text_params_t;

int hls_parse_text_parameters(const char *body, size_t len, hls_text_params_t *out);

#endif
