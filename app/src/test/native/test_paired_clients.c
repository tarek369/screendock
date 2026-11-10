/* Host tests for the persisted PIN-paired sender registry (paired_clients.c).
 * adapted from jqssun/android-airplay-server PR #80 (GPL-3.0) */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "check.h"
#include "paired_clients.h"

static const char *K1 = "MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w7lshQhqowtrbLDFw4rXAxZuE=";
static const char *K2 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
static const char *K3 = "ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY=";

static char g_path[512];

static void write_file(const char *content) {
    FILE *f = fopen(g_path, "w");
    if (!f) { perror("fopen"); exit(2); }
    fputs(content, f);
    fclose(f);
}

static void test_validation(void) {
    CHECK(paired_clients_key_valid(K1));
    CHECK(paired_clients_key_valid(K2));
    CHECK(!paired_clients_key_valid(NULL));
    CHECK(!paired_clients_key_valid(""));
    CHECK(!paired_clients_key_valid("short"));
    CHECK(!paired_clients_key_valid("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG xwdHh8="));   /* space */
    CHECK(!paired_clients_key_valid("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=\n"));
    char longk[100]; memset(longk, 'A', sizeof(longk) - 1); longk[99] = '\0';
    CHECK(!paired_clients_key_valid(longk));
}

static void test_persist_and_reload(void) {
    unlink(g_path);
    paired_clients_t pc;
    paired_clients_init(&pc);
    CHECK(paired_clients_load(&pc, g_path) == 0);           /* missing file = empty */
    CHECK(!paired_clients_contains(&pc, K1));
    CHECK(paired_clients_add(&pc, K1) == 1);
    CHECK(paired_clients_add(&pc, K1) == 0);                 /* duplicate */
    CHECK(paired_clients_add(&pc, K2) == 1);
    CHECK(paired_clients_add(&pc, "not base64!") == -1);
    CHECK(paired_clients_contains(&pc, K1) && paired_clients_contains(&pc, K2));
    paired_clients_clear(&pc);
    paired_clients_clear(&pc);                               /* idempotent */

    /* "receiver restart": a fresh registry loads the same senders */
    paired_clients_init(&pc);
    CHECK(paired_clients_load(&pc, g_path) == 2);
    CHECK(paired_clients_contains(&pc, K1) && paired_clients_contains(&pc, K2));
    CHECK(!paired_clients_contains(&pc, K3));
    CHECK(!paired_clients_contains(&pc, NULL));
    paired_clients_clear(&pc);
}

static void test_malformed_file(void) {
    char content[2048];
    char longline[400]; memset(longline, 'B', sizeof(longline) - 1); longline[399] = '\0';
    snprintf(content, sizeof(content), "\n%s\r\ngarbage line\n%s\n%s\n%s", K1, longline, K1, K2);  /* no final newline */
    write_file(content);
    paired_clients_t pc;
    paired_clients_init(&pc);
    CHECK(paired_clients_load(&pc, g_path) == 2);            /* K1 (CRLF ok, dup skipped), K2 (no newline) */
    CHECK(paired_clients_contains(&pc, K1) && paired_clients_contains(&pc, K2));
    CHECK(!paired_clients_contains(&pc, longline));
    paired_clients_clear(&pc);
}

static void test_eviction_and_rewrite(void) {
    unlink(g_path);
    paired_clients_t pc;
    paired_clients_init(&pc);
    paired_clients_load(&pc, g_path);
    char keys[PAIRED_CLIENTS_MAX + 1][48];
    for (int i = 0; i <= PAIRED_CLIENTS_MAX; i++) {
        snprintf(keys[i], sizeof(keys[i]), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%04d=", i);
        CHECK(paired_clients_add(&pc, keys[i]) == 1);
    }
    CHECK(!paired_clients_contains(&pc, keys[0]));           /* oldest evicted */
    CHECK(paired_clients_contains(&pc, keys[PAIRED_CLIENTS_MAX]));
    paired_clients_clear(&pc);
    paired_clients_init(&pc);
    CHECK(paired_clients_load(&pc, g_path) == PAIRED_CLIENTS_MAX);   /* file rewritten, not appended */
    CHECK(!paired_clients_contains(&pc, keys[0]));
    paired_clients_clear(&pc);
}

static void test_unwritable_path(void) {
    paired_clients_t pc;
    paired_clients_init(&pc);
    paired_clients_load(&pc, "/nonexistent-dir/x.clients");
    CHECK(paired_clients_add(&pc, K3) == -2);                /* kept in memory */
    CHECK(paired_clients_contains(&pc, K3));
    paired_clients_clear(&pc);
    paired_clients_t zero;
    memset(&zero, 0, sizeof(zero));
    paired_clients_clear(&zero);                             /* zeroed struct: no-op */
    CHECK(!paired_clients_contains(&zero, K1));
    CHECK(paired_clients_add(&zero, K1) == -1);
}

int main(int argc, char **argv) {
    const char *dir = argc > 1 ? argv[1] : "/tmp";
    snprintf(g_path, sizeof(g_path), "%s/paired_test_%d.clients", dir, (int) getpid());
    test_validation();
    test_persist_and_reload();
    test_malformed_file();
    test_eviction_and_rewrite();
    test_unwritable_path();
    unlink(g_path);
    TEST_MAIN_END("paired_clients");
}
