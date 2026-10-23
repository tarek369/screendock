/* See sodium.h. SPDX-License-Identifier: GPL-3.0-or-later */
#include "sodium.h"

#include <string.h>
#include <openssl/evp.h>
#include <openssl/rand.h>

int sodium_init(void)
{
    return 0; /* libsodium: 0 = initialised now, 1 = already; -1 = failure */
}

static int ed25519_pk_from_seed(unsigned char *pk, const unsigned char *seed)
{
    size_t len = crypto_sign_PUBLICKEYBYTES;
    EVP_PKEY *key = EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, NULL, seed, crypto_sign_SEEDBYTES);
    if (!key) return -1;
    int ok = EVP_PKEY_get_raw_public_key(key, pk, &len) == 1 && len == crypto_sign_PUBLICKEYBYTES;
    EVP_PKEY_free(key);
    return ok ? 0 : -1;
}

int crypto_sign_seed_keypair(unsigned char *pk, unsigned char *sk, const unsigned char *seed)
{
    if (ed25519_pk_from_seed(pk, seed) != 0) return -1;
    memcpy(sk, seed, crypto_sign_SEEDBYTES);
    memcpy(sk + crypto_sign_SEEDBYTES, pk, crypto_sign_PUBLICKEYBYTES);
    return 0;
}

int crypto_sign_keypair(unsigned char *pk, unsigned char *sk)
{
    unsigned char seed[crypto_sign_SEEDBYTES];
    if (RAND_bytes(seed, sizeof(seed)) != 1) return -1;
    int ret = crypto_sign_seed_keypair(pk, sk, seed);
    OPENSSL_cleanse(seed, sizeof(seed));
    return ret;
}

int crypto_sign_ed25519_sk_to_pk(unsigned char *pk, const unsigned char *sk)
{
    memcpy(pk, sk + crypto_sign_SEEDBYTES, crypto_sign_PUBLICKEYBYTES);
    return 0;
}

int crypto_sign_detached(unsigned char *sig, unsigned long long *siglen_p,
                         const unsigned char *m, unsigned long long mlen, const unsigned char *sk)
{
    size_t siglen = crypto_sign_BYTES;
    int ok = 0;
    EVP_PKEY *key = EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, NULL, sk, crypto_sign_SEEDBYTES);
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (key && ctx && EVP_DigestSignInit(ctx, NULL, NULL, NULL, key) == 1 &&
        EVP_DigestSign(ctx, sig, &siglen, m, (size_t) mlen) == 1 && siglen == crypto_sign_BYTES) {
        ok = 1;
    }
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    if (!ok) return -1;
    if (siglen_p) *siglen_p = crypto_sign_BYTES;
    return 0;
}

int crypto_sign_verify_detached(const unsigned char *sig, const unsigned char *m,
                                unsigned long long mlen, const unsigned char *pk)
{
    int ok = 0;
    EVP_PKEY *key = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, NULL, pk, crypto_sign_PUBLICKEYBYTES);
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (key && ctx && EVP_DigestVerifyInit(ctx, NULL, NULL, NULL, key) == 1 &&
        EVP_DigestVerify(ctx, sig, crypto_sign_BYTES, m, (size_t) mlen) == 1) {
        ok = 1;
    }
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return ok ? 0 : -1;
}

static int x25519_base(unsigned char *q, const unsigned char *n)
{
    size_t len = crypto_box_PUBLICKEYBYTES;
    EVP_PKEY *key = EVP_PKEY_new_raw_private_key(EVP_PKEY_X25519, NULL, n, crypto_box_SECRETKEYBYTES);
    if (!key) return -1;
    int ok = EVP_PKEY_get_raw_public_key(key, q, &len) == 1 && len == crypto_scalarmult_BYTES;
    EVP_PKEY_free(key);
    return ok ? 0 : -1;
}

int crypto_box_keypair(unsigned char *pk, unsigned char *sk)
{
    if (RAND_bytes(sk, crypto_box_SECRETKEYBYTES) != 1) return -1;
    return x25519_base(pk, sk);
}

int crypto_scalarmult(unsigned char *q, const unsigned char *n, const unsigned char *p)
{
    static const unsigned char basepoint[crypto_scalarmult_BYTES] = { 9 };
    if (memcmp(p, basepoint, sizeof(basepoint)) == 0)
        return x25519_base(q, n);

    size_t len = crypto_scalarmult_BYTES;
    int ok = 0;
    EVP_PKEY *priv = EVP_PKEY_new_raw_private_key(EVP_PKEY_X25519, NULL, n, crypto_box_SECRETKEYBYTES);
    EVP_PKEY *peer = EVP_PKEY_new_raw_public_key(EVP_PKEY_X25519, NULL, p, crypto_box_PUBLICKEYBYTES);
    EVP_PKEY_CTX *ctx = (priv && peer) ? EVP_PKEY_CTX_new(priv, NULL) : NULL;
    if (ctx && EVP_PKEY_derive_init(ctx) == 1 && EVP_PKEY_derive_set_peer(ctx, peer) == 1 &&
        EVP_PKEY_derive(ctx, q, &len) == 1 && len == crypto_scalarmult_BYTES) {
        ok = 1;
    }
    EVP_PKEY_CTX_free(ctx);
    EVP_PKEY_free(peer);
    EVP_PKEY_free(priv);
    return ok ? 0 : -1;
}
