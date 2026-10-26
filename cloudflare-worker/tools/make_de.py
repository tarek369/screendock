"""Generate public/de/index.html from public/index.html with German copy.

Run after every change to the English page:
    python3 tools/make_de.py public/index.html public/de/index.html

Every pair must match exactly once (or the stated count), so drift in the
English page fails loudly instead of leaving English text in the German page.
"""
import sys

src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()

PLAY = "https://play.google.com/store/apps/details?id=com.cressu.screendock"

PAIRS = [
    # Head
    ('<html lang="en">', '<html lang="de">'),
    ('<title>ScreenDock: AirPlay Receiver for Android</title>',
     '<title>ScreenDock: AirPlay-Empfänger für Android</title>'),
    ('content="Mirror your Mac, iPhone or iPad to an Android phone, tablet or TV. Free AirPlay receiver with no ads and a one-time Pro unlock. Open source."',
     'content="Spiegle deinen Mac, dein iPhone oder iPad auf ein Android-Smartphone, Tablet oder einen Fernseher. Kostenloser AirPlay-Empfänger ohne Werbung, Pro als Einmalkauf. Open Source."'),
    ('<link rel="canonical" href="https://screendock.cressu.com/">',
     '<link rel="canonical" href="https://screendock.cressu.com/de/">'),
    ('<meta property="og:title" content="ScreenDock: AirPlay Receiver for Android">',
     '<meta property="og:title" content="ScreenDock: AirPlay-Empfänger für Android">\n<meta property="og:locale" content="de_DE">'),
    ('content="Your Android shows up in the Screen Mirroring menu. Mirror a Mac, iPhone or iPad over Wi-Fi."',
     'content="Dein Android erscheint im Menü „Bildschirmsynchronisierung“. Spiegle Mac, iPhone oder iPad über WLAN."'),
    ('<meta property="og:url" content="https://screendock.cressu.com/">',
     '<meta property="og:url" content="https://screendock.cressu.com/de/">'),
    ('"description":"AirPlay receiver for Android. Mirror a Mac, iPhone or iPad to an Android phone, tablet or TV."',
     '"description":"AirPlay-Empfänger für Android. Spiegle Mac, iPhone oder iPad auf ein Android-Smartphone, Tablet oder einen Fernseher.","inLanguage":"de"'),
    ('"url":"https://screendock.cressu.com/","name":"ScreenDock"',
     '"url":"https://screendock.cressu.com/de/","name":"ScreenDock","inLanguage":"de"'),

    # German compounds run long: smaller hero headline so it never meets the stage.
    # The German badge file is cropped to its visible bounds, so no negative margin.
    ('<link rel="stylesheet" href="/site.css">',
     '<link rel="stylesheet" href="/site.css">\n<style>.hero h1{font-size:clamp(38px,4.4vw,64px)}'
     '.play-badge img{height:52px;width:auto;margin:0}</style>'),
    ('src="/assets/google-play-badge.png" alt="Get it on Google Play" width="646" height="250"',
     'src="/assets/google-play-badge-de.png" alt="Get it on Google Play" width="646" height="192"', 2),

    # Answer block, specs, FAQ additions, compare link, schema feature list
    ('<h2 id="answer-title">What is ScreenDock?</h2>', '<h2 id="answer-title">Was ist ScreenDock?</h2>'),
    ("<p>ScreenDock is an AirPlay receiver for Android. Android can't receive AirPlay on its own; ScreenDock adds it, so an Android phone, tablet, Android TV or Google TV appears in the Screen Mirroring list on a Mac, iPhone or iPad. It mirrors the screen in H.264 or H.265 up to 4K at 60 fps, plays AirPlay music including Apple Lossless, and works over your Wi-Fi with nothing to install on the Apple side. It's free with 10-minute sessions, $9.99 once to remove the limit, has no ads, and is open source under GPLv3.</p>",
     '<p>ScreenDock ist ein AirPlay-Empfänger für Android. Android kann AirPlay von sich aus nicht empfangen; ScreenDock rüstet es nach. Dein Android-Smartphone, Tablet, Android TV oder Google TV erscheint dann in der Liste „Bildschirmsynchronisierung“ auf Mac, iPhone oder iPad. Die App spiegelt den Bildschirm in H.264 oder H.265 bis 4K mit 60 fps, spielt AirPlay-Musik auch in Apple Lossless und läuft über dein WLAN, ohne dass du auf dem Apple-Gerät etwas installieren musst. Sie ist kostenlos mit Sitzungen von 10 Minuten, kostet einmalig 9,99 €, um das Limit aufzuheben, zeigt keine Werbung und ist Open Source unter der GPLv3.</p>'),
    ('<div><dt>Latest version</dt><dd>0.3.5, updated October 2026</dd></div>', '<div><dt>Aktuelle Version</dt><dd>0.3.5, aktualisiert im Oktober 2026</dd></div>'),
    ('<div><dt>Made by</dt><dd><a href="https://cressu.com">Cressu</a>, open source on <a href="https://github.com/tarek369/screendock">GitHub</a></dd></div>',
     '<div><dt>Entwickelt von</dt><dd><a href="https://cressu.com">Cressu</a>, Open Source auf <a href="https://github.com/tarek369/screendock">GitHub</a></dd></div>'),
    ('<summary>Can Android receive AirPlay?</summary>', '<summary>Kann Android AirPlay empfangen?</summary>'),
    ("<p>Not out of the box. Android has no built-in AirPlay receiver, so a Mac, iPhone or iPad won't list it. Install an AirPlay receiver app such as ScreenDock and the Android device appears in the Apple device's Screen Mirroring and AirPlay menus.</p>",
     '<p>Nicht von Haus aus. Android hat keinen eingebauten AirPlay-Empfänger, deshalb zeigen Mac, iPhone und iPad es nicht an. Installiere eine AirPlay-Empfänger-App wie ScreenDock, dann erscheint das Android-Gerät in den Menüs „Bildschirmsynchronisierung“ und AirPlay deines Apple-Geräts.</p>'),
    ('<summary>How does ScreenDock compare with AirScreen or Mirroring360?</summary>', '<summary>Wie schneidet ScreenDock gegenüber AirScreen oder Mirroring360 ab?</summary>'),
    ('<p>ScreenDock is AirPlay-only, ad-free, open source and a one-time purchase. AirScreen also supports Google Cast and Miracast but shows ads, and Mirroring360 is a paid app. See the <a href="/compare/">side-by-side comparison</a>.</p>',
     '<p>ScreenDock unterstützt nur AirPlay, ist werbefrei, Open Source und ein Einmalkauf. AirScreen kann zusätzlich Google Cast und Miracast, zeigt aber Werbung, und Mirroring360 ist eine kostenpflichtige App. Mehr im <a href="/compare/" hreflang="en">Vergleich</a> (auf Englisch).</p>'),
    ('<a href="/compare/">Compare apps</a>', '<a href="/compare/" hreflang="en">Vergleich</a>'),
    ('"featureList":["AirPlay screen mirroring from Mac, iPhone and iPad","H.264 and H.265 up to 4K at 60 fps","AirPlay audio including Apple Lossless (ALAC)","AirPlay video from apps","Android TV and Google TV"]',
     '"featureList":["AirPlay-Bildschirmspiegelung von Mac, iPhone und iPad","H.264 und H.265 bis 4K mit 60 fps","AirPlay-Audio inklusive Apple Lossless (ALAC)","AirPlay-Video aus Apps","Android TV und Google TV"]'),

    # Nav
    ('<a class="skip" href="#main">Skip to content</a>', '<a class="skip" href="#main">Zum Inhalt springen</a>'),
    ('aria-label="ScreenDock home"', 'aria-label="ScreenDock Startseite"'),
    ('<a class="brand" href="/"', '<a class="brand" href="/de/"'),
    ('<nav class="nav-links" aria-label="Main">', '<nav class="nav-links" aria-label="Hauptmenü">'),
    ('href="#how">How it works</a>', 'href="#how">So funktioniert’s</a>'),
    ('href="#pricing">Pricing</a>', 'href="#pricing">Preise</a>'),
    ('href="#faq">FAQ</a>', 'href="#faq">Fragen</a>'),
    ('data-play>Get the app</a>', 'data-play>App laden</a>'),

    # Hero
    ('<span class="kicker">AirPlay receiver for Android</span> Your Android, in the Screen Mirroring menu.',
     '<span class="kicker">AirPlay-Empfänger für Android</span> Dein Android im Menü Bildschirm&shy;synchronisierung.'),
    ('ScreenDock turns an Android phone, tablet or TV into an AirPlay receiver. Pick it on your Mac, iPhone or iPad and your screen appears, sharp and in sync, over Wi-Fi.',
     'ScreenDock macht dein Android-Smartphone, Tablet oder deinen Fernseher zum AirPlay-Empfänger. Wähle es auf deinem Mac, iPhone oder iPad aus, und dein Bildschirm erscheint scharf und synchron über WLAN.'),
    ('alt="Get it on Google Play"', 'alt="Jetzt bei Google Play"', 2),
    ('<b>Free to install.</b><br>No ads, no account, no subscription.',
     '<b>Kostenlos installieren.</b><br>Keine Werbung, kein Konto, kein Abo.'),

    # Stage
    ('Screen Mirroring\n          </div>', 'Bildschirmsynchronisierung\n          </div>'),
    ('<div class="menu-cap">Try it: pick a receiver</div>', '<div class="menu-cap">Probier es aus: Wähle einen Empfänger</div>'),
    ('<span class="dev-name">Living Room TV</span>', '<span class="dev-name">Wohnzimmer-TV</span>'),
    ('<div class="menu-foot">Both receivers are running ScreenDock.</div>', '<div class="menu-foot">Auf beiden Empfängern läuft ScreenDock.</div>'),
    ('alt="ScreenDock on an Android tablet, ready to receive"', 'alt="ScreenDock auf einem Android-Tablet, bereit zum Empfang"'),
    ('alt="A Mac desktop mirrored to the Android tablet"', 'alt="Ein Mac-Schreibtisch, gespiegelt auf das Android-Tablet"'),
    ('alt="ScreenDock on Android TV, ready to receive"', 'alt="ScreenDock auf Android TV, bereit zum Empfang"'),
    ('alt="A Mac desktop mirrored to the Android TV"', 'alt="Ein Mac-Schreibtisch, gespiegelt auf Android TV"'),
    ('<span id="stage-text">Ready to receive</span>', '<span id="stage-text">Bereit zum Empfang</span>'),

    # Demo
    ('>Watch the 20-second demo</h2>', '>Die Demo in 20 Sekunden</h2>'),
    ('aria-label="20-second ScreenDock demo with sound"', 'aria-label="20-Sekunden-Demo von ScreenDock mit Ton"'),
    ('<track kind="captions" src="/assets/demo.en.vtt" srclang="en" label="English">',
     '<track kind="captions" src="/assets/demo.de.vtt" srclang="de" label="Deutsch" default>'),

    # How
    ('<h2 id="how-title">Connected in three taps.</h2>', '<h2 id="how-title">Verbunden mit drei Fingertipps.</h2>'),
    ('Nothing to install on the Apple side. ScreenDock uses the Screen Mirroring button your Mac, iPhone and iPad already have.',
     'Auf dem Apple-Gerät musst du nichts installieren. ScreenDock nutzt die Taste „Bildschirmsynchronisierung“, die Mac, iPhone und iPad bereits haben.'),
    ('<h3>Open ScreenDock</h3><p>Install it on your Android phone, tablet or TV and open it. It shows <kbd>Ready to receive</kbd> when it\'s listening.</p>',
     '<h3>ScreenDock öffnen</h3><p>Installiere die App auf deinem Android-Smartphone, Tablet oder Fernseher und öffne sie. Sobald sie bereit ist, zeigt sie <kbd>Bereit zum Empfang</kbd>.</p>'),
    ('<h3>Open Screen Mirroring</h3><p>On iPhone or iPad, swipe down for Control Center. On a Mac, click Control Center in the menu bar.</p>',
     '<h3>Bildschirmsynchronisierung öffnen</h3><p>Wische auf iPhone oder iPad nach unten, um das Kontrollzentrum zu öffnen. Auf dem Mac klickst du in der Menüleiste auf das Kontrollzentrum.</p>'),
    ('<h3>Pick your Android</h3><p>Choose ScreenDock from the list. Your screen appears in a second or two. Both devices need to be on the same Wi-Fi.</p>',
     '<h3>Dein Android wählen</h3><p>Wähle ScreenDock aus der Liste. Nach ein bis zwei Sekunden erscheint dein Bildschirm. Beide Geräte müssen im selben WLAN sein.</p>'),

    # Uses
    ('<h2 id="uses-title">Put the screen you already own to work.</h2>', '<h2 id="uses-title">Nutze die Bildschirme, die du schon hast.</h2>'),
    ('That spare tablet in the drawer, the Android TV in the living room, the old phone on your desk. Each one becomes somewhere to send your screen.',
     'Das Tablet in der Schublade, der Android TV im Wohnzimmer, das alte Smartphone auf dem Schreibtisch: Jedes davon wird zum Ziel für deinen Bildschirm.'),
    ('alt="A Mac desktop shown on an Android tablet"', 'alt="Ein Mac-Schreibtisch auf einem Android-Tablet"'),
    ('<h3>A wireless second screen for your Mac</h3>', '<h3>Ein kabelloser zweiter Bildschirm für deinen Mac</h3>'),
    ('Mirror your Mac to an Android tablet for a presentation, a demo, or a screen you can hand across the table. No cables or adapters.',
     'Spiegle deinen Mac auf ein Android-Tablet, für eine Präsentation, eine Vorführung oder einen Bildschirm, den du über den Tisch reichen kannst. Ohne Kabel und Adapter.'),
    ("<li>Shown at your tablet's native resolution, up to 4K</li>", '<li>In der nativen Auflösung deines Tablets, bis 4K</li>'),
    ('<li>Fit, fill or stretch the picture to the screen</li>', '<li>Bild einpassen, füllen oder strecken</li>'),
    ('alt="ScreenDock running on Android TV"', 'alt="ScreenDock auf Android TV"'),
    ('<h3>AirPlay on your Android TV</h3>', '<h3>AirPlay auf deinem Android TV</h3>'),
    ('Install ScreenDock on Android TV or Google TV and it shows up next to every Apple device in the house. Share photos, a video, or a game from your iPhone to the big screen.',
     'Installiere ScreenDock auf Android TV oder Google TV, und es erscheint auf jedem Apple-Gerät im Haus. Zeige Fotos, ein Video oder ein Spiel vom iPhone auf dem großen Bildschirm.'),
    ('<li>Works with a TV remote, start to finish</li>', '<li>Komplett mit der TV-Fernbedienung bedienbar</li>'),
    ('<li>Can start automatically when the TV turns on</li>', '<li>Startet auf Wunsch automatisch mit dem Fernseher</li>'),
    ('alt="ScreenDock home screen on a phone, ready to receive"', 'alt="ScreenDock-Startbildschirm auf einem Smartphone, bereit zum Empfang"'),
    ('alt="ScreenDock settings with PIN protection and device approval"', 'alt="ScreenDock-Einstellungen mit PIN-Schutz und Gerätefreigabe"'),
    ('<h3>Music, video and your rules</h3>', '<h3>Musik, Video und deine Regeln</h3>'),
    ('Stream songs from your iPhone with title, artist and cover art on screen, including lossless ALAC. Send YouTube and web videos with the AirPlay button.',
     'Streame Musik vom iPhone mit Titel, Interpret und Albumcover auf dem Bildschirm, auch verlustfrei in ALAC. Sende YouTube- und Web-Videos mit der AirPlay-Taste.'),
    ('<li>Optional PIN, or approve each new device</li>', '<li>Optionale PIN oder Freigabe für jedes neue Gerät</li>'),
    ('<li>Rename the receiver to anything you like</li>', '<li>Gib dem Empfänger einen beliebigen Namen</li>'),
    ('<li>Stop it any time from the notification</li>', '<li>Jederzeit über die Benachrichtigung beenden</li>'),

    # Specs
    ('<h2 id="specs-title">What it supports</h2>', '<h2 id="specs-title">Was ScreenDock unterstützt</h2>'),
    ('Built on UxPlay, the open-source AirPlay engine, with hardware video decoding on your Android device.',
     'Basiert auf UxPlay, der Open-Source-AirPlay-Engine, mit Hardware-Videodekodierung auf deinem Android-Gerät.'),
    ('<dt>Send from</dt><dd>Mac, iPhone and iPad</dd>', '<dt>Senden von</dt><dd>Mac, iPhone und iPad</dd>'),
    ('<dt>Receive on</dt><dd>Android phones, tablets, Android TV and Google TV</dd>', '<dt>Empfangen auf</dt><dd>Android-Smartphones, Tablets, Android TV und Google TV</dd>'),
    ('<dt>Video</dt><dd>H.264 and H.265 (HEVC), up to 4K at 60 fps</dd>', '<dt>Video</dt><dd>H.264 und H.265 (HEVC), bis 4K mit 60 fps</dd>'),
    ('<dt>Audio</dt><dd>AAC and Apple Lossless (ALAC)</dd>', '<dt>Audio</dt><dd>AAC und Apple Lossless (ALAC)</dd>'),
    ('<dt>Modes</dt><dd>Screen mirroring, music, and video from apps</dd>', '<dt>Modi</dt><dd>Bildschirmspiegelung, Musik und Video aus Apps</dd>'),
    ('<dt>Needs</dt><dd>Android 7.0 or newer, same Wi-Fi network (5 GHz recommended)</dd>', '<dt>Voraussetzungen</dt><dd>Android 7.0 oder neuer, dasselbe WLAN (5 GHz empfohlen)</dd>'),

    # Pricing
    ('<h2 id="pricing-title">Try it free. Buy it once.</h2>', '<h2 id="pricing-title">Kostenlos testen. Einmal kaufen.</h2>'),
    ('Everything works in the free version. Pro removes the time limit, for good, with one purchase through Google Play.',
     'In der kostenlosen Version funktioniert alles. Pro hebt das Zeitlimit dauerhaft auf, mit einem einzigen Kauf über Google Play.'),
    ('<h3>Free</h3>', '<h3>Kostenlos</h3>'),
    ('<p class="price">$0</p>', '<p class="price">0 €</p>'),
    ('<p>Every feature, in 10-minute sessions.</p>', '<p>Alle Funktionen, in Sitzungen von 10 Minuten.</p>'),
    ('10 minutes of mirroring or video, then a 30-minute break</li>', '10 Minuten Spiegelung oder Video, danach 30 Minuten Pause</li>'),
    ('Music streaming is always unlimited</li>', 'Musik-Streaming ist immer unbegrenzt</li>'),
    ('No ads, ever</li>', 'Niemals Werbung</li>'),
    ('<p class="price">$9.99<small>once</small></p>', '<p class="price">9,99 €<small>einmalig</small></p>'),
    ('<p>Unlimited mirroring on this Google account.</p>', '<p>Unbegrenzte Spiegelung für dein Google-Konto.</p>'),
    ('No time limits on mirroring or video</li>', 'Kein Zeitlimit für Spiegelung oder Video</li>'),
    ('No subscription, nothing to renew</li>', 'Kein Abo, nichts zu verlängern</li>'),
    ("Unlock it inside the app, after you've tried it</li>", 'Freischaltung in der App, nachdem du sie getestet hast</li>'),
    ('<p class="plans-note">US price shown. Google Play shows the price in your local currency.</p>',
     '<p class="plans-note">Preis für Deutschland und Österreich. Google Play zeigt den Preis in deiner Landeswährung.</p>'),

    # FAQ
    ('<h2 id="faq-title">ScreenDock questions</h2>', '<h2 id="faq-title">Fragen zu ScreenDock</h2>'),
    ("<summary>Why doesn't ScreenDock show up on my iPhone or Mac?</summary>", '<summary>Warum erscheint ScreenDock nicht auf meinem iPhone oder Mac?</summary>'),
    ("<p>Make sure both devices are on the same Wi-Fi network and ScreenDock says Ready to receive. Guest networks and some mesh or office networks block device discovery. The app's Troubleshooting screen walks through the common fixes.</p>",
     '<p>Prüfe, ob beide Geräte im selben WLAN sind und ScreenDock „Bereit zum Empfang“ anzeigt. Gastnetzwerke und manche Mesh- oder Firmennetzwerke blockieren die Geräteerkennung. Der Bildschirm „Fehlerbehebung“ in der App führt durch die häufigsten Lösungen.</p>'),
    ('<summary>Can I watch Netflix, Disney+ or the Apple TV app?</summary>', '<summary>Kann ich Netflix, Disney+ oder die Apple TV App ansehen?</summary>'),
    ('<p>No. Those apps protect their video with DRM, which only works with Apple-certified receivers. This is a limit of the protection, not a bug, and no third-party receiver can get around it. YouTube, browsers, photos, presentations and most other apps work.</p>',
     '<p>Nein. Diese Apps schützen ihre Videos mit DRM, das nur mit von Apple zertifizierten Empfängern funktioniert. Das ist eine Einschränkung des Kopierschutzes, kein Fehler, und kein Empfänger eines Drittanbieters kann sie umgehen. YouTube, Browser, Fotos, Präsentationen und die meisten anderen Apps funktionieren.</p>'),
    ('<summary>How much delay is there?</summary>', '<summary>Wie groß ist die Verzögerung?</summary>'),
    ("<p>On a good 5 GHz or Wi-Fi 6 network it's low enough for presentations, video and casual use. Keep both devices close to the router for the best result. Fast-paced gaming will feel some delay, as with any wireless mirroring.</p>",
     '<p>In einem guten 5-GHz- oder Wi-Fi-6-Netz ist sie gering genug für Präsentationen, Videos und den Alltag. Für das beste Ergebnis sollten beide Geräte nah am Router sein. Bei schnellen Spielen merkst du eine leichte Verzögerung, wie bei jeder kabellosen Spiegelung.</p>'),
    ('<summary>Can I control my Mac by touching the tablet?</summary>', '<summary>Kann ich meinen Mac über den Touchscreen des Tablets steuern?</summary>'),
    ("<p>Not today. ScreenDock shows your screen and plays its sound. Touch control back to the Mac isn't supported.</p>",
     '<p>Derzeit nicht. ScreenDock zeigt deinen Bildschirm und gibt den Ton wieder. Eine Touch-Steuerung zurück zum Mac wird nicht unterstützt.</p>'),
    ('<summary>Is my screen sent to any server?</summary>', '<summary>Wird mein Bildschirm an einen Server gesendet?</summary>'),
    ('<p>No. Everything stays on your local network, between your Apple device and your Android. ScreenDock has no accounts, analytics or tracking. Read the <a href="/privacy">privacy policy</a>.</p>',
     '<p>Nein. Alles bleibt in deinem lokalen Netzwerk, zwischen deinem Apple-Gerät und deinem Android. ScreenDock hat keine Konten, keine Analyse und kein Tracking. Lies die <a href="/privacy" hreflang="en">Datenschutzerklärung</a> (auf Englisch).</p>'),
    ('<summary>Is ScreenDock made by Apple?</summary>', '<summary>Ist ScreenDock von Apple?</summary>'),
    ('<p>No. ScreenDock is an independent open-source project under the GPLv3 license, and you can read every line of its <a href="https://github.com/tarek369/screendock">source code on GitHub</a>.</p>',
     '<p>Nein. ScreenDock ist ein unabhängiges Open-Source-Projekt unter der GPLv3-Lizenz. Jede Zeile des <a href="https://github.com/tarek369/screendock">Quellcodes auf GitHub</a> ist öffentlich.</p>'),

    # Final + footer
    ('<h2 id="final-title">Your next screen is already on your desk.</h2>', '<h2 id="final-title">Dein nächster Bildschirm liegt schon auf deinem Schreibtisch.</h2>'),
    ('<nav aria-label="Footer">', '<nav aria-label="Fußzeile">'),
    ('data-play>Google Play</a>', 'data-play>Google Play</a>'),
    ('>Source code</a>', '>Quellcode</a>'),
    ('<a href="/privacy">Privacy</a>\n      <a href="/de/" hreflang="de" lang="de">Deutsch</a>',
     '<a href="/privacy" hreflang="en">Datenschutz</a>\n      <a href="/" hreflang="en" lang="en">English</a>'),
    ('>Contact</a>', '>Kontakt</a>'),
    ('>Report a problem</a>', '>Problem melden</a>'),
    ('ScreenDock is an independent open-source project (GPLv3) and is not affiliated with Apple Inc. AirPlay, Mac, iPhone and iPad are trademarks of Apple Inc. Google Play and the Google Play logo are trademarks of Google LLC.',
     'ScreenDock ist ein unabhängiges Open-Source-Projekt (GPLv3) und nicht mit Apple Inc. verbunden. AirPlay, Mac, iPhone und iPad sind Marken der Apple Inc. Google Play und das Google Play-Logo sind Marken der Google LLC.'),
    ('<span><b>ScreenDock</b>Free AirPlay receiver</span>', '<span><b>ScreenDock</b>Kostenloser AirPlay-Empfänger</span>'),
    ('data-play tabindex="-1">Install</a>', 'data-play tabindex="-1">Installieren</a>'),

    # Script strings
    ("const NAMES = { tablet: 'ScreenDock (OnePlus Pad 3)', tv: 'Living Room TV' };",
     "const NAMES = { tablet: 'ScreenDock (OnePlus Pad 3)', tv: 'Wohnzimmer-TV' };"),
    ("text.textContent = 'Ready to receive';", "text.textContent = 'Bereit zum Empfang';"),
    ("text.textContent = 'Connecting to ' + NAMES[target] + '…';", "text.textContent = 'Verbinden mit ' + NAMES[target] + ' …';"),
    ("btn.querySelector('.dev-state').textContent = 'Mirroring';", "btn.querySelector('.dev-state').textContent = 'Aktiv';"),
    ("text.textContent = 'Mirroring your Mac to ' + NAMES[target];", "text.textContent = 'Dein Mac wird gespiegelt auf ' + NAMES[target];"),
]

for pair in PAIRS:
    a, b = pair[0], pair[1]
    n = pair[2] if len(pair) > 2 else 1
    found = s.count(a)
    if found != n:
        sys.exit(f"expected {n}x, found {found}x: {a[:80]}")
    s = s.replace(a, b)

# German Play listing + separate UTM source on every store link.
s = s.replace(f"{PLAY}&amp;referrer=utm_source%3Dwebsite", f"{PLAY}&amp;hl=de&amp;referrer=utm_source%3Dwebsite_de")
s = s.replace(f'href="{PLAY}" data-play', f'href="{PLAY}&amp;hl=de" data-play')

open(dst, "w", encoding="utf-8").write(s)
print("ok")
