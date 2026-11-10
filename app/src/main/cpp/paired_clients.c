/*
 * paired_clients.c — see paired_clients.h.
 *
 * File format: one base64 client public key per line (same as
 * jqssun/android-airplay-server's "<keyfile>.clients").
 *
 * adapted from jqssun/android-airplay-server PR #80 (GPL-3.0)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "paired_clients.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void paired_clients_init(paired_clients_t *pc)
{
    if (!pc) return;
    memset(pc, 0, sizeof(*pc));
    pthread_mutex_init(&pc->mu, NULL);
    pc->ready = true;
}

bool paired_clients_key_valid(const char *pk)
{
    if (!pk) return false;
    size_t n = strlen(pk);
    if (n < 16 || n > PAIRED_CLIENT_KEY_MAX) return false;
    for (size_t i = 0; i < n; i++) {
        char c = pk[i];
        bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
                  c == '+' || c == '/' || c == '=';
        if (!ok) return false;
    }
    return true;
}

static int index_of_locked(const paired_clients_t *pc, const char *pk)
{
    for (int i = 0; i < pc->count; i++) {
        if (!strcmp(pc->keys[i], pk)) return i;
    }
    return -1;
}

/* Append (evicting the oldest when full). Takes ownership of dup. */
static void push_locked(paired_clients_t *pc, char *dup)
{
    if (pc->count == PAIRED_CLIENTS_MAX) {
        free(pc->keys[0]);
        memmove(&pc->keys[0], &pc->keys[1], (PAIRED_CLIENTS_MAX - 1) * sizeof(pc->keys[0]));
        pc->count--;
    }
    pc->keys[pc->count++] = dup;
}

static int save_locked(const paired_clients_t *pc)
{
    if (!pc->path) return 0;
    size_t len = strlen(pc->path) + sizeof(".tmp");
    char *tmp = (char *) malloc(len);
    if (!tmp) return -1;
    snprintf(tmp, len, "%s.tmp", pc->path);
    FILE *f = fopen(tmp, "w");
    if (!f) { free(tmp); return -1; }
    int err = 0;
    for (int i = 0; i < pc->count && !err; i++) {
        if (fprintf(f, "%s\n", pc->keys[i]) < 0) err = 1;
    }
    if (fclose(f) != 0) err = 1;
    if (err || rename(tmp, pc->path) != 0) {
        remove(tmp);
        free(tmp);
        return -1;
    }
    free(tmp);
    return 0;
}

int paired_clients_load(paired_clients_t *pc, const char *path)
{
    if (!pc || !pc->ready) return -1;
    pthread_mutex_lock(&pc->mu);
    free(pc->path);
    pc->path = path ? strdup(path) : NULL;
    int loaded = 0;
    FILE *f = path ? fopen(path, "r") : NULL;
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f)) {
            size_t n = strcspn(line, "\r\n");
            if (line[n] == '\0' && !feof(f)) {
                /* over-long line: skip the rest of it */
                int c;
                while ((c = fgetc(f)) != EOF && c != '\n') { }
                continue;
            }
            line[n] = '\0';
            if (!paired_clients_key_valid(line) || index_of_locked(pc, line) >= 0) continue;
            char *dup = strdup(line);
            if (!dup) break;
            push_locked(pc, dup);
            loaded++;
        }
        fclose(f);
    }
    pthread_mutex_unlock(&pc->mu);
    return loaded;
}

bool paired_clients_contains(paired_clients_t *pc, const char *pk)
{
    if (!pc || !pc->ready || !pk) return false;
    pthread_mutex_lock(&pc->mu);
    bool known = index_of_locked(pc, pk) >= 0;
    pthread_mutex_unlock(&pc->mu);
    return known;
}

int paired_clients_add(paired_clients_t *pc, const char *pk)
{
    if (!pc || !pc->ready || !paired_clients_key_valid(pk)) return -1;
    pthread_mutex_lock(&pc->mu);
    int rc;
    if (index_of_locked(pc, pk) >= 0) {
        rc = 0;
    } else {
        char *dup = strdup(pk);
        if (!dup) {
            rc = -1;
        } else {
            push_locked(pc, dup);
            rc = save_locked(pc) == 0 ? 1 : -2;
        }
    }
    pthread_mutex_unlock(&pc->mu);
    return rc;
}

void paired_clients_clear(paired_clients_t *pc)
{
    if (!pc || !pc->ready) return;
    pthread_mutex_lock(&pc->mu);
    for (int i = 0; i < pc->count; i++) {
        free(pc->keys[i]);
        pc->keys[i] = NULL;
    }
    pc->count = 0;
    free(pc->path);
    pc->path = NULL;
    pthread_mutex_unlock(&pc->mu);
    pthread_mutex_destroy(&pc->mu);
    pc->ready = false;
}
