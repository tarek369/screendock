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

echo "== AirPlay Video V2 over RTSP (llhttp + libplist built from the vendored sources)"
PL="$CPP/third_party/libplist"
if [ ! -f "$PL/src/plist.c" ]; then echo "libplist submodule missing: git submodule update --init" >&2; exit 2; fi
PLSRC="$PL/src/base64.c $PL/src/bytearray.c $PL/src/hashtable.c $PL/src/ptrarray.c $PL/src/time64.c \
 $PL/src/common.c $PL/src/xplist.c $PL/src/bplist.c $PL/src/jsmn.c $PL/src/jplist.c $PL/src/oplist.c \
 $PL/src/out-default.c $PL/src/out-plutil.c $PL/src/out-limd.c $PL/src/plist.c \
 $PL/libcnary/node.c $PL/libcnary/node_list.c"
PLFLAGS="-std=gnu11 -g -O1 -w -fsanitize=address,undefined -D__LITTLE_ENDIAN__=1 -DPACKAGE_VERSION=\"2.7.0\" \
 -DHAVE_STRDUP=1 -DHAVE_STRNDUP=1 -DHAVE_STRERROR=1 -DHAVE_GMTIME_R=1 -DHAVE_LOCALTIME_R=1 -DHAVE_TIMEGM=1 \
 -DHAVE_STRPTIME=1 -DHAVE_MEMMEM=1 -DHAVE_TM_TM_GMTOFF=1 -DHAVE_TM_TM_ZONE=1 \
 -I$PL/include -I$PL/src -I$PL/libcnary/include"
PLOUT="$OUT/plist-obj"
rm -rf "$PLOUT" && mkdir -p "$PLOUT"
(cd "$PLOUT" && $CC $PLFLAGS -c $PLSRC)
$CC $FLAGS -I"$LIB" -I"$PL/include" -I"$HERE" "$HERE/test_video_v2.c" "$LIB/video_v2.c" "$LIB/hls_playlist.c" \
    "$LIB/http_request.c" "$LIB/http_response.c" "$LIB/logger.c" \
    "$LIB/llhttp/api.c" "$LIB/llhttp/http.c" "$LIB/llhttp/llhttp.c" \
    "$PLOUT"/*.o -lm -o "$OUT/test_video_v2"
"$OUT/test_video_v2" || status=1

exit $status
