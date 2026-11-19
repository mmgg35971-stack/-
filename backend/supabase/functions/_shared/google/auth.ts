// Google service-account OAuth (JWT bearer grant, RS256 via WebCrypto).
// Used for the Play Developer API and FCM HTTP v1. No SDK needed.
import { encodeBase64Url } from "jsr:@std/encoding@1.0.10/base64url";
import { AppError } from "../http.ts";

export interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id?: string;
  token_uri?: string;
}

export function parseServiceAccount(raw: string | undefined | null): ServiceAccount | null {
  if (!raw) return null;
  try {
    const sa = JSON.parse(raw);
    if (typeof sa.client_email !== "string" || typeof sa.private_key !== "string") return null;
    return sa as ServiceAccount;
  } catch {
    return null;
  }
}

function pemToDer(pem: string): Uint8Array<ArrayBuffer> {
  const b64 = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

const enc = new TextEncoder();

/** Builds the signed JWT assertion (exported for tests). */
export async function signAssertion(sa: ServiceAccount, scope: string, nowSec = Math.floor(Date.now() / 1000)): Promise<string> {
  const header = encodeBase64Url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = encodeBase64Url(enc.encode(JSON.stringify({
    iss: sa.client_email,
    scope,
    aud: sa.token_uri ?? "https://oauth2.googleapis.com/token",
    iat: nowSec,
    exp: nowSec + 3600,
  })));
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claims}`)));
  return `${header}.${claims}.${encodeBase64Url(sig)}`;
}

const cache = new Map<string, { token: string; exp: number }>();

/** Access token for one scope, cached per instance until 60 s before expiry. */
export async function accessToken(sa: ServiceAccount, scope: string): Promise<string> {
  const key = `${sa.client_email}|${scope}`;
  const hit = cache.get(key);
  if (hit && hit.exp > Date.now() + 60_000) return hit.token;

  const res = await fetch(sa.token_uri ?? "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: await signAssertion(sa, scope),
    }),
  });
  if (!res.ok) {
    console.error("google token error", res.status, (await res.text()).slice(0, 300));
    throw new AppError(503, "provider_unavailable", "Google authentication failed");
  }
  const body = await res.json();
  cache.set(key, { token: body.access_token, exp: Date.now() + Number(body.expires_in ?? 3600) * 1000 });
  return body.access_token;
}
