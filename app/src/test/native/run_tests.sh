#!/bin/sh
# Host unit tests for the native AirPlay code (plain C, clang, ASan + UBSan).
#   sh app/src/test/native/run_tests.sh
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../../main/cpp"
LIB="$CPP/third_party/UxPlay/lib"
OUT="${TMPDIR:-/tmp}/screendock-native-tests"
CC="${CC:-clang}"
mkdir -p "$OUT"

# android_dmap_parse lives in a JNI-dependent file: extract just the pure parser.
awk '/^#define DMAP_STR_MAX/{p=1} /^static void cb_audio_set_metadata/{p=0} p' \
    "$CPP/android_raop_callbacks.c" > "$OUT/dmap_extract.inc"
[ -s "$OUT/dmap_extract.inc" ] || { echo "could not extract android_dmap_parse" >&2; exit 2; }

FLAGS="-std=c11 -g -O1 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -fsanitize=address,undefined -fno-sanitize-recover=undefined"
status=0

echo "== hls_playlist + airplay_video store"
$CC $FLAGS -I"$LIB" -I"$HERE" "$HERE/test_hls_playlist.c" "$LIB/hls_playlist.c" "$LIB/airplay_video.c" \
    -lm -o "$OUT/test_hls_playlist"
"$OUT/test_hls_playlist" || status=1

echo "== dmap parser"
$CC $FLAGS -I"$OUT" -I"$HERE" "$HERE/test_dmap.c" -o "$OUT/test_dmap"
"$OUT/test_dmap" || status=1

exit $status
