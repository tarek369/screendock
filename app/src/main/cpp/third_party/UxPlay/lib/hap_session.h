/*
 * hap_session: AirPlay 2 HomeKit-style (HAP) pairing + encrypted RTSP control
 * channel, receiver side. Thin, HTTP-agnostic glue over pair_ap (MIT).
 *
 * Android port addition (SPDX-License-Identifier: GPL-3.0-or-later).
 *
 * Lifecycle (one hap_session_t per RTSP connection):
 *   1. hap_route_*()     decide whether a /pair-setup or /pair-verify request is
 *                        HAP (TLV8) or legacy UxPlay pairing.
 *   2. hap_pair_setup() / hap_pair_verify() process one TLV8 request body and
 *                        produce the TLV8 response body.
 *   3. When pairing yields a session key (transient: end of pair-setup M4;
 *      normal: end of pair-verify M4) hap_cipher_ready() turns true. The final
 *      pairing response MUST still go out in plaintext, so encryption starts only
 *      when the transport calls hap_activate() after sending that response.
 *   4. From then on hap_decrypt() / hap_encrypt() frame the byte stream
 *      (HAP framing: LE16 length | ChaCha20-Poly1305 ciphertext | 16-byte tag,
 *      up to 1024 plaintext bytes per frame, 64-bit LE frame counter as nonce).
 */
#ifndef HAP_SESSION_H
#define HAP_SESSION_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct hap_session_s hap_session_t;

typedef enum {
    HAP_ROUTE_LEGACY = 0,   /* hand the request to the original UxPlay handler */
    HAP_ROUTE_HAP    = 1    /* TLV8 HomeKit pairing */
} hap_route_t;

/* Classification. Header arguments may be NULL when the header is absent.
 *   pair-setup : legacy body is exactly 32 raw bytes; HAP body is TLV8 whose first
 *                byte is 0x06 (State) and carries "X-Apple-HKP: 3|4".
 *   pair-verify: legacy bodies start with 0x00/0x01 (+3 zero bytes) and carry
 *                "X-Apple-PD: 1"; HAP bodies are TLV8 starting with 0x06. */
hap_route_t hap_route_pair_setup(const char *x_apple_hkp, const uint8_t *body, size_t len);
hap_route_t hap_route_pair_verify(const char *x_apple_hkp, const char *x_apple_pd,
                                  const uint8_t *body, size_t len);

/* Use this 32-byte Ed25519 seed as the receiver's persistent HAP identity
 * (UxPlay's airplay_key.pem key). NULL reverts to a device_id-derived key. */
void hap_set_identity_seed(const uint8_t seed[32]);

hap_session_t *hap_session_new(const char *device_id);
void hap_session_free(hap_session_t *s);

/* Process one request body. On success returns 0 and *out is a malloc'd response
 * body (caller free()s). On failure returns -1 (connection should be dropped). */
int hap_pair_setup(hap_session_t *s, const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len);
int hap_pair_verify(hap_session_t *s, const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len);

bool hap_cipher_ready(const hap_session_t *s);   /* session key derived, cipher built */
bool hap_is_encrypted(const hap_session_t *s);   /* transport is encrypting right now */
void hap_activate(hap_session_t *s);             /* switch encryption on (idempotent) */

/* Feed raw received bytes. Partial frames are buffered inside the session.
 * Returns the number of plaintext bytes produced (>= 0; *out may be NULL when 0)
 * or -1 on authentication/format/overflow error. *out is malloc'd. */
ssize_t hap_decrypt(hap_session_t *s, const uint8_t *in, size_t in_len, uint8_t **out);

/* Frame + encrypt a plaintext buffer. Returns ciphertext length (malloc'd in *out)
 * or -1 on error. */
ssize_t hap_encrypt(hap_session_t *s, const uint8_t *in, size_t in_len, uint8_t **out);

#ifdef __cplusplus
}
#endif
#endif
