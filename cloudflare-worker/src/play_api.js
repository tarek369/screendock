// Minimal Google Play Developer API client for the worker (service-account JWT via WebCrypto).
// Secret PLAY_SA_JSON = a service-account key with Play "View financial data" on ScreenDock only.

const PACKAGE = 'com.cressu.screendock';
const API = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}`;
const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';

const b64url = (bytes) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const b64urlJson = (obj) => b64url(new TextEncoder().encode(JSON.stringify(obj)));

function pemToDer(pem) {
  const body = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  return Uint8Array.from(atob(body), (c) => c.charCodeAt(0)).buffer;
}

async function accessToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${b64urlJson({ alg: 'RS256', typ: 'JWT' })}.${b64urlJson({
    iss: sa.client_email, scope: SCOPE, aud: sa.token_uri, iat: now, exp: now + 3600,
  })}`;
  const key = await crypto.subtle.importKey(
    'pkcs8', pemToDer(sa.private_key), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned));
  const res = await fetch(sa.token_uri, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${b64url(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`token ${res.status}`);
  return (await res.json()).access_token;
}

async function get(token, path) {
  const res = await fetch(`${API}/${path}`, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`GET ${path.split('/')[0]} ${res.status}`);
  return res.json();
}

/** Returns a Play API client, or null when the PLAY_SA_JSON secret is missing/invalid. */
export async function playClient(env) {
  if (!env.PLAY_SA_JSON) return null;
  let sa;
  try {
    sa = JSON.parse(env.PLAY_SA_JSON);
  } catch {
    return null;
  }
  if (!sa.client_email || !sa.private_key || !sa.token_uri) return null;
  const token = await accessToken(sa);
  return {
    productPurchase: (sku, purchaseToken) =>
      get(token, `purchases/products/${encodeURIComponent(sku)}/tokens/${encodeURIComponent(purchaseToken)}`),
    order: (orderId) => get(token, `orders/${encodeURIComponent(orderId)}`),
  };
}
