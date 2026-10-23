# AirPlay Video V2 (URL playback): receiver-side spec

Status legend: **VERIFIED** = seen in source code or on the wire in this investigation. **INFERRED** = deduced, not observed.

Sources (all local unless a URL is given):

- `PYATV` = `/private/tmp/claude-501/-Users-tarekhosni-Dev-Airplay/a5620f1f-cd45-4ff4-84e5-b8785936cff1/scratchpad/ref/pyatv` (git b277a4c, pyatv 0.18.0 installed from PyPI for the tool):
  `pyatv/protocols/raop/protocols/airplayv2.py`, `pyatv/protocols/airplay/player.py`, `pyatv/protocols/airplay/auth/{__init__,hap_transient,hap}.py`,
  `pyatv/auth/{hap_srp,hap_session,hap_tlv8,hap_channel}.py`, `pyatv/support/rtsp.py`, `pyatv/support/http.py`, `pyatv/protocols/airplay/{channels,ap2_session}.py`.
- `UXPLAY` = in-repo `app/src/main/cpp/third_party/UxPlay/lib/{raop.c,http_handlers.h}` (the receiver we ship; it implements the HTTP/HLS "V1-style" video path).
- `NTO` = https://nto.github.io/AirPlay.html (openairplay spec, Apple TV 5.x era).
- `LGTRACE` = `docs/airplay2_lg_trace.txt` (live capture, this repo, pyatv -> LG webOS TV).
- `LGINFO` = the LG `/info` plist (`plutil -p` of `lg_info.bin`).

> **Headline finding (VERIFIED, LGTRACE).** The pyatv `play_url` flow (HAP transient pair-setup -> SETUP -> RECORD -> `POST /play`) works up to and
> including RECORD on the LG (an Apple-licensed AirPlay SDK 2.3.1 receiver), but the LG answers **`404 Not Found` to `POST /play`**, `/playback-info`,
> `/reverse`, `/rate`, `/scrub`, `/stop`, `/server-info`, `/fp-setup`, `/action`, `/getProperty` (both HTTP/1.1 and RTSP/1.0 framing, with or without
> RECORD, with or without matching `X-Apple-Session-ID`). So pyatv's flow reproduces what an **Apple TV** accepts; it is **not** what a Mac sends to an
> `AirPlayVideoV2` third-party TV. The real macOS flow to the LG must be captured separately (section 11). Everything below about `/play` & friends is
> therefore "Apple-TV/pyatv + openairplay + UxPlay-verified"; whether macOS 26 uses the same endpoints toward V2-only receivers is INFERRED/UNPROVEN.

---

## 1. Feature bits and what a sender needs to pick the video path

Feature string = `features` TXT `0xLOW,0xHIGH`; 64-bit value = HIGH<<32 | LOW. Names from `PYATV/pyatv/protocols/airplay/utils.py` (`AirPlayFlags`) (VERIFIED naming; semantics beyond naming INFERRED).

| Receiver | features | Notable bits |
|---|---|---|
| ScreenDock today | `0x5A7FFEF7,0x400` | 0 VideoV1, 1 Photo, 2, 4 HLS, 5 Slideshow, 7 Screen, 9 Audio, 11, 14-27 (auth/metadata/audio fmts, 27 LegacyPairing), 30 UnifiedAdvertiser, 42 ScreenMultiCodec |
| LG UK6470PLC | `0x7F8AD0,0x38BCB46` (=255521305393072848) | **4 HLS**, 6, 7 Screen, 9 Audio, 11, 15-22; **33 VideoPlayQueue, 34 FromCloud, 38 UnifiedMediaControl, 40 BufferedAudio, 41 PTP, 43 SystemPairing, 46 HKPairing, 48 CoreUtilsPairing, 49 AirPlayVideoV2**, 51 UnifiedPairSetupMFi, 55/56 WoL. **No bit 0 (VideoV1), no bit 27 (legacy pairing), no FairPlay bits 2/14/23/26.** |
| macOS 26 (this Mac) | `0x4A7FCFD5,0x38174FDE` | (from `atvremote scan` in this session) |

- VERIFIED: pyatv picks **transient HAP** credentials purely from feature bits 43 (SystemPairing) or 48 (CoreUtilsPairing) (`PYATV auth/__init__.py extract_credentials`); otherwise no auth / legacy.
- VERIFIED: pyatv picks AirPlayV2 vs V1 stream protocol via `get_protocol_version(service, ...)` (RAOP/AirPlay features), not by video bits.
- INFERRED: a sender chooses the V2 *video* path when bit 49 is set (and 33/38 for the play-queue/unified-control variant). Nothing in pyatv reads bit 49. The LG has bit 49 but no bit 0, which is consistent with "HLS (bit 4) + V2 only". ScreenDock advertising bit 0/4 but lacking 43/48/49 is why macOS uses legacy pairing and then idles (INFERRED: it does not see a V2 video-capable receiver and is waiting for a mirroring/audio start).
- `/info` capability block (VERIFIED, LGINFO): `playbackCapabilities = {hlsJSVersion "4.0.16", fairPlayCDMPluginVersion "0.0", fairPlayLibVersion "0.0", supportsFPSSecureStop true, supportsUIForAudioOnlyContent false, webAppVersion "19.80.27", webEngineName "Stub", webEngineVersion "1.0"}`; `keepAliveLowPower true`, `keepAliveSendStatsAsBody true`, `statusFlags 580` (0x244, same as TXT `flags=0x244`), `PTPInfo`/`sdk` = `AirPlay;2.3.1-f.1`, `sourceVersion 377.25.06`, `protocolVersion "1.1"`, `volumeControlType 3`, `displays[{1920x1080, maxFPS 60, uuid}]`, `audioLatencies[...]`, `pi`/`deviceID`, `features` (int). INFERRED: the sender reads `playbackCapabilities` to decide it may hand the receiver an HLS URL (the receiver runs hls.js in a web app) and whether it must FairPlay-protect keys (`fairPlayLibVersion`/`CDMPlugin` "0.0" = no FairPlay).

## 2. Transport

1. TCP to the `_airplay._tcp` port (LG: 7000; ScreenDock: dynamic). Same port for HTTP/1.1-framed and RTSP/1.0-framed requests (VERIFIED, LGTRACE shows both on one connection).
2. Unencrypted: `POST /pair-pin-start`, `POST /pair-setup`, (`POST /pair-verify` for persistent pairing) as HTTP/1.1 (VERIFIED: pyatv sends these via `HttpConnection.post` -> `HTTP/1.1`).
3. After pair-setup M4 (transient) or pair-verify M4, **all subsequent bytes both directions are HAP-framed** (VERIFIED `hap_session.py`, LGTRACE):
   - Frame = `len(2 bytes, little endian) || ChaCha20-Poly1305(ciphertext, up to 1024 B plaintext) || tag(16)`; AAD = the 2 length bytes; nonce = 12 bytes, low 8 bytes = per-direction message counter (LE), starts at 0, increments per frame (Chacha20Cipher; INFERRED counter detail from `pyatv/support/chacha20.py`, matches shairport-sync).
   - Keys: `HKDF-SHA512(ikm = shared secret, salt="Control-Salt", info=...)`, 32 bytes. Sender->receiver ("output") info = `"Control-Write-Encryption-Key"`; receiver->sender ("input") info = `"Control-Read-Encryption-Key"` (VERIFIED `auth/__init__.py`). Receiver swaps them.
   - Shared secret: transient = the **SRP session key K** (SHA-512 of S, 64 bytes) (VERIFIED `hap_transient.encryption_keys`: `unhexlify(srp.shared_key)`); persistent verify = the X25519 ECDH shared secret (VERIFIED `hap_srp.verify2`).
4. Inside the encrypted stream, requests are **mixed framing** (VERIFIED, LGTRACE): `SETUP`, `RECORD`, `POST /feedback`, `GET /info`, `PUT /setProperty`, `POST /rate` etc. are `RTSP/1.0` with `CSeq`, `DACP-ID`, `Active-Remote`, `Client-Instance`; pyatv sends `POST /play` and `GET /playback-info` as `HTTP/1.1` (no CSeq) on the same connection. A receiver must answer each in the framing it was received (echo `RTSP/1.0` + `CSeq`, or `HTTP/1.1`). UxPlay does exactly this split: RTSP/1.0 handlers vs HTTP/1.1 handlers (`raop.c` ~line 410-495, VERIFIED).
5. Responses to RTSP requests may arrive out of order relative to concurrent requests (the sender fires `/feedback` every 2 s from a background task; LGTRACE part 1 shows the `/feedback` 200 interleaved with the `/play` exchange). Match by `CSeq`.

## 3. HAP transient pair-setup (VERIFIED: pyatv `hap_transient.py`, LGTRACE)

Sender headers on every pairing request: `User-Agent: AirPlay/320.20`, `Connection: keep-alive`, `X-Apple-HKP: 4` (transient) — `3` is used for **non-transient** pair-setup/verify (`auth/hap.py`), `Content-Type: application/octet-stream`. (The task brief said HKP 3; pyatv uses **4** for transient and the LG accepted it. 3 is the normal-pairing value; INFERRED that the receiver keys behaviour off the TLV Flags instead.) TLV8 encoding: type(1) len(1) value; values >255 bytes are split into consecutive fragments of the same type (the 384-byte SRP public key arrives as 255+129, LGTRACE).

| Step | Request | Response |
|---|---|---|
| 0 | `POST /pair-pin-start` HTTP/1.1, no body | `200 OK`, no body (LG: `Server: AirTunes/377.25.06`). On a real device with PIN display this would show a PIN; transient PIN is fixed `3939` (VERIFIED `TRANSIENT_PIN = 3939`). |
| M1 | `POST /pair-setup` body TLV: `Method(0x00)=0x00`, `SeqNo(0x06)=0x01`, `Flags(0x13)=0x10` (TransientPairing) | `200`, `application/octet-stream`, TLV: `SeqNo=0x02`, `Salt(0x02)` 16 B, `PublicKey(0x03)` 384 B (B, SRP-3072; fragmented 255+129). |
| M3 | `POST /pair-setup` TLV: `SeqNo=0x03`, `PublicKey` 384 B (A), `Proof(0x04)` 64 B (M1) | `200` TLV: `SeqNo=0x04`, `Proof` 64 B (M2). **Transient pairing ends here.** |

SRP details (VERIFIED `hap_srp.py step1/step2`): RFC 5054 3072-bit group (`PRIME_3072`, g=5), hash SHA-512, identity `"Pair-Setup"`, password = PIN `"3939"` (ASCII), client private = 32 random bytes, session key K = H(S). Receiver verifies M1 and returns M2; if the proof is wrong send TLV `SeqNo=0x04, Error(0x07)=0x02` (HAP Authentication; INFERRED from HAP spec). pyatv raises "proofs do not match" if M2 is wrong.

After M4 the sender immediately sends `SETUP` encrypted with keys from section 2. There is **no pair-verify in transient mode** (VERIFIED: `AirPlayHapTransientPairVerifyProcedure.verify_credentials` performs pair-setup M1-M4 and returns True; `_send(/pair-verify)` exists but is unused).

### Persistent pair-setup (PIN) M1..M6 (VERIFIED `hap.py`, `hap_srp.py`; not exercised on the wire)
`X-Apple-HKP: 3`; M1 `Method=0, SeqNo=1` (no Flags); M3 as above; **M5** `SeqNo=5, EncryptedData` = ChaCha20-Poly1305(TLV{Identifier=pairing_id, PublicKey=Ed25519 pub, Signature=Ed25519(HKDF("Pair-Setup-Controller-Sign-Salt","Pair-Setup-Controller-Sign-Info",K) || pairing_id || pub), optional Name(0x11)=opack({"name":..})}) keyed `HKDF("Pair-Setup-Encrypt-Salt","Pair-Setup-Encrypt-Info",K)` with nonce `"PS-Msg05"`; **M6** the receiver's equivalent with nonce `"PS-Msg06"`.

### pair-verify M1..M4 (VERIFIED `hap.py AirPlayHapPairVerifyProcedure`, `hap_srp.verify1/2`)
`POST /pair-verify` (`X-Apple-HKP: 3`, octet-stream TLV).
- M1: `SeqNo=1`, `PublicKey` = sender X25519 public (32 B).
- M2 (receiver): `SeqNo=2`, `PublicKey` = receiver X25519 public, `EncryptedData` = ChaCha20-Poly1305(TLV{Identifier=receiver id, Signature=Ed25519_ltsk(recv_x25519_pub || identifier || sender_x25519_pub)}) with key `HKDF("Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info", ECDH)`, nonce `"PV-Msg02"`.
- M3: `SeqNo=3`, `EncryptedData` = same cipher, nonce `"PV-Msg03"`, TLV{Identifier=sender id, Signature=Ed25519_ltsk(sender_x25519_pub || sender id || recv_x25519_pub)}.
- M4 (receiver): `SeqNo=4` (empty ok). Session keys from section 2 with the ECDH secret.
(The 8-byte-nonce ChaCha20 is `Chacha20Cipher8byteNonce`: 4 zero bytes + 8-byte nonce string.)

## 4. Events channel and SETUP

### 4.1 First SETUP (session; no `streams`) (VERIFIED, LGTRACE)
`SETUP rtsp://<senderIP>/<sessionID> RTSP/1.0`, `CSeq`, `DACP-ID`, `Active-Remote`, `Client-Instance`, `Content-Type: application/x-apple-binary-plist`. Body (pyatv values):

| key | type | value |
|---|---|---|
| deviceID | string | sender MAC |
| macAddress | string | sender MAC |
| sessionUUID | string | UUID upper-case |
| timingProtocol | string | `"NTP"` (pyatv). The remote-control-only session uses `"None"` (`ap2_session.py`). `"PTP"` is used by real senders when receiver bit 41 is set (INFERRED). |
| timingPort | int | sender's UDP NTP-timing server port (pyatv runs one; reply to the receiver's NTP-style requests there) |
| isMultiSelectAirPlay, groupContainsGroupLeader, senderSupportsRelay, statsCollectionEnabled | bool | True, False, False, False |
| model, name, osName, osVersion, osBuildVersion, sourceVersion | string | sender info |

Receiver response (VERIFIED, LG): `200`, `application/x-apple-binary-plist`, `CSeq` echoed, `Server: AirTunes/<ver>`. Body: `{"eventPort": int, "timingPort": int, "uiPreloaded": true}`. `uiPreloaded` is LG-specific (hls.js web app preloaded; INFERRED meaning). Minimum pyatv needs: `eventPort` (`resp.get("eventPort", 0)`).

### 4.2 Event channel (VERIFIED code, no events observed from LG in LGTRACE)
Right after the response, the **sender connects to the receiver's `eventPort`** (TCP), wrapped in HAP framing with keys `HKDF(salt "Events-Salt", info "Events-Read-Encryption-Key"/"Events-Write-Encryption-Key", sharedSecret)` (sender output key = `Events-Write...`? pyatv passes `EVENTS_READ_INFO` as output_info and `EVENTS_WRITE_INFO` as input_info in the ap2_session variant with comment "reversed here as connection originates from receiver"; in `airplayv2.py` it passes `(EVENTS_SALT, EVENTS_READ_INFO, EVENTS_WRITE_INFO)` = output=Read, input=Write. Receiver must mirror: it encrypts with the "Read" key and decrypts with "Write"... implementers: verify against a real capture; marked INFERRED). Retries up to 5x at 1 s because some receivers open the port late: **listen on `eventPort` before replying to SETUP.**
Events on this channel are HTTP-style requests from the receiver (`POST /event`, plist body); the sender replies `HTTP/1.1 200 OK`, `Content-Length: 0`, `Audio-Latency: 0`, echoing `CSeq`/`Server` (VERIFIED `channels.EventChannel`). Event body for video (NTO, V1 reverse channel; INFERRED to apply): `{category:"video", sessionID:int, state:"loading"|"playing"|"paused"|"stopped"}`. With `keepAliveSendStatsAsBody` (LG) feedback bodies carry stats (INFERRED).

### 4.3 RECORD (VERIFIED)
`RECORD rtsp://... RTSP/1.0` (no body). Response `200 OK`, `CSeq`, `Server`. pyatv sends it **before** `/play`.

### 4.4 Audio-stream SETUP (not used for URL video; used by pyatv for PCM streaming)
`SETUP` body `{"streams":[{"type":96(0x60),"audioFormat":0x800,"audioMode":"default","ct":1,"controlPort":int,"isMedia":true,"latencyMin":11025,"latencyMax":88200,"shk":<32B>,"spf":352,"sr":44100,"supportsDynamicStreamID":false,"streamConnectionID":int}]}` -> `{"streams":[{"controlPort","dataPort"}]}` (VERIFIED `airplayv2.setup_audio_stream`). Other stream types: 130 = remote-control data stream (`{"type":130,"controlType":2,"channelID","seed","clientUUID","wantsDedicatedSocket":true,"clientTypeUUID"}` -> `{"streams":[{"dataPort"}]}`, VERIFIED `ap2_session.py`); 103 buffered audio, 110 screen mirroring (INFERRED/known from shairport-sync/UxPlay).
**No `streams` entry exists for URL video in pyatv's flow (VERIFIED)**: video is entirely `/play`-driven over the control connection. Whether macOS->V2 receivers add a video `stream` type: UNKNOWN.

## 5. POST /play (VERIFIED request from pyatv + LGTRACE; response from UxPlay/NTO)

Request (`HTTP/1.1` framing in pyatv, sent after RECORD):
```
POST /play HTTP/1.1
User-Agent: AirPlay/550.10
Content-Type: application/x-apple-binary-plist
X-Apple-ProtocolVersion: 1
X-Apple-Session-ID: <uuid lower>      (pyatv: module-constant per process; must be the same on all later requests of the session)
X-Apple-Stream-ID: 1
Content-Length: N
```
Body bplist keys (pyatv): `Content-Location` string (the media URL, https or `mlhls://localhost/master.m3u8` for sender-hosted HLS), `Start-Position-Seconds` real (0.0; older senders: `Start-Position` real 0..1 fraction, NTO), `uuid` string (per-item), `streamType` int 1, `mediaType` string `"file"`, `mightSupportStorePastisKeyRequests` bool true (sender can answer FairPlay "Pastis" key requests), `playbackRestrictions` int 0, `referenceRestrictions` int 3, `rate` real 1.0, `volume` real 1.0, timing diagnostics `secureConnectionMs/infoMs/connectMs/authMs/bonjourMs/postAuthMs` ints, `SenderMACAddress`, `model`, `clientBundleID`, `clientProcName` (e.g. `"YouTube;"` is the only name UxPlay warns about not matching), `osBuildVersion`. Older/iTunes form: `Content-Type: text/parameters`, body `Content-Location: <url>\nStart-Position: 0.5\n` (NTO; UxPlay accepts both, `parse_play_request`).

Response: `200 OK` (HTTP/1.1, `Content-Length: 0` acceptable; pyatv only checks code; 500 => it retries 3x, other >=400 => failure). UxPlay answers 400 + disconnect on malformed plist. Per NTO, a digest-auth receiver may answer 401. **pyatv's behaviour: the `/play` request stays open on Apple TV for the whole playback (comment in `player.py`)**; do not rely on it; reply promptly (INFERRED safe: UxPlay replies immediately).
State to start: pyatv sets `rate` to 1.0 via a separate `/rate` after play ("will start paused otherwise").

**UxPlay caveat that matters for ScreenDock (VERIFIED `http_handlers.h http_handler_play`):** the in-repo receiver only accepts `Content-Location` containing `/master.m3u8` (the sender-proxied "FCUP" HLS used by YouTube: the receiver asks the sender for playlists through `POST /event` `unhandledURLRequest` on the reverse channel and the sender replies with `POST /action {type:"unhandledURLResponse"}`). A plain `https://.../x.m3u8` or `.mp4` (what pyatv sends and what Safari/QuickTime send for ordinary web video, INFERRED) is rejected with 400. A V2 receiver must play a plain HTTP(S) URL directly (ExoPlayer/Media3 can).

## 6. Follow-up requests after /play (VERIFIED request forms pyatv; response forms UxPlay/NTO)

All via `rtsp.exchange` (RTSP/1.0 + CSeq) in pyatv, headers `DACP-ID`, `Active-Remote`, `Client-Instance`:

| Request | Body | Receiver response |
|---|---|---|
| `PUT /setProperty?isInterestedInDateRange` | bplist `{"value": true}` | `200`; bplist `{"errorCode": 0}` (UxPlay for known props; NTO). VERIFIED: the LG answers `RTSP/1.0 PUT /setProperty` with **501 Not Implemented** (it is not in the LG's route table even though OPTIONS lists PUT) |
| `PUT /setProperty?actionAtItemEnd` | `{"value": 0}` (0 advance, 1 pause, 2 none; UxPlay comment) | `200` `{"errorCode":0}` |
| `POST /rate?value=1.000000` | none | `200`, `Content-Length: 0`. 0 = pause, 1 = play; other values trick-play. UxPlay -> `on_video_rate`. |
| `PUT /setProperty?forwardEndTime` / `reverseEndTime` | `{"value":{"flags":0,"value":0,"epoch":0,"timescale":0}}` (CMTime) | `200` `{"errorCode":0}` |
| `PUT /setProperty?selectedMediaArray` | `{"value":[{"MediaSelectionOptionsName","MediaSelectionOptionsUnicodeLanguageIdentifier",...}]}` | `200` (UxPlay stores language) |
| `POST /scrub?position=<seconds>` | none | `200`, `Content-Length: 0`. (HTTP/1.1 form in UxPlay/NTO.) |
| `GET /scrub` | none | `200`, `Content-Type: text/parameters`, body `duration: 596.47\nposition: 12.0\n` (NTO; UxPlay does not implement) |
| `POST /stop` | none | `200`, `Content-Length: 0`; receiver tears down player; UxPlay -> `on_video_stop`. |
| `POST /getProperty?playbackAccessLog` / `playbackErrorLog` | (NTO says GET, example POST) | bplist `{"errorCode":0,"value":[{...access log dict...}]}`. UxPlay: unhandled (empty 200). INFERRED optional. |
| `POST /action` | bplist `{"type": "playlistInsert" / "playlistRemove" / "unhandledURLResponse", "params": {...}}` + `X-Apple-Session-ID` | `200`. UxPlay handles `playlistRemove`, `playlistInsert`("playlistAdd" in comment), `unhandledURLResponse`; the **queue form for bit 33** (`playlistInsert` with item dicts) is only INFERRED; its param schema is not in pyatv. |
| `POST /feedback` | none (every 2 s in pyatv) | `200`, no body (LG). With `keepAliveSendStatsAsBody` the sender may put stats in the body (INFERRED); reply `200` regardless. |
| `GET /playback-info` | none (HTTP/1.1, polled 1/s) | below |
| `POST /command` | bplist (400 from LG when empty/`{}`) | exists on the LG: INFERRED unified-media-control (bit 38) command endpoint, schema UNKNOWN. |
| `POST /reverse` | headers `Upgrade: PTTH/1.0`, `Connection: Upgrade`, `X-Apple-Purpose: event`, `X-Apple-Session-ID` | `101 Switching Protocols` + `Upgrade: PTTH/1.0` + `Connection: Upgrade` (UxPlay; NTO). Connection then becomes receiver->sender HTTP (`POST /event`). Used by V1-style senders, not by pyatv; LG answers 404. |
| `GET /server-info` | headers `X-Apple-Device-ID`, `X-Apple-Session-ID` | `text/x-apple-plist+xml` `{features,macAddress,model,osBuildVersion,protovers,srcvers,vv}` (UxPlay; V1 only). LG: 404. |

### GET /playback-info response
pyatv: parses bplist; **"duration" present => playing**; `"error": {"code","domain"}` => playback error; no body/no `duration` for >5 polls => ended; 403 => treated as auth error. NTO/UxPlay content type `text/x-apple-plist+xml` (pyatv's `decode_bplist_from_body` accepts XML or binary). Keys (UxPlay `create_playback_info_plist_xml`, NTO):

`duration` real (s), `position` real (s), `rate` real (0/1), `readyToPlay` bool (UxPlay writes uint), `playbackBufferEmpty` bool, `playbackBufferFull` bool, `playbackLikelyToKeepUp` bool, `loadedTimeRanges` array of `{start: real, duration: real}`, `seekableTimeRanges` same. Before ready: return `{"readyToPlay": false}` (pyatv's own fake device, `tests/fake_device/airplay.py`, returns `{readyToPlay: False, uuid: 123}`). Finished: UxPlay closes the connection / returns no duration. Error: `{"error": {"code": int, "domain": str}}`.

## 7. TEARDOWN / end of session
`TEARDOWN rtsp://... RTSP/1.0` with `Session` header and optional bplist `streams` (LG replies `200`; VERIFIED in probe). pyatv closes the TCP connection to stop (`AirPlayStream.stop`). Receiver must treat TCP close of the control connection as end of playback.

## 8. FairPlay-protected streams (mostly INFERRED / literature)
- Apple TV+, iTunes, Netflix-style senders send FPS-encrypted HLS (`#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://..."`). With bit 2/14 FairPlay auth the sender runs `POST /fp-setup` (FairPlay v3, 2 round trips; UxPlay has `/fp-setup` for mirroring keys and `/fp-setup2` returning 421 "unhandled", VERIFIED `http_handlers.h`). `mightSupportStorePastisKeyRequests: true` in `/play` says the sender can relay "Pastis" key requests.
- A non-licensed receiver cannot decrypt FPS content: it has no Apple FairPlay Streaming key server/SPC-CKC capability. UxPlay explicitly refuses `/fp-setup2`; the project README states DRM is permanently unsupported. The LG advertises `fairPlayLibVersion/CDMPluginVersion "0.0"` and `supportsFPSSecureStop true` (VERIFIED /info) i.e. it advertises hooks but no library version; whether it plays FPS content is UNKNOWN (not tested).
- Practical rule (INFERRED): answer `/play` with `200`, then if the media playlist has `skd://` keys or ExoPlayer errors, surface `{"error":{"code":...,"domain":"..."}}` in `/playback-info` (pyatv aborts on this) and send `POST /event` state `stopped`, rather than crashing; the sender then falls back/shows an error. Clear (unencrypted) HLS and mp4 URLs have no key requests.

## 9. Test-sender coverage and gaps
`tools/airplay2_test_sender/airplay2_sender.py` (pyatv-based) reproduces sections 2-6 against any receiver: `discover`, `play` (HLS default or `--mp4`), `scrub`, `rate`, `stop`, `probe` (route table) and `--verbose/--trace-file` (decoded plaintext trace, key material redacted). It cannot exercise: non-transient pairing, PTP timing, play-queue (`/action playlistInsert`), FPS content, or the reverse (`/reverse`, PTTH) channel. Use `--actions "wait:4,scrub:30,rate:0,wait:2,rate:1,getscrub,stop"` for scripted sequences.

## 10. Observed behaviour of ScreenDock today (VERIFIED, LGTRACE part 4)
`pyatv` transient pairing: `POST /pair-pin-start` -> `200` (empty); `POST /pair-setup` M1 (Method=0, SeqNo=1, Flags=0x10) -> `200` with an **empty body** (UxPlay's legacy `/pair-setup` handler wants 32-byte Ed25519 key, not TLV8) -> pyatv raises `InvalidResponseError: got unexpected response`. Nothing after M1 is reachable. The receiver advertises `Server: AirTunes/220.68`, `vv=2`, TXT `flags=0x4`, no `pw`, and pyatv sees "Pairing: NotNeeded".
Receiver work implied: implement TLV8 pair-setup M1/M3 (SRP-3072/SHA-512, PIN 3939, Flags 0x10 transient), derive `Control-*` keys, HAP-frame the stream (both framings), SETUP-1 + eventPort + RECORD + `/feedback`, then the video verbs above; advertise bits 43/48 (and 49 if desired) in `features`, set `flags` accordingly.

## 11. Open questions / what must still be captured (UNKNOWN)
1. The exact request sequence a **Mac (QuickTime/Safari, macOS 26)** sends to an `AirPlayVideoV2` receiver. The LG refuses pyatv's `/play`, so either (a) the Mac sends `/play` on a different connection/session type, (b) it uses play-queue/unified-media-control (`/command`, `/action`, or MRP data-stream type 130) instead, or (c) pyatv's SETUP lacks a field that gates `/play` on the LG (tried: matching `X-Apple-Session-ID`/`sessionUUID`, with/without RECORD, `timingProtocol None`; all 404). Recommended capture: a Python fake receiver advertising the LG feature bits, performing server-side transient pair-setup (SRP server; pyatv has `pyatv/protocols/airplay/server_auth.py`) and logging every decrypted request while the user presses QuickTime's AirPlay button; or `log stream --predicate 'subsystem == "com.apple.airplay"'` on the Mac with an AirPlay logging profile.
2. Event-channel key direction and any events a V2 receiver is expected to emit.
3. PTP timing requirements (bit 41) vs NTP (pyatv uses NTP and the LG accepted it).
