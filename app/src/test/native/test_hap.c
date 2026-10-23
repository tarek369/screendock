/* Host tests for AirPlay 2 HAP pairing + encrypted RTSP control channel.
 *
 * Part A drives pair_ap's CLIENT side against our server glue (hap_session) in-process.
 * Part B boots the REAL receiver core (raop_init/raop_init2 + httpd, the same code that
 * ships in libairplay_native.so, minus JNI) on a loopback socket and talks to it with raw
 * RTSP bytes: legacy pair-setup must still hit the legacy handler, v2 must pair
 * transiently with PIN 3939, and after pairing everything is ChaCha20-Poly1305 framed.
 *
 * Built by run_tests.sh (needs host OpenSSL 3). */
#include "check.h"

#include <stdint.h>
#include <stdbool.h>
#include <unistd.h>
#include <errno.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>

#include <openssl/evp.h>

#include "hap_session.h"
#include "pair.h"
#include "raop.h"
#include "pairing.h"

#define DEVICE_ID "00:11:22:33:44:55"
#define PIN "3939"

/* ------------------------------------------------------------------ Part A */

static void
test_routing(void)
{
    uint8_t legacy32[32];
    memset(legacy32, 0xAB, sizeof(legacy32));
    const uint8_t tlv_m1[] = { 0x06, 0x01, 0x01, 0x00, 0x01, 0x00, 0x13, 0x01, 0x10 };
    const uint8_t pv_legacy[] = { 0x01, 0x00, 0x00, 0x00, 1, 2, 3, 4 };
    const uint8_t pv_legacy0[] = { 0x00, 0x00, 0x00, 0x00, 1, 2, 3, 4 };
    const uint8_t pv_tlv[] = { 0x06, 0x01, 0x01, 0x03, 0x20, 0x01 };

    /* pair-setup */
    CHECK(hap_route_pair_setup(NULL, legacy32, 32) == HAP_ROUTE_LEGACY);
    CHECK(hap_route_pair_setup("", legacy32, 32) == HAP_ROUTE_LEGACY);
    CHECK(hap_route_pair_setup(NULL, tlv_m1, sizeof(tlv_m1)) == HAP_ROUTE_HAP);
    CHECK(hap_route_pair_setup("3", tlv_m1, sizeof(tlv_m1)) == HAP_ROUTE_HAP);
    CHECK(hap_route_pair_setup("4", tlv_m1, sizeof(tlv_m1)) == HAP_ROUTE_HAP);
    CHECK(hap_route_pair_setup(NULL, NULL, 0) == HAP_ROUTE_LEGACY);
    CHECK(hap_route_pair_setup(NULL, legacy32, 5) == HAP_ROUTE_LEGACY);   /* legacy handler rejects it */

    /* pair-verify */
    CHECK(hap_route_pair_verify(NULL, "1", pv_legacy, sizeof(pv_legacy)) == HAP_ROUTE_LEGACY);
    CHECK(hap_route_pair_verify(NULL, NULL, pv_legacy, sizeof(pv_legacy)) == HAP_ROUTE_LEGACY);
    CHECK(hap_route_pair_verify(NULL, NULL, pv_legacy0, sizeof(pv_legacy0)) == HAP_ROUTE_LEGACY);
    CHECK(hap_route_pair_verify(NULL, NULL, pv_tlv, sizeof(pv_tlv)) == HAP_ROUTE_HAP);
    CHECK(hap_route_pair_verify("4", NULL, pv_tlv, sizeof(pv_tlv)) == HAP_ROUTE_HAP);
    CHECK(hap_route_pair_verify("3", "1", pv_tlv, sizeof(pv_tlv)) == HAP_ROUTE_LEGACY);  /* X-Apple-PD wins */
}

/* Run transient pair-setup: pair_ap client <-> our server glue. Returns the client's cipher. */
static struct pair_cipher_context *
transient_pair_with(hap_session_t *srv, const char *pin)
{
    struct pair_setup_context *c = pair_setup_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, pin, NULL, NULL, "client-device");
    struct pair_cipher_context *cipher = NULL;
    uint8_t *rq = NULL, *rs = NULL;
    size_t rq_len = 0, rs_len = 0;
    struct pair_result *result = NULL;
    if (!c) return NULL;

    rq = pair_setup_request1(&rq_len, c);
    if (!rq) goto out;
    if (hap_pair_setup(srv, rq, rq_len, &rs, &rs_len) < 0) goto out;      /* M1 -> M2 */
    free(rq); rq = NULL;
    if (pair_setup_response1(c, rs, rs_len) < 0) goto out;
    free(rs); rs = NULL;
    CHECK(!hap_cipher_ready(srv));                                       /* not yet: needs M3/M4 */
    rq = pair_setup_request2(&rq_len, c);
    if (!rq) goto out;
    if (hap_pair_setup(srv, rq, rq_len, &rs, &rs_len) < 0) goto out;      /* M3 -> M4 */
    if (pair_setup_response2(c, rs, rs_len) < 0) goto out;
    if (pair_setup_result(NULL, &result, c) < 0) goto out;
    cipher = pair_cipher_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, PAIR_CHANNEL_CONTROL,
                             result->shared_secret, result->shared_secret_len, NULL);
 out:
    free(rq);
    free(rs);
    pair_setup_free(c);
    return cipher;
}

static void
test_transient_pairing_and_cipher(void)
{
    hap_session_t *srv = hap_session_new(DEVICE_ID);
    CHECK(srv != NULL);
    struct pair_cipher_context *cli = transient_pair_with(srv, PIN);
    CHECK(cli != NULL);
    CHECK(hap_cipher_ready(srv));
    CHECK(!hap_is_encrypted(srv));      /* final response must still go out in plaintext */
    hap_activate(srv);
    CHECK(hap_is_encrypted(srv));
    if (!cli) { hap_session_free(srv); return; }

    /* client -> server */
    const char *req = "OPTIONS * RTSP/1.0\r\nCSeq: 7\r\n\r\n";
    uint8_t *ct = NULL, *pt = NULL;
    size_t ct_len = 0, pt_len = 0;
    ssize_t done = pair_encrypt(&ct, &ct_len, (const uint8_t *) req, strlen(req), cli);
    CHECK(done == (ssize_t) strlen(req));
    ssize_t n = hap_decrypt(srv, ct, ct_len, &pt);
    CHECK(n == (ssize_t) strlen(req));
    CHECK(pt && memcmp(pt, req, strlen(req)) == 0);
    free(ct); free(pt);

    /* server -> client */
    const char *resp = "RTSP/1.0 200 OK\r\nCSeq: 7\r\n\r\n";
    ssize_t cl = hap_encrypt(srv, (const uint8_t *) resp, strlen(resp), &ct);
    CHECK(cl > (ssize_t) strlen(resp));
    done = pair_decrypt(&pt, &pt_len, ct, (size_t) cl, cli);
    CHECK(done == cl);
    CHECK(pt_len == strlen(resp) && memcmp(pt, resp, pt_len) == 0);
    free(ct); free(pt);

    /* multi-frame (> 1024 bytes) + byte-at-a-time partial delivery */
    uint8_t big[3000];
    for (size_t i = 0; i < sizeof(big); i++) big[i] = (uint8_t) (i * 7 + 3);
    pair_encrypt(&ct, &ct_len, big, sizeof(big), cli);
    uint8_t got[3000];
    size_t got_len = 0;
    for (size_t i = 0; i < ct_len; i++) {
        uint8_t *chunk = NULL;
        ssize_t m = hap_decrypt(srv, ct + i, 1, &chunk);
        CHECK(m >= 0);
        if (m > 0) {
            CHECK(got_len + (size_t) m <= sizeof(got));
            if (got_len + (size_t) m <= sizeof(got)) memcpy(got + got_len, chunk, (size_t) m);
            got_len += (size_t) m;
        }
        free(chunk);
    }
    CHECK(got_len == sizeof(big) && memcmp(got, big, sizeof(big)) == 0);
    free(ct);

    /* two frames glued into one read */
    uint8_t *ct2 = NULL;
    size_t ct2_len = 0;
    pair_encrypt(&ct, &ct_len, (const uint8_t *) "AAAA", 4, cli);
    pair_encrypt(&ct2, &ct2_len, (const uint8_t *) "BBBBBB", 6, cli);
    uint8_t *both = malloc(ct_len + ct2_len);
    memcpy(both, ct, ct_len); memcpy(both + ct_len, ct2, ct2_len);
    n = hap_decrypt(srv, both, ct_len + ct2_len, &pt);
    CHECK(n == 10 && pt && memcmp(pt, "AAAABBBBBB", 10) == 0);
    free(pt); free(both); free(ct); free(ct2);

    /* tampering is detected and refused */
    pair_encrypt(&ct, &ct_len, (const uint8_t *) "tamper-me", 9, cli);
    ct[5] ^= 0x01;
    CHECK(hap_decrypt(srv, ct, ct_len, &pt) < 0);
    free(ct);

    pair_cipher_free(cli);
    hap_session_free(srv);
}

static void
test_wrong_pin_and_garbage(void)
{
    hap_session_t *srv = hap_session_new(DEVICE_ID);
    struct pair_cipher_context *cli = transient_pair_with(srv, "1111");
    CHECK(cli == NULL);                  /* wrong PIN: proof fails */
    CHECK(!hap_cipher_ready(srv));
    hap_session_free(srv);

    srv = hap_session_new(DEVICE_ID);
    uint8_t *out = NULL; size_t out_len = 0;
    const uint8_t junk[] = { 0x01, 0x02, 0x03 };
    CHECK(hap_pair_setup(srv, junk, sizeof(junk), &out, &out_len) < 0);
    const uint8_t m3_first[] = { 0x06, 0x01, 0x03 };   /* M3 without M1: protocol error */
    CHECK(hap_pair_setup(srv, m3_first, sizeof(m3_first), &out, &out_len) < 0);
    CHECK(hap_pair_verify(srv, m3_first, sizeof(m3_first), &out, &out_len) < 0);
    uint8_t *pt = NULL;
    CHECK(hap_decrypt(srv, junk, sizeof(junk), &pt) < 0);   /* no cipher yet */
    hap_session_free(srv);
}

static void
test_normal_pairing_and_verify_with_identity(void)
{
    /* Persistent identity: pk advertised by the receiver == HAP accessory identity. */
    uint8_t seed[32];
    for (int i = 0; i < 32; i++) seed[i] = (uint8_t) (0x40 + i);
    hap_set_identity_seed(seed);

    uint8_t expect_pk[32];
    size_t l = 32;
    EVP_PKEY *k = EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, NULL, seed, 32);
    CHECK(k && EVP_PKEY_get_raw_public_key(k, expect_pk, &l) == 1);
    EVP_PKEY_free(k);
    uint8_t server_pk[32];
    pair_public_key_get(PAIR_SERVER_HOMEKIT, server_pk, DEVICE_ID);
    CHECK(memcmp(server_pk, expect_pk, 32) == 0);

    /* normal pair-setup (M1..M6), PIN 3939, then pair-verify on a fresh "connection" */
    hap_session_t *srv = hap_session_new(DEVICE_ID);
    struct pair_setup_context *c = pair_setup_new(PAIR_CLIENT_HOMEKIT_NORMAL, PIN, NULL, NULL, "client-device");
    uint8_t *rq = NULL, *rs = NULL;
    size_t rq_len = 0, rs_len = 0;
    const char *keys = NULL;
    struct pair_result *res = NULL;
    int ok = 0;

    rq = pair_setup_request1(&rq_len, c);
    if (!rq || hap_pair_setup(srv, rq, rq_len, &rs, &rs_len) < 0) goto setup_done;
    free(rq); rq = NULL;
    if (pair_setup_response1(c, rs, rs_len) < 0) goto setup_done;
    free(rs); rs = NULL;
    rq = pair_setup_request2(&rq_len, c);
    if (!rq || hap_pair_setup(srv, rq, rq_len, &rs, &rs_len) < 0) goto setup_done;
    free(rq); rq = NULL;
    if (pair_setup_response2(c, rs, rs_len) < 0) goto setup_done;
    free(rs); rs = NULL;
    rq = pair_setup_request3(&rq_len, c);
    if (!rq || hap_pair_setup(srv, rq, rq_len, &rs, &rs_len) < 0) goto setup_done;
    free(rq); rq = NULL;
    if (pair_setup_response3(c, rs, rs_len) < 0) goto setup_done;
    ok = pair_setup_result(&keys, &res, c) == 0;
 setup_done:
    free(rq); free(rs);
    CHECK(ok);
    CHECK(!hap_cipher_ready(srv));       /* normal setup yields no session key; verify follows */
    if (ok) {
        CHECK(memcmp(res->server_public_key, expect_pk, 32) == 0);   /* client saw OUR identity */

        hap_session_t *srv2 = hap_session_new(DEVICE_ID);
        struct pair_verify_context *v = pair_verify_new(PAIR_CLIENT_HOMEKIT_NORMAL, keys, NULL, NULL, "client-device");
        rq = pair_verify_request1(&rq_len, v);
        int vok = rq && hap_pair_verify(srv2, rq, rq_len, &rs, &rs_len) == 0;
        free(rq); rq = NULL;
        vok = vok && pair_verify_response1(v, rs, rs_len) == 0;   /* verifies the server signature */
        free(rs); rs = NULL;
        rq = vok ? pair_verify_request2(&rq_len, v) : NULL;
        vok = vok && rq && hap_pair_verify(srv2, rq, rq_len, &rs, &rs_len) == 0;
        free(rq); rq = NULL;
        vok = vok && pair_verify_response2(v, rs, rs_len) == 0;
        free(rs); rs = NULL;
        CHECK(vok);
        CHECK(hap_cipher_ready(srv2));
        struct pair_result *vr = NULL;
        struct pair_cipher_context *cli = NULL;
        if (vok && pair_verify_result(&vr, v) == 0) {
            cli = pair_cipher_new(PAIR_CLIENT_HOMEKIT_NORMAL, PAIR_CHANNEL_CONTROL, vr->shared_secret, vr->shared_secret_len, NULL);
        }
        CHECK(cli != NULL);
        if (cli) {
            hap_activate(srv2);
            uint8_t *ct = NULL, *pt = NULL; size_t ct_len = 0;
            pair_encrypt(&ct, &ct_len, (const uint8_t *) "verify-channel", 14, cli);
            CHECK(hap_decrypt(srv2, ct, ct_len, &pt) == 14 && memcmp(pt, "verify-channel", 14) == 0);
            free(ct); free(pt);
            pair_cipher_free(cli);
        }
        pair_verify_free(v);
        hap_session_free(srv2);
    }
    pair_setup_free(c);
    hap_session_free(srv);
    hap_set_identity_seed(NULL);
}

/* ------------------------------------------------------------------ Part B */

static void cb_audio(void *cls, raop_ntp_t *ntp, audio_decode_struct *d) { (void) cls; (void) ntp; (void) d; }
static void cb_log(void *cls, int level, const char *msg) { (void) cls; if (getenv("HAP_TEST_VERBOSE") && level <= 6) fprintf(stderr, "[raop] %s\n", msg); }
static void cb_video(void *cls, raop_ntp_t *ntp, video_decode_struct *d) { (void) cls; (void) ntp; (void) d; }

static int
connect_to(unsigned short port)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(port);
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, (struct sockaddr *) &sa, sizeof(sa)) != 0) { close(fd); return -1; }
    struct timeval tv = { 5, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return fd;
}

static void
send_all(int fd, const uint8_t *p, size_t n)
{
    while (n) {
        ssize_t w = send(fd, p, n, 0);
        if (w <= 0) return;
        p += w; n -= (size_t) w;
    }
}

/* Reads one RTSP response (headers + Content-Length body). If cipher != NULL the wire is
 * HAP-framed and decrypted with it. Returns malloc'd NUL-terminated message, *len set. */
static char *
read_response(int fd, struct pair_cipher_context *cipher, size_t *len)
{
    uint8_t raw[8192];
    size_t raw_len = 0;
    char *plain = calloc(1, 65536);
    size_t plain_len = 0;
    uint8_t *dec = NULL;
    size_t dec_len = 0;

    for (;;) {
        char *hdr_end = plain_len ? strstr(plain, "\r\n\r\n") : NULL;
        if (hdr_end) {
            size_t hdr_len = (size_t) (hdr_end - plain) + 4;
            size_t body = 0;
            const char *cl = strcasestr(plain, "Content-Length:");
            if (cl && cl < hdr_end) body = (size_t) atoi(cl + 15);
            if (plain_len >= hdr_len + body) { *len = hdr_len + body; return plain; }
        }
        ssize_t r = recv(fd, raw + raw_len, sizeof(raw) - raw_len, 0);
        if (r <= 0) { *len = plain_len; free(plain); return NULL; }
        if (cipher) {
            raw_len += (size_t) r;
            ssize_t used = pair_decrypt(&dec, &dec_len, raw, raw_len, cipher);
            if (used < 0) { free(plain); return NULL; }
            if (used > 0) { memmove(raw, raw + used, raw_len - (size_t) used); raw_len -= (size_t) used; }
            if (dec_len) { memcpy(plain + plain_len, dec, dec_len); plain_len += dec_len; plain[plain_len] = 0; }
            free(dec); dec = NULL; dec_len = 0;
        } else {
            memcpy(plain + plain_len, raw, (size_t) r);
            plain_len += (size_t) r;
            plain[plain_len] = 0;
        }
    }
}

/* body points into msg; returns NULL if no body */
static const uint8_t *
body_of(const char *msg, size_t len, size_t *blen)
{
    const char *e = strstr(msg, "\r\n\r\n");
    if (!e) { *blen = 0; return NULL; }
    size_t h = (size_t) (e - msg) + 4;
    *blen = len - h;
    return (const uint8_t *) msg + h;
}

static size_t
build_request(char *out, size_t cap, const char *method, const char *url, int cseq,
              const char *extra_hdr, const uint8_t *body, size_t blen)
{
    int n = snprintf(out, cap, "%s %s RTSP/1.0\r\nCSeq: %d\r\n%s", method, url, cseq, extra_hdr ? extra_hdr : "");
    if (blen) n += snprintf(out + n, cap - (size_t) n, "Content-Type: application/octet-stream\r\nContent-Length: %zu\r\n", blen);
    n += snprintf(out + n, cap - (size_t) n, "\r\n");
    if (blen) { memcpy(out + n, body, blen); n += (int) blen; }
    return (size_t) n;
}

/* One plaintext request/response exchange. */
static char *
rtsp_plain(int fd, const char *method, const char *url, int cseq, const char *hdr,
           const uint8_t *body, size_t blen, size_t *rlen)
{
    char req[4096];
    size_t n = build_request(req, sizeof(req), method, url, cseq, hdr, body, blen);
    send_all(fd, (const uint8_t *) req, n);
    return read_response(fd, NULL, rlen);
}

static void
test_real_receiver(void)
{
    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.audio_process = cb_audio;
    cbs.video_process = cb_video;
    raop_t *raop = raop_init(&cbs);
    CHECK(raop != NULL);
    if (!raop) return;
    raop_set_log_callback(raop, cb_log, NULL);
    raop_set_log_level(raop, 6);
    CHECK(raop_init2(raop, 1, DEVICE_ID, "") == 0);
    raop_set_v2_enabled(raop, true);
    unsigned short port = 0;
    CHECK(raop_start_httpd(raop, &port) > 0);
    CHECK(port != 0);
    if (!port) { raop_destroy(raop); return; }

    size_t rl = 0;
    char *resp = NULL;
    const uint8_t *b = NULL;
    size_t bl = 0;

    /* ---- legacy 32-byte pair-setup is still answered by the legacy handler ---- */
    {
        int fd = connect_to(port);
        uint8_t legacy[32];
        memset(legacy, 0x5A, sizeof(legacy));
        resp = rtsp_plain(fd, "POST", "/pair-setup", 1, NULL, legacy, sizeof(legacy), &rl);
        CHECK(resp && strstr(resp, "RTSP/1.0 200 OK"));
        b = resp ? body_of(resp, rl, &bl) : NULL;
        CHECK(bl == 32);                          /* legacy: the receiver's raw 32-byte Ed25519 pk */
        /* it must be exactly the pk UxPlay derives for this device (the "pk" in /info) */
        if (b && bl == 32) {
            pairing_t *p = pairing_init_generate(DEVICE_ID, "", &(int){0});
            unsigned char pk[32];
            pairing_get_public_key(p, pk);
            CHECK(memcmp(pk, b, 32) == 0);
            pairing_destroy(p);
        }
        free(resp);
        close(fd);
    }

    /* ---- v2 OFF: a TLV pair-setup does NOT go to HAP (production behaviour) ---- */
    {
        raop_set_v2_enabled(raop, false);
        int fd = connect_to(port);
        const uint8_t m1[] = { 0x06, 0x01, 0x01, 0x00, 0x01, 0x00, 0x13, 0x01, 0x10 };
        resp = rtsp_plain(fd, "POST", "/pair-setup", 1, "X-Apple-HKP: 3\r\n", m1, sizeof(m1), &rl);
        b = resp ? body_of(resp, rl, &bl) : NULL;
        CHECK(resp != NULL);
        CHECK(bl == 0);                           /* legacy handler rejected "Invalid pair-setup data" */
        free(resp);
        close(fd);
        raop_set_v2_enabled(raop, true);
    }

    /* ---- v2 ON: transient pairing over the wire, then an encrypted channel ---- */
    {
        int fd = connect_to(port);
        CHECK(fd >= 0);
        struct pair_setup_context *c = pair_setup_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, PIN, NULL, NULL, "client-device");
        size_t rq_len = 0;
        uint8_t *rq = pair_setup_request1(&rq_len, c);
        resp = rtsp_plain(fd, "POST", "/pair-setup", 1, "X-Apple-HKP: 3\r\n", rq, rq_len, &rl);
        free(rq);
        CHECK(resp && strstr(resp, "RTSP/1.0 200 OK"));
        b = resp ? body_of(resp, rl, &bl) : NULL;
        CHECK(b && bl > 0 && pair_setup_response1(c, b, bl) == 0);
        free(resp);

        rq = pair_setup_request2(&rq_len, c);
        resp = rtsp_plain(fd, "POST", "/pair-setup", 2, "X-Apple-HKP: 3\r\n", rq, rq_len, &rl);
        free(rq);
        CHECK(resp && strstr(resp, "RTSP/1.0 200 OK"));      /* final pairing response: PLAINTEXT */
        b = resp ? body_of(resp, rl, &bl) : NULL;
        CHECK(b && bl > 0 && pair_setup_response2(c, b, bl) == 0);   /* verifies the server proof M2 */
        free(resp);

        struct pair_result *result = NULL;
        CHECK(pair_setup_result(NULL, &result, c) == 0);
        struct pair_cipher_context *cli = result ? pair_cipher_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, PAIR_CHANNEL_CONTROL,
                                                                  result->shared_secret, result->shared_secret_len, NULL) : NULL;
        CHECK(cli != NULL);

        if (cli) {
            /* encrypted OPTIONS, delivered in 3 fragments (1 byte, a few, the rest) */
            char req[1024];
            size_t n = build_request(req, sizeof(req), "OPTIONS", "*", 3, NULL, NULL, 0);
            uint8_t *ct = NULL; size_t ct_len = 0;
            pair_encrypt(&ct, &ct_len, (const uint8_t *) req, n, cli);
            send_all(fd, ct, 1);
            usleep(60 * 1000);
            send_all(fd, ct + 1, 5);
            usleep(60 * 1000);
            send_all(fd, ct + 6, ct_len - 6);
            free(ct);
            resp = read_response(fd, cli, &rl);
            CHECK(resp != NULL);
            CHECK(resp && strstr(resp, "RTSP/1.0 200 OK"));
            CHECK(resp && strstr(resp, "CSeq: 3"));
            CHECK(resp && strstr(resp, "Public:"));          /* raop_handler_options ran on the decrypted request */
            free(resp);

            /* second encrypted request on the same connection (counters advance) */
            n = build_request(req, sizeof(req), "OPTIONS", "*", 4, NULL, NULL, 0);
            pair_encrypt(&ct, &ct_len, (const uint8_t *) req, n, cli);
            send_all(fd, ct, ct_len);
            free(ct);
            resp = read_response(fd, cli, &rl);
            CHECK(resp && strstr(resp, "CSeq: 4"));
            free(resp);

            /* a plaintext request on an encrypted connection must NOT be served */
            n = build_request(req, sizeof(req), "OPTIONS", "*", 5, NULL, NULL, 0);
            send_all(fd, (const uint8_t *) req, n);
            char buf[256];
            ssize_t r = recv(fd, buf, sizeof(buf), 0);
            CHECK(r <= 0 || memcmp(buf, "RTSP/1.0", 8) != 0);
            pair_cipher_free(cli);
        }
        pair_setup_free(c);
        close(fd);
    }

    /* ---- wrong PIN: pairing is refused, connection never becomes encrypted ---- */
    {
        int fd = connect_to(port);
        struct pair_setup_context *c = pair_setup_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, "0000", NULL, NULL, "client-device");
        size_t rq_len = 0;
        uint8_t *rq = pair_setup_request1(&rq_len, c);
        resp = rtsp_plain(fd, "POST", "/pair-setup", 1, "X-Apple-HKP: 3\r\n", rq, rq_len, &rl);
        free(rq);
        b = resp ? body_of(resp, rl, &bl) : NULL;
        CHECK(b && pair_setup_response1(c, b, bl) == 0);
        free(resp);
        rq = pair_setup_request2(&rq_len, c);
        resp = rtsp_plain(fd, "POST", "/pair-setup", 2, "X-Apple-HKP: 3\r\n", rq, rq_len, &rl);
        free(rq);
        b = resp ? body_of(resp, rl, &bl) : NULL;
        CHECK(!b || bl == 0 || pair_setup_response2(c, b, bl) < 0);
        free(resp);
        pair_setup_free(c);
        close(fd);
    }

    raop_destroy(raop);
}

int
main(void)
{
    test_routing();
    test_transient_pairing_and_cipher();
    test_wrong_pin_and_garbage();
    test_normal_pairing_and_verify_with_identity();
    test_real_receiver();
    TEST_MAIN_END("hap");
}
