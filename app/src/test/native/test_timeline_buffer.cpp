/* Host tests for the Oboe path's TimelineBuffer (header-only, no Oboe needed). */
#include <cstdint>
#include <vector>
#include "check.h"
#include "timeline_buffer.h"

static const int SR = 44100, CH = 2;
static const int64_t MS = 1000000LL;

static std::vector<int16_t> pcmMs(int ms, int16_t v) {
    return std::vector<int16_t>((size_t) SR * ms / 1000 * CH, v);
}

static void test_ring() {
    SpscRing r(8);
    int16_t in[6] = {1, 2, 3, 4, 5, 6}, out[8] = {0};
    CHECK(r.write(in, 6) == 6);
    CHECK(r.write(in, 6) == 2);  /* only 2 free: excess dropped */
    CHECK(r.available() == 8);
    CHECK(r.read(out, 3) == 3 && out[0] == 1 && out[2] == 3);
    CHECK(r.write(in, 3) == 3);  /* wraps */
    CHECK(r.read(out, 8) == 8);
    CHECK(out[0] == 4 && out[1] == 5 && out[2] == 6 && out[3] == 1 && out[4] == 2 && out[5] == 1 && out[7] == 3);
    r.skip(100);
    CHECK(r.available() == 0);
}

static void test_prime_then_play() {
    TimelineBuffer t(SR, CH, 60, 95);  /* fixed 60 ms cushion */
    std::vector<int16_t> out((size_t) SR / 100 * CH, 7);  /* 10 ms */
    auto a = pcmMs(20, 5);
    t.write(a.data(), a.size(), 1000 * MS);
    t.read(out.data(), SR / 100);
    CHECK(out[0] == 0);  /* still priming: 20 ms < 60 ms cushion -> silence */
    auto b = pcmMs(50, 5);
    t.write(b.data(), b.size(), 1020 * MS);
    t.read(out.data(), SR / 100);
    CHECK(out[0] == 5);  /* cushion filled -> real audio */
}

static void test_gap_filled_with_silence() {
    TimelineBuffer t(SR, CH, 1, 95);
    auto a = pcmMs(20, 5);
    t.write(a.data(), a.size(), 1000 * MS);
    t.write(a.data(), a.size(), 1060 * MS);  /* 40 ms gap after first packet ends at 1020 */
    CHECK(t.debugInfo().metrics.silences == 1);
    CHECK(t.debugInfo().backlogMs >= 78 && t.debugInfo().backlogMs <= 82);  /* 20+40+20 */
}

static void test_late_packet_dropped() {
    TimelineBuffer t(SR, CH, 1, 95);
    auto a = pcmMs(20, 5);
    t.write(a.data(), a.size(), 1000 * MS);
    t.write(a.data(), a.size(), 1000 * MS);  /* overlaps: slot already passed */
    CHECK(t.debugInfo().metrics.drops == 1);
    CHECK(t.debugInfo().backlogMs >= 19 && t.debugInfo().backlogMs <= 21);
}

static void test_discontinuity_reanchors_without_silence() {
    TimelineBuffer t(SR, CH, 1, 95);
    auto a = pcmMs(20, 5);
    t.write(a.data(), a.size(), 1000 * MS);
    t.write(a.data(), a.size(), 9000 * MS);  /* >750 ms jump: written immediately */
    CHECK(t.debugInfo().metrics.silences == 0);
    CHECK(t.debugInfo().backlogMs >= 39 && t.debugInfo().backlogMs <= 41);
}

static void test_underrun_pads_silence_and_counts() {
    TimelineBuffer t(SR, CH, 1, 95);
    std::vector<int16_t> out((size_t) SR / 100 * CH, 9);
    auto a = pcmMs(5, 5);
    t.write(a.data(), a.size(), 1000 * MS);
    t.read(out.data(), SR / 100);  /* 10 ms wanted, 5 ms available */
    CHECK(out[0] == 5);
    CHECK(out[out.size() - 1] == 0);
    CHECK(t.debugInfo().metrics.underruns == 1);
}

static void test_unsynced_pts_passthrough() {
    TimelineBuffer t(SR, CH, 1, 95);
    auto a = pcmMs(20, 5);
    t.write(a.data(), a.size(), 0);  /* pts 0 = sender not NTP-synced: no gap logic */
    t.write(a.data(), a.size(), 0);
    CHECK(t.debugInfo().backlogMs >= 39 && t.debugInfo().backlogMs <= 41);
    CHECK(t.debugInfo().metrics.drops == 0);
}

static void test_flush() {
    TimelineBuffer t(SR, CH, 1, 95);
    auto a = pcmMs(20, 5);
    t.write(a.data(), a.size(), 1000 * MS);
    t.flushAndReprime();
    CHECK(t.debugInfo().backlogMs == 0);
}

/* Sender FLUSH: old PCM discarded, PCM decoded after the flush kept; jitter
 * history reset. adapted from jqssun/android-airplay-server PR #81 (GPL-3.0) */
static void test_sender_flush_keeps_new_audio() {
    TimelineBuffer t(44100, 2, 1, 95);
    std::vector<int16_t> oldSound(960, 111), newSound(960, 222), out(960, 7);
    t.write(oldSound.data(), oldSound.size(), 10 * NS_PER_SEC);
    t.flush();
    /* new audio may be decoded before the output thread sees the FLUSH */
    t.write(newSound.data(), newSound.size(), 20 * NS_PER_SEC);
    t.read(out.data(), 480);
    bool allNew = true;
    for (auto s : out) allNew = allNew && s == 222;
    CHECK(allNew);
    CHECK(t.debugInfo().metrics.drops == 0);
    /* repeated flush, even while empty, preserves the latest generation */
    bool ok = true;
    for (int i = 0; i < 100; i++) {
        t.write(oldSound.data(), oldSound.size(), (30 + i) * NS_PER_SEC);
        t.flush();
        t.write(oldSound.data(), oldSound.size(), (40 + i) * NS_PER_SEC);
        t.flush();
        t.write(newSound.data(), newSound.size(), (50 + i) * NS_PER_SEC);
        t.read(out.data(), 480);
        for (auto s : out) ok = ok && s == 222;
    }
    CHECK(ok);
    t.flush();
    t.read(out.data(), 480);
    bool silent = true;
    for (auto s : out) silent = silent && s == 0;
    CHECK(silent);

    DelayTracker tracker(44100, 2, 0, 95);
    for (int i = 0; i < 100; i++)
        tracker.observe(i * 10000000LL, i * 10000000LL + (i % 2) * 500000000LL, 10000000);
    const size_t tuned = tracker.target();
    tracker.reset();
    CHECK(tuned > 0);
    CHECK(tracker.target() == 0);  /* MIN_CUSHION_MS floor */
}

int main(void) {
    test_ring();
    test_prime_then_play();
    test_gap_filled_with_silence();
    test_late_packet_dropped();
    test_discontinuity_reanchors_without_silence();
    test_underrun_pads_silence_and_counts();
    test_unsynced_pts_passthrough();
    test_flush();
    test_sender_flush_keeps_new_audio();
    TEST_MAIN_END("timeline_buffer");
}
