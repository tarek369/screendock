/*
 * hap_session: AirPlay 2 HAP pairing + encrypted control channel glue.
 * Android port addition (SPDX-License-Identifier: GPL-3.0-or-later).
 * See hap_session.h for the protocol overview.
 */
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "hap_session.h"
#include "pair.h"

#define HAP_TLV_STATE 0x06
#define HAP_HDR_BYTES 2                 /* LE16 frame length */
#define HAP_TAG_BYTES 16
#define HAP_MAX_BUFFERED (1u << 20)     /* cap on undecoded bytes held per connection */
#define HAP_DEVICE_ID_MAX 64

struct hap_session_s {
    char device_id[HAP_DEVICE_ID_MAX];
    struct pair_setup_context *setup;
    struct pair_verify_context *verify;
    struct pair_cipher_context *cipher;
    bool active;

    uint8_t *inbuf;     /* bytes received but not yet forming a whole frame */
    size_t inlen;
    size_t incap;
};

/* ------------------------------------------------------------- routing --- */

hap_route_t
hap_route_pair_setup(const char *x_apple_hkp, const uint8_t *body, size_t len)
{
    if (x_apple_hkp && *x_apple_hkp) {
        return HAP_ROUTE_HAP;
    }
    /* Legacy AirPlay 1 pair-setup: exactly 32 raw bytes (sender's Ed25519 key). */
    if (len == 32) {
        return HAP_ROUTE_LEGACY;
    }
    if (body && len >= 3 && body[0] == HAP_TLV_STATE && body[1] == 0x01) {
        return HAP_ROUTE_HAP;
    }
    return HAP_ROUTE_LEGACY;
}

hap_route_t
hap_route_pair_verify(const char *x_apple_hkp, const char *x_apple_pd,
                      const uint8_t *body, size_t len)
{
    if (x_apple_pd && *x_apple_pd) {
        return HAP_ROUTE_LEGACY;
    }
    if (x_apple_hkp && *x_apple_hkp) {
        return HAP_ROUTE_HAP;
    }
    /* Legacy bodies are 4-byte header (first byte 0 or 1) + keys/signature. */
    if (body && len >= 3 && body[0] == HAP_TLV_STATE && body[1] == 0x01) {
        return HAP_ROUTE_HAP;
    }
    return HAP_ROUTE_LEGACY;
}

void
hap_set_identity_seed(const uint8_t seed[32])
{
    pair_server_identity_set(seed);
}

/* ------------------------------------------------------------- session --- */

hap_session_t *
hap_session_new(const char *device_id)
{
    if (!device_id || strlen(device_id) >= HAP_DEVICE_ID_MAX) {
        return NULL;
    }
    hap_session_t *s = calloc(1, sizeof(*s));
    if (!s) {
        return NULL;
    }
    snprintf(s->device_id, sizeof(s->device_id), "%s", device_id);
    return s;
}

void
hap_session_free(hap_session_t *s)
{
    if (!s) {
        return;
    }
    pair_setup_free(s->setup);
    pair_verify_free(s->verify);
    pair_cipher_free(s->cipher);
    if (s->inbuf) {
        memset(s->inbuf, 0, s->incap);
        free(s->inbuf);
    }
    free(s);
}

static int
hap_install_cipher(hap_session_t *s, const struct pair_result *result)
{
    if (!result || result->shared_secret_len == 0) {
        return -1;
    }
    struct pair_cipher_context *c = pair_cipher_new(PAIR_SERVER_HOMEKIT, PAIR_CHANNEL_CONTROL,
                                                    result->shared_secret, result->shared_secret_len, NULL);
    if (!c) {
        return -1;
    }
    pair_cipher_free(s->cipher);
    s->cipher = c;
    return 0;
}

int
hap_pair_setup(hap_session_t *s, const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len)
{
    const char *errmsg = NULL;
    struct pair_result *result = NULL;

    if (!s || !in || !out || !out_len) {
        return -1;
    }
    *out = NULL;
    *out_len = 0;

    int state = pair_state_get(PAIR_SERVER_HOMEKIT, &errmsg, in, in_len);
    if (state < 0) {
        return -1;
    }
    if (state == 1 || !s->setup) {
        /* M1 always starts a fresh exchange. A non-M1 first message is a protocol error. */
        if (state != 1) {
            return -1;
        }
        pair_setup_free(s->setup);
        s->setup = pair_setup_new(PAIR_SERVER_HOMEKIT, NULL, NULL, NULL, s->device_id);
        if (!s->setup) {
            return -1;
        }
    }
    if (pair_setup(out, out_len, s->setup, in, in_len) < 0) {
        free(*out);
        *out = NULL;
        *out_len = 0;
        return -1;
    }
    /* Transient pairing completes with M4 and yields the 64-byte SRP session key.
     * Normal pairing completes with M6 and yields no key (pair-verify follows). */
    if (pair_setup_result(NULL, &result, s->setup) == 0 && result->shared_secret_len > 0) {
        if (hap_install_cipher(s, result) < 0) {
            free(*out);
            *out = NULL;
            *out_len = 0;
            return -1;
        }
    }
    return 0;
}

int
hap_pair_verify(hap_session_t *s, const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len)
{
    const char *errmsg = NULL;
    struct pair_result *result = NULL;

    if (!s || !in || !out || !out_len) {
        return -1;
    }
    *out = NULL;
    *out_len = 0;

    int state = pair_state_get(PAIR_SERVER_HOMEKIT, &errmsg, in, in_len);
    if (state < 0) {
        return -1;
    }
    if (state == 1) {
        pair_verify_free(s->verify);
        /* get_cb = NULL: do not require the client to have been added via pair-add
         * (we keep no pairing database; setup is PIN-3939/transient-grade anyway). */
        s->verify = pair_verify_new(PAIR_SERVER_HOMEKIT, NULL, NULL, NULL, s->device_id);
        if (!s->verify) {
            return -1;
        }
    } else if (!s->verify) {
        return -1;
    }
    if (pair_verify(out, out_len, s->verify, in, in_len) < 0) {
        free(*out);
        *out = NULL;
        *out_len = 0;
        return -1;
    }
    if (pair_verify_result(&result, s->verify) == 0) {
        if (hap_install_cipher(s, result) < 0) {
            free(*out);
            *out = NULL;
            *out_len = 0;
            return -1;
        }
    }
    return 0;
}

bool hap_cipher_ready(const hap_session_t *s) { return s && s->cipher != NULL; }
bool hap_is_encrypted(const hap_session_t *s) { return s && s->active && s->cipher; }

void
hap_activate(hap_session_t *s)
{
    if (s && s->cipher) {
        s->active = true;
    }
}

/* ------------------------------------------------------------- framing --- */

static int
hap_inbuf_append(hap_session_t *s, const uint8_t *in, size_t in_len)
{
    if (in_len == 0) {
        return 0;
    }
    if (in_len > HAP_MAX_BUFFERED || s->inlen > HAP_MAX_BUFFERED - in_len) {
        return -1;
    }
    size_t need = s->inlen + in_len;
    if (need > s->incap) {
        size_t cap = s->incap ? s->incap : 2048;
        while (cap < need) {
            cap *= 2;
        }
        uint8_t *nb = realloc(s->inbuf, cap);
        if (!nb) {
            return -1;
        }
        s->inbuf = nb;
        s->incap = cap;
    }
    memcpy(s->inbuf + s->inlen, in, in_len);
    s->inlen = need;
    return 0;
}

ssize_t
hap_decrypt(hap_session_t *s, const uint8_t *in, size_t in_len, uint8_t **out)
{
    if (!s || !out || (!in && in_len)) {
        return -1;
    }
    *out = NULL;
    if (!s->cipher) {
        return -1;
    }
    if (hap_inbuf_append(s, in, in_len) < 0) {
        return -1;
    }
    if (s->inlen < HAP_HDR_BYTES) {
        return 0;   /* pair_decrypt rejects <2 bytes; wait for more */
    }

    uint8_t *plain = NULL;
    size_t plain_len = 0;
    ssize_t used = pair_decrypt(&plain, &plain_len, s->inbuf, s->inlen, s->cipher);
    if (used < 0) {
        /* pair_decrypt frees *plaintext itself on its error paths. */
        return -1;
    }
    if ((size_t) used > s->inlen) {
        free(plain);
        return -1;
    }
    if (used > 0) {
        memmove(s->inbuf, s->inbuf + used, s->inlen - (size_t) used);
        s->inlen -= (size_t) used;
    }
    if (plain_len == 0) {
        free(plain);
        return 0;
    }
    *out = plain;
    return (ssize_t) plain_len;
}

ssize_t
hap_encrypt(hap_session_t *s, const uint8_t *in, size_t in_len, uint8_t **out)
{
    size_t out_len = 0;

    if (!s || !out || !in || in_len == 0 || !s->cipher) {
        return -1;
    }
    *out = NULL;
    ssize_t done = pair_encrypt(out, &out_len, in, in_len, s->cipher);
    if (done < 0 || (size_t) done != in_len) {
        free(*out);
        *out = NULL;
        return -1;
    }
    return (ssize_t) out_len;
}
