# ScreenDock: screenshot, TV and promo plan

Status: draft, local only. Nothing here is published. Captions are overlay text on each phone or tablet screenshot. Keep them at six words or fewer.

## Phone and tablet screenshots (8 concepts)

Order follows what a searcher sees first. Play shows the first two or three in search results, so slots 1 to 3 carry the differentiator and the strongest use case.

| # | Concept (image) | en-US | de-DE | fr-FR | es-ES | ja-JP | pt-BR |
|---|---|---|---|---|---|---|---|
| 1 | Mac screen mirrored on an Android tablet. Overlay shows "no ads" | AirPlay receiver. No ads. | AirPlay-Empfänger. Ohne Werbung. | Récepteur AirPlay sans publicité. | Receptor AirPlay sin anuncios. | 広告なしのAirPlayレシーバー | Receptor AirPlay sem anúncios. |
| 2 | Android tablet used as second Mac display (desk shot, cable-free) | Use Android as a second display | Android als zweites Display | Votre second écran Android | Tu segunda pantalla Android | Androidをセカンドディスプレイに | Use o Android como segunda tela |
| 3 | iPhone mirrored to an Android device (phone-in-hand shot) | Mirror your iPhone or iPad | iPhone oder iPad spiegeln | Miroir d'iPhone ou d'iPad | Refleja tu iPhone o iPad | iPhone・iPadをミラーリング | Espelhe iPhone ou iPad |
| 4 | Audio mode: now-playing card, large cover art, ALAC badge | Lossless music with cover art | Verlustfreie Musik mit Cover | Musique sans perte, pochette incluse | Música sin pérdida con portada | ロスレス音楽とジャケット表示 | Música sem perdas com capa |
| 5 | Video mode: YouTube playing full screen after AirPlay tap | Cast YouTube with AirPlay | YouTube per AirPlay senden | Envoyez YouTube en AirPlay | Envía YouTube con AirPlay | YouTubeをAirPlayで送信 | Envie YouTube por AirPlay |
| 6 | Android TV / Google TV home with ScreenDock running (TV frame) | Your TV becomes an AirPlay receiver | Dein TV wird zum AirPlay-Empfänger | Votre TV devient récepteur AirPlay | Tu TV, receptor AirPlay | テレビがAirPlayレシーバーに | Sua TV vira receptor AirPlay |
| 7 | Settings: fit / fill / zoom and resolution, native-resolution label | Fit, fill or zoom your picture | Bild einpassen, füllen, zoomen | Ajustez, remplissez ou zoomez | Ajusta, rellena o amplía | フィット・フィル・ズーム | Ajuste, preencha ou amplie |
| 8 | Security: "Ask before allowing" prompt over mirrored content, PIN field visible | Ask before allowing each sender | Vor jedem Zugriff nachfragen | Demander avant d'autoriser | Pregunta antes de permitir | 接続前に許可を確認 | Pergunte antes de permitir |

### Ordering rationale

1. Slot 1 states the two differentiators the description leads with (AirPlay receiver, no ads). It is the only shot most people see in search.
2. Slots 2 and 3 show the two strongest use cases (second display for a Mac, phone or tablet mirroring). Both match the high-intent queries in keywords.md.
3. Slots 4 and 5 show the new media modes. They are less common in competitor listings, so they make the page look different. Slot 5 uses YouTube content only, never a DRM service.
4. Slot 6 targets TV intent, which is a new surface this release.
5. Slots 7 and 8 close on control and safety. Security reassures users who worry about strangers joining their screen.

Free versus Pro is stated in the description, not in a screenshot. That avoids a paywall image the store may treat as a price claim.

### Production rules

- Show no DRM service (Netflix, Disney+, Apple TV+) on any screen, even as a blurred background.
- Show no touch or remote-input features. Touch forwarding is not supported and must not appear.
- Show the frame rate only as "up to 60 fps" if it appears at all.
- Use the native device frame, not a generic mockup, so the screen matches the app.
- English screenshot pixels can be reused for all locales. Only the caption layer changes. Japanese captions need a font with full Japanese coverage.

## TV banner (320x180 concept)

Concept: Android TV home row on the left, ScreenDock icon, the AirPlay logo replaced by a generic wifi-and-screen glyph (do not use the Apple logo). Text: "ScreenDock: AirPlay Receiver" in a single line, 18 px minimum at 320 wide.

- Background: solid dark (#101418) with a soft teal gradient, no photography.
- Overlay tagline (en-US): "AirPlay on your TV" (4 words).
- Locale tags: de "AirPlay auf dem TV", fr "AirPlay sur votre TV", es "AirPlay en tu TV", ja "テレビでAirPlay", pt "AirPlay na sua TV".
- Export note: Play Console's TV banner spec is larger (1280x720 as I recall). Build at 16:9 and export at 1280x720; 320x180 is the preview size. Confirm the exact spec in Play Console before upload.

## TV screenshot concepts (landscape, 16:9)

1. TV-1: A Mac screen mirrored full screen on a TV, ScreenDock status card in the corner. Caption: "Mirror your Mac to the TV."
2. TV-2: Android TV launcher with the AirPlay receiver active, plus a remote cursor. Caption: "Your TV, an AirPlay receiver."
3. TV-3: Audio mode full screen, large cover art, title and artist text, ALAC badge. Caption: "Lossless music, full-screen cover."

Captions for other locales follow the same translations as the phone captions. TV-2 wording ("remote") must be checked in each language against the actual Android TV remote labels.

## 30-second promo video storyboard

Format: 1920x1080, 30 seconds, no voiceover. Music bed only, with on-screen captions in the locale of the listing. Keep end-card text legible for 3 seconds.

| Time | Visual | On-screen text (en-US) |
|---|---|---|
| 0:00 to 0:03 | Mac menu bar, cursor clicks the AirPlay icon, ScreenDock appears in the list | "AirPlay receiver for Android" |
| 0:03 to 0:08 | Mac screen mirrors to an Android tablet, window drag shown | "Mirror your Mac" |
| 0:08 to 0:12 | Tablet used as second display, desk setup, no cables | "Use Android as a second display" |
| 0:12 to 0:17 | iPhone mirror, phone in hand, Android device beside it | "Mirror iPhone or iPad" |
| 0:17 to 0:21 | Audio mode: now-playing card, cover art, lossless badge | "Lossless music with cover art" |
| 0:21 to 0:25 | YouTube AirPlay tap, video plays on Android | "Cast YouTube with AirPlay" |
| 0:25 to 0:27 | Android TV home with receiver visible | "Your TV is a receiver too" |
| 0:27 to 0:30 | End card: logo, "No ads. One-time Pro.", store badge, legal line below | "No ads. One-time Pro." |

Legal line on the end card (all locales, small text): "AirPlay is a trademark of Apple Inc. ScreenDock is not affiliated with Apple Inc. DRM-protected content is not supported."

Production rules: no competitor or Apple UI beyond the standard AirPlay icon. Record the AirPlay tap on a real device, not a mockup.
