# GEO Analysis: screendock.cressu.com

Audited 2026-10-26. Pages: `/` (English), `/de/` (German). Not measured with a
rank-tracking tool (no DataForSEO / SE Ranking), so platform readiness below is
qualitative.

## 1. GEO Readiness Score: 67/100

| Category | Weight | Score | Why |
|---|---|---|---|
| Citability | 25% | 60 | Clear facts (codecs, 4K/60, price, DRM limit) but no one-paragraph "what is ScreenDock" answer and no comparison against alternatives |
| Structural readability | 20% | 80 | Clean H1→H2→H3, steps as an ordered list, a spec list, FAQ as questions |
| Multi-modal | 15% | 80 | Real screenshots, demo video with EN/DE captions, interactive hero |
| Authority & brand | 20% | 25 | No dates, no named maker, almost no third-party mentions (one AlternativeTo listing, no Reddit/YouTube) |
| Technical accessibility | 20% | 95 | Static HTML, every crawler gets the full page, robots allows all, sitemap with hreflang |

The site is technically ready. The gap is off-site: AI answers for "AirPlay
receiver for Android" are built from listicles and forums that don't mention
ScreenDock yet.

## 2. Platform readiness (qualitative, not measured)

| Platform | Readiness | Blocking factor |
|---|---|---|
| Google AI Overviews / AI Mode | Low today | Site is new and likely not indexed yet; AIO mostly cites pages already ranking. Submit the sitemap in Search Console. |
| ChatGPT Search | Low | Uses Bing's index; site isn't submitted to Bing. No Reddit/Wikipedia presence. |
| Perplexity | Low | Leans heavily on Reddit; no Reddit threads mention ScreenDock. |
| Bing Copilot | Low | Same Bing index gap; no IndexNow. |

## 3. AI crawler access

Fetched `/` with each user-agent string on 2026-10-26: all got HTTP 200 with the
full 32 KB HTML. robots.txt is `User-agent: * / Allow: /`.

Search citability:

| Crawler | Governs | Status |
|---|---|---|
| Googlebot | Google Search, AI Overviews, AI Mode | Allowed |
| OAI-SearchBot | ChatGPT Search citations | Allowed |
| Claude-SearchBot | Claude search citations | Allowed |
| PerplexityBot | Perplexity search | Allowed |
| Applebot | Siri / Spotlight / Safari | Allowed |
| bingbot | Bing, Copilot, ChatGPT Search index | Allowed |

Training (a licensing choice, not a visibility one):

| Crawler | Governs | Status |
|---|---|---|
| GPTBot | OpenAI model training | Allowed |
| ClaudeBot | Anthropic model training | Allowed |
| Google-Extended | Gemini/Vertex training and grounding | Allowed (no rule) |
| CCBot | Common Crawl | Allowed |
| Applebot-Extended | Apple Intelligence training | Allowed (no rule) |

Allowing training is reasonable here: the app is open source and wants to be
known. **Caveat:** these tests spoofed the user-agent from one IP. Cloudflare's
AI Crawl Control can block *verified* AI bots by IP even when robots.txt allows
them. Check Cloudflare dashboard → AI Crawl Control and make sure the search
crawlers above aren't set to Block.

## 4. llms.txt

Present at `/llms.txt` with a summary, key links and key facts. Google says it
doesn't use it; it carries no weight in this score.

## 5. Brand mentions

| Source | Found |
|---|---|
| AlternativeTo | Listed, no reviews |
| Reddit | Nothing |
| YouTube | Nothing |
| Wikipedia / Wikidata | Nothing (not expected for a new app) |
| "Best AirPlay apps for Android" listicles (AirDroid, Wondershare) | Not included; they name AirScreen, Mirroring360, AirPlayMirror |
| GitHub | Repo is public (tarek369/screendock) |

## 6. Passage-level citability

Strong, self-contained passages already on the page:
- The DRM FAQ answer (explains what fails, why, and what works).
- The spec list (send from / receive on / video / audio / needs).
- The Free vs Pro comparison with exact limits (10 minutes, 30-minute break, $9.99 once).

Missing:
- A direct answer to "What is ScreenDock?" / "Can Android receive AirPlay?" in
  the first screen of text. The hero lede is close but written as marketing.
- Any comparison with AirScreen or Mirroring360, which is what "best AirPlay
  receiver for Android" answers are made of.
- A visible date or version, so freshness can't be judged.

## 7. Rendering

Fully static HTML served from Cloudflare Workers assets. All copy, specs, FAQ
and JSON-LD are in the initial HTML; JavaScript only drives the hero demo and
the mobile install bar. Nothing needs rendering.

## 8. Top 5 highest-impact changes

1. **Get indexed in Google and Bing.** Submit `sitemap.xml` in Search Console
   and Bing Webmaster Tools (Bing feeds ChatGPT Search and Copilot); add
   IndexNow to the deploy.
   *Fails if:* `site:screendock.cressu.com` returns nothing after 2 weeks.
   *Watch:* Search Console → Pages → Indexed count.
2. **Seed the places AI answers read from.** A "Show HN" / r/androidapps /
   r/MacOS / r/AndroidTV post framed around the open-source angle; upload the
   demo plus a 60-second setup video to YouTube (YouTube mentions correlate most
   strongly with AI visibility, Ahrefs 2025-12-12). Ask early users to review
   on AlternativeTo and mark it as an alternative to AirScreen.
   *Fails if:* no third-party page mentions ScreenDock in 30 days.
   *Watch:* Play Console acquisition from `utm_source` / referrers.
3. **Add a direct answer block near the top** (draft in section 10).
   *Fails if:* AI answers to "can Android receive AirPlay" still don't cite the site after indexing.
4. **Publish an honest comparison page** (`/compare` — ScreenDock vs AirScreen
   vs Mirroring360: price model, ads, open source, max resolution, H.265, ALAC,
   Android TV). This is the passage AI systems quote for "best X" queries.
   Keep claims about competitors factual and dated.
   *Watch:* impressions for "airscreen alternative" in Search Console.
5. **Show freshness and who makes it.** A "Version 0.3.5, updated October 2026"
   line in the specs and a one-line "Made by Cressu" with a link; mirror them in
   JSON-LD (`softwareVersion`, `dateModified`). Refresh both with every release.

## 9. Schema recommendations

Current: `SoftwareApplication` + `Organization` + `WebSite`. Add:

```json
"softwareVersion": "0.3.5",
"dateModified": "2026-10-24",
"featureList": ["AirPlay screen mirroring from Mac, iPhone and iPad","H.264 and H.265 up to 4K at 60 fps","AirPlay audio with ALAC","Android TV and Google TV"],
"isAccessibleForFree": true
```

Add the YouTube channel and AlternativeTo URL to `sameAs` once they exist.
Don't add FAQPage (Google retired FAQ rich results in May 2026); don't add
`aggregateRating` until the Play listing has real ratings.

## 10. Content rewrite suggestions

Add directly under the hero (both languages), as a short paragraph:

> **ScreenDock is an AirPlay receiver for Android.** Android can't receive
> AirPlay on its own; ScreenDock adds it, so an Android phone, tablet, Android
> TV or Google TV appears in the Screen Mirroring list on a Mac, iPhone or iPad.
> It mirrors the screen in H.264 or H.265 up to 4K at 60 fps, plays AirPlay
> music including Apple Lossless, and works over the same Wi-Fi network with no
> app on the Apple side. It's free with 10-minute sessions, $9.99 once to remove
> the limit, has no ads, and is open source (GPLv3).

Rename the FAQ heading "Questions" to "ScreenDock questions" and add one more
entry: "Can Android receive AirPlay?" answered in two sentences.

## Update 2026-10-26: changes applied

Done and deployed:
- **#3 Direct answer:** "What is ScreenDock?" paragraph under the hero (EN + DE), FAQ heading renamed "ScreenDock questions", new FAQ entries "Can Android receive AirPlay?" and "How does ScreenDock compare…".
- **#4 Comparison page:** https://screendock.cressu.com/compare/ (English) — ScreenDock vs AirScreen vs Mirroring360, facts from their Google Play listings, dated "Last checked 26 October 2026", WebPage + BreadcrumbList schema. Linked from both home pages' footers and the FAQ; in sitemap and llms.txt.
- **#5 Freshness + maker:** specs show "Latest version 0.3.5, updated October 2026" and "Made by Cressu"; JSON-LD gains `softwareVersion`, `dateModified`, `isAccessibleForFree`, `featureList`.

Still open:
- **#1** Submit sitemap in Google Search Console and Bing Webmaster Tools (owner).
- **#2** Owner made Reddit posts ~2026-10-19; Reddit blocks the audit tools, so they couldn't be checked. YouTube upload, Show HN and AlternativeTo reviews not done yet.
- Re-check the compare page's competitor facts every release (bump "Last checked").
