// Google Play Real-Time Developer Notifications → ntfy push.
// Pub/Sub push subscription POSTs here with ?token=<RTDN_TOKEN>.
// Secrets (wrangler secret put): RTDN_TOKEN, NTFY_TOPIC.

import { playClient } from './play_api.js';

const PACKAGE = 'com.cressu.screendock';
const NTFY_BASE = 'https://ntfy.sh/';

// https://developer.android.com/google/play/billing/rtdn-reference
const ONE_TIME_TYPES = {
  1: { title: 'ScreenDock Pro sold 💰', tags: 'moneybag', priority: '4' },
  2: { title: 'ScreenDock purchase canceled', tags: 'x', priority: '3' },
};

/** Constant-time string compare (avoid leaking the token via timing). */
function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Decode the Pub/Sub envelope; returns the RTDN object or null. */
export function decodeEnvelope(body) {
  const data = body && body.message && body.message.data;
  if (typeof data !== 'string') return null;
  try {
    return JSON.parse(atob(data));
  } catch {
    return null;
  }
}

/** Map an RTDN payload to a notification, or null when it is not worth a push. */
export function describe(n) {
  if (!n || n.packageName !== PACKAGE) return null;
  const when = n.eventTimeMillis ? new Date(Number(n.eventTimeMillis)).toISOString() : '';
  if (n.testNotification) {
    return { title: 'ScreenDock RTDN test ✅', tags: 'white_check_mark', priority: '3',
      body: `Play test notification received ${when}` };
  }
  if (n.oneTimeProductNotification) {
    const o = n.oneTimeProductNotification;
    const kind = ONE_TIME_TYPES[o.notificationType];
    if (!kind) return null;
    return { ...kind, body: `Product: ${o.sku || 'unknown'}\n${when}` };
  }
  if (n.voidedPurchaseNotification) {
    const v = n.voidedPurchaseNotification;
    return { title: 'ScreenDock purchase refunded ↩️', tags: 'leftwards_arrow_with_hook', priority: '4',
      body: `Order: ${v.orderId || 'unknown'}\n${when}` };
  }
  return null;
}

/** "BR" -> regional-indicator flag emoji; '' for anything that is not two letters. */
export function flag(country) {
  if (!/^[A-Za-z]{2}$/.test(country || '')) return '';
  return String.fromCodePoint(...[...country.toUpperCase()].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65));
}

/** Google Money {currencyCode, units, nanos} -> "50.99 BRL"; '' when absent. */
export function money(m) {
  if (!m || !m.currencyCode) return '';
  const value = Number(m.units || 0) + Number(m.nanos || 0) / 1e9;
  return `${value.toFixed(2)} ${m.currencyCode}`;
}

/** Order (Play orders API) -> extra title suffix + body lines. Pure, unit-tested. */
export function orderDetails(order) {
  if (!order) return null;
  const country = order.buyerAddress && order.buyerAddress.buyerCountry;
  const item = (order.lineItems || [])[0] || {};
  const lines = [
    money(item.listingPrice || order.total) && `Price: ${money(item.listingPrice || order.total)}`,
    money(order.developerRevenueInBuyerCurrency) && `You get: ${money(order.developerRevenueInBuyerCurrency)}`,
    country && `Country: ${flag(country)} ${country}`,
    order.orderId && `Order: ${order.orderId}`,
  ].filter(Boolean);
  return { suffix: country ? ` ${flag(country)} ${country}` : '', lines };
}

/** Best effort: look up country + price for purchases and refunds. Never throws. */
async function enrich(env, n, msg) {
  try {
    const play = await playClient(env);
    if (!play) return msg;
    let orderId = n.voidedPurchaseNotification && n.voidedPurchaseNotification.orderId;
    const o = n.oneTimeProductNotification;
    if (!orderId && o && o.purchaseToken && o.sku) {
      orderId = (await play.productPurchase(o.sku, o.purchaseToken)).orderId;
    }
    if (!orderId) return msg;
    const details = orderDetails(await play.order(orderId));
    if (!details) return msg;
    return { ...msg, title: msg.title + details.suffix, body: [...details.lines, msg.body].join('\n') };
  } catch (err) {
    console.error('rtdn enrich failed', err && err.message);
    return { ...msg, body: `${msg.body}\n(details unavailable)` };
  }
}

async function notify(topic, msg) {
  // JSON publish: header values must be Latin-1, which breaks emoji/flags in titles.
  const res = await fetch(NTFY_BASE, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      topic, title: msg.title, message: msg.body, tags: [msg.tags], priority: Number(msg.priority),
    }),
  });
  if (!res.ok) throw new Error(`ntfy ${res.status}`);
}

export async function handleRtdn(request, env) {
  if (request.method !== 'POST') return new Response('method not allowed', { status: 405 });
  const token = new URL(request.url).searchParams.get('token');
  if (!env.RTDN_TOKEN || !env.NTFY_TOPIC || !safeEqual(token, env.RTDN_TOKEN)) {
    return new Response('forbidden', { status: 403 });
  }
  let body;
  try {
    body = await request.json();
  } catch {
    // Malformed envelope: ack so Pub/Sub does not retry forever.
    return new Response('bad envelope', { status: 204 });
  }
  const rtdn = decodeEnvelope(body);
  const base = describe(rtdn);
  if (!base) return new Response(null, { status: 204 });
  const msg = await enrich(env, rtdn, base);
  try {
    await notify(env.NTFY_TOPIC, msg);
  } catch (err) {
    console.error('rtdn notify failed', err && err.message);
    // Non-2xx makes Pub/Sub retry the delivery later.
    return new Response('notify failed', { status: 502 });
  }
  return new Response(null, { status: 204 });
}
