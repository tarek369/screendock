# Schema report: screendock.cressu.com

Checked 2026-10-26 on `/`, `/de/`, `/compare/`, `/privacy`. Format: JSON-LD only
(no Microdata or RDFa). Proposed fixes: `generated-schema.json`.

## Validation results

| Page | Type | Status | Issues |
|---|---|---|---|
| `/` | SoftwareApplication | ⚠️ | Valid, but **not eligible for the app rich result**: Google requires `aggregateRating` or `review`, and there are no first-party ratings. Only the free offer is listed; Pro ($9.99) is missing. |
| `/` | Organization | ⚠️ | No `logo`, no `sameAs`. |
| `/` | WebSite | ✅ | — |
| `/` | (missing) VideoObject | ❌ | Demo video has no markup, so it can't appear in video results. |
| `/` | (missing) WebPage | ⚠️ | No node ties the page to its language and topic. |
| `/de/` | SoftwareApplication, WebSite | ⚠️ | Same `@id`s as `/` but with `inLanguage: de` — two pages give conflicting values for one entity. |
| `/compare/` | WebPage + BreadcrumbList | ✅ | Publisher is an inline Organization instead of a reference to `#org` (duplicate entity). |
| `/privacy` | — | ⚠️ | No markup (low priority). |

All URLs absolute, dates ISO 8601, no placeholders, no deprecated types, no FAQPage/HowTo.

## Recommendations (in order)

1. **Add VideoObject for the demo** (High) — the only new rich-result surface available. *Failure check:* Rich Results Test shows "Video" detected with no errors.
2. **Add the Pro offer, Organization logo, WebPage nodes, SoftwareSourceCode** (Medium) — clearer entity graph for Google and AI systems.
3. **Fix the `/de/` @id conflict and the inline publisher on `/compare/`** (Medium).
4. **Ratings** (blocked) — don't copy Play Store ratings into `aggregateRating`: Google's review-snippet rules forbid ratings collected on other sites. The app rich result needs first-party reviews on this site, which isn't worth building now.
5. `/privacy` WebPage (Low).
