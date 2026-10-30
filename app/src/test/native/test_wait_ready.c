/* Host tests for the direct-URL /play hold (cb_on_video_wait_ready + android_hls_update_info in
 * src/main/cpp/android_raop_callbacks.c). Functions are extracted verbatim by run_tests.sh;
 * the rest of that file needs JNI / android/log.h. */
#include "check.h"
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <time.h>
#include <unistd.h>

#define HLS_WAIT_READY_MS 400
#ifdef __APPLE__   /* no pthread_condattr_setclock: cond waits use the realtime clock */
#define CLOCK_MONOTONIC CLOCK_REALTIME
#endif
#define LOGW(...) ((void) 0)

typedef struct {
    pthread_mutex_t hls_mu;
    pthread_cond_t hls_cv;
    bool hls_mu_ready, hls_active, hls_ready;
    double hls_duration, hls_position;
    float hls_rate;
} android_callback_ctx_t;

#include "wait_extract.inc"

static void init(android_callback_ctx_t *c) {
    memset(c, 0, sizeof(*c));
    pthread_mutex_init(&c->hls_mu, NULL);
    pthread_condattr_t ca;
    pthread_condattr_init(&ca);
    pthread_cond_init(&c->hls_cv, &ca);
    c->hls_mu_ready = true;
    c->hls_active = true;
    c->hls_duration = 0.0;
}

static double now_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

static void *pusher(void *arg) {
    android_callback_ctx_t *c = (android_callback_ctx_t *) arg;
    usleep(60 * 1000);
    android_hls_update_info(c, 0.0, 0.0, 0.0f, false);   /* not ready yet: must not release */
    usleep(60 * 1000);
    android_hls_update_info(c, 123.4, 0.0, 1.0f, true);
    return NULL;
}

int main(void) {
    android_callback_ctx_t c;

    /* released by a ready push, well before the timeout */
    init(&c);
    pthread_t th; pthread_create(&th, NULL, pusher, &c);
    double t0 = now_ms();
    cb_on_video_wait_ready(&c);
    double dt = now_ms() - t0;
    pthread_join(th, NULL);
    CHECK(dt >= 100.0 && dt < 350.0);
    CHECK(c.hls_ready && c.hls_duration > 123.0);

    /* never ready: bounded by the timeout */
    init(&c);
    t0 = now_ms();
    cb_on_video_wait_ready(&c);
    dt = now_ms() - t0;
    CHECK(dt >= 350.0 && dt < 1500.0);

    /* player failure (duration < 0) releases immediately */
    init(&c);
    android_hls_update_info(&c, -1.0, 0.0, 0.0f, false);
    t0 = now_ms();
    cb_on_video_wait_ready(&c);
    CHECK(now_ms() - t0 < 100.0);

    /* stopped/inactive session does not block */
    init(&c);
    c.hls_active = false;
    t0 = now_ms();
    cb_on_video_wait_ready(&c);
    CHECK(now_ms() - t0 < 100.0);

    /* NULL / uninitialised ctx is a no-op */
    cb_on_video_wait_ready(NULL);
    memset(&c, 0, sizeof(c));
    cb_on_video_wait_ready(&c);

    TEST_MAIN_END("wait_ready");
}
