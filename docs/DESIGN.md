# Technical Design — Android AirPlay Receiver (UxPlay-via-JNI)

> **Status:** Proposed — awaiting owner review (per brief §0). No code written yet beyond this doc.

This document captures the architecture for v1 as scoped by the project brief
(§3): a GPLv3 Android app that wraps UxPlay's C core via JNI to act as an
AirPlay / RAOP receiver (screen mirroring + audio streaming). It is informed by
study of the upstream UxPlay core (`FDH2/UxPlay`), the closest prior art
(`jqssun/android-airplay-server`), and the AirPlay/RAOP protocol.

Two corrections to the brief's working model surfaced during study and are worth
calling out before review:

1. **UxPlay's FairPlay is a static reply table + stored key message, not a live
   elliptic-curve exchange.** The live Curve25519/Ed25519 math belongs to the
   *separate* pairing layer (`/pair-setup` via `lib/pairing.c` + `lib/srp.c`).
   We reuse both verbatim and do not reimplement either.
2. **Video is AES-128-CTR** (key derived as
   `SHA256("AirPlayStreamKey"||streamConnectionID||audioAESkey)[:16]`). CBC is
   used only on the audio channel. The C core handles both; the JNI layer never
   touches crypto.

---

## 1. Goals & non-goals (restated for context)

**Goals (v1):** mDNS discovery (FR-1), FairPlay/RTSP session (FR-2), H.264/H.265
mirroring (FR-3), AAC+ALAC audio with sync (FR-4), clean connect/disconnect
lifecycle (FR-5), idle/active UI (FR-6), settings (FR-7), foreground service
(FR-8).

**Non-goals (v1):** PIN pairing UI, now-playing/cover-art metadata, HomeKit /
AirPlay 2 buffered playback, sender-side mirroring, and **DRM-protected content**
(FairPlay-DRM streams such as the Apple TV app are permanently undecryptable by
the open-source stack — handled gracefully + documented, never "fixed").

---

## 2. Dependency decisions

| Dependency | Source | How provided | License |
|---|---|---|---|
| UxPlay protocol core (`lib/*.c`, `lib/*.h`) | `FDH2/UxPlay` | Git submodule under `app/src/main/cpp/third_party/UxPlay` | GPLv3 |
| UxPlay `lib/mdnsd/` | vendored in UxPlay | **NOT compiled** — replaced by `android_dnssd_shim.c` (see §6) | LGPLv2.1+ |
| UxPlay `lib/llhttp/` | vendored in UxPlay | Compiled as static lib (HTTP parser) | MIT |
| UxPlay `lib/playfair/` | vendored in UxPlay | Compiled as static lib (FairPlay decrypt) | GPLv3 |
| UxPlay `renderers/`, `uxplay.cpp`, `lib/dns_sd/` | upstream | **Excluded** — GStreamer/X11/desktop-only | — |
| **libplist** | `libimobiledevice/libplist` | Git submodule, static lib | LGPLv2.1+ (see §9) |
| **OpenSSL libcrypto** | `viaduck/openssl-cmake` (OpenSSL 3.4.x) | Git submodule, static lib. Used by `lib/crypto.c` (AES-CTR/CBC/GCM, X25519, Ed25519, SHA, RAND) | Apache-2.0 |
| **ALAC** (software fallback) | `macosforge/alac` | Git submodule, compiled into the `.so` | Apache-2.0 |
| mDNS advertisement | Android platform | `NsdManager` in Kotlin (no native mDNS) | — |
| Video decode | Android platform | `MediaCodec` → `Surface` | — |
| Audio decode | Android platform | `MediaCodec` (AAC) + bundled ALAC fallback + `AudioTrack` | — |

**Why this set:** the protocol core is GPLv3 and statically linked, so the whole
app is a GPLv3 derivative (brief §2, hard constraint). Everything else is chosen
to be license-compatible and to minimize the surface we maintain. No zlib
(UxPlay `lib/` does not use it). No Avahi/Bonjour.

---

## 3. Module layout

```
Airplay/                                   # repo root (Gradle project)
├── settings.gradle.kts
├── build.gradle.kts                       # root
├── gradle/libs.versions.toml              # version catalog
├── .gitmodules                            # 4 submodules (UxPlay, libplist, openssl-cmake, alac)
├── docs/
│   ├── DESIGN.md                          # this file
│   └── TEST-CHECKLIST.md                  # (M4) §10 of the brief
├── LICENSE                                # GPLv3 full text
├── NOTICE                                 # third-party attributions
├── README.md
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── cpp/                        # ===== NATIVE LAYER =====
        │   │   ├── CMakeLists.txt
        │   │   ├── native_bridge.cpp       # JNI downward surface (Java -> C)
        │   │   ├── android_raop_callbacks.{c,h}  # raop_callbacks_t impl (C -> Java)
        │   │   ├── android_dnssd_shim.c    # TXT-record builder, no networking
        │   │   └── third_party/            # git submodules
        │   │       ├── UxPlay/
        │   │       ├── libplist/
        │   │       ├── openssl-cmake/
        │   │       └── alac/
        │   ├── kotlin/com/<pkg>/          # ===== APP LAYER =====
        │   │   ├── bridge/                 # NativeBridge, RaopCallbackHandler
        │   │   ├── discovery/              # NsdServiceManager (mDNS advertise)
        │   │   ├── service/               # AirPlayService (foreground service)
        │   │   ├── renderer/              # VideoRenderer, VideoPipeline, AudioRenderer
        │   │   ├── decode/                # codec config, NAL parsing helpers
        │   │   ├── sync/                  # NTP/PTS clock anchor, A/V sync
        │   │   ├── ui/                    # idle + render screens (Compose)
        │   │   ├── settings/              # DataStore-backed settings
        │   │   └── model/                 # state holders, enums
        │   └── res/                       # drawables, strings, themes
        └── test/ , androidTest/           # unit + instrumented tests (§10)
```

Package id and app name are **open questions** (brief §14) — pending owner
decision; placeholders below use `com.cressu.screendock`.

---

## 4. Layering & responsibilities

```
┌──────────────────────────────────────────────────────────────┐
│  UI (Compose Activity)                                       │
│  idle screen · full-screen render surface · settings         │
└───────────────▲───────────────────────────▲──────────────────┘
                │ state (StateFlow)          │ Surface / settings
┌───────────────┴───────────────────────────┴──────────────────┐
│  App layer (Kotlin)                                          │
│  AirPlayService (foreground) ── owns lifecycle ──┐           │
│   ├─ NsdServiceManager : advertise _airplay/_raop via NsdManager │
│   ├─ VideoRenderer : MediaCodec(decode) → VideoPipeline (EGL)  │
│   ├─ AudioRenderer : MediaCodec(AAC) / SW-ALAC → AudioTrack    │
│   ├─ SyncController : NTP/PTS anchor, A/V scheduling          │
│   └─ SettingsRepository : DataStore                          │
└───────────────▲───────────────────────────▲──────────────────┘
                │ JNI downward calls         │ JNI upward callbacks
┌───────────────┴───────────────────────────┴──────────────────┐
│  JNI bridge (C++/C)                                          │
│  native_bridge.cpp           android_raop_callbacks.c        │
│  (RegisterNatives table)     (raop_callbacks_t → RaopCallbackHandler) │
└───────────────▲───────────────────────────▲──────────────────┘
                │ raop_* / dnssd_* API       │ audio/video_process
┌───────────────┴───────────────────────────┴──────────────────┐
│  Native core (C, NDK)                                        │
│  UxPlay lib/*.c (raop, rtsp, rtp, rtp_mirror, ntp,           │
│    fairplay_playfair, pairing, srp, crypto, httpd, ...)      │
│  + android_dnssd_shim.c (TXT records only)                   │
│  + static: playfair, llhttp, libplist, OpenSSL, ALAC         │
└──────────────────────────────────────────────────────────────┘
```

**Key responsibilities:**

- **Native core** does: mDNS TXT construction (shim), RTSP/HTTP session,
  FairPlay handshake, pairing, RTP receive, AES-CTR/CBC decryption, NAL/ALAC
  framing. Emits *plaintext* frames upward.
- **JNI bridge** is the *only* coupling. Downward: start/stop/configure.
  Upward: lifecycle, video frame, audio frame, format/size. No protocol types
  leak across; structs are flattened to primitives + byte arrays.
- **App layer** owns all platform policy: MediaCodec, AudioTrack, NsdManager,
  foreground service, permissions, UI. No business logic in native.

---

## 5. JNI surface (the contract that must be stable early)

### 5.1 Downward (Java → native) — `NativeBridge.kt` / `native_bridge.cpp`

| Kotlin `external fun` | Behavior |
|---|---|
| `nativeInit(cb: RaopCallbackHandler, hwAddr: ByteArray, name: String, keyFile: String, requirePin: Boolean): Long` | `raop_init` + `raop_init2` (gen Ed25519 key, persist to `keyFile`), build device-id string. Returns opaque `server_ctx_t*` as `Long`. |
| `nativeStart(handle: Long, requestedPort: Int): Int` | `raop_set_*_ports`, `raop_start_httpd`, populate shim TXT records, return bound TCP port (for NsdManager to advertise). |
| `nativeStop(handle: Long)` | `raop_stop_httpd`, unregister. |
| `nativeDestroy(handle: Long)` | `raop_destroy`, free ctx. |
| `nativeSetDisplaySize(handle: Long, w: Int, h: Int, fps: Int)` | `raop_set_plist` (width/height/refreshRate/maxFPS) — what senders negotiate against. |
| `nativeSetH265Enabled(handle: Long, enabled: Boolean)` | Toggle `features` bit 42 (`SupportsScreenMultiCodec`) + codec whitelist. |
| `nativeSetCodecs(handle: Long, alac: Boolean, aac: Boolean)` | Build RAOP `cn` TXT list (0,1,2,3 numbering). |
| `nativeGetRaopTxtRecords(handle: Long): Map<String,String>` | Expose shim's RAOP TXT map to NsdManager. |
| `nativeGetAirplayTxtRecords(handle: Long): Map<String,String>` | Expose shim's AirPlay TXT map. |
| `nativeGetRaopServiceName(handle: Long): String` | `"<hexmac>@<name>"` instance name. |
| `nativeAlacInit(frameLen, ch, bitDepth, pb, mb, kb): Long` | Build 24-byte `ALACSpecificConfig`, `new ALACDecoder`, return handle. |
| `nativeAlacDecode(handle: Long, input: ByteArray): ByteArray` | Decode one ALAC packet → PCM. |
| `nativeAlacDestroy(handle: Long)` | Release ALAC decoder. |

PIN (`nativeSetRequirePin`, client-allowlist) is **designed-in** but not wired to
UI for v1 (brief §3).

### 5.2 Upward (native → Java) — `RaopCallbackHandler.kt`

Implemented by `AirPlayService`. JNI descriptors are load-bearing — frozen here
so C and Kotlin stay in lockstep:

```kotlin
interface RaopCallbackHandler {
    // lifecycle
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)

    // video: data = one or more Annex-B NALUs (00 00 00 01 prefixed), already decrypted
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
    fun onVideoSize(w: Float, h: Float, par: Float, rotation: Float)

    // audio: data = one AAC-ELD/LC frame or one ALAC packet (post-decrypt);
    //        ct: 2=ALAC, 4=AAC-LC, 8=AAC-ELC
    fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int)
    fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean)
    fun onVolumeChange(volume: Float)

    // PIN (v2) — stubbed for v1
    fun onDisplayPin(pin: String)
}
```

### 5.3 Marshalling decision

- **v1: `byte[]` + `SetByteArrayRegion`.** Matches prior art, simplest, correct.
  Per-frame copy is acceptable at the bitrates mirroring produces (≤ ~30 Mbps
  HEVC).
- **Deferred optimization:** if profiling (M4) shows NALU marshalling as a hot
  spot on HEVC, switch the video path to a `DirectByteBuffer` ring with
  `NewDirectByteBuffer` or `GetPrimitiveArrayCritical`. **Not in v1 scope** — the
  JNI surface above is forward-compatible (an overload), so we can add it without
  rewriting the contract.

### 5.4 Critical robustness rule (from prior-art post-mortem)

Native callbacks run on UxPlay's own pthreads (httpd, rtp_video, rtp_audio). The
JNI `_get_env()` helper **must `AttachCurrentThread` AND clear any pending
exception** before every up-call. A stale pending exception makes the next
`NewByteArray` abort the process. This is non-negotiable in the bridge.

---

## 6. mDNS strategy (TXT in C, advertise in Kotlin)

**Decision:** replicate the prior-art substitution, validated by study.

- Compile UxPlay `lib/*.c` **except `dnssd.c`**.
- Provide `android_dnssd_shim.c` implementing `lib/dnssd.h` so the core links,
  but whose `register_*` functions only populate an in-memory TXT map + service
  name. No sockets.
- Kotlin `NsdServiceManager` registers `_airplay._tcp` and `_raop._tcp` via
  `NsdManager`, applying TXT records fetched through
  `nativeGet{Raop,Airplay}TxtRecords`.
- **`WifiManager.MulticastLock`** (reference-counted=false, acquired for the
  service lifetime) is **mandatory** — without it Android drops mDNS multicast
  while dozing and the device never appears in the sender's list.

This keeps the protocol-significant TXT construction (the part Apple changes
across iOS versions) inside the maintained UxPlay core, and lets Android own
battery/network-aware advertisement. **Brief §6 anticipated NsdManager might
fail to express the TXT records; study confirms it can** (it accepts arbitrary
key/value attributes).

The TXT records the shim must produce (verified against upstream
`dnssd_mdnsd.c` + `dnssdint.h`):

- `_airplay._tcp`: `deviceid`, `features=0x5A7FFEE6,0x0`, `flags=0x4`, `model`,
  `pk` (64-hex Ed25519 public key), `pi`, `srcvers=220.68`, `vv=2`, `pw=false`.
- `_raop._tcp` (name `<hexmac>@<name>`): `ch=2`, `cn=0,1,2,3`, `da=true`,
  `et=0,3,5`, `vv=2`, `ft=0x5A7FFEE6,0x0`, `am`, `md=0,1,2`, `rhd=5.6.0.0`,
  `pw`, `sr=44100`, `ss=16`, `sv=false`, `tp=UDP`, `txtvers=1`, `sf=0x4`,
  `vs=220.68`, `vn=65537`, `pk`.

`features`/`ft = 0x5A7FFEE6,0x0` sets bit 7 (ScreenMirroring), bit 12
(FPSAPv2pt5_AES_GCM — the FairPlay-SAP v2.5 cipher), and bit 27
(SupportsLegacyPairing) so iOS falls back to the **APv1 / NTP-timing path** that
UxPlay speaks. We do **not** implement the AirPlay 2 / PTP / HomeKit-auth path
(`features` bit 41) — deliberately out of scope and `http_handler_fpsetup2` is a
no-op in upstream.

---

## 7. Media pipelines

### 7.1 Video

1. `video_set_codec` callback tells us H.264 (`video/avc`) vs H.265
   (`video/hevc`). Configure `MediaCodec` decoder for that MIME. We rely on
   MediaCodec's internal format detection from the first NALs (SPS/PPS arrive
   in-band as unencrypted type-7/8 NALs and are prepended to the next IDR by the
   core); explicit CSD-0/CSD-1 optional.
2. `onVideoData` → `VideoRenderer.feedFrame`: detect keyframe (H.264 NAL type
   5/7; H.265 type 19/20/21/32/33), (re)start codec on first keyframe, then
   `dequeueInputBuffer` → copy → `queueInputBuffer(idx, 0, len, ptsUs, 0)`.
3. Decode to an **`SurfaceTexture` owned by `VideoPipeline`** (an external OES
   texture), **not** directly to the display `Surface`. A dedicated EGL thread
   blits the texture onto the display surface. **Why:** this decouples codec
   lifecycle from Activity/Surface lifecycle (rotation, PiP, fullscreen toggle)
   — codec need not restart on surface changes.
4. A/V sync: `releaseOutputBuffer(idx, renderTimestampNs)` where
   `renderTimestampNs = wallBaseNs + (ptsUs - ptsBaseUs)*1000`, anchored to the
   first frame. PTS comes from the 64-bit NTP timestamp at bytes `[8:15]` of the
   mirror RTP record, converted via the NTP offset from `raop_ntp`.

### 7.2 Audio

1. `onAudioFormat(ct, spf, usingScreen)` → configure AAC or ALAC. AAC-ELD:
   `KEY_AAC_PROFILE=39`, `csd-0={0xF8,0xE8,0x50,0x00}`. AAC-LC: profile 2,
   `csd-0={0x12,0x10}`. ALAC via MediaCodec with the 36-byte ALAC atom csd-0.
2. Software ALAC fallback: if MediaCodec throws (no hardware ALAC, common on
   cheaper TV boxes), use `nativeAlacInit/Decode` (bundled ALAC) → write PCM to
   `AudioTrack`. Blacklist the failed `ct` so we don't retry-storm.
3. `AudioTrack`: 44100 Hz, stereo, PCM-16, `MODE_STREAM`.
4. **A/V sync gap acknowledged:** prior art schedules video by PTS but writes
   audio as-decoded (no `AudioTimestamp` anchor). For v1 we accept this (good
   enough for slideshow/UI mirroring); M3 budgets explicit time to add an
   `AudioTimestamp`-based anchor if drift is perceptible. Brief NFR-1 explicitly
   scopes sync to "non-gaming use."

### 7.3 Resilience (NFR-6)

Any unexpected packet / codec error must fail the **session**, not crash the app.
On codec `IllegalStateException`: blacklist `ct`, drop to fallback if any, and
let the RAOP layer's `conn_reset`/`conn_teardown` return us to idle (FR-5).

---

## 8. Threading model

| Thread | Owner | Work |
|---|---|---|
| Native httpd / rtp_video / rtp_audio / ntp | UxPlay pthreads | protocol, decrypt, framing. Invoke `raop_callbacks_t` **synchronously** here. |
| JNI callback dispatch | (rides the native threads) | `AttachCurrentThread`, clear exceptions, up-call into `RaopCallbackHandler`. **Media path is synchronous — no Handler posting** (lowest latency). |
| VideoPipeline (EGL) | app, dedicated thread | `SurfaceTexture.onFrameAvailable` → blit to display. |
| MediaCodec async | app / platform | decode callbacks. |
| Main / UI | app | Compose, settings, notification. |

Renderers exposed to native threads use `@Volatile` fields + dedicated pipeline
thread for safety. UI updates go through `MutableStateFlow` (thread-safe
emission).

---

## 9. Build system (Gradle + CMake / externalNativeBuild)

- **NDK:** 27.0.12077973 (installed). **CMake:** 3.22.1 (installed).
- **ABIs (NFR-5):** `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- **`minSdk` 24**, `compileSdk` latest (35), `targetSdk` latest.
- **Single `.so`** `libairplay_native.so` from: UxPlay `lib/*.c` (minus
  `dnssd.c`) + `android_dnssd_shim.c` + `android_raop_callbacks.c` +
  `native_bridge.cpp` + ALAC sources, linking static `playfair`, `llhttp`,
  `libplist`, OpenSSL `libcrypto`, `log`, `android`. `-DANDROID_STL=c++_shared`.
- **UxPlay compile defines:** `-DNOHOLD -DSTANDALONE -D__STDC_CONSTANT_MACROS
  -DTARGET_POSIX -D_LINUX -DPLIST_210 -DOPENSSL_API_COMPAT=0x10101000L
  -DTARGET_RT_LITTLE_ENDIAN=1`. **Do not** set `-DSYS_ENDIAN_H`, `-DDBUS`,
  `-DUSE_DNS_SD`, or `-march=native` (cross-compile).
- **Submodules** fetched recursively; OpenSSL built from source via
  `openssl-cmake` (reproducible). Build needs `git submodule update --init
  --recursive` — documented in README.

**LGPL note (libplist):** statically linking LGPL into the `.so` is allowed
provided object files remain relinkable. We will keep build artifacts / object
files producible from source on request; documented in NOTICE. (OpenSSL is
Apache-2.0; ALAC is Apache-2.0 — no such concern.)

---

## 10. Milestone mapping (brief §8) — each ends in a checkpoint

| M | Scope | Definition of done |
|---|---|---|
| **M0** Skeleton & native build | AS project, CMake compiling UxPlay for `arm64-v8a`+`x86_64`, app launches, `System.loadLibrary` succeeds, native version logged. | App runs; `.so` loads on a device + emulator. |
| **M1** Discovery + handshake (no media) | NsdManager advertises both services with correct TXT; FairPlay + pairing complete; sender shows "connected". | iOS sender connects without error (black screen OK). |
| **M2** Video mirroring | H.264 decode → Surface; H.265 toggle. | iOS/Mac screen visibly mirrors. |
| **M3** Audio + sync + lifecycle | AAC + ALAC (+sw fallback), AudioTrack, A/V sync pass, clean disconnect/reconnect. | Mirror a video with in-sync audio; reconnect cleanly. |
| **M4** Form-factor polish | Idle UI, foreground service + notification, settings, D-pad + touch, 30-min soak, reconnect soak. | AC-1..AC-7 pass on one TV box + one phone. |

Tests (brief §10): unit tests for JNI marshalling + TXT construction + settings
(JVM); instrumented for codec config; manual integration against real senders
with versions recorded in README.

---

## 11. Risks carried forward (brief §11, updated with study findings)

- **Protocol drift (primary risk):** UxPlay rides the **APv1 / NTP** mirroring
  path. Apple has kept this fallback alive iOS 9→18, but a future iOS could drop
  it. **Mitigation:** vendor UxPlay as an updatable submodule; pin a tested
  commit; record sender OS versions in README.
- **A/V sync:** classic hard part. **Mitigation:** M3 budget; expose a
  low-latency toggle if drift is perceptible; consider `AudioTimestamp` anchor.
- **mDNS on aggressive OEMs:** some Android TV OEMs mangle multicast. **Mitigation:**
  MulticastLock + foreground service (FR-8); document known-bad OEMs.
- **GPLv3:** hard constraint. Whole app GPLv3, source-available. NOTICE + LICENSE
  + per-component attribution shipped. No Apple trademarks/artwork.
- **DRM content:** permanently unsupported; handled gracefully (session fails
  with a clear message), documented to user. Not a bug to fix.
- **OpenSSL size:** static OpenSSL bloats the APK. **Mitigation (v1.1, not
  blocking):** consider BoringSSL/mbedTLS shim later; v1 ships OpenSSL for
  fidelity with UxPlay's `crypto.c`.

---

## 12. Open questions for owner (brief §14)

1. **App name + package id?** (currently `com.cressu.screendock` placeholder.)
2. **Priority sender OS versions?** (Drives M4 test matrix; default = latest iOS
   + latest macOS.)
3. **v2 PIN + now-playing expected?** (Affects JNI generality now. Current
   surface is designed to add PIN without rework; now-playing metadata callbacks
   are not wired.)
