// Gift-code + admin-secret cryptography. Pure functions => unit-testable.
//
// Codes look like  PRO-X7K9-M2PL-Q8WA  /  PREM-9K2L-7M5Q-3XPA
// * 12 random symbols from a 32-char alphabet without 0/O/1/I  => 60 bits of entropy.
// * 32 divides 256, so `byte % 32` has no modulo bias.
// * Only HMAC-SHA256(pepper, normalized code) is stored in the database.

export const CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // 32 symbols
export type GiftPlan = "pro" | "premium";

const PREFIX: Record<GiftPlan, string> = { pro: "PRO", premium: "PREM" };

export function generateGiftCode(plan: GiftPlan, random: (n: number) => Uint8Array = secureRandom): string {
  const bytes = random(12);
  let body = "";
  for (let i = 0; i < 12; i++) {
    body += CODE_ALPHABET[bytes[i] % 32];
    if (i === 3 || i === 7) body += "-";
  }
  return `${PREFIX[plan]}-${body}`;
}

/**
 * Accepts user input with spaces, lowercase, other dash characters or missing
 * dashes ("prem 9k2l7m5q3xpa") and returns the canonical form "PREM-9K2L-7M5Q-3XPA".
 */
export function normalizeCode(input: string): string {
  const compact = input.toUpperCase().replace(/[\s_\-—–]+/g, "");
  const m = /^(PREM|PRO)([A-Z0-9]{12})$/.exec(compact);
  if (!m) return compact;
  const b = m[2];
  return `${m[1]}-${b.slice(0, 4)}-${b.slice(4, 8)}-${b.slice(8, 12)}`;
}

export function looksLikeGiftCode(normalized: string): boolean {
  return /^(PRO|PREM)-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$/.test(normalized);
}

/** Display hint stored in DB so admins can recognise a code without storing it. */
export function codeHint(code: string): string {
  const parts = code.split("-");
  return `${parts[0]}-…-${parts[parts.length - 1]}`;
}

export function secureRandom(n: number): Uint8Array {
  const out = new Uint8Array(n);
  crypto.getRandomValues(out);
  return out;
}

export async function hmacSha256(pepperB64: string, message: string): Promise<Uint8Array> {
  if (!pepperB64 || pepperB64.length < 32) throw new Error("pepper too short");
  const keyBytes = Uint8Array.from(atob(pepperB64), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return new Uint8Array(sig);
}

/** Random admin activation secret, shown once to the operator. */
export function generateAdminSecret(): string {
  const bytes = secureRandom(24);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Constant-time string comparison for shared secrets. */
export function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length || a.length === 0) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
