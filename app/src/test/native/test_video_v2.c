/* Host tests for AirPlay Video V2 command handling on the RTSP (RAOP) connection
 * (lib/video_v2.c).  Raw RTSP/1.0 requests are parsed by the real llhttp-based
 * http_request, routed + dispatched exactly as conn_request() in raop.c does it
 * (video_v2_route -> video_v2_dispatch -> CSeq/finish), and the raw response bytes
 * are inspected. */
#include "check.h"
#include <stdbool.h>
#include <stdint.h>
#include <strings.h>
#include <math.h>
#include <plist/plist.h>
#include "video_v2.h"

/* ---- fake receiver callbacks ---- */
typedef struct {
    int play_calls, stop_calls, rate_calls, scrub_calls, remove_calls;
    char location[VIDEO_V2_MAX_LOCATION];
    float start, rate, scrub;
    bool active;                 /* what on_video_acquire_playback_info reports */
    double duration, position;
    bool ready;
} fake_t;

static fake_t g;

static void cb_play(void *cls, const char *loc, const float start) {
    (void) cls; g.play_calls++; snprintf(g.location, sizeof(g.location), "%s", loc); g.start = start;
    g.active = true; g.duration = 120.0; g.position = start > 0 ? start : 0.0; g.ready = true;
}
static void cb_scrub(void *cls, const float p) { (void) cls; g.scrub_calls++; g.scrub = p; }
static void cb_rate(void *cls, const float r) { (void) cls; g.rate_calls++; g.rate = r; }
static void cb_stop(void *cls) { (void) cls; g.stop_calls++; g.active = false; }
static float cb_remove(void *cls) { (void) cls; g.remove_calls++; g.active = false; return 5.0f; }
static void cb_info(void *cls, playback_info_t *i) {
    (void) cls;
    if (!g.active) { i->duration = -1.0; i->position = -1.0; return; }
    i->duration = g.duration; i->position = g.position; i->rate = 1.0f; i->ready_to_play = g.ready;
    i->playback_buffer_empty = !g.ready; i->playback_buffer_full = g.ready;
    i->playback_likely_to_keep_up = g.ready;
    i->seek_start = 0.0; i->seek_duration = g.duration;
}

static raop_callbacks_t make_cbs(void) {
    raop_callbacks_t c;
    memset(&c, 0, sizeof(c));
    c.on_video_play = cb_play; c.on_video_scrub = cb_scrub; c.on_video_rate = cb_rate;
    c.on_video_stop = cb_stop; c.on_video_playlist_remove = cb_remove;
    c.on_video_acquire_playback_info = cb_info;
    return c;
}

/* ---- round trip ---- */
typedef struct {
    bool routed;
    char status_line[96];
    char *raw;                 /* whole response (malloc'd) */
    int raw_len;
    const char *body;          /* into raw */
    int body_len;
} reply_t;

static video_v2_t g_state;
static raop_callbacks_t g_cbs;
static logger_t *g_logger;
static bool g_enabled = true;

static const char *find_header(const reply_t *r, const char *name, char *out, size_t cap) {
    size_t nl = strlen(name);
    const char *p = strstr(r->raw, "\r\n");
    while (p) {
        p += 2;
        if (!strncasecmp(p, name, nl) && p[nl] == ':') {
            p += nl + 1;
            while (*p == ' ') p++;
            size_t n = strcspn(p, "\r\n");
            if (n >= cap) n = cap - 1;
            memcpy(out, p, n); out[n] = 0;
            return out;
        }
        p = strstr(p, "\r\n");
        if (p && p[2] == '\r') break;
    }
    return NULL;
}

static reply_t roundtrip(const char *head, const void *body, int body_len) {
    reply_t r;
    memset(&r, 0, sizeof(r));
    char *req = (char *) malloc(strlen(head) + (size_t) body_len + 64);
    int n = snprintf(req, strlen(head) + 64, "%sContent-Length: %d\r\n\r\n", head, body_len);
    if (body_len) memcpy(req + n, body, (size_t) body_len);
    http_request_t *request = http_request_init();
    http_request_add_data(request, req, n + body_len);
    free(req);
    CHECK(http_request_is_complete(request));
    if (http_request_has_error(request)) fprintf(stderr, "parse error: %s (%s)\n", http_request_get_error_name(request), http_request_get_error_description(request));
    CHECK(!http_request_has_error(request));

    const char *method = http_request_get_method(request);
    const char *url = http_request_get_url(request);
    const char *protocol = http_request_get_protocol(request);
    const char *cseq = http_request_get_header(request, "CSeq");
    http_response_t *response = http_response_create();
    http_response_init(response, protocol, 200, "OK");
    char *data = NULL; int datalen = 0;
    r.routed = video_v2_route(method, url);
    if (r.routed) {
        video_v2_dispatch(&g_state, &g_cbs, g_logger, g_enabled, request, response, &data, &datalen);
    }
    http_response_add_header(response, "Server", "AirTunes/test");
    if (cseq) http_response_add_header(response, "CSeq", cseq);
    http_response_finish(response, data, datalen);
    int len = 0;
    const char *out = http_response_get_data(response, &len);
    r.raw = (char *) malloc((size_t) len + 1);
    memcpy(r.raw, out, (size_t) len);
    r.raw[len] = 0;
    r.raw_len = len;
    size_t sl = strcspn(r.raw, "\r\n");
    snprintf(r.status_line, sizeof(r.status_line), "%.*s", (int) sl, r.raw);
    const char *sep = strstr(r.raw, "\r\n\r\n");
    r.body = sep ? sep + 4 : r.raw + len;
    r.body_len = (int) (r.raw + len - r.body);
    free(data);
    http_response_destroy(response);
    http_request_destroy(request);
    return r;
}

static void reply_free(reply_t *r) { free(r->raw); r->raw = NULL; }

static plist_t reply_plist(const reply_t *r) {
    plist_t p = NULL;
    if (r->body_len > 0) plist_from_memory(r->body, (uint32_t) r->body_len, &p, NULL);
    return p;
}

static double real_of(plist_t d, const char *k) {
    double v = -999.0; plist_t n = plist_dict_get_item(d, k);
    if (PLIST_IS_REAL(n)) plist_get_real_val(n, &v);
    return v;
}
static int bool_of(plist_t d, const char *k) {
    uint8_t v = 9; plist_t n = plist_dict_get_item(d, k);
    if (PLIST_IS_BOOLEAN(n)) plist_get_bool_val(n, &v);
    return v;
}

/* ---- request builders ---- */
static int to_bin(plist_t p, char **out) { uint32_t n = 0; plist_to_bin(p, out, &n); plist_free(p); return (int) n; }

static int play_plist(char **out, const char *loc, const char *uuid, double frac, double secs) {
    plist_t d = plist_new_dict();
    plist_dict_set_item(d, "Content-Location", plist_new_string(loc));
    if (uuid) plist_dict_set_item(d, "uuid", plist_new_string(uuid));
    if (frac >= 0) plist_dict_set_item(d, "Start-Position", plist_new_real(frac));
    if (secs >= 0) plist_dict_set_item(d, "Start-Position-Seconds", plist_new_real(secs));
    plist_dict_set_item(d, "rate", plist_new_real(1.0));
    return to_bin(d, out);
}

#define HDRS(m, u, cseq) m " " u " RTSP/1.0\r\nCSeq: " cseq "\r\nX-Apple-Session-ID: SESS-1\r\n" \
    "Content-Type: application/x-apple-binary-plist\r\n"

static void reset(void) {
    memset(&g, 0, sizeof(g));
    memset(&g_state, 0, sizeof(g_state));
    g_enabled = true;
}

static void test_routing(void) {
    CHECK(video_v2_route("POST", "/play"));
    CHECK(video_v2_route("GET", "/playback-info"));
    CHECK(video_v2_route("POST", "/rate?value=1.000000"));
    CHECK(video_v2_route("POST", "/scrub?position=3.5"));
    CHECK(video_v2_route("GET", "/scrub"));
    CHECK(video_v2_route("POST", "/stop"));
    CHECK(video_v2_route("PUT", "/setProperty?actionAtItemEnd"));
    CHECK(video_v2_route("POST", "/setProperty?actionAtItemEnd"));
    CHECK(video_v2_route("POST", "/getProperty?playbackAccessLog"));
    CHECK(video_v2_route("POST", "/action"));
    /* must not steal RAOP / pairing / mirroring requests */
    CHECK(!video_v2_route("POST", "/pair-setup"));
    CHECK(!video_v2_route("POST", "/fp-setup"));
    CHECK(!video_v2_route("GET", "/info"));
    CHECK(!video_v2_route("SETUP", "/play"));
    CHECK(!video_v2_route("POST", "/feedback"));
    CHECK(!video_v2_route("GET", "/play"));
    CHECK(!video_v2_route(NULL, "/play"));
}

static void test_play_plist(void) {
    reset();
    char *body; int n = play_plist(&body, "http://192.168.1.5:7000/movie.m3u8", "ABC-UUID", 0.25, -1);
    reply_t r = roundtrip(HDRS("POST", "/play", "7"), body, n);
    free(body);
    CHECK(r.routed);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    char v[64];
    CHECK(find_header(&r, "CSeq", v, sizeof(v)) && !strcmp(v, "7"));
    CHECK(g.play_calls == 1);
    CHECK_STR(g.location, "http://192.168.1.5:7000/movie.m3u8");
    CHECK(fabsf(g.start - (-0.25f)) < 1e-6f);          /* fraction is encoded negative */
    CHECK_STR(g_state.uuid, "ABC-UUID");
    reply_free(&r);

    /* absolute seconds wins over fraction */
    body = NULL; n = play_plist(&body, "https://example.com/a.mp4", NULL, 0.5, 42.5);
    r = roundtrip(HDRS("POST", "/play", "8"), body, n);
    free(body);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    CHECK(fabsf(g.start - 42.5f) < 1e-4f);
    CHECK_STR(g_state.uuid, "SESS-1");                 /* falls back to X-Apple-Session-ID */
    reply_free(&r);

    /* no start position -> 0 s */
    body = NULL; n = play_plist(&body, "http://h/x.mov", "u", -1, -1);
    r = roundtrip(HDRS("POST", "/play", "9"), body, n);
    free(body);
    CHECK(g.start == 0.0f);
    reply_free(&r);
}

static void test_play_text_and_errors(void) {
    reset();
    const char *txt = "Content-Location: http://mac.local:8080/v.mp4\nStart-Position: 0.5\n";
    reply_t r = roundtrip("POST /play RTSP/1.0\r\nCSeq: 3\r\nContent-Type: text/parameters\r\n", txt, (int) strlen(txt));
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    CHECK_STR(g.location, "http://mac.local:8080/v.mp4");
    CHECK(fabsf(g.start - (-0.5f)) < 1e-6f);
    reply_free(&r);

    /* garbage / missing / non-http locations are 400, never reach the player */
    int before = g.play_calls;
    r = roundtrip(HDRS("POST", "/play", "4"), "not a plist", 11);
    CHECK_STR(r.status_line, "RTSP/1.0 400 Bad Request");
    reply_free(&r);
    char *body; int n = play_plist(&body, "file:///etc/passwd", "u", -1, -1);
    r = roundtrip(HDRS("POST", "/play", "5"), body, n);
    free(body);
    CHECK_STR(r.status_line, "RTSP/1.0 400 Bad Request");
    reply_free(&r);
    r = roundtrip("POST /play RTSP/1.0\r\nCSeq: 6\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 400 Bad Request");
    reply_free(&r);
    CHECK(g.play_calls == before);

    /* video disabled -> 501, no callback */
    g_enabled = false;
    n = play_plist(&body, "http://h/x.mp4", "u", -1, -1);
    r = roundtrip(HDRS("POST", "/play", "10"), body, n);
    free(body);
    CHECK_STR(r.status_line, "RTSP/1.0 501 Not Implemented");
    CHECK(g.play_calls == before);
    reply_free(&r);
}

static void test_playback_info(void) {
    reset();
    /* idle: ready=false, no error */
    reply_t r = roundtrip("GET /playback-info RTSP/1.0\r\nCSeq: 11\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    plist_t p = reply_plist(&r);
    CHECK(PLIST_IS_DICT(p));
    CHECK(bool_of(p, "readyToPlay") == 0);
    CHECK(plist_dict_get_item(p, "error") == NULL);
    plist_free(p); reply_free(&r);

    char *body; int n = play_plist(&body, "http://h/x.m3u8", "u", -1, 10.0);
    r = roundtrip(HDRS("POST", "/play", "12"), body, n);
    free(body); reply_free(&r);

    r = roundtrip("GET /playback-info RTSP/1.0\r\nCSeq: 13\r\n", "", 0);
    char v[96];
    CHECK(find_header(&r, "Content-Type", v, sizeof(v)) && strstr(v, "binary-plist"));
    CHECK(find_header(&r, "CSeq", v, sizeof(v)) && !strcmp(v, "13"));
    p = reply_plist(&r);
    CHECK(PLIST_IS_DICT(p));
    CHECK(fabs(real_of(p, "duration") - 120.0) < 1e-6);
    CHECK(fabs(real_of(p, "position") - 10.0) < 1e-6);
    CHECK(fabs(real_of(p, "rate") - 1.0) < 1e-6);
    CHECK(bool_of(p, "readyToPlay") == 1);
    CHECK(bool_of(p, "playbackBufferEmpty") == 0);
    CHECK(bool_of(p, "playbackBufferFull") == 1);
    CHECK(bool_of(p, "playbackLikelyToKeepUp") == 1);
    plist_t loaded = plist_dict_get_item(p, "loadedTimeRanges");
    CHECK(PLIST_IS_ARRAY(loaded) && plist_array_get_size(loaded) == 1);
    CHECK(fabs(real_of(plist_array_get_item(loaded, 0), "start") - 10.0) < 1e-6);
    CHECK(fabs(real_of(plist_array_get_item(loaded, 0), "duration") - 110.0) < 1e-6);
    plist_t seek = plist_dict_get_item(p, "seekableTimeRanges");
    CHECK(PLIST_IS_ARRAY(seek) && plist_array_get_size(seek) == 1);
    CHECK(fabs(real_of(plist_array_get_item(seek, 0), "duration") - 120.0) < 1e-6);
    CHECK(plist_dict_get_item(p, "error") == NULL);
    plist_free(p); reply_free(&r);

    /* item ended/failed on its own: error dict, once */
    g.active = false;
    r = roundtrip("GET /playback-info RTSP/1.0\r\nCSeq: 14\r\n", "", 0);
    p = reply_plist(&r);
    plist_t err = plist_dict_get_item(p, "error");
    CHECK(PLIST_IS_DICT(err));
    CHECK(PLIST_IS_STRING(plist_dict_get_item(err, "localizedDescription")));
    CHECK(bool_of(p, "readyToPlay") == 0);
    plist_free(p); reply_free(&r);
    r = roundtrip("GET /playback-info RTSP/1.0\r\nCSeq: 15\r\n", "", 0);
    p = reply_plist(&r);
    CHECK(plist_dict_get_item(p, "error") == NULL);
    plist_free(p); reply_free(&r);
}

static void test_rate_scrub_stop(void) {
    reset();
    reply_t r = roundtrip("POST /rate?value=1.000000 RTSP/1.0\r\nCSeq: 20\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    CHECK(g.rate_calls == 1 && g.rate == 1.0f);
    reply_free(&r);
    r = roundtrip("POST /rate?value=0 RTSP/1.0\r\nCSeq: 21\r\n", "", 0);
    CHECK(g.rate_calls == 2 && g.rate == 0.0f);
    reply_free(&r);
    r = roundtrip("POST /rate?value=abc RTSP/1.0\r\nCSeq: 22\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 400 Bad Request");
    CHECK(g.rate_calls == 2);
    reply_free(&r);

    r = roundtrip("POST /scrub?position=33.5 RTSP/1.0\r\nCSeq: 23\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    CHECK(g.scrub_calls == 1 && fabsf(g.scrub - 33.5f) < 1e-4f);
    reply_free(&r);
    r = roundtrip("POST /scrub?position=-4 RTSP/1.0\r\nCSeq: 24\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 400 Bad Request");
    CHECK(g.scrub_calls == 1);
    reply_free(&r);

    g.active = true; g.duration = 90.0; g.position = 12.25;
    r = roundtrip("GET /scrub RTSP/1.0\r\nCSeq: 25\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    char v[64];
    CHECK(find_header(&r, "Content-Type", v, sizeof(v)) && !strcmp(v, "text/parameters"));
    CHECK(strstr(r.body, "duration: 90.0") != NULL);
    CHECK(strstr(r.body, "position: 12.25") != NULL);
    reply_free(&r);

    g_state.started = true;
    r = roundtrip("POST /stop RTSP/1.0\r\nCSeq: 26\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    CHECK(g.stop_calls == 1 && !g_state.started);
    CHECK(find_header(&r, "CSeq", v, sizeof(v)) && !strcmp(v, "26"));
    reply_free(&r);
}

static void test_properties(void) {
    reset();
    plist_t d = plist_new_dict();
    plist_dict_set_item(d, "value", plist_new_uint(1));
    char *body; int n = to_bin(d, &body);
    reply_t r = roundtrip(HDRS("POST", "/setProperty?actionAtItemEnd", "30"), body, n);   /* vendored llhttp rejects PUT over RTSP/1.0 */
    free(body);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    plist_t p = reply_plist(&r);
    CHECK(PLIST_IS_DICT(p) && PLIST_IS_UINT(plist_dict_get_item(p, "errorCode")));
    plist_free(p); reply_free(&r);

    r = roundtrip("POST /getProperty?playbackAccessLog RTSP/1.0\r\nCSeq: 31\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    p = reply_plist(&r);
    plist_t val = plist_dict_get_item(p, "value");
    CHECK(PLIST_IS_ARRAY(val) && plist_array_get_size(val) == 0);
    plist_free(p); reply_free(&r);

    r = roundtrip("POST /getProperty?whatever RTSP/1.0\r\nCSeq: 32\r\n", "", 0);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    reply_free(&r);
}

static int action_plist(char **out, const char *type, const char *loc, const char *uuid, bool in_params,
                        double secs) {
    plist_t item = plist_new_dict();
    if (loc) plist_dict_set_item(item, "Content-Location", plist_new_string(loc));
    if (uuid) plist_dict_set_item(item, "uuid", plist_new_string(uuid));
    if (secs >= 0) plist_dict_set_item(item, "Start-Position-Seconds", plist_new_real(secs));
    plist_t root = plist_new_dict();
    plist_dict_set_item(root, "type", plist_new_string(type));
    if (in_params) {
        plist_t params = plist_new_dict();
        plist_dict_set_item(params, "item", item);
        plist_dict_set_item(root, "params", params);
    } else {
        plist_dict_set_item(root, "item", item);
    }
    return to_bin(root, out);
}

static void test_action(void) {
    reset();
    char *body; int n = action_plist(&body, "playlistInsert", "http://h/q.m3u8", "Q-1", false, 7.0);
    reply_t r = roundtrip(HDRS("POST", "/action", "40"), body, n);
    free(body);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    CHECK(g.play_calls == 1);
    CHECK_STR(g.location, "http://h/q.m3u8");
    CHECK(fabsf(g.start - 7.0f) < 1e-4f);
    CHECK_STR(g_state.uuid, "Q-1");
    reply_free(&r);

    /* params.item form */
    n = action_plist(&body, "playlistInsert", "https://h/second.mp4", "Q-2", true, -1);
    r = roundtrip(HDRS("POST", "/action", "41"), body, n);
    free(body);
    CHECK(g.play_calls == 2);
    CHECK_STR(g.location, "https://h/second.mp4");
    reply_free(&r);

    /* remove of a different uuid is ignored; matching uuid stops */
    n = action_plist(&body, "playlistRemove", NULL, "OTHER", false, -1);
    r = roundtrip(HDRS("POST", "/action", "42"), body, n);
    free(body);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    CHECK(g.remove_calls == 0);
    reply_free(&r);
    n = action_plist(&body, "playlistRemove", NULL, "Q-2", false, -1);
    r = roundtrip(HDRS("POST", "/action", "43"), body, n);
    free(body);
    CHECK(g.remove_calls == 1 && !g_state.started);
    reply_free(&r);

    /* insert without Content-Location, bad body: 400 */
    n = action_plist(&body, "playlistInsert", NULL, "Q-3", false, -1);
    r = roundtrip(HDRS("POST", "/action", "44"), body, n);
    free(body);
    CHECK_STR(r.status_line, "RTSP/1.0 400 Bad Request");
    reply_free(&r);
    r = roundtrip(HDRS("POST", "/action", "45"), "xx", 2);
    CHECK_STR(r.status_line, "RTSP/1.0 400 Bad Request");
    CHECK(g.play_calls == 2);
    reply_free(&r);

    /* unknown type is acknowledged */
    n = action_plist(&body, "somethingNew", NULL, NULL, false, -1);
    r = roundtrip(HDRS("POST", "/action", "46"), body, n);
    free(body);
    CHECK_STR(r.status_line, "RTSP/1.0 200 OK");
    reply_free(&r);
}

static void test_fuzz(void) {
    /* random bodies must never crash any routed command */
    static const char *heads[] = {
        "POST /play RTSP/1.0\r\nCSeq: 1\r\n", "POST /action RTSP/1.0\r\nCSeq: 1\r\n",
        "POST /setProperty?x RTSP/1.0\r\nCSeq: 1\r\n", "POST /getProperty?x RTSP/1.0\r\nCSeq: 1\r\n",
    };
    for (int i = 0; i < 400; i++) {
        unsigned char buf[96];
        int len = (int) (rnd() % sizeof(buf));
        for (int j = 0; j < len; j++) buf[j] = (unsigned char) rnd();
        if (i % 3 == 0 && len > 6) memcpy(buf, "bplist", 6);
        reply_t r = roundtrip(heads[i % 4], buf, len);
        reply_free(&r);
    }
}

int main(void) {
    g_cbs = make_cbs();
    g_logger = logger_init();
    logger_set_level(g_logger, 0);
    test_routing();
    test_play_plist();
    test_play_text_and_errors();
    test_playback_info();
    test_rate_scrub_stop();
    test_properties();
    test_action();
    test_fuzz();
    TEST_MAIN_END("video_v2");
}
