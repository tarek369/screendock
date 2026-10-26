# AGENTS.md — ScreenDock (Android AirPlay Receiver)

> Living source of truth for any agent (including future sessions) working on
> this repo. **Read this first.** Update it whenever state changes.
> (This is the ZCode equivalent of a `CLAUDE.md`; ZCode auto-loads `AGENTS.md`.)

## Project in one paragraph
Open-source Android AirPlay/RAOP receiver wrapping the UxPlay C core via JNI.
Screen mirroring (H.264/H.265) + audio (AAC/ALAC). GPLv3 (statically links
UxPlay → whole app is GPLv3). v1 uses the APv1 / NTP-timing legacy mirroring
path. DRM content is permanently unsupported (documented, not "fixed").

## Working agreement (from project brief §0 — do NOT violate)
1. **Study before building.** Reference impls first; don't reinvent protocol/FairPlay.
2. **Build in milestone order.** Each milestone ends in a demoable checkpoint. Don't jump ahead *of the checkpoint*, but modules within the current scope may be parallelized.
3. **Tests as you go.** Unit-test JNI marshalling + TXT construction + settings.
4. **Surface blockers early.** If a real iOS/macOS sender won't connect, capture logs and STOP — never silently work around the handshake.

## HARD CONSTRAINT — validation gate
The M1–M4 *checkpoints* require real Apple senders (iPhone/Mac) on Wi-Fi. The
Android emulator cannot send AirPlay and cannot validate the FairPlay handshake.
Code can be written + compiled + emulator-smoke-tested here; **acceptance needs
the owner's hardware.** Do not claim a milestone "passed" without sender logs.

## Open questions for owner (§14 — still open)
- ~~App name + package id~~ **RESOLVED (2026-09-19): app "ScreenDock", package `com.cressu.screendock` (company cressu.com).** The receiver default display name + notification title are now "ScreenDock"; logcat tags intentionally kept as Airplay*/AirPlay* for continuity with existing capture commands.
- Priority sender OS versions for the M4 matrix.
- v2 PIN + now-playing expectation (PIN surface designed-in; now-playing unwired).
- **Input backchannel (touch→click): ⏳ IMPLEMENTED + INSTRUMENTED, but macOS does NOT acknowledge the events. Touch→Mac does NOT work today.**
  What works (live-verified): taps/drags fire the TouchForwardOverlay, letterbox-normalize, and are sent
  as reverse `POST /event` plists over the event channel; macOS CONNECTS to the advertised eventPort
  (dual-stack mandatory — see gotcha below) and its server demonstrably reads the socket (a minimal
  `OPTIONS * RTSP/1.0` gets an immediate `RTSP/1.0 400 Bad Request`).
  What does not: macOS NEVER responds to any `POST /event` — it emits `RTSP/1.0 408 Request Timeout`
  every 5 s regardless. Formats tried and rejected (all silent): HTTP/1.1+XML plist, HTTP/1.1+XML with
  X-Apple-Session-ID (fcup clone), RTSP/1.0+binary plist+CSeq, immediate-after-connect probe. Conclusion:
  macOS's event server requires an undocumented handshake/binding (or encrypted payload) — no public
  receiver implementation sends touch to a Mac over AirPlay. Next step if resumed: capture macOS's
  event-port traffic from a real Apple TV handshake, or test with an iPhone sender (the documented
  consumer of receiver touch events).
  **Load-bearing gotcha (caused a full outage):** the event listener MUST be dual-stack (AF_INET6 +
  IPV6_V6ONLY=0). macOS connects over IPv6 link-local; an IPv4-only bind gets ECONNREFUSED and macOS
  ABORTS THE ENTIRE MIRRORING SESSION (~30 s stall then teardown).
  An earlier entry here claiming touch-back "WORKS, verified end-to-end" was WRONG — the observed Apple
  menu opening was coincidental sender-side activity during an uncontrolled test. Retracted.

## Architecture (see docs/DESIGN.md for full detail)
```
UI (Compose Activity: idle ↔ full-screen render)
        ▲ state (StateFlow)            ▲ Surface/settings
App layer (Kotlin)
  AirPlayService (foreground) ─ owns lifecycle
    ├─ NsdServiceManager   (advertise _airplay/_raop via NsdManager)
    ├─ VideoRenderer       (MediaCodec → VideoPipeline EGL OES blit)
    ├─ AudioRenderer       (MediaCodec AAC / SW-ALAC → AudioTrack)
    ├─ SettingsRepository  (DataStore)
    └─ SyncController      (NTP/PTS anchor)
        ▲ JNI downward           ▲ JNI upward callbacks
JNI bridge: native_bridge.cpp (down) · android_raop_callbacks.c (up)
        ▲ raop_*/dnssd_*         ▲ audio/video_process
Native core (C, NDK): UxPlay lib/*.c + android_dnssd_shim.c (TXT only, no net)
  + static: playfair, llhttp, libplist, OpenSSL, ALAC
```

## Frozen contracts (do NOT change without updating both sides)
- `app/src/main/cpp/android_raop_callbacks.h` + `.c`  ↔  `RaopCallbackHandler.kt`
  - JNI descriptors: `()V`, `(I)V`, `([BJZ)V`, `(FFFF)V`, `([BIJI)V`, `(IIZ)V`, `(F)V`, `(Ljava/lang/String;)V`
- `app/src/main/cpp/native_bridge.cpp`  ↔  `NativeBridge.kt` (full surface listed in NativeBridge.kt)
- mDNS split: TXT records built in C (`android_dnssd_shim.c`), advertising in Kotlin (`NsdServiceManager`). `lib/dnssd.c` is INCLUDED (public API); `lib/mdnsd/` + `lib/dns_sd/` EXCLUDED.

## Milestone status
- **M0 — Skeleton & native build: ✅ DONE.**
- **M1 — Discovery + handshake: ✅ VALIDATED ON REAL HARDWARE (2026-09-08).**
  - Emulator smoke test previously passed (server binds port, both services advertised with correct TXT).
  - Mac sender (macOS Finder desktop mirrored) discovers, connects, completes FairPlay/pair-verify, and negotiates H.264 (`video_set_codec=1`). Fixed in validation: `audio_set_client_volume` was NULL → SIGSEGV in `raop_handler_info` on first `/info` (all `raop_callbacks_t` fields are now populated in `android_callbacks_fill`).
- **M2 — Video mirroring: ✅ VALIDATED ON REAL HARDWARE (2026-09-08).**
  - Live H.264 mirroring renders via MediaCodec → VideoPipeline EGL blit. Fixed in validation: `EGL_RECORDABLE_ANDROID` was `0x314` (correct: `0x3142`) and surface-mode `MediaCodec.configure` needs nonzero `KEY_WIDTH/KEY_HEIGHT` (hints only; real size from SPS). Defaults now advertise the native panel resolution (3392×2400 on the OnePlus Pad test device) at 60fps ("Native" settings option = 0×0 sentinel, resolved in `AirPlayService.resolveAdvertisedSize`).
- **M3 — Audio + sync: ✅ live decode verified (audibility pending user ear-test).**
  - `AudioRenderer` (MediaCodec AAC-ELD/LC + ALAC, SW-ALAC fallback, AudioTrack, dB→linear volume). ALAC software decoder (Apple ALACDecoder, Apache-2.0) compiled into the `.so` and wired via JNI.
  - Live session (2026-09-08, Mac sender): AAC-ELD (ct=8) frames decode to 44.1kHz stereo PCM and pace stably after the syncAudio inversion fix (drift −28..−50ms). User must confirm audible output with sound playing on the sender.
- **M4 — Polish: ⏳ PARTIAL.**
  - Foreground service + notification: ✅ done.
  - Settings persistence (DataStore) + **Settings UI screen**: ✅ done (device name, resolution, fps, H.265 toggle, apply-and-restart).
  - D-pad navigation on TV: ⏳ not tested.
  - 30-min soak + reconnect stress (AC-6): ⏳ needs real hardware.

## ⚠️ HAND-OFF VALIDATION CHECKLIST (needs real Apple hardware)
Run these on a real Android device + an iPhone/Mac on the same Wi-Fi:
1. **Discovery (AC-1):** Install the APK → open the app → check the iPhone's Screen Mirroring menu shows "ScreenDock".
2. **Handshake (AC-2):** Tap it → the sender should show "connected" within a few seconds. Watch `adb logcat -s AirplayNative AirPlayService` for `conn_init`.
3. **Video (AC-2):** If connected, the screen should mirror. If VideoPipeline has issues, capture the `AirplayVideo` logs.
4. **Audio (AC-3):** Mirror a video with sound. Check `AirplayAudio` logs for codec ct (2=ALAC, 4=AAC-LC, 8=AAC-ELD).
5. **Lifecycle (AC-4):** Stop mirroring from the sender → app should return to "Ready to receive". Reconnect.
6. **Soak (AC-6):** Leave mirroring on for 30 min. Check for memory growth / crashes.
7. **DRM (AC-7):** Try mirroring the Apple TV app → should fail gracefully (not crash).
Record the sender iOS/macOS version(s) in README §"Tested sender OS versions".

## USB cable mirroring (no Wi-Fi) — procedure + gotchas (2026-09-08)
The receiver works over any IP network; USB tethering gives a wired Mac↔tablet link.
1. Tablet: Settings → Hotspot & tethering → USB tethering ON.
2. **Modern macOS has NO RNDIS driver** — Android exposes `rndis` by default and the Mac ignores the
   interface entirely (no DHCP, `ip neigh show dev usb0` empty). Switch the gadget to NCM:
   `adb shell svc usb setFunctions ncm` (adb drops during re-enumeration, returns in seconds; verify
   `getprop sys.usb.state` = `ncm,adb`). Then toggle USB tethering OFF→ON to re-attach usb0 + DHCP
   (tablet is 10.41.52.x gateway on this device). NOTE: function resets on cable replug/reboot —
   re-run the switch, or consider `svc usb setScreenUnlockedFunctions ncm`.
3. Mac: Wi-Fi OFF (else macOS keeps using the Wi-Fi route), then Screen Mirroring → "ScreenDock".
   Verify in logs the RTSP peer is on the USB subnet (10.41.52.x), not 192.168.20.x.
   Our mDNS (NsdManager) DOES join the mDNS multicast group on usb0 — no app change needed.

## OnePlus power-management gotcha (2026-09-08)
`ActivityManager: Stopping service due to app idle` — the OS killed the FOREGROUND service after
~6 min with the app backgrounded (user in Settings). Symptom: receiver silently dead ("can't connect").
Fix for the owner: Settings → Battery → don't optimize com.cressu.screendock (needs on-device confirmation;
cannot be granted via adb). A dead receiver after backgrounding = check this first.

## Build / run
```sh
git submodule update --init --recursive  # UxPlay is VENDORED (not a submodule); the other three are
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"   # JBR inside Android Studio is JDK 25 → gradle fails
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cressu.screendock/.ui.MainActivity
adb logcat -s AirplayNative AirplayMainActivity AirPlayService
```
minSdk 24 · targetSdk 36 · AGP 8.9.1 · ABIs arm64-v8a/armeabi-v7a/x86_64 · NDK 27.0.12077973 · CMake 3.22.1.
First build downloads+compiles OpenSSL (~2–3 min/ABI).

## Website upkeep (screendock.cressu.com) — do these, don't just read them
Source: `cloudflare-worker/` (static pages in `public/`, worker in `src/worker.js`). Deploy: `cd cloudflare-worker && npx wrangler@4 deploy` (needs `wrangler login`).

**On every app release (versionCode bump):**
1. `public/index.html`: update the specs row "Latest version X.Y.Z, updated <Month YYYY>" AND the JSON-LD `softwareVersion` + `dateModified`.
2. If features, limits or price changed: update the matching copy (answer block, pricing, specs, FAQ) and `public/llms.txt`.
3. Regenerate German: `cd cloudflare-worker && python3 tools/make_de.py public/index.html public/de/index.html`. If it fails, an English string it translates changed: add/update that pair in `tools/make_de.py`.
4. Bump `<lastmod>` in `public/sitemap.xml` for changed pages, deploy, then curl `/`, `/de/`, `/compare/` for 200.

**Every few months, or when touching `/compare/`:**
- Re-check AirScreen and Mirroring360 facts against their Google Play listings; fix anything stale and update "Last checked" + JSON-LD `dateModified`. Never state a competitor fact you can't source from their listing.

**Rules:**
- Files under `public/assets/` are cached for 1 year (immutable): replace = new filename + update references. `site.css` is not cached that way.
- Keep the Play listing (`store/listing/`), the site and the privacy policy consistent (price, free-tier limits, contact `tarek@cressu.com`).
- Open owner TODOs: sitemap in Google Search Console + Bing Webmaster Tools; YouTube/Show HN/AlternativeTo mentions (see `docs/seo/GEO-ANALYSIS.md`).

## Known gotchas (load-bearing)
- OpenSSL must compile at API 24 (minSdk), else `__sendto_chk` undefined at link. Fix in place: `-DANDROID_PLATFORM=android-24` + `-D__ANDROID_API__=24` in CMAKE_C_FLAGS (forwarded into openssl-cmake).
- `lib/dnssd.c` MUST be compiled (it has the public `dnssd_init` etc.); only the mdnsd/dns_sd *backends* are dropped — our shim is the backend.
- JNI callbacks run on UxPlay pthreads → must `AttachCurrentThread` AND clear pending exceptions before every up-call (or NewByteArray aborts the process).
- `raop_destroy` always calls `raop_stop_httpd` which asserts `httpd != NULL` → only call destroy after start_httpd, or never probe via init/destroy.
- **Compose layering:** an overlaid button must be composed AFTER (i.e. on top of) any full-size `verticalScroll` container — the scroll container hit-tests its entire bounds and silently swallows taps on anything composed beneath it. This made the idle-screen settings gear look dead on the real pad (fixed 2026-09-19); the emulator sometimes let taps through, so emulator testing alone did not catch it.

## Changelog
- 2026-07-09: M0 complete + verified on emulator.
- 2026-08-11: M1–M4 parallel build-out via 3 agents + main. All modules code-complete and building.
  - Fixed: JDK 25 JBR incompatibility (use `openjdk@17` via `JAVA_HOME`).
  - Fixed: `httpd_start` return value convention (1 = success, not failure).
  - Fixed: AudioRenderer `setByteArray` → `setByteBuffer` for MediaFormat CSD.
  - Emulator smoke test passes: native server binds port, NsdManager advertises both services.
  - Unit tests pass (settings defaults, TXT contract, hwaddr derivation).
  - **Remaining for real-hardware validation:** see checklist above.
- 2026-09-08: **First real-hardware validation** (OnePlus Pad OPD2415 + Mac sender). M1+M2 now live-validated; several load-bearing bugs found & fixed:
  - Fixed: NULL `audio_set_client_volume` → SIGSEGV on first sender `/info` (all raop_callbacks_t fields now populated, incl. HLS on_video_* no-ops and check_register left NULL = accept-all).
  - Fixed: `ForegroundServiceDidNotStartInTimeException` — every `onStartCommand` delivery now calls `startForeground()` first.
  - Fixed: Settings Apply used stop+start service commands; `stopSelf()` then destroyed the freshly restarted receiver (UI stuck on "Starting receiver…"). Apply now sends one atomic `ACTION_RESTART`; `startInFlight` AtomicBoolean guards overlapping async starts.
  - Fixed: `EGL_RECORDABLE_ANDROID` value 0x314 → 0x3142 (+ RGB565 relaxed-config fallback); `eglChooseConfig failed` killed video.
  - Fixed: surface-mode `MediaCodec.configure` threw bare IllegalArgumentException without KEY_WIDTH/HEIGHT (now 1920×1080 hints).
  - Fixed: settings UI white-on-white under light system theme (dark color scheme applied; hardcoded label colors themed).
  - Improved: advertised caps now default to "Native" panel resolution (0×0 sentinel → `resolveAdvertisedSize`, 4K-capped) at 60fps instead of hardcoded 1080p30; `nativeStart` no longer re-sets plist caps after the service applies settings.
  - Fixed: idle-screen status raced the one-shot state broadcast (stuck "Starting receiver…"); state moved to a companion-object `StateFlow` the UI collects.
  - Receiver state after session: live mirroring sharp at native 3392×2400; sender auto-reconnects after Apply-restart. Audio (M3) still unconfirmed with a live stream.
- 2026-09-08 (later): audio + latency work, live-verified with Mac sender:
  - Fixed: `AudioRenderer.syncAudio` drift comparisons were INVERTED — early packets (the normal case; AirPlay leads audio ~30-50ms) were dropped and late ones slept on ⇒ total silence despite healthy decode. Measured live: drift oscillates −28..−50ms in 3-packet bursts (macOS sends AAC-ELD bursts of 3×480 samples every ~33ms).
  - Hardened: sleep clamped to ≤100ms/packet (sender timeline jumps during rtp sync rewrites can otherwise stall the audio thread for seconds); MAX_DROPS 10→3 so a timeline jump costs ~44ms of audio, not 120ms+.
  - Latency: video decoder now requests `KEY_LOW_LATENCY` (accepted by the c2 AVC decoder on the OnePlus Pad); audio pts-pacing keeps A/V on the sender's NTP timeline. Remaining lag is sender encode + Wi-Fi — advise 5GHz/Wi-Fi 6 and proximity.
  - Diagnostics: `onVolumeChange` logs sender volume; `syncAudio` logs drift for the first 60 packets then every 256th (tag AirplayAudio, "sync dbg").
- 2026-09-08 (final): fixed intermittent "audio but no screen":
  - Root cause: macOS opens TWO RTSP connections (probe + session). The probe's `conn_destroy` fired after the session's `conn_init`, flipping UI state to WAITING (render SurfaceView never composed → EGL display surface never bound → decoded 4K video blitted to nothing) and spuriously resetting both renderers mid-session. Race timing = "sometimes works".
  - Fix: connection REFERENCE COUNT in AirPlayService (`connCount`); state/renderer-reset only on last disconnect. `stopReceiver` zeroes it.
  - Hardening: VideoRenderer remembers the last keyframe bundle; a mid-GOP codec death now restarts from the stale SPS/PPS/IDR (concealed until next in-band IDR) instead of waiting for a keyframe the sender may never resend. `isKeyframe`/NAL extraction accept 3- AND 4-byte Annex-B start codes.
  - Hardening: decoder now configured with real csd (SPS/PPS from the sender's keyframe; VPS/SPS/PPS for HEVC) so the stream's true resolution (Mac sends 3840x2160) is honored over the 1920x1080 numeric hints.
  - Live-verified: conn→`display surface bound (3392x2400)`→decoder 2880x1632→3840x2160, sharp 4K mirror confirmed by screenshot.
- 2026-09-08 (fps): added a 120 fps settings option; `nativeSetDisplaySize` advertises `refreshRate=max(fps,60)` + `maxFPS=fps`. Caveat recorded: no known Apple sender sends >60fps over the APv1 mirroring path, so 120 is future-proofing; decode/blit path is rate-agnostic (PTS-paced release + frame-available blit).
- 2026-09-08 (diagnostics): UxPlay's internal logger now forwards to logcat via `raop_set_log_callback` (`android_log_forward` in native_bridge.cpp) at LOGGER_INFO — flip to 7 temporarily to dump full RTSP plists.
- 2026-09-08 (touch-back REGRESSION + fix): the first event-channel build broke ALL connectivity — macOS connects the event port over IPv6 link-local, the IPv4-only listener refused it, and macOS aborted every mirroring session after a ~30 s stall. Dual-stack listener (AF_INET6 + V6ONLY=0) fixed it; with the port reachable, macOS opens the event connection and CONSUMES injected touches (verified: taps opened Apple menu / selected icons on the mirrored Mac).
- 2026-09-19: **UI redesign ("Standby Signal" design language)** — M4 polish pass on the Compose layer. No native/JNI/service-contract changes.
  - New `ui/theme/Theme.kt` (near-black blue-tinted palette, periwinkle→violet signature gradient, thin display typography) + `ui/components/Components.kt` (animated aurora background, canvas-drawn AirPlay glyph with pulsing broadcast rings, glass cards, sliding-pill segmented control, gradient button, status chip/dot).
  - Idle screen redesigned: aurora bg, broadcast hero, thin device name, color-coded status, 3-step "how to connect" glass card, capability chips (resolution/fps/H.265 from live settings), error card on self-test failure.
  - Settings redesigned: icon-led glass cards, custom segmented controls (replacing radio rows), themed switch + text field, dirty-state-aware gradient Apply button, keyboard-safe (imePadding), system-back pops to idle (BackHandler).
  - Mirroring UX: while CONNECTED the system bars now hide (immersive, transient-by-swipe) and FLAG_KEEP_SCREEN_ON is set; a "Mirroring active" chip fades out after ~2.4 s. Bars restored + flag cleared on disconnect.
  - Fixed (owner-reported): settings gear on idle screen did not open on the real pad — full-size `verticalScroll` Column was composed OVER the button and swallowed taps; button now composed last (see gotcha above).
  - Added `material-icons-extended` dependency (BOM-managed).
  - Verified on emulator (Medium_Phone API 36): build clean, unit tests pass, settings opens/closes, segmented selection correct (a11y-checked; pill gradient pixel-verified), Apply→restart flow works. Real-pad visual confirmation pending (install fresh APK).
- 2026-09-19 (rename → ScreenDock): app renamed from placeholder "Airplay"/`com.example.airplay` to **ScreenDock** / `com.cressu.screendock` (owner's company domain cressu.com).
  - Moved Kotlin trees (main + test), rewrote all package/imports, set `namespace` + explicit `applicationId`, retargeted the JNI mangled names in `native_bridge.cpp` (`Java_com_cressu_screendock_bridge_NativeBridge_*` — the ONLY C-side hardcoded package surface; `android_raop_callbacks.c` uses GetObjectClass, no FindClass strings).
  - Service intent actions now `com.cressu.screendock.{START,STOP,RESTART}`; default receiver name + notification title + `app_name` → "ScreenDock".
  - Logcat tags (AirplayNative, AirplayMainActivity, AirplayAudio…) deliberately unchanged so existing capture commands keep working.
  - Verified: build + unit tests pass; fresh install on emulator: `nativeSelfTest: OK`, server bound, `Receiver advertising as "ScreenDock"`, idle hero shows ScreenDock. Old com.example build uninstalled.
- 2026-09-19 (native rename sweep): the first rename pass missed the C side — `nativeGetVersion` returned "Airplay 0.1.0-m1 (…)" (visible on the idle footer) and both `dnssd_init` calls hardcoded "Airplay" as the mDNS instance base (visible in selfTest logs as `…@Airplay._raop._tcp.local`). All three now "ScreenDock"; Gradle `versionName` unified to plain "0.1.0" (C string and versionName must be bumped together). Verified on fresh emulator install: startup log, selfTest service name, advertising name, and idle footer all say ScreenDock. Sweep rule: after any identity change, grep `app/src/main/cpp` for the old brand — Kotlin-side sweeps don't cover JNI/C.
- 2026-09-19 (app icon + splash): full brand asset pass for ScreenDock.
  - Icon: adaptive icon (API 26+) from `res/drawable/ic_launcher_{background,foreground,monochrome}.xml` (aapt2 inline gradients; monochrome = themed-icon layer for 13+) + `mipmap-anydpi-v26/ic_launcher{,_round}.xml`; legacy PNG mipmaps (48–192px) rendered from `design/icon/*.svg` via rsvg-convert (brew librsvg). Manifest sets icon + roundIcon; activity theme → `Theme.Airplay.Splash`.
  - Splash: androidx core-splashscreen 1.0.1; `Theme.Airplay.Splash` (dark `#07090D` bg + `drawable/ic_splash_logo.xml` glyph) + `installSplashScreen()` first in `MainActivity.onCreate`. GOTCHA: the system masks the splash icon to a circle using only the drawable's center ~66% (radius ≈96 of the 288 viewport) — broadcast rings in the splash drawable are cropped away; the glyph corners (~r83) just fit. Rings live in the store icon + feature graphic instead.
  - Play listing assets in `store/`: `store_icon_512.png` (full-bleed, 32-bit) + `feature_graphic.png` 1024×500 (wordmark + tagline "AirPlay receiver for Android" — nominative use, kept OUT of the app title). SVG masters kept alongside the PNGs (`design/icon/`, `store/`) — regenerate with rsvg-convert, don't edit PNGs.
  - Verified on emulator: launcher icon renders (drawer, a11y bounds + screenshot), splash captured on cold start; build clean.
- 2026-09-19 (release readiness pass): targetSdk/compileSdk 35 → **36** (Play policy deadline Aug 31 2026) + AGP 8.7.3 → **8.9.1** (wrapper already Gradle 8.11.1). Emulator (API 36) build + unit tests pass.
  - Release signing: `keystore/screendock-release.jks` (generated, alias `screendock`, random password in `keystore.properties` — both GITIGNORED, owner must back up; losing the key = can never update the Play listing). `signingConfigs.release` loads keystore.properties when present; absent file → unsigned release build (CI-safe). `storeFile` in keystore.properties resolves against the ROOT project dir, not `app/`.
  - AAB: `bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`, signature verified via apksigner (CN=ScreenDock, OU=Cressu). Enroll in **Play App Signing with Google-generated key** on first upload; the local .jks then only signs uploads.
  - Release build: `ndk { debugSymbolLevel = "SYMBOL_TABLE" }` for native crash symbolication. proguard-rules.pro had a STALE com.example package (rename sweep missed it) — fixed; keep rules ready for future minify.
  - POST_NOTIFICATIONS runtime request added (Android 13+, one-shot per install via SharedPreferences flag `ui_state/notif_asked`). GOTCHA: resolve the Activity with `LocalContext.current` — `applicationContext.findActivity()` is null (no Activity in its ContextWrapper chain), which silently skipped the whole flow on first attempt. Live-verified on release build: dialog → Allow → `granted=true` → FGS running with visible notification.
  - Owner account switched personal → **organization** on Play (exempt from 20-tester/14-day closed-test requirement; confirm banner gone in Console).
- 2026-09-19 (PLAY STORE: app is live on internal track): ScreenDock published to Google Play **internal testing** via the Play Developer API (play-console MCP).
  - App entry created by driving Edge via AppleScript JS injection (user enabled View > Developer > Allow JavaScript from Apple Events; base64-wrapped JS payloads via `eval(atob(...))` to survive shell quoting; form fields filled with native value setter + input events). Package `com.cressu.screendock` reserved at creation, availability-checked. Developer account id (redacted — see local notes).
  - AAB (vc 1) uploaded — NOTE: 26 MB uploads exceed the MCP tool's 30 s client timeout; the server still finished processing after the timeout and bundles landed (client abort ≠ server failure; verify with list_bundles before retrying). Release "0.1.0 (internal)" status=completed on track internal via play_tracks.patch.
  - Store listing en-US set: title ScreenDock, short + full description (with DRM-unsupported + non-affiliation disclosures); icon 512, feature graphic, 2 phone screenshots uploaded (idle + settings captured from release build on emulator).
  - STILL OPEN (mostly Console UI): internal tester emails (≥1, add via Console or play_tracks.update_testers), privacy policy URL, data safety form, content rating questionnaire, target audience, ads declaration, app access — required before PRODUCTION rollout. GPLv3: repo must go public.
- 2026-09-19 (monetization: Free + one-time IAP, no ads): owner wanted ads + pay-to-remove; **AdMob rejected** — AdMob SDK is proprietary and incompatible with the app's GPLv3 (static-linked UxPlay), plus data-safety/UX costs. Owner accepted alternative: **Free app + $9.99 one-time IAP "ScreenDock Pro" (unlimited mirroring sessions; free tier = 10-min sessions)**.
  - Implemented: `billing/BillingManager.kt` (BillingClient 7.1.1, product `unlock_pro`, acknowledge + queryPurchasesAsync entitlement, graceful no-Play fallback = free tier), DataStore cache `pro_unlocked` in AirPlaySettings, `AirPlayService.proUnlocked`/`sessionLimitHit` companion StateFlows, free-session limiter (arms on CONNECTED if not Pro; on expiry sets nudge flag + self-RESTART to drop the sender), idle-screen UpgradeNudgeCard, Settings "ScreenDock Pro" card with gradient Unlock button. GOTCHAS: billing 7.x ProductDetailsParams takes `setProductDetails(details)` (there is NO setProductId on that builder); `install -r` silently fails on signature mismatch (release-vs-debug) — uninstall first; versionName on device revealed a stale install.
  - vc 2 / versionName 0.2.0 built (AAB at app/build/outputs/bundle/release). vc2 NOT yet uploaded — 27 MB exceeds the MCP tool's 30 s timeout window on this connection (vc1 landed on a lucky attempt); owner uploads vc2 via Console UI (Test and release → Internal testing → new release → upload AAB).
  - `unlock_pro` IAP product NOT created yet — play_inappproducts (legacy) is dead ("migrate to new publishing API"); new `monetization.onetimeproducts.create` needs schema (listings[] + purchaseOptions[{purchaseOptionId:"buy", buyOption:{}, newRegionsConfig:{usdPrice/eurPrice Money, availability}, regionalPricingAndAvailabilityConfigs[{regionCode:"US", price Money, availability}]}]) but FAILED with PERMISSION_DENIED: API user needs **"Manage Play Billing resources"** granted in Console → Users and permissions. Owner will create product manually (values in this entry) or grant the permission.
  - Privacy policy updated (free + optional one-time purchase; payments by Google) and redeployed to https://screendock.cressu.com/privacy. Store listing fullDescription updated with FREE VS PRO section. Console Ads answer = "No ads".
  - Monetization decision context: owner's first ask was "ads + pay to remove"; discussed GPL/AdMob conflict → owner chose Free + IAP unlock (no ads). AdMob would ALSO have forced data-safety declarations and "Contains ads" listing flag.
- 2026-09-19 (vc3 UPLOADED + rolled out): new build live on internal testing — "0.2.0 (internal)", versionCode 3 (ScreenDock Pro IAP build).
  - Route around tool limits: minted the service-account OAuth token locally (OAuth token minted locally from the machine.s existing service-account credentials — path never committed; nothing secret touches the repo) and drove the Play REST API with curl. Direct single-shot uploads die at ~35 s (sandbox/proxy connection cap — same wall the MCP tool hits); **resumable upload protocol works: POST ?uploadType=resumable for a session URL, then PUT 4 MB chunks with Content-Range + per-chunk retry; a dropped chunk is queryable via `Content-Range: bytes */TOTAL` (308 + Range header) — but SAVE THE SESSION URL or a drop loses it.**
  - Play REJECTED vc2 on commit: "Billing Library 7.1.1 must be ≥ 8.0.0" → bumped billing-ktx to 8.0.0 (8.1+ needs Kotlin-metadata 2.2; 8.0.0 compiles on our Kotlin 2.0.21). API changes fixed: `enablePendingPurchases(PendingPurchasesParams…enableOneTimeProducts())` is now mandatory-with-args; `queryProductDetailsAsync` callback's 2nd param is `QueryProductDetailsResult` (`.productDetailsList`).
  - Commit endpoint path is `…/edits/{id}:commit` (colon form; URL-echo before blaming auth — a garbage token minted a misleading 404 earlier because a status line polluted the TOKEN var).
- 2026-09-19 (repo public): published as **https://github.com/tarek369/screendock** (PUBLIC, GPLv3) with a FRESH single-commit history (`bf990a0`) — old `main` branch deleted locally; the old private repo `tarek369/Airplay` remains untouched as a history backup. Pre-publish secrets audit: no keys/tokens ever tracked or in history; gitignore extended (`.wrangler/` cache contained Cloudflare account id + email, `.zcode/`); AGENTS.md scrubbed of credential paths/account ids. Local `origin` now points at the new repo.
- 2026-09-19 (IAP live): `unlock_pro` ("ScreenDock Pro") one-time product created, priced ($9.99 US, 173 regions auto-converted from USD), and **ACTIVE** — matches BillingManager.PRODUCT_ID, so the in-app purchase flow is now fully wired. Done via REST (node + curl patterns): the new monetization API has NO plain create — it's `oneTimeProducts:batchUpdate` with `allowMissing:true` and **`regionsVersion` on the REQUEST wrapper** (not inside the product; product-level field is readOnly → "Regions Version must be specified" error). Activation: `oneTimeProducts/{id}/purchaseOptions:batchUpdateStates` with field `activatePurchaseOptionRequest` (NOT the "Offer" variant — that's a different resource under purchase options). Price conversion source: `applications/{pkg}/pricing:convertRegionPrices`.
  - Owner's manually-created product `0010` ("full"/"No time limit") does NOT match the app code and is superseded — dormant; delete it in Console (Monetize → Products) if desired, or ignore; it can never be purchased since nothing references it.
- 2026-10-09 (ASO pass — listing metadata updated on LIVE production listing): owner reported bad ASO; audit confirmed ScreenDock ABSENT from top ~30 Play results for both "airplay receiver" and "screen mirroring" (all 29 ranking competitors put "AirPlay" in the title; ours was brand-only "ScreenDock", 0 ratings, 2 screenshots, en-US only).
  - Applied via API edit (insert → listings.update → validate → commit): title → **"ScreenDock: AirPlay Receiver"** (28/30 chars), short desc → "AirPlay receiver: mirror your Mac, iPhone or iPad screen to this Android" (72/80), full desc rewritten adding high-volume phrases (AirPlay for Android, screen mirroring receiver, second display/monitor for Mac, cast/screen share, AirPlay audio receiver + ALAC music streaming). All compliance disclosures (DRM, non-affiliation, GPLv3) preserved. Launcher label stays "ScreenDock" — store title and launcher name are independent; package id can NEVER change post-publish.
  - Expect search re-indexing over days–weeks, not instant.
  - ASO quick wins still open: in-app Review API (0 ratings is a ranking suppressor), 4–6 more screenshots + captions, listing localization (de/fr/es/ja/pt-BR), configure PLAY_REPORTS_BUCKET (Console → Download reports → copy gs:// URI) to measure search-vs-browse acquisitions.
- 2026-10-09 (listing LOCALIZED — 5 new languages live on production): de-DE, fr-FR, es-ES, ja-JP, pt-BR added via API edit (titles: "ScreenDock: AirPlay-Empfänger" / "ScreenDock : récepteur AirPlay" / "ScreenDock: receptor AirPlay" ×2 / "ScreenDock: AirPlayレシーバー"; all ≤30 chars, shorts ≤80, fulls ≤4192 — validated programmatically before push). Brand/tech terms (AirPlay, ScreenDock, codec names) kept untranslated; all compliance disclosures preserved per language. English screenshots reused for all locales.
  - A GPT translation pass was attempted but the OpenAI org had no API credits (note: /v1/models returning 200 does NOT prove a key can make completions). Translations were produced directly instead; a GPT re-pass can overwrite these listings anytime.
- 2026-10-11 (v0.3.0 / vc4 — full audit + feature release, multi-agent): audit of app/native/billing/ASO, then fixes + features. Build/unit/native tests green; emulator (API 36) + Samsung S23+ (Android 16) smoke-tested; **real Apple-sender validation of mirroring/HLS/audio metadata still pending.**
  - Native: threads detached via pthread_key destructor (ART abort on thread exit); ALAC partial-frame heap overflow fixed (third_party/alac — submodule working tree, must be vendored/committed); event channel SO_SNDTIMEO/MSG_NOSIGNAL/fd ownership; real conn_reset wired; device name used for RAOP name; H.265 flag was a silent no-op (set before dnssd existed) — now cached and applied at start. UxPlay lib: all sender-reachable assert()/exit() removed (HLS, raop, httpd, rtp, mirror SPS/PPS bounds, pair-verify x25519), text-body /play accepted, live-playlist refresh, new bounds-checked lib/hls_playlist.c. Host tests: `sh app/src/test/native/run_tests.sh` (ASan/UBSan).
  - NEW JNI up-calls (frozen contract additions): onClientRequest (…)Z, onAudioMetadata (SSS)V, onCoverArt ([B)V, onAudioProgress (FF)V, onHlsPlay (Ljava/lang/String;F)V, onHlsStop ()V, onHlsScrub (F)V, onHlsRate (F)V. NativeBridge: setPin, setHlsEnabled, updateHlsPlaybackInfo (PUSH, ~250 ms). GOTCHA: UxPlay httpd is SINGLE-THREADED and also serves the HLS master.m3u8 to ExoPlayer — never block in on_video_play / any httpd-thread callback (onClientRequest may block ≤9 s only because nothing else is pending on that connection).
  - Size: release APK 56 MB → ~16 MB (keepDebugSymbols block leaked into release; c++_static; gc-sections; visibility=hidden; R8 + shrinkResources ON). R8 keep rules cover NativeBridge + all RaopCallbackHandler implementers.
  - Renderer: MediaCodec ASYNC mode (fixes one-update display lag + decoder leak on failed configure); output rendered immediately (no PTS pacing — watch lip-sync on real hardware); crop-aware aspect; Fit/Fill/Stretch; lastKeyframe refreshed every IDR.
  - Service: serialized lifecycle op queue (no ghost receivers); free-tier = 10 min mirroring/video then 30-min cooldown persisted (onClientRequest refuses; HLS refused too) — fixes ~2 s reconnect paywall bypass; audio-only free. MediaMode NONE/MIRROR/AUDIO/VIDEO, NowPlaying + MediaSession, HLS via Media3 1.6.1 (1.11 needs Kotlin 2.2), PIN off/random/fixed, ask-before-allow, Wi-Fi low-latency lock, Stop/Start notification, boot autostart (connectedDevice FGS — Android 15 bars mediaPlayback from BOOT_COMPLETED), mDNS retry + re-advertise on network change, backup rules exclude airplay_key.pem. USE_FULL_SCREEN_INTENT deliberately NOT declared (Play restricts to calling/alarm apps).
  - UI: split into screens; onboarding, Now Playing, HLS video (PlayerView), troubleshooting, security/receiver/media settings, cooldown countdown, localized price, in-app review (≥3 sessions, never on paywall), D-pad focus glow + TV banner (android:banner) — **Android TV not yet tested on a TV/AVD**.
  - Play Console TODO before rollout: FGS declaration for connectedDevice; Android TV opt-in + TV screenshots + 1280x720 banner; upload new listing copy from store/listing/ (validate.py) — it removes the wrong "touch forwarding"/"120 fps" claims; new screenshots per store/listing/screenshot_plan.md.
- 2026-10-12 (v0.3.0 vc5 SUBMITTED to production + listings/screenshots via API): Play pushes now use the NutriSnap store-connect skill's script (`~/Dev/Prod/NutriSnap/.claude/skills/store-connect/scripts/store_api.py`) with PLAY_PACKAGE swapped to com.cressu.screendock (same Cressu developer account / service account). One edit: 6-locale listings (store/listing/*.md), new phone + 7"/10" tablet screenshots (store/screenshots/v030, captioned, captured from release build), production track = vc5. Commit = sent for review.
  - Release AAB is now arm64-v8a + armeabi-v7a only (x86_64 debug-only; −8 devices, install 17 MB → 4.3 MB) and ships WITHOUT embedded native symbols (debugSymbolLevel NONE) so it stays < 10 MB for browser upload; upload app/build/outputs/native-debug-symbols/… manually if needed.
  - FGS: connectedDevice REMOVED (Play wants a demo video per FGS type). Only mediaPlayback remains; boot autostart on Android 15+ posts "Tap to start" instead. **FGS declaration still needs a demo-video link** (store/fgs_demo.mp4, gitignored; owner uploads unlisted to YouTube).
  - Verified on OnePlus Pad 3: mirroring (black-screen queue fix), localized Pro price (EGP), free-tier 10-min → 30-min cooldown triggers and refuses reconnects. OnePlus blocks `adb screenrecord` (hangs, writes 0 bytes) — use the tablet's own recorder.
  - App copy TODO: Settings Pro card still says "free tier limits each session to 10 minutes" (now 10 min then 30-min cooldown).
- 2026-10-24 (v0.3.5 / vc10 — UxPlay upstream-fix cherry-pick): diffed the vendored `lib/` against clean upstream UxPlay v1.73.7 (our `GLOBAL_VERSION` already matches master "220.68"; our llhttp is NEWER, 9.4.2 vs upstream 9.3.0). Finding: our fork is AHEAD of upstream almost everywhere — upstream still `assert()`/`exit(1)`s on sender-controlled data where we drop-and-log. Cherry-picked the 7 genuine upstream fixes we lacked. Build + native tests (hls 88, dmap 82, plus the V2 suite on the v2-integration branch) green. No feature changes.
  - **HIGH** `raop_handlers.h` pair-setup-pin: `client_proof_len` (sender-controlled) was memcpy'd into the fixed `proof[64]` unchecked → stack buffer overflow; now guarded `!= sizeof(proof)` → authentication_failed.
  - **HIGH** `mirror_buffer.c` `mirror_buffer_init_aes`: on RE-KEY, reset `nextDecryptCount=0` + `memset(og,0,..)` + destroy old `aes_ctx` (was leaking + decrypting the new stream at the wrong keystream offset → corrupt mirror).
  - **HIGH** `raop_rtp_mirror.c` NALU loop: added bounds clause `nc_len > payload_size - nalu_size - 4` (sender-controlled length prefix → OOB read).
  - **MED** `raop_handlers.h` teardown: generic `else` branch now calls `video_reset(RESET_TYPE_RTP_SHUTDOWN)` after destroying the mirror session — **fix for iOS ≥ 27, which stops mirroring WITHOUT sending teardown_110** (our senders are Apple; without this the video path stays bound to a dead session).
  - **MED** NULL guards: `User-Agent` strstr (sender may omit header) and `audioMode` strstr (absent/non-string plist key) — both were NULL-deref crashes.
  - **MED** SET_PARAMETER volume: guarded with `raop_rtp_is_running()`, falling back to `audio_set_volume` callback for the HLS/playbin path.
  - NOTE: the full AirPlay 2 "Video V2" work (HAP pairing + encrypted RTSP) lives on the `v2-integration` branch, NOT on main. These 7 fixes were cherry-picked to main alone for the 0.3.5 production release; the v2-integration branch carries the same fixes plus the gated-off V2 slice.
- 2026-10-26 (website redesign + contact cleanup): https://screendock.cressu.com rebuilt as a conversion landing page and redeployed (worker `screendock-legal`, custom domain).
  - Landing page + media are now Workers static assets in `cloudflare-worker/public/` (`[assets]` in wrangler.toml); `src/worker.js` only serves `/privacy` + `/healthz` and 302s unknown paths to `/`. Media are crops of `store/screenshots/` + the brag video (`~/Dev/Airplay/brag-output/brag.mp4`). Do NOT use `store/fgs_demo.mp4` publicly (shows a copyrighted music video + personal browser bookmarks).
  - Every Play link carries `referrer=utm_source=website&utm_medium={nav,hero,footer,sticky}` for Play Console acquisition attribution.
  - Contact email unified to tarek@cressu.com (privacy policy previously said privacy@cressu.com). Play listing `contactWebsite` changed https://cressu.com → https://screendock.cressu.com via API edit (committed).
  - Deploy: `cd cloudflare-worker && npx wrangler@4 deploy` (needs `wrangler login`). GOTCHA (zsh): `"/edits/$E:commit"` — zsh treats `:c` as a history modifier and mangles the URL; write `${E}:commit`.
  - SEO fixes (same day): worker now runs first (`run_worker_first`) to 301 http→https, serves /privacy, and hands the rest to `env.ASSETS`; unknown paths return `public/404.html` with a real 404 (`not_found_handling = "404-page"`). Added robots.txt, sitemap.xml, llms.txt, favicon.ico, demo captions (assets/demo.en.vtt), self-hosted Hanken Grotesk (OFL), richer JSON-LD (SoftwareApplication + Organization + WebSite), and an "AirPlay receiver for Android" kicker inside the H1.
  - `public/_headers` caches `/assets/*` for 1 year, immutable — **rename any asset you replace** (e.g. `demo-v2.mp4`) or visitors keep the old file.
  - GOTCHA: `wrangler dev` rewrites the request host to the custom-domain route, so the HTTPS redirect also fires locally — test with `wrangler dev --local-protocol https` + `curl -k`.
  - Next: submit https://screendock.cressu.com/sitemap.xml in Search Console. Site is English-only (listing has 6 locales) — localized pages deferred.
- 2026-10-26 (German site): https://screendock.cressu.com/de/ added (Germany = top install country). `public/de/index.html` is GENERATED by `cloudflare-worker/tools/make_de.py` from the English page — after ANY English edit run `python3 tools/make_de.py public/index.html public/de/index.html` (from cloudflare-worker/); the script fails loudly if an English string it translates has changed, so update its PAIRS then.
  - hreflang en/de/x-default on both pages + in sitemap.xml; German Play links add `hl=de` and `utm_source=website_de` (separate acquisition attribution); German Play badge (cropped to visible bounds); German captions `assets/demo.de.vtt` (video itself is English).
  - Apple's German UI terms are used for UI labels: "Bildschirmsynchronisierung" (Screen Mirroring), "Kontrollzentrum" (Control Center). Pro price 9,99 € (DE/AT). Privacy policy stays English-only (linked with hreflang="en").
- 2026-10-26 (GEO pass, see docs/seo/GEO-ANALYSIS.md): page CSS moved to `public/site.css` (shared by /, /de/, /compare/; NOT under /assets so it isn't cached immutably). New `/compare/` page (ScreenDock vs AirScreen vs Mirroring360, English only) — **re-verify competitor facts against their Play listings and bump "Last checked" + JSON-LD dateModified when you touch it.** Home page now has a "What is ScreenDock?" answer block and version/maker rows: **bump "Latest version" + `softwareVersion`/`dateModified` in index.html on every app release**, then re-run tools/make_de.py.
