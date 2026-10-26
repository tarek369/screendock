# Agent-readiness report: screendock.cressu.com

Checked 2026-10-26. Lighthouse 13.5.0, run locally in headless Chrome 156
(PSI's shared quota was exhausted). Vendor/standards facts from the skill's
matrix dated 2026-09-23.

## Summary

- **Lighthouse Agentic Browsing: 3/3 mobile, 3/3 desktop.**
- **Agent-UX heuristic: 100/100** (505-node accessibility tree, 23 interactive nodes, 0 unnamed).
- **P0 failures: 0.**
- Nothing limits agents today. The site has no forms or transactions, so the
  remaining items are optional policy and discovery choices.

## Lighthouse Agentic Browsing

| Audit | Mobile | Desktop |
|---|---|---|
| agent-accessibility-tree | pass | pass |
| cumulative-layout-shift | pass (0) | pass (0) |
| llms-txt | pass | pass |
| webmcp-form-coverage | N/A (no forms) | N/A |
| webmcp-registered-tools | N/A | N/A |
| webmcp-schema-validity | N/A | N/A |
| ard-schema | N/A (no ai-catalog.json) | N/A |

Only path to a larger N: publish `/.well-known/ai-catalog.json` — only if there
are agent resources (MCP server, A2A agent) to list. There aren't, so don't.

## Findings by priority

| Pri | Check | Status | Evidence |
|---|---|---|---|
| P0 | Accessible tree | pass | Lighthouse 0 failed axe rules; heuristic 0 unnamed interactive |
| P0 | CLS ≤ 0.1 | pass | 0 on both form factors |
| P0 | Content without JS | pass | 1,031 words in raw HTML, no JS-shell marker |
| P0 | robots.txt reachable | pass | 200, `User-agent: *` / `Allow: /` |
| P0 | WAF vs AI user agents | pass (unverified traffic) | GPTBot, OAI-SearchBot, ChatGPT-User and others: 200, no challenge |
| P0 | Deliberate per-purpose robots groups | info | All agents fall through to `*`; fine while the policy is "allow everything" |
| P1 | Real 404 for unknown URLs | pass | `/nope` → 404 |
| P1 | llms.txt | pass | Present and passes Lighthouse rules |
| P1 | Content-Signal | info | None declared (owner's training-policy choice) |
| P1 | Markdown version | info | No `.md` sibling, no `Accept: text/markdown` negotiation |
| P1 | Private paths | pass | No private paths exist |
| P1 | Stable confirmation states / no hover-only menus or focus traps | pass (code review, not tool-measured) | Hero menu uses real `<button aria-pressed>`; FAQ uses `<details>`; sticky bar toggles `aria-hidden` + `tabindex` |
| P2/P3 | WebMCP, API catalog, OAuth, A2A, UCP, ai-catalog | N/A | No forms, APIs or agent resources |

## Access policy (robots.txt `*` group applies to all)

- **Training** (GPTBot, ClaudeBot, Google-Extended, CCBot, Applebot-Extended): allowed.
- **Search** (OAI-SearchBot, Claude-SearchBot, PerplexityBot, Googlebot, bingbot): allowed.
- **User-triggered** (ChatGPT-User, Claude-User, Perplexity-User, Google-Agent): allowed; several of these ignore robots.txt anyway.

## Standards status (checked 2026-09-23)

- WebMCP: W3C Community Group draft; WebKit opposes, Mozilla neutral.
- Content-Signal: Cloudflare policy + IETF draft; a preference, not enforcement; Google does not act on it.
- ai-catalog.json (ARD): proposal.
- llms.txt: community proposal; Google says it does not use it.

## Recommendations

1. **Decide the training policy, then declare it (optional, P1).** If you want
   AI search and answers but not model training:
   `Content-Signal: search=yes, ai-input=yes, ai-train=no` in the `*` group.
   Unblocks: an explicit, machine-readable preference. Confirm: rerun
   `agentic_check.py` → `content-signal` = pass. It changes nothing in Google Search.
2. **Markdown copy (optional, P1).** Publish `/index.md` (and `/de/index.md`)
   linked via `<link rel="alternate" type="text/markdown">`. No consumer agent
   is confirmed to request it; low cost, low expected value.
3. **Nothing else.** No WebMCP, ai-catalog or agent card — there are no forms,
   APIs or agent resources to describe.
