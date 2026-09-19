# ScreenDock — Android AirPlay Receiver

An open-source Android AirPlay / RAOP receiver that wraps the [UxPlay](https://github.com/FDH2/UxPlay)
C core via JNI. Screen mirroring (H.264/H.265) and audio streaming (AAC/ALAC)
from iOS, iPadOS, and macOS senders, decoded with Android platform codecs.

> **Status: M0 — skeleton & native build.** The native library (UxPlay core +
> mDNS shim + JNI bridge) compiles for all target ABIs and loads at runtime;
> the app launches and runs a native self-test. Discovery, handshake, and media
> pipelines land in M1–M3 (see `docs/DESIGN.md` §10).

## License

This project is licensed under the **GNU General Public License v3.0 or later**
(see [LICENSE](./LICENSE) and [NOTICE](./NOTICE)). It statically links UxPlay
(GPLv3), so the whole application is a GPLv3 derivative: source must accompany
any distribution. It is **not** affiliated with Apple Inc.; "AirPlay" is an
Apple trademark. No Apple trademarks or artwork are shipped.

## What works (M0)

- ✅ Android Studio (Gradle + CMake) project building `libairplay_native.so`
- ✅ UxPlay protocol core (`lib/*.c`) compiled with the NDK
- ✅ mDNS shim that constructs the AirPlay/RAOP TXT records in C (advertising
  via Android `NsdManager` lands in M1)
- ✅ JNI bridge skeleton (`nativeInit`/`Destroy`, version, self-test) + the
  full callback contract declared for later milestones
- ✅ Stubbed idle UI loads the lib, logs its version, runs a native self-test
- ✅ Builds for `arm64-v8a`, `armeabi-v7a`, and `x86_64`

## What does not work yet (M1–M4)

- mDNS advertising / sender discovery (M1)
- RTSP + FairPlay session establishment (M1)
- Video decode + render (M2)
- Audio decode + A/V sync + reconnect lifecycle (M3)
- Foreground service, settings, D-pad/touch polish, stability pass (M4)

## Requirements

- Android Studio with NDK 27.0.12077973 and CMake 3.22.1 (the SDK install
  must contain both)
- JDK 17+ (Android Studio's bundled JBR works)
- Host build tools for OpenSSL: `perl`, `python3`, `make`, `patch`
- Tested on macOS (arm64); other Unix hosts should work

## Build

The native dependencies are git submodules. Initialize them first (needed once,
and after any clone):

```sh
git submodule update --init --recursive
```

Then build the debug APK (uses the Android Studio JBR for Gradle):

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

The APK is emitted at `app/build/outputs/apk/debug/app-debug.apk`.

> **First build is slow:** it downloads and compiles OpenSSL from source for
> each ABI (~2–3 min/ABI). Subsequent builds are incremental.

### Run on a device/emulator

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cressu.screendock/.ui.MainActivity
adb logcat -s AirplayNative AirplayMainActivity
```

Expect on launch:
```
I AirplayMainActivity: Native version: Airplay 0.1.0-m0 (UxPlay core 220.68)
I AirplayNative: nativeSelfTest: OK (raop_txt=... bytes, airplay_txt=... bytes, raop_name=...@Airplay._raop._tcp.local)
I AirplayMainActivity: selfTest passed=true
```

## Targets

| | |
|---|---|
| `minSdk` | 24 (Android 7.0) |
| `compileSdk` / `targetSdk` | 35 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| NDK | 27.0.12077973 |

## Known limitations

- **DRM-protected content** (e.g. the Apple TV app) cannot be decrypted by the
  open-source stack. This is permanent, not a bug — FairPlay-DRM streams are
  out of scope. The app will fail such sessions gracefully once M1 lands.
- **AirPlay 2 buffered / HomeKit / multi-room** paths are out of scope (v1 uses
  the APv1 / NTP-timing legacy mirroring path, which Apple has maintained as a
  fallback iOS 9→current).
- **Protocol drift:** Apple may change the handshake in a future iOS release.
  UxPlay is vendored as an updatable submodule for exactly this reason.

## Tested sender OS versions

*(To be filled in during the M4 acceptance run per the test plan. Open-source
AirPlay receivers are version-sensitive; a passing test is only valid for the
versions listed here.)*

## Architecture

See [`docs/DESIGN.md`](./docs/DESIGN.md) for the full technical design: layering,
module layout, the frozen JNI surface, mDNS strategy, media pipelines, threading
model, and milestone mapping.
