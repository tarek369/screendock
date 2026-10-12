/*
 * HLS playlist helpers (see hls_playlist.h).
 * Originally part of lib/airplay_video.c (c) 2024 fduncanh; rewritten with
 * bounds checking so that malformed sender data cannot crash the process.
 *
 * Licensed under the GNU Lesser General Public License v2.1 or later.
 */
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdbool.h>

#include "hls_playlist.h"

/* ---- growable string builder (bounded) ---- */
typedef struct {
    char *buf;
    size_t len;
    size_t cap;
    bool err;
} sbuf_t;

static void sb_append(sbuf_t *sb, const char *data, size_t n) {
    if (sb->err || n == 0) {
        return;
    }
    if (n > HLS_MAX_PLAYLIST_BYTES || sb->len > HLS_MAX_PLAYLIST_BYTES - n) {
        sb->err = true;
        return;
    }
    size_t need = sb->len + n + 1;
    if (need > sb->cap) {
        size_t ncap = sb->cap ? sb->cap : 256;
        while (ncap < need) {
            ncap *= 2;
        }
        char *nb = (char *) realloc(sb->buf, ncap);
        if (!nb) {
            sb->err = true;
            return;
        }
        sb->buf = nb;
        sb->cap = ncap;
    }
    memcpy(sb->buf + sb->len, data, n);
    sb->len += n;
    sb->buf[sb->len] = '\0';
}

static void sb_puts(sbuf_t *sb, const char *s) {
    sb_append(sb, s, strlen(s));
}

/* returns the NUL-terminated buffer (never NULL on success, even if empty) or NULL */
static char *sb_finish(sbuf_t *sb) {
    if (sb->err) {
        free(sb->buf);
        sb->buf = NULL;
        return NULL;
    }
    if (!sb->buf) {
        sb->buf = (char *) calloc(1, 1);
    }
    return sb->buf;
}

/* portable memmem (not declared by bionic/glibc without _GNU_SOURCE) */
static const char *find_bytes(const char *hay, size_t hlen, const char *needle, size_t nlen) {
    if (nlen == 0) {
        return hay;
    }
    if (hlen < nlen) {
        return NULL;
    }
    for (size_t i = 0; i + nlen <= hlen; i++) {
        if (hay[i] == needle[0] && !memcmp(hay + i, needle, nlen)) {
            return hay + i;
        }
    }
    return NULL;
}

static char *copy_n(const char *s, size_t n) {
    char *c = (char *) malloc(n + 1);
    if (!c) {
        return NULL;
    }
    memcpy(c, s, n);
    c[n] = '\0';
    return c;
}

/* ---- master playlist prefix rewrite ---- */
char *hls_adjust_master_playlist(const char *data, size_t datalen,
                                 const char *uri_prefix, const char *local_prefix) {
    if (!data || !uri_prefix || !local_prefix) {
        return NULL;
    }
    size_t n = strnlen(data, datalen);
    size_t plen = strlen(uri_prefix);
    size_t llen = strlen(local_prefix);
    if (plen == 0) {
        return copy_n(data, n);
    }
    sbuf_t sb = {0};
    const char *pos = data;
    const char *end = data + n;
    while (pos < end) {
        const char *hit = find_bytes(pos, (size_t) (end - pos), uri_prefix, plen);
        if (!hit) {
            break;
        }
        sb_append(&sb, pos, (size_t) (hit - pos));
        sb_append(&sb, local_prefix, llen);
        pos = hit + plen;
    }
    sb_append(&sb, pos, (size_t) (end - pos));
    return sb_finish(&sb);
}

/* ---- media playlist: condensed (YouTube) expansion ---- */

/* find KEY=" ... " inside line[0..linelen); returns malloc'd value */
static char *quoted_value(const char *line, size_t linelen, const char *key) {
    size_t klen = strlen(key);
    const char *k = find_bytes(line, linelen, key, klen);
    if (!k) {
        return NULL;
    }
    const char *p = k + klen;
    const char *lend = line + linelen;
    while (p < lend && *p != '"') {
        p++;
    }
    if (p >= lend) {
        return NULL;
    }
    p++;
    const char *q = p;
    while (q < lend && *q != '"') {
        q++;
    }
    if (q >= lend) {
        return NULL;
    }
    return copy_n(p, (size_t) (q - p));
}

static bool expand_uri_line(sbuf_t *sb, const char *line, size_t len, const char *base_uri,
                            const char *prefix_unused, size_t prefix_len,
                            const char **params, const size_t *param_len, int nparams) {
    (void) prefix_unused;
    const char *rest = line + prefix_len;
    const char *end = line + len;
    sb_puts(sb, base_uri);
    if (nparams == 0) {
        sb_append(sb, rest, (size_t) (end - rest));
        return true;
    }
    for (int i = 0; i < nparams; i++) {
        const char *piece_end = end;
        if (i != nparams - 1) {
            piece_end = (const char *) memchr(rest, '/', (size_t) (end - rest));
            if (!piece_end) {
                return false;   /* fewer values than params */
            }
        }
        sb_append(sb, "/", 1);
        sb_append(sb, params[i], param_len[i]);
        sb_append(sb, "/", 1);
        sb_append(sb, rest, (size_t) (piece_end - rest));
        rest = (i != nparams - 1) ? piece_end + 1 : end;
    }
    return true;
}

char *hls_expand_condensed_playlist(const char *playlist) {
    static const char cond[] = "#YT-EXT-CONDENSED-URL";
    if (!playlist) {
        return NULL;
    }
    size_t total = strlen(playlist);
    const char *hdr = strstr(playlist, "#EXTM3U\n");
    if (!hdr) {
        return copy_n(playlist, total);
    }
    const char *line = hdr + strlen("#EXTM3U\n");
    if (strncmp(line, cond, strlen(cond)) != 0) {
        return copy_n(playlist, total);
    }
    const char *nl = strchr(line, '\n');
    size_t hlen = nl ? (size_t) (nl - line) : strlen(line);

    char *base_uri = quoted_value(line, hlen, "BASE-URI=");
    char *params = quoted_value(line, hlen, "PARAMS=");
    char *prefix = quoted_value(line, hlen, "PREFIX=");
    const char **pstart = NULL;
    size_t *plen = NULL;
    char *result = NULL;
    sbuf_t sb = {0};
    int nparams = 0;
    size_t prefix_len = 0, params_len = 0;
    const char *first, *pos;

    if (!base_uri || !params || !prefix) {
        goto fallback;
    }
    prefix_len = strlen(prefix);
    params_len = strlen(params);

    if (params_len) {
        nparams = 1;
        for (const char *c = params; *c; c++) {
            if (*c == ',') {
                nparams++;
            }
        }
        pstart = (const char **) calloc((size_t) nparams, sizeof(char *));
        plen = (size_t *) calloc((size_t) nparams, sizeof(size_t));
        if (!pstart || !plen) {
            goto fallback;
        }
        const char *p = params;
        for (int i = 0; i < nparams; i++) {
            const char *comma = strchr(p, ',');
            pstart[i] = p;
            plen[i] = comma ? (size_t) (comma - p) : strlen(p);
            p = comma ? comma + 1 : p + plen[i];
        }
    }

    /* header section before the first chunk is copied verbatim */
    first = strstr(playlist, "#EXTINF:");
    if (!first) {
        goto fallback;
    }
    sb_append(&sb, playlist, (size_t) (first - playlist));
    pos = first;
    while (*pos) {
        const char *eol = strchr(pos, '\n');
        size_t llen = eol ? (size_t) (eol - pos) : strlen(pos);
        bool is_uri = llen > 0 && pos[0] != '#' && llen >= prefix_len &&
                      !strncmp(pos, prefix, prefix_len);
        if (is_uri) {
            if (!expand_uri_line(&sb, pos, llen, base_uri, prefix, prefix_len, pstart, plen, nparams)) {
                goto fallback;
            }
        } else {
            sb_append(&sb, pos, llen);
        }
        if (eol) {
            sb_append(&sb, "\n", 1);
            pos = eol + 1;
        } else {
            pos += llen;
        }
        if (sb.err) {
            goto fallback;
        }
    }
    result = sb_finish(&sb);
    if (result) {
        goto done;
    }
fallback:
    free(sb.buf);
    result = copy_n(playlist, total);
done:
    free(base_uri);
    free(params);
    free(prefix);
    free(pstart);
    free(plen);
    return result;
}

/* ---- media playlist analysis ---- */
int hls_analyze_media_playlist(const char *playlist, float *duration, bool *endlist) {
    static const char extinf[] = "#EXTINF:";
    int count = 0;
    double total = 0.0;
    *duration = 0.0f;
    *endlist = false;
    if (!playlist) {
        return 0;
    }
    const char *ptr = strstr(playlist, extinf);
    while (ptr) {
        char *end = NULL;
        ptr += strlen(extinf);
        double next = strtod(ptr, &end);
        if (isfinite(next) && next >= 0.0) {
            total += next;
        }
        count++;
        ptr = strstr(end && end > ptr ? end : ptr, extinf);
    }
    *duration = (float) total;
    *endlist = strstr(playlist, "#EXT-X-ENDLIST") != NULL;
    return count;
}

/* ---- master playlist -> media playlist URI table ---- */
int hls_create_media_uri_table(const char *url_prefix, const char *master,
                               char ***table, int *num_uri) {
    *table = NULL;
    *num_uri = 0;
    if (!url_prefix || !master || !*url_prefix) {
        return -1;
    }
    char **list = (char **) calloc(HLS_MAX_MEDIA_URIS, sizeof(char *));
    if (!list) {
        return -1;
    }
    int count = 0;
    const char *ptr = strstr(master, url_prefix);
    while (ptr && count < HLS_MAX_MEDIA_URIS) {
        const char *m3u8 = strstr(ptr, "m3u8");
        if (!m3u8) {
            break;
        }
        const char *end = m3u8 + 4;
        char *uri = copy_n(ptr, (size_t) (end - ptr));
        if (!uri) {
            for (int i = 0; i < count; i++) {
                free(list[i]);
            }
            free(list);
            return -1;
        }
        list[count++] = uri;
        ptr = strstr(end, url_prefix);
    }
    if (count == 0) {
        free(list);
        return 0;
    }
    *table = list;
    *num_uri = count;
    return 0;
}

/* ---- audio language selection ---- */
#define LANG_CODE_MAX 8
typedef struct {
    size_t start;      /* offset of line in master */
    size_t len;        /* line length including trailing '\n' */
    bool is_lang;
    bool is_default;
    char code[LANG_CODE_MAX];
    char *name;
} hls_line_t;

static bool parse_lang_line(const char *line, size_t len, hls_line_t *out) {
    if (len < 13 || strncmp(line, "#EXT-X-MEDIA:URI=", 17) != 0) {
        return false;
    }
    if (!find_bytes(line, len, "YT-EXT-AUDIO-CONTENT-ID=", 24)) {
        return false;
    }
    char *lang = quoted_value(line, len, "LANGUAGE=");
    char *name = quoted_value(line, len, "NAME=");
    if (!lang || !name || strlen(lang) >= LANG_CODE_MAX) {
        free(lang);
        free(name);
        return false;
    }
    strcpy(out->code, lang);
    out->name = name;
    const char *d = find_bytes(line, len, "DEFAULT=", 8);
    out->is_default = d && (size_t) (d - line) + 11 <= len && !strncmp(d + 8, "YES", 3);
    free(lang);
    return true;
}

static void free_lines(hls_line_t *lines, size_t n) {
    for (size_t i = 0; i < n; i++) {
        free(lines[i].name);
    }
    free(lines);
}

int hls_select_language(const char *master, const char *want_name, const char *pref_langs,
                        char **out_master, char **out_code, char **out_name) {
    if (!master) {
        return -1;
    }
    size_t total = strlen(master);
    if (total > HLS_MAX_PLAYLIST_BYTES) {
        return -1;
    }
    size_t cap = 64, n = 0;
    hls_line_t *lines = (hls_line_t *) calloc(cap, sizeof(hls_line_t));
    if (!lines) {
        return -1;
    }
    int n_lang_lines = 0;
    size_t pos = 0;
    while (pos < total) {
        const char *nl = (const char *) memchr(master + pos, '\n', total - pos);
        size_t len = nl ? (size_t) (nl - (master + pos)) + 1 : total - pos;
        if (n == cap) {
            hls_line_t *nl2 = (hls_line_t *) realloc(lines, cap * 2 * sizeof(hls_line_t));
            if (!nl2) {
                free_lines(lines, n);
                return -1;
            }
            memset(nl2 + cap, 0, cap * sizeof(hls_line_t));
            lines = nl2;
            cap *= 2;
        }
        hls_line_t *l = &lines[n++];
        l->start = pos;
        l->len = len;
        l->is_lang = parse_lang_line(master + pos, len, l);
        if (l->is_lang) {
            n_lang_lines++;
        }
        pos += len;
    }
    if (n_lang_lines == 0) {
        free_lines(lines, n);
        return 0;
    }

    /* distinct languages in order of first appearance */
    size_t *firsts = (size_t *) calloc((size_t) n_lang_lines, sizeof(size_t));
    if (!firsts) {
        free_lines(lines, n);
        return -1;
    }
    int nlang = 0;
    for (size_t i = 0; i < n; i++) {
        if (!lines[i].is_lang) {
            continue;
        }
        bool seen = false;
        for (int j = 0; j < nlang; j++) {
            if (!strcmp(lines[firsts[j]].code, lines[i].code)) {
                seen = true;
                break;
            }
        }
        if (!seen) {
            firsts[nlang++] = i;
        }
    }

    int chosen = -1, def = -1, by_name = -1;
    for (int j = 0; j < nlang; j++) {
        const hls_line_t *l = &lines[firsts[j]];
        if (want_name && !strcmp(want_name, l->name)) {
            by_name = j;
        }
        if (l->is_default && def < 0) {
            def = j;
        }
    }
    const char *p = pref_langs;
    while (p && chosen < 0) {
        if (strlen(p) < 2) {
            break;
        }
        for (int j = 0; j < nlang; j++) {
            if (!strncmp(lines[firsts[j]].code, p, 2)) {
                chosen = j;
                break;
            }
        }
        p = strchr(p, ':');
        if (p) {
            p++;
        }
    }
    if (chosen < 0) {
        chosen = by_name >= 0 ? by_name : (def >= 0 ? def : 0);
    }

    const hls_line_t *sel = &lines[firsts[chosen]];
    sbuf_t sb = {0};
    for (size_t i = 0; i < n; i++) {
        if (!lines[i].is_lang || !strcmp(lines[i].code, sel->code)) {
            sb_append(&sb, master + lines[i].start, lines[i].len);
        }
    }
    char *code = copy_n(sel->code, strlen(sel->code));
    char *name = copy_n(sel->name, strlen(sel->name));
    char *new_master = sb_finish(&sb);
    free(firsts);
    free_lines(lines, n);
    if (!code || !name || !new_master) {
        free(code);
        free(name);
        free(new_master);
        return -1;
    }
    *out_master = new_master;
    *out_code = code;
    *out_name = name;
    return 1;
}

/* ---- "text/parameters" /play body ---- */
#define HLS_MAX_LOCATION_LEN 8192

static bool key_equals(const char *k, size_t klen, const char *want) {
    size_t wlen = strlen(want);
    if (klen != wlen) {
        return false;
    }
    for (size_t i = 0; i < klen; i++) {
        char a = k[i], b = want[i];
        if (a >= 'A' && a <= 'Z') a = (char) (a - 'A' + 'a');
        if (b >= 'A' && b <= 'Z') b = (char) (b - 'A' + 'a');
        if (a != b) {
            return false;
        }
    }
    return true;
}

static double parse_number(const char *v, size_t vlen) {
    char tmp[64];
    if (vlen == 0 || vlen >= sizeof(tmp)) {
        return -1.0;
    }
    memcpy(tmp, v, vlen);
    tmp[vlen] = '\0';
    char *end = NULL;
    double d = strtod(tmp, &end);
    if (end == tmp || !isfinite(d) || d < 0.0) {
        return -1.0;
    }
    return d;
}

int hls_parse_text_parameters(const char *body, size_t len, hls_text_params_t *out) {
    if (!out) {
        return -1;
    }
    out->location = NULL;
    out->start_fraction = -1.0;
    out->start_seconds = -1.0;
    if (!body || len == 0 || len > HLS_MAX_PLAYLIST_BYTES) {
        return -1;
    }
    size_t pos = 0;
    while (pos < len) {
        const char *line = body + pos;
        const char *nl = (const char *) memchr(line, '\n', len - pos);
        size_t llen = nl ? (size_t) (nl - line) : len - pos;
        pos += nl ? llen + 1 : llen;
        const char *colon = (const char *) memchr(line, ':', llen);
        if (!colon) {
            continue;
        }
        const char *k = line, *kend = colon;
        const char *v = colon + 1, *vend = line + llen;
        while (k < kend && (*k == ' ' || *k == '\t' || *k == '\r')) k++;
        while (kend > k && (kend[-1] == ' ' || kend[-1] == '\t')) kend--;
        while (v < vend && (*v == ' ' || *v == '\t')) v++;
        while (vend > v && (vend[-1] == ' ' || vend[-1] == '\t' || vend[-1] == '\r')) vend--;
        size_t klen = (size_t) (kend - k), vlen = (size_t) (vend - v);
        if (key_equals(k, klen, "Content-Location")) {
            if (vlen == 0 || vlen > HLS_MAX_LOCATION_LEN) {
                free(out->location);
                out->location = NULL;
                return -1;
            }
            char *loc = copy_n(v, vlen);
            if (!loc) {
                return -1;
            }
            free(out->location);
            out->location = loc;
        } else if (key_equals(k, klen, "Start-Position-Seconds")) {
            out->start_seconds = parse_number(v, vlen);
        } else if (key_equals(k, klen, "Start-Position")) {
            out->start_fraction = parse_number(v, vlen);
        }
    }
    return 0;
}
