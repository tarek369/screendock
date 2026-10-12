/* Host tests for lib/hls_playlist.c and the playlist store in lib/airplay_video.c.
 * Built with ASan/UBSan: any out-of-bounds access by the fuzz cases aborts the run. */
#include "check.h"
#include <stdbool.h>
#include "hls_playlist.h"
#include "raop.h"
#include "airplay_video.h"

#define PREFIX "https://rr1.example.com/videoplayback"
#define LOCAL "http://localhost:7000"

static const char MASTER[] =
    "#EXTM3U\n"
    "#EXT-X-MEDIA:URI=\"" PREFIX "/a-en.m3u8\",TYPE=AUDIO,DEFAULT=YES,NAME=\"English\",LANGUAGE=\"en\",YT-EXT-AUDIO-CONTENT-ID=\"en.1\"\n"
    "#EXT-X-MEDIA:URI=\"" PREFIX "/a-de.m3u8\",TYPE=AUDIO,DEFAULT=NO,NAME=\"Deutsch\",LANGUAGE=\"de\",YT-EXT-AUDIO-CONTENT-ID=\"de.1\"\n"
    "#EXT-X-MEDIA:URI=\"" PREFIX "/b-en.m3u8\",TYPE=AUDIO,DEFAULT=YES,NAME=\"English\",LANGUAGE=\"en\",YT-EXT-AUDIO-CONTENT-ID=\"en.2\"\n"
    "#EXT-X-MEDIA:URI=\"" PREFIX "/b-de.m3u8\",TYPE=AUDIO,DEFAULT=NO,NAME=\"Deutsch\",LANGUAGE=\"de\",YT-EXT-AUDIO-CONTENT-ID=\"de.2\"\n"
    "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"a\"\n"
    PREFIX "/video.m3u8\n";

static const char CONDENSED[] =
    "#EXTM3U\n"
    "#YT-EXT-CONDENSED-URL:BASE-URI=\"https://cdn/base\",PARAMS=\"sq,rr\",PREFIX=\"https://x/\"\n"
    "#EXT-X-VERSION:6\n"
    "#EXTINF:5.0,\n"
    "https://x/1/aa\n"
    "#EXTINF:5.0,\n"
    "https://x/2/bb\n"
    "#EXT-X-ENDLIST\n";

static void test_adjust_master(void) {
    char *r = hls_adjust_master_playlist("a PFX b PFX c", 13, "PFX", "http://localhost:1");
    CHECK_STR(r, "a http://localhost:1 b http://localhost:1 c");
    free(r);
    /* local prefix shorter than remote prefix, empty prefix, no match, datalen shorter than string */
    r = hls_adjust_master_playlist("xxLONGPREFIXxx", 14, "LONGPREFIX", "L");
    CHECK_STR(r, "xxLxx"); free(r);
    r = hls_adjust_master_playlist("abc", 3, "", "L"); CHECK_STR(r, "abc"); free(r);
    r = hls_adjust_master_playlist("abc", 3, "zzz", "L"); CHECK_STR(r, "abc"); free(r);
    r = hls_adjust_master_playlist("abcPFXdef", 4, "PFX", "L"); CHECK_STR(r, "abcP"); free(r);
    CHECK(hls_adjust_master_playlist(NULL, 0, "a", "b") == NULL);
    /* real master */
    r = hls_adjust_master_playlist(MASTER, strlen(MASTER), PREFIX, LOCAL);
    CHECK(r && strstr(r, LOCAL "/video.m3u8") && !strstr(r, "rr1.example.com"));
    free(r);
}

static void test_expand_condensed(void) {
    char *r = hls_expand_condensed_playlist(CONDENSED);
    CHECK(r != NULL);
    if (r) {
        CHECK(strstr(r, "https://cdn/base/sq/1/rr/aa\n") != NULL);
        CHECK(strstr(r, "https://cdn/base/sq/2/rr/bb\n") != NULL);
        CHECK(strstr(r, "#EXT-X-ENDLIST") != NULL);
        CHECK(strstr(r, "https://x/") == NULL || strstr(r, "PREFIX=\"https://x/\"") != NULL);
    }
    free(r);
    /* non-condensed: plain copy */
    r = hls_expand_condensed_playlist("#EXTM3U\n#EXTINF:1,\nseg.ts\n"); CHECK_STR(r, "#EXTM3U\n#EXTINF:1,\nseg.ts\n"); free(r);
    /* not even a playlist */
    r = hls_expand_condensed_playlist("garbage"); CHECK_STR(r, "garbage"); free(r);
    r = hls_expand_condensed_playlist(""); CHECK_STR(r, ""); free(r);
    /* condensed header with missing attributes -> unexpanded copy, no crash */
    const char *bad1 = "#EXTM3U\n#YT-EXT-CONDENSED-URL:BASE-URI=\"abc\n#EXTINF:1,\nx\n";
    r = hls_expand_condensed_playlist(bad1); CHECK_STR(r, bad1); free(r);
    /* fewer values than params -> fallback copy */
    const char *bad2 = "#EXTM3U\n#YT-EXT-CONDENSED-URL:BASE-URI=\"b\",PARAMS=\"p,q,r\",PREFIX=\"u/\"\n#EXTINF:1,\nu/1/2\n";
    r = hls_expand_condensed_playlist(bad2); CHECK_STR(r, bad2); free(r);
    /* no PARAMS values at all */
    const char *np = "#EXTM3U\n#YT-EXT-CONDENSED-URL:BASE-URI=\"b\",PARAMS=\"\",PREFIX=\"u/\"\n#EXTINF:1,\nu/rest\n";
    r = hls_expand_condensed_playlist(np); CHECK(r && strstr(r, "brest")); free(r);
    /* no #EXTINF */
    const char *ne = "#EXTM3U\n#YT-EXT-CONDENSED-URL:BASE-URI=\"b\",PARAMS=\"p\",PREFIX=\"u/\"\n";
    r = hls_expand_condensed_playlist(ne); CHECK_STR(r, ne); free(r);
    /* huge param count must not overflow */
    size_t n = 100000;
    char *big = malloc(n * 2 + 200);
    strcpy(big, "#EXTM3U\n#YT-EXT-CONDENSED-URL:BASE-URI=\"b\",PARAMS=\"");
    for (size_t i = 0; i < n; i++) strcat(big, "p,");
    strcat(big, "p\",PREFIX=\"u/\"\n#EXTINF:1,\nu/1\n");
    r = hls_expand_condensed_playlist(big); CHECK(r != NULL); free(r); free(big);
}

static void test_analyze(void) {
    float d; bool end;
    int c = hls_analyze_media_playlist(CONDENSED, &d, &end);
    CHECK(c == 2 && d > 9.99f && d < 10.01f && end);
    c = hls_analyze_media_playlist("#EXTM3U\n#EXTINF:4.5,\na\n#EXTINF:abc,\nb\n#EXTINF:-3,\nc\n", &d, &end);
    CHECK(c == 3 && d > 4.49f && d < 4.51f && !end);   /* garbage/negative durations ignored: live playlist */
    c = hls_analyze_media_playlist("#EXTM3U\n#EXT-X-ENDLIST\n", &d, &end);
    CHECK(c == 0 && end);
    c = hls_analyze_media_playlist("#EXTINF:", &d, &end); CHECK(c == 1 && !end);
    c = hls_analyze_media_playlist("#EXTINF:1e999,\n", &d, &end); CHECK(c == 1 && d == 0.0f);
    c = hls_analyze_media_playlist(NULL, &d, &end); CHECK(c == 0);
}

static void test_uri_table(void) {
    char **t; int n;
    CHECK(hls_create_media_uri_table(PREFIX, MASTER, &t, &n) == 0);
    CHECK(n == 5);
    if (n == 5) {
        CHECK_STR(t[0], PREFIX "/a-en.m3u8");
        CHECK_STR(t[4], PREFIX "/video.m3u8");
        for (int i = 0; i < n; i++) free(t[i]);
        free(t);
    }
    /* prefix present but no "m3u8" after it */
    CHECK(hls_create_media_uri_table("PFX", "xx PFX/a.ts\n", &t, &n) == 0 && n == 0 && t == NULL);
    /* "m3u8" is the very last thing in the buffer (old code read past the NUL) */
    CHECK(hls_create_media_uri_table("PFX", "PFX/a.m3u8", &t, &n) == 0 && n == 1);
    if (n == 1) { CHECK_STR(t[0], "PFX/a.m3u8"); free(t[0]); free(t); }
    CHECK(hls_create_media_uri_table("", "x", &t, &n) < 0);
    CHECK(hls_create_media_uri_table("PFX", "no match", &t, &n) == 0 && n == 0);
    /* more URIs than the cap: truncated, not overflowed */
    size_t cap = (HLS_MAX_MEDIA_URIS + 50) * 12 + 1;
    char *many = calloc(1, cap);
    for (int i = 0; i < HLS_MAX_MEDIA_URIS + 50; i++) strcat(many, "PFX/a.m3u8\n");
    CHECK(hls_create_media_uri_table("PFX", many, &t, &n) == 0 && n == HLS_MAX_MEDIA_URIS);
    for (int i = 0; i < n; i++) free(t[i]);
    free(t); free(many);
}

static void test_language(void) {
    char *m, *code, *name;
    /* default language chosen */
    CHECK(hls_select_language(MASTER, NULL, NULL, &m, &code, &name) == 1);
    CHECK_STR(code, "en"); CHECK_STR(name, "English");
    CHECK(strstr(m, "a-en.m3u8") && strstr(m, "b-en.m3u8") && !strstr(m, "-de.m3u8") && strstr(m, "video.m3u8"));
    free(m); free(code); free(name);
    /* sender-chosen name */
    CHECK(hls_select_language(MASTER, "Deutsch", NULL, &m, &code, &name) == 1);
    CHECK_STR(code, "de"); CHECK(strstr(m, "a-de.m3u8") && !strstr(m, "-en.m3u8"));
    free(m); free(code); free(name);
    /* unknown name falls back to default instead of asserting */
    CHECK(hls_select_language(MASTER, "Klingon", NULL, &m, &code, &name) == 1);
    CHECK_STR(code, "en"); free(m); free(code); free(name);
    /* preference list wins */
    CHECK(hls_select_language(MASTER, NULL, "fr:de_DE:en", &m, &code, &name) == 1);
    CHECK_STR(code, "de"); free(m); free(code); free(name);
    CHECK(hls_select_language(MASTER, NULL, "x", &m, &code, &name) == 1);   /* too short: ignored */
    CHECK_STR(code, "en"); free(m); free(code); free(name);
    /* no language lines */
    CHECK(hls_select_language("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", NULL, NULL, &m, &code, &name) == 0);
    /* overlong LANGUAGE code (old code overflowed char[6]) and unbalanced quotes: treated as non-language lines */
    const char *evil =
        "#EXTM3U\n#EXT-X-MEDIA:URI=\"u\",DEFAULT=YES,NAME=\"n\",LANGUAGE=\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",YT-EXT-AUDIO-CONTENT-ID=\"x\"\n"
        "#EXT-X-MEDIA:URI=\"u\",DEFAULT=YES,NAME=\"n\n";
    CHECK(hls_select_language(evil, NULL, NULL, &m, &code, &name) == 0);
    /* DEFAULT= at the very end of the line */
    CHECK(hls_select_language("#EXT-X-MEDIA:URI=\"u\",NAME=\"n\",LANGUAGE=\"en\",YT-EXT-AUDIO-CONTENT-ID=\"x\",DEFAULT=", NULL, NULL, &m, &code, &name) == 1);
    free(m); free(code); free(name);
    /* no default flag anywhere: first language */
    const char *nodef = "#EXT-X-MEDIA:URI=\"u\",DEFAULT=NO,NAME=\"A\",LANGUAGE=\"aa\",YT-EXT-AUDIO-CONTENT-ID=\"x\"\n"
                        "#EXT-X-MEDIA:URI=\"u2\",DEFAULT=NO,NAME=\"B\",LANGUAGE=\"bb\",YT-EXT-AUDIO-CONTENT-ID=\"y\"\n";
    CHECK(hls_select_language(nodef, NULL, NULL, &m, &code, &name) == 1 && !strcmp(code, "aa"));
    free(m); free(code); free(name);
}

static void test_text_params(void) {
    hls_text_params_t p;
    const char *b = "Content-Location: http://h/x/master.m3u8\r\nStart-Position: 0.25\r\nOther: 1\r\n";
    CHECK(hls_parse_text_parameters(b, strlen(b), &p) == 0);
    CHECK_STR(p.location, "http://h/x/master.m3u8");
    CHECK(p.start_fraction > 0.24 && p.start_fraction < 0.26 && p.start_seconds < 0.0);
    free(p.location);
    const char *b2 = "content-location:http://h/m.m3u8\nSTART-POSITION-SECONDS:  12.5  \n";
    CHECK(hls_parse_text_parameters(b2, strlen(b2), &p) == 0);
    CHECK_STR(p.location, "http://h/m.m3u8"); CHECK(p.start_seconds == 12.5);
    free(p.location);
    CHECK(hls_parse_text_parameters("nothing here", 12, &p) == 0 && p.location == NULL);
    CHECK(hls_parse_text_parameters("", 0, &p) < 0);
    CHECK(hls_parse_text_parameters("Content-Location:", 17, &p) < 0);          /* empty value */
    CHECK(hls_parse_text_parameters("Start-Position: nan\n", 20, &p) == 0 && p.start_fraction < 0.0);
    /* non-terminated buffer: parser must honour len */
    char raw[] = {'C','o','n','t','e','n','t','-','L','o','c','a','t','i','o','n',':',' ','x','y','z','Q','Q'};
    CHECK(hls_parse_text_parameters(raw, 21, &p) == 0 && p.location && !strcmp(p.location, "xyz"));
    free(p.location);
}

/* ---- airplay_video store: duplicates, live refresh ---- */
static char *dupstr(const char *s) { char *c = malloc(strlen(s) + 1); strcpy(c, s); return c; }

static void test_store_and_live(void) {
    airplay_video_t *av = airplay_video_init((raop_t *) 0x1, 7000, NULL);
    CHECK(av != NULL);
    CHECK(airplay_video_init(NULL, 7000, NULL) == NULL);
    CHECK(set_apple_session_id(av, "0123456789", 10) == 0);
    CHECK(set_playback_uuid(av, "", 0) < 0 && set_playback_uuid(av, NULL, 5) < 0);
    CHECK(set_uri_prefix(av, "x", 100000) < 0);
    CHECK(create_media_data_store(av, NULL, 0) == 0 && get_num_media_uri(av) == 0);

    char **uris = calloc(3, sizeof(char *));
    uris[0] = dupstr("http://h/a.m3u8"); uris[1] = dupstr("http://h/a.m3u8"); uris[2] = dupstr("http://h/b.m3u8");
    CHECK(create_media_data_store(av, uris, 3) == 0); free(uris);

    int c = 1; float d = 1.0f; bool e = false;
    char *oob = dupstr("#EXTM3U\n#EXTINF:1,\nx\n");
    CHECK(store_media_playlist(av, oob, &c, &d, &e, 7) == -1);
    free(oob);
    char *junk = dupstr("zz");
    CHECK(store_media_playlist(av, junk, &c, &d, &e, -1) == -1); free(junk);
    CHECK(store_media_playlist(av, dupstr("#EXTM3U\n#EXTINF:1,\nx\n"), &c, &d, &e, 0) == 0);
    char *again = dupstr("again");
    CHECK(store_media_playlist(av, again, &c, &d, &e, 0) == -2);   /* already stored: caller keeps ownership */
    free(again);
    /* same uri, identical content => dedupe (used to assert() on mismatch) */
    CHECK(store_media_playlist(av, dupstr("#EXTM3U\n#EXTINF:1,\nx\n"), &c, &d, &e, 1) == 1);
    /* same-uri-different-content (live playlist re-sent) must not abort */
    char *other = dupstr("#EXTM3U\n#EXTINF:2,\ny\n");
    CHECK(store_media_playlist(av, other, &c, &d, &e, 2) == 0);

    int cc; float dd;
    CHECK(get_media_playlist(av, &cc, &dd, "/a.m3u8") != NULL);
    CHECK(get_media_playlist(av, &cc, &dd, "/nope.m3u8") == NULL);

    /* live refresh: playlist has no ENDLIST (endlist=false) so a poll may trigger a refresh */
    int idx = airplay_video_live_refresh_begin(av, "/b.m3u8");
    CHECK(idx == 2);
    CHECK(airplay_video_live_refresh_pending(av) == 2);
    CHECK(airplay_video_live_refresh_begin(av, "/b.m3u8") < 0);       /* one outstanding request at a time */
    CHECK(airplay_video_live_refresh_complete(av, dupstr("#EXTM3U\n#EXTINF:3,\nnew\n#EXT-X-ENDLIST\n")) == 0);
    CHECK(airplay_video_live_refresh_pending(av) == -1);
    char *now = get_media_playlist(av, &cc, &dd, "/b.m3u8");
    CHECK(now && strstr(now, "new") && cc == 1);
    CHECK(airplay_video_live_refresh_begin(av, "/b.m3u8") < 0);       /* ENDLIST now: VOD, never refreshed */
    CHECK(airplay_video_live_refresh_complete(av, dupstr("x")) == -1); /* nothing pending: rejected, no crash */
    CHECK(airplay_video_live_refresh_begin(av, "/missing.m3u8") < 0);
    airplay_video_destroy(av);
}

/* ---- fuzz: truncations and random mutations must never crash (ASan/UBSan) ---- */
static void fuzz_one(const char *src, size_t n) {
    char *buf = malloc(n + 1);
    for (int iter = 0; iter < 400; iter++) {
        size_t len = iter < 200 ? (size_t) iter % (n + 1) : n;
        memcpy(buf, src, len);
        buf[len] = '\0';
        if (iter >= 200 && n) {
            for (int m = 0; m < 1 + (int) (rnd() % 6); m++) {
                buf[rnd() % n] = (char) (rnd() % 5 == 0 ? '"' : rnd() % 4 == 0 ? '\n' : rnd() % 256);
            }
            for (size_t i = 0; i < n; i++) if (!buf[i]) buf[i] = ' ';
        }
        char *r = hls_expand_condensed_playlist(buf); free(r);
        r = hls_adjust_master_playlist(buf, len, "https://", "http://localhost:1"); free(r);
        float d; bool e; hls_analyze_media_playlist(buf, &d, &e);
        char **t; int cnt;
        if (hls_create_media_uri_table("https://", buf, &t, &cnt) == 0) { for (int i = 0; i < cnt; i++) free(t[i]); free(t); }
        char *m, *code, *name;
        if (hls_select_language(buf, "English", "en:de", &m, &code, &name) == 1) { free(m); free(code); free(name); }
        hls_text_params_t p;
        if (hls_parse_text_parameters(buf, len, &p) == 0) free(p.location);
    }
    free(buf);
}

int main(void) {
    test_adjust_master();
    test_expand_condensed();
    test_analyze();
    test_uri_table();
    test_language();
    test_text_params();
    test_store_and_live();
    fuzz_one(MASTER, strlen(MASTER));
    fuzz_one(CONDENSED, strlen(CONDENSED));
    const char *tp = "Content-Location: http://h/master.m3u8\nStart-Position: 0.5\n";
    fuzz_one(tp, strlen(tp));
    /* pure random garbage */
    for (int i = 0; i < 300; i++) {
        char g[256];
        size_t n = rnd() % sizeof(g);
        for (size_t k = 0; k < n; k++) g[k] = (char) (1 + rnd() % 255);
        g[n] = '\0';
        fuzz_one(g, n);
    }
    TEST_MAIN_END("hls_playlist");
}
