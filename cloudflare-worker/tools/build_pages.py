"""Build guide pages: wrap each pages/**/*.html fragment in the shared layout.

Each fragment starts with a JSON header comment:
    <!--meta {"path": "/android-tv/", "title": "...", "description": "...",
              "crumb": "Android TV", "published": "2026-10-26", "modified": "2026-10-26"} -->
followed by the page body (the <h1> and everything below it).

Run from cloudflare-worker/ after editing any fragment:
    python3 tools/build_pages.py
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGES = ROOT / "pages"
PUBLIC = ROOT / "public"
SITE = "https://screendock.cressu.com"
PLAY = "https://play.google.com/store/apps/details?id=com.cressu.screendock"

META_RE = re.compile(r"\A<!--meta (\{.*?\}) -->\n", re.S)
REQUIRED = ("path", "title", "description", "crumb", "published", "modified")

LAYOUT = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<link rel="canonical" href="{url}">
<meta name="theme-color" content="#090B1A">
<meta property="og:type" content="article">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{description}">
<meta property="og:url" content="{url}">
<meta property="og:image" content="{site}/assets/og.png">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="/assets/icon.webp" type="image/webp">
<link rel="apple-touch-icon" href="/assets/apple-touch-icon.png">
<link rel="preload" href="/assets/fonts/hanken-grotesk-latin.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="/site.css">
<script type="application/ld+json">
{jsonld}
</script>
</head>
<body>
<a class="skip" href="#main">Skip to content</a>

<header class="wrap nav">
  <a class="brand" href="/" aria-label="ScreenDock home"><img src="/assets/icon.webp" alt="" width="34" height="34">ScreenDock</a>
  <nav class="nav-links" aria-label="Main">
    <a class="hide-sm" href="/#guides">Guides</a>
    <a class="hide-sm" href="/compare/">Compare</a>
    <a class="btn-small" href="{play}&amp;referrer=utm_source%3Dwebsite%26utm_medium%3D{medium}_nav" data-play>Get the app</a>
  </nav>
</header>

<main id="main" class="wrap guide">
  <nav class="crumbs" aria-label="Breadcrumb"><a href="/">ScreenDock</a> <span aria-hidden="true">/</span> <span aria-current="page">{crumb}</span></nav>
{body}
  <aside class="guide-cta" aria-label="Get ScreenDock">
    <a class="play-badge" href="{play}&amp;referrer=utm_source%3Dwebsite%26utm_medium%3D{medium}_footer" data-play>
      <img src="/assets/google-play-badge.png" alt="Get ScreenDock on Google Play" width="646" height="250" loading="lazy">
    </a>
    <p class="cta-note"><b>Free to install.</b><br>No ads, no account, no subscription.</p>
  </aside>
  <p class="updated">Updated <time datetime="{modified}">{modified_human}</time></p>
</main>

<footer>
  <div class="wrap">
    <nav aria-label="Footer">
      <a href="/">Home</a>
      <a href="/android-tv/">Android TV</a>
      <a href="/iphone-to-android/">iPhone &amp; iPad</a>
      <a href="/mac-to-android-tablet/">Mac second screen</a>
      <a href="/airplay-audio/">Music</a>
      <a href="/help/not-showing/">Not showing up?</a>
      <a href="/compare/">Compare apps</a>
      <a href="/privacy">Privacy</a>
      <a href="mailto:tarek@cressu.com">Contact</a>
    </nav>
    <p>© 2026 Cressu. ScreenDock is an independent open-source project (GPLv3) and is not affiliated with Apple Inc. AirPlay, Mac, iPhone and iPad are trademarks of Apple Inc. Other product names are trademarks of their owners and are used here only to identify their products.</p>
  </div>
</footer>
</body>
</html>
"""

MONTHS = ["January", "February", "March", "April", "May", "June", "July",
          "August", "September", "October", "November", "December"]


def human_date(iso):
    y, m, d = (int(x) for x in iso.split("-"))
    return f"{d} {MONTHS[m - 1]} {y}"


def jsonld(meta, url):
    h1 = meta["h1"]
    graph = [
        {"@type": "TechArticle", "@id": url + "#article", "headline": h1,
         "description": meta["description"], "url": url, "inLanguage": "en",
         "datePublished": meta["published"], "dateModified": meta["modified"],
         "author": {"@id": SITE + "/#org"}, "publisher": {"@id": SITE + "/#org"},
         "about": {"@id": SITE + "/#app"}, "isPartOf": {"@id": SITE + "/#site"},
         "image": SITE + "/assets/og.png"},
        {"@type": "BreadcrumbList", "itemListElement": [
            {"@type": "ListItem", "position": 1, "name": "ScreenDock", "item": SITE + "/"},
            {"@type": "ListItem", "position": 2, "name": meta["crumb"], "item": url}]},
    ]
    return json.dumps({"@context": "https://schema.org", "@graph": graph},
                      ensure_ascii=False, indent=1)


def esc(text):
    return text.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;")


def build(fragment):
    raw = fragment.read_text(encoding="utf-8")
    m = META_RE.match(raw)
    if not m:
        sys.exit(f"{fragment}: missing <!--meta {{...}} --> header")
    meta = json.loads(m.group(1))
    missing = [k for k in REQUIRED if k not in meta]
    if missing:
        sys.exit(f"{fragment}: meta missing {missing}")
    body = raw[m.end():].rstrip() + "\n"
    h1 = re.search(r"<h1>(.*?)</h1>", body, re.S)
    if not h1:
        sys.exit(f"{fragment}: body needs exactly one <h1>")
    meta["h1"] = re.sub(r"<[^>]+>", "", h1.group(1)).strip()
    url = SITE + meta["path"]
    html = LAYOUT.format(
        title=esc(meta["title"]), description=esc(meta["description"]), url=url,
        site=SITE, play=PLAY, crumb=esc(meta["crumb"]), body=body,
        medium=meta["path"].strip("/").replace("/", "_") or "home",
        modified=meta["modified"], modified_human=human_date(meta["modified"]),
        jsonld=jsonld(meta, url))
    out = PUBLIC / meta["path"].strip("/") / "index.html"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    return meta["path"]


def main():
    built = [build(f) for f in sorted(PAGES.rglob("*.html"))]
    print("built:", ", ".join(built))


if __name__ == "__main__":
    main()
