/*
 * paired_clients.h — registry of senders that completed PIN pairing, persisted
 * to a file so they stay paired across receiver restarts (as on an Apple TV).
 *
 * Pure C (no JNI / Android log) so it is host-testable:
 *   app/src/test/native/test_paired_clients.c
 *
 * adapted from jqssun/android-airplay-server PR #80 (GPL-3.0)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef SCREENDOCK_PAIRED_CLIENTS_H
#define SCREENDOCK_PAIRED_CLIENTS_H

#include <pthread.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Registry file = receiver key file + this suffix ("airplay_key.pem.clients");
 * res/xml backup rules exclude it like the key itself. */
#define PAIRED_CLIENTS_SUFFIX ".clients"

/* Oldest entry is evicted when full. */
#define PAIRED_CLIENTS_MAX 32
/* base64 Ed25519 public key is 44 chars; allow some slack, reject anything longer. */
#define PAIRED_CLIENT_KEY_MAX 64

typedef struct paired_clients_s {
    pthread_mutex_t mu;
    bool  ready;                         /* mu initialised */
    char *keys[PAIRED_CLIENTS_MAX];      /* base64 client Ed25519 public keys, oldest first */
    int   count;
    char *path;                          /* backing file; NULL = memory only */
} paired_clients_t;

/* Zero + init the mutex. */
void paired_clients_init(paired_clients_t *pc);

/* Set the backing file and load it (missing file = empty registry). Malformed
 * lines are skipped. Returns the number of keys loaded, or -1 on bad args. */
int paired_clients_load(paired_clients_t *pc, const char *path);

/* true if pk (base64) has paired before. */
bool paired_clients_contains(paired_clients_t *pc, const char *pk);

/* Remember pk and rewrite the backing file atomically (tmp + rename).
 * Returns 1 = added, 0 = already known, -1 = invalid key, -2 = added but the
 * file could not be written (kept in memory). */
int paired_clients_add(paired_clients_t *pc, const char *pk);

/* Free everything and destroy the mutex. Safe on a zeroed or already-cleared struct. */
void paired_clients_clear(paired_clients_t *pc);

/* Exposed for tests: a plausible base64 key (charset + length). */
bool paired_clients_key_valid(const char *pk);

#ifdef __cplusplus
}
#endif

#endif /* SCREENDOCK_PAIRED_CLIENTS_H */
