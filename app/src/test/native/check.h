/* minimal test harness */
#ifndef CHECK_H
#define CHECK_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_checks = 0, g_failures = 0;

#define CHECK(cond) do { g_checks++; if (!(cond)) { g_failures++; \
    fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } } while (0)
#define CHECK_STR(actual, expected) do { g_checks++; const char *a_ = (actual); const char *e_ = (expected); \
    if (!a_ || strcmp(a_, e_) != 0) { g_failures++; \
    fprintf(stderr, "FAIL %s:%d: got \"%s\" want \"%s\"\n", __FILE__, __LINE__, a_ ? a_ : "(null)", e_); } } while (0)

#define TEST_MAIN_END(name) \
    printf("%s: %d checks, %d failures\n", name, g_checks, g_failures); \
    return g_failures ? 1 : 0

/* xorshift PRNG: deterministic fuzzing */
static unsigned long long g_rng = 88172645463325252ULL;
static unsigned rnd(void) { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return (unsigned) (g_rng >> 16); }
#endif
