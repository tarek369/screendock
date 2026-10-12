/* Host tests for android_dmap_parse (src/main/cpp/android_raop_callbacks.c).
 * The function is extracted verbatim at build time (see run_tests.sh), because the
 * rest of that file needs JNI / android/log.h. */
#include "check.h"
#include <stdint.h>

#include "dmap_extract.inc"

static int put(unsigned char *o, const char *tag, const char *val, int vlen) {
    memcpy(o, tag, 4);
    o[4] = (unsigned char) (vlen >> 24); o[5] = (unsigned char) (vlen >> 16);
    o[6] = (unsigned char) (vlen >> 8);  o[7] = (unsigned char) vlen;
    memcpy(o + 8, val, (size_t) vlen);
    return 8 + vlen;
}

static int build(unsigned char *buf) {
    unsigned char *p = buf + 8;
    p += put(p, "minm", "Song Title", 10);
    p += put(p, "asar", "The Artist", 10);
    p += put(p, "asal", "An Album", 8);
    p += put(p, "astn", "\x00\x01", 2);   /* unknown tag skipped */
    int total = (int) (p - (buf + 8));
    memcpy(buf, "mlit", 4);
    buf[4] = (unsigned char) (total >> 24); buf[5] = (unsigned char) (total >> 16);
    buf[6] = (unsigned char) (total >> 8);  buf[7] = (unsigned char) total;
    return 8 + total;
}

static void free3(char *a, char *b, char *c) { free(a); free(b); free(c); }

int main(void) {
    unsigned char buf[512];
    int len = build(buf);
    char *t, *a, *al;
    CHECK(android_dmap_parse(buf, len, &t, &a, &al) == 0);
    CHECK_STR(t, "Song Title"); CHECK_STR(a, "The Artist"); CHECK_STR(al, "An Album");
    free3(t, a, al);

    /* every truncation must be rejected or handled without out-of-bounds access */
    for (int n = 0; n < len; n++) {
        int r = android_dmap_parse(buf, n, &t, &a, &al);
        CHECK(r == -1 || r == 0);
        free3(t, a, al);
    }
    /* wrong magic, NULL, tiny */
    CHECK(android_dmap_parse((const unsigned char *) "abcd\0\0\0\0", 8, &t, &a, &al) == -1);
    CHECK(android_dmap_parse(NULL, 100, &t, &a, &al) == -1);
    CHECK(android_dmap_parse(buf, 4, &t, &a, &al) == -1);

    /* oversized item length (0xFFFFFFFF, negative as int) and length > remaining */
    unsigned char bad[64];
    memcpy(bad, "mlit", 4); bad[4] = 0; bad[5] = 0; bad[6] = 0; bad[7] = 16;
    memcpy(bad + 8, "minm", 4); bad[12] = 0xFF; bad[13] = 0xFF; bad[14] = 0xFF; bad[15] = 0xFF;
    CHECK(android_dmap_parse(bad, 24, &t, &a, &al) == -1);
    free3(t, a, al);
    bad[12] = 0; bad[13] = 0; bad[14] = 0x10; bad[15] = 0;   /* 4096 > remaining */
    CHECK(android_dmap_parse(bad, 24, &t, &a, &al) == -1);
    /* container length larger than buffer / negative */
    bad[4] = 0xFF; CHECK(android_dmap_parse(bad, 24, &t, &a, &al) == -1);
    bad[4] = 0x7F; CHECK(android_dmap_parse(bad, 24, &t, &a, &al) == -1);

    /* very long title is capped at 1024 bytes */
    static unsigned char big[4096 + 16];
    char *longv = malloc(3000); memset(longv, 'x', 3000);
    int plen = put(big + 8, "minm", longv, 3000);
    memcpy(big, "mlit", 4);
    big[4] = (unsigned char) (plen >> 24); big[5] = (unsigned char) (plen >> 16);
    big[6] = (unsigned char) (plen >> 8);  big[7] = (unsigned char) plen;
    CHECK(android_dmap_parse(big, 8 + plen, &t, &a, &al) == 0 && t && strlen(t) == 1024);
    free3(t, a, al); free(longv);

    /* random byte flips on a valid buffer (ASan) */
    for (int i = 0; i < 20000; i++) {
        unsigned char m[512];
        memcpy(m, buf, (size_t) len);
        for (int k = 0; k < 1 + (int) (rnd() % 5); k++) m[rnd() % (unsigned) len] = (unsigned char) rnd();
        int n = (int) (rnd() % (unsigned) (len + 1));
        android_dmap_parse(m, n, &t, &a, &al);
        free3(t, a, al);
    }
    TEST_MAIN_END("dmap");
}
