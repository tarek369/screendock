# Cluster plan: "airplay receiver android"

Built 2026-10-26 for https://screendock.cressu.com. Data: 41 US web searches,
top results compared by shared URLs (spam hosts removed), with grouping by
search intent where the overlap data was too thin. Machine-readable plan:
`cluster-plan.json`. Visual map: `cluster-map.html`.

## What the search results show

- **ScreenDock appears for none of the 41 queries.**
- Results are fragmented and full of spam pages on unrelated domains
  (university and company subdomains). No site owns this topic, which makes
  focused, accurate pages a real opening.
- The strongest real competitor in results is **jqssun's open-source AirPlay
  Server** (GitHub, F-Droid; also built on UxPlay). It ranks across the "free",
  "open source", "for Android TV" and "audio receiver" queries.
- Three queries return **no page that answers them**: "mirror mac to android
  tablet" (results are about the reverse direction), "airplay to samsung galaxy
  tab" (only Samsung TV pages) and "airplay to pixel tablet".
- No search-volume data was available, so priorities come from overlap, gaps
  and fit with the app, not from volume.

## Architecture

The home page stays the pillar (it already targets "airplay receiver android"
and answers "can Android receive AirPlay"). No separate pillar page, to avoid
two pages competing for the same query.

| # | Page | Primary keyword | Also covers | Type | Words |
|---|---|---|---|---|---|
| 1 | `/android-tv/` AirPlay to Android TV and Google TV | airplay to android tv | airplay android tv, receiver for android tv, to google tv, chromecast with google tv, mirror iphone/mac to android tv (without apple tv), Sony Bravia, TCL, Netflix/DRM | how-to | 1,500 |
| 2 | `/iphone-to-android/` Mirror an iPhone or iPad to Android | mirror iphone to android tablet | screen mirroring iphone to android, mirror ipad to android tablet, airplay to android tablet, Galaxy Tab, Pixel Tablet | how-to | 1,400 |
| 3 | `/mac-to-android-tablet/` Android tablet as a Mac second screen | android tablet as second monitor for mac | …wireless second screen, mirror mac to android tablet | how-to | 1,400 |
| 4 | `/compare/` (update) AirPlay receivers compared | best airplay receiver app android | free / no ads / open source / AirPlay 2 / airplay server / uxplay android | comparison | 1,400 |
| 5 | `/airplay-audio/` AirPlay music to Android | airplay music to android | airplay audio receiver android, stream apple music to android tv | how-to | 1,200 |
| 6 | `/alternatives/airscreen/` AirScreen alternative without ads | airscreen alternative | airscreen android tv | comparison | 1,100 |
| 7 | `/help/not-showing/` Not showing on Android TV: fixes | airplay not showing on android tv | — | how-to | 1,000 |
| 8 | `/alternatives/mirroring360/` Mirroring360 alternative | mirroring360 alternative | — | comparison | 1,000 |

Order = recommended build order. Excluded: "airplay receiver fire tv"
(ScreenDock isn't on the Amazon Appstore).

## Internal links (35)

Every page links to and from the home page. Within clusters: TV guide ↔
troubleshooting; compare ↔ both alternative pages. Cross-links: iPhone ↔ Mac,
TV ↔ audio, TV → iPhone, iPhone → audio, compare → TV and Mac, all how-tos →
troubleshooting. Every page gets at least 3 incoming links; anchors use the
target keyword.

## Facts to confirm before writing (do not publish guesses)

1. **Mac page: mirror or extend?** macOS can "Use as separate display" with
   AirPlay receivers. The page must say whether ScreenDock supports extended
   display or mirroring only. Needs a test on real hardware.
2. **Compare update:** add jqssun's AirPlay Server honestly (free, open source,
   F-Droid/GitHub). Check its current features before writing.
3. **Galaxy Tab / Pixel Tablet:** include only what's been tested; say
   "any Android 7.0+ tablet" otherwise.
4. **TV brands (Sony, TCL, Chromecast with Google TV):** confirm the app is
   installable from Play on Google TV devices (it is listed for Android TV).

## German follow-up

After the English TV page proves out: `/de/android-tv/` for "airplay auf
android tv" and "iphone auf android tv spiegeln".

## How to know it worked

- Leading indicator: Search Console impressions for each primary keyword
  within 4–8 weeks of publishing.
- Failure check: a page with zero impressions after 8 weeks, or two pages
  showing for the same query (fix by merging).
