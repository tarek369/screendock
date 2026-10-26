/**
 * ScreenDock site — Cloudflare Worker (free plan).
 * The landing page and its media are static assets in ./public. This script
 * runs first: it forces HTTPS, serves the privacy policy, and hands every other
 * request to the assets binding.
 * No dependencies, no storage, no data collection (fitting, for this app).
 */

const PRIVACY = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Privacy Policy — ScreenDock</title>
<script type="application/ld+json">
{"@context":"https://schema.org","@type":"WebPage","@id":"https://screendock.cressu.com/privacy#webpage","url":"https://screendock.cressu.com/privacy","name":"Privacy Policy — ScreenDock","inLanguage":"en","datePublished":"2026-09-19","isPartOf":{"@id":"https://screendock.cressu.com/#site"},"about":{"@id":"https://screendock.cressu.com/#app"}}
</script>
<style>
  body{margin:0;background:#07090D;color:#F2F5FA;font:16px/1.65 -apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}
  main{max-width:760px;margin:0 auto;padding:48px 24px 96px}
  h1{font-weight:200;font-size:38px;margin:0 0 4px}
  h2{font-weight:600;font-size:19px;margin:32px 0 8px;color:#F2F5FA}
  p,li{color:#A7B0C0}
  ul{padding-left:22px}
  a{color:#7FB3FF;text-decoration:none}
  .muted{color:#5E6779;font-size:13px}
</style>
</head>
<body>
<main>
  <h1>Privacy Policy</h1>
  <p class="muted">Effective date: September 19, 2026 · Applies to the ScreenDock Android application</p>

  <h2>1. Who we are</h2>
  <p>ScreenDock ("the app") is developed and operated by Cressu ("we", "us"). It turns an Android device into an AirPlay receiver so that Apple devices can mirror their screen or stream audio to it.</p>

  <h2>2. The short version</h2>
  <p><strong style="color:#F2F5FA">We do not collect, store, or share any personal data.</strong> The app has no accounts, no analytics, no advertising, and no tracking. It works entirely on your local network.</p>

  <h2>3. What the app processes (and why)</h2>
  <ul>
    <li><strong style="color:#F2F5FA">Local network traffic.</strong> To receive mirroring, the app communicates directly with Apple devices on your Wi-Fi network using the AirPlay protocol. Screen, audio, and control data sent by your Apple devices is decoded on your Android device and is never transmitted to us or to any third party.</li>
    <li><strong style="color:#F2F5FA">Device-generated identifiers.</strong> The app generates a local cryptographic key pair and derives a device identifier from your device's network hardware address, as required by the AirPlay protocol for discovery and encryption. These identifiers never leave your local network except as part of the protocol handshake with your own Apple devices.</li>
    <li><strong style="color:#F2F5FA">Notifications.</strong> The app posts a persistent Android notification while the receiver is running. This never sends data anywhere.</li>
    <li><strong style="color:#F2F5FA">Your settings.</strong> Preferences such as the receiver name and video quality are stored locally on your device only.</li>
  </ul>

  <h2>4. Payments</h2>
  <p>ScreenDock is free to download and use. An optional one-time purchase ("ScreenDock Pro") unlocks unlimited mirroring sessions. All purchases, transactions, and payment data are processed solely by Google Play. We never see, receive, or store your payment information.</p>

  <h2>5. Permissions the app requests</h2>
  <ul>
    <li>Internet / network state — required to receive AirPlay streams.</li>
    <li>Nearby network devices (Wi-Fi state / multicast) — required to advertise and discover devices via mDNS on your local network.</li>
    <li>Notifications &amp; foreground service — required to keep the receiver alive and visible while mirroring.</li>
  </ul>

  <h2>6. Children</h2>
  <p>ScreenDock is a general-audience utility and is not directed at children under 13. It does not knowingly collect any data from anyone, including children.</p>

  <h2>7. Third parties</h2>
  <p>The app contains no third-party analytics, advertising, or tracking SDKs. The only third-party service involved is Google Play, which handles distribution and payment.</p>

  <h2>8. Open source</h2>
  <p>ScreenDock is open source under GPLv3. You can inspect exactly how it handles data by reading the source code.</p>

  <h2>9. Changes to this policy</h2>
  <p>If we change this policy, we will update it on this page and revise the effective date. Material changes will also be reflected in the app's Play Store listing.</p>

  <h2>10. Contact</h2>
  <p>Questions or requests: <a href="mailto:tarek@cressu.com">tarek@cressu.com</a></p>
</main>
</body>
</html>`;

const HTML = { 'content-type': 'text/html; charset=utf-8' };

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // Cloudflare terminates TLS; plain-HTTP requests still reach us, so force HTTPS here.
    if (url.protocol === 'http:') {
      url.protocol = 'https:';
      return Response.redirect(url.toString(), 301);
    }
    const path = url.pathname.replace(/\/+$/, '') || '/';
    if (path === '/privacy' || path === '/privacypolicy') {
      return new Response(PRIVACY, { headers: HTML });
    }
    if (path === '/healthz') return new Response('ok');
    // Static site in ./public; unknown paths get public/404.html with a real 404 status.
    return env.ASSETS.fetch(request);
  },
};
