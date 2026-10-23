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

echo "== hap: pair_ap transient pairing + encrypted RTSP (real raop/httpd on loopback)"
OSSL="${OPENSSL_PREFIX:-}"
if [ -z "$OSSL" ] && command -v brew >/dev/null 2>&1; then OSSL="$(brew --prefix openssl@3 2>/dev/null || true)"; fi
if [ -z "$OSSL" ] || [ ! -f "$OSSL/include/openssl/evp.h" ]; then
    echo "SKIP/FAIL: host OpenSSL 3 not found (set OPENSSL_PREFIX)" >&2
    status=1
else
    TP="$CPP/third_party"
    PLIST="$TP/libplist"
    PAIR="$TP/pair_ap"
    HB="$OUT/hapbuild"
    rm -rf "$HB"; mkdir -p "$HB"
    DEFS="-DNOHOLD -DSTANDALONE -D__STDC_CONSTANT_MACROS -DTARGET_POSIX -D_LINUX -DPLIST_210 -DPLIST_230 -DOPENSSL_API_COMPAT=0x10101000L -DTARGET_RT_LITTLE_ENDIAN=1"
    INC="-I$LIB -I$LIB/llhttp -I$LIB/playfair -I$PLIST/include -I$PLIST/src -I$PLIST/libcnary/include -I$PAIR -I$OSSL/include"
    PDEFS='-D__LITTLE_ENDIAN__=1 -DPACKAGE_VERSION="2.7.0" -DHAVE_STRDUP=1 -DHAVE_STRNDUP=1 -DHAVE_STRERROR=1 -DHAVE_GMTIME_R=1 -DHAVE_LOCALTIME_R=1 -DHAVE_TIMEGM=1 -DHAVE_STRPTIME=1 -DHAVE_MEMMEM=1 -DHAVE_TM_TM_GMTOFF=1 -DHAVE_TM_TM_ZONE=1'
    QUIET="-w -O1 -g -fsanitize=address,undefined"
    SRCS="$LIB/airplay_video.c $LIB/byteutils.c $LIB/compat.c $LIB/crypto.c $LIB/fairplay_playfair.c $LIB/hap_session.c $LIB/video_v2.c \
        $LIB/hls_playlist.c $LIB/http_request.c $LIB/http_response.c $LIB/httpd.c $LIB/logger.c $LIB/mirror_buffer.c \
        $LIB/netutils.c $LIB/pairing.c $LIB/raop.c $LIB/raop_buffer.c $LIB/raop_ntp.c $LIB/raop_rtp.c $LIB/raop_rtp_mirror.c \
        $LIB/srp.c $LIB/utils.c $LIB/llhttp/api.c $LIB/llhttp/http.c $LIB/llhttp/llhttp.c \
        $LIB/playfair/playfair.c $LIB/playfair/omg_hax.c $LIB/playfair/hand_garble.c $LIB/playfair/modified_md5.c $LIB/playfair/sap_hash.c"
    PAIRSRCS="$PAIR/pair.c $PAIR/pair-tlv.c $PAIR/pair_homekit.c $PAIR/pair_fruit_stub.c"
    PLISTSRCS="$PLIST/src/base64.c $PLIST/src/bytearray.c $PLIST/src/hashtable.c $PLIST/src/ptrarray.c $PLIST/src/time64.c \
        $PLIST/src/common.c $PLIST/src/xplist.c $PLIST/src/bplist.c $PLIST/src/jsmn.c $PLIST/src/jplist.c $PLIST/src/oplist.c \
        $PLIST/src/out-default.c $PLIST/src/out-plutil.c $PLIST/src/out-limd.c $PLIST/src/plist.c \
        $PLIST/libcnary/node.c $PLIST/libcnary/node_list.c"
    OBJS=""
    for f in $SRCS $PAIRSRCS "$PAIR/compat/sodium_compat.c" $PLISTSRCS; do
        o="$HB/$(echo "$f" | cksum | cut -d' ' -f1)_$(basename "$f").o"
        case "$f" in
            */libplist/*) EXTRA="$PDEFS" ;;
            */pair_ap/compat/*) EXTRA="-I$PAIR/compat" ;;
            */pair_ap/*) EXTRA="-DCONFIG_OPENSSL=1 -I$PAIR/compat" ;;
            *) EXTRA="$DEFS" ;;
        esac
        # shellcheck disable=SC2086
        $CC -std=gnu11 $QUIET $EXTRA $INC -c "$f" -o "$o" &
        OBJS="$OBJS $o"
        # throttle to ~8 parallel compiles
        [ "$(jobs -r | wc -l)" -ge 8 ] && wait
    done
    wait
    for o in $OBJS; do [ -f "$o" ] || { echo "hap build failed: missing $o" >&2; exit 2; }; done
    # dnssd.c is replaced by android_dnssd_shim.c on device; raop's /info path (never taken here) is the only user.
    # shellcheck disable=SC2086
    $CC -std=gnu11 $FLAGS $DEFS $INC -I"$HERE" "$HERE/test_hap.c" $OBJS -L"$OSSL/lib" -lcrypto -lm -lpthread \
        -Wl,-undefined,dynamic_lookup -o "$OUT/test_hap"
    "$OUT/test_hap" || status=1
fi

exit $status
