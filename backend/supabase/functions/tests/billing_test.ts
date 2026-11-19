// Unit tests for Play purchase normalization, Google JWT signing and secrets.
import { assert, assertEquals, assertThrows } from "@std/assert";
import { normalizePurchase } from "../_shared/google/play.ts";
import { parseServiceAccount, signAssertion } from "../_shared/google/auth.ts";
import { safeEqual } from "../_shared/codes.ts";
import { encodeBase64 } from "@std/encoding/base64";

Deno.test("normalizePurchase picks the latest line item and reads flags", () => {
  const p = normalizePurchase({
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
    startTime: "2026-11-01T00:00:00Z",
    latestOrderId: "GPA.1",
    linkedPurchaseToken: "old",
    externalAccountIdentifiers: { obfuscatedExternalAccountId: "user-1" },
    lineItems: [
      { productId: "mt_pro", expiryTime: "2026-12-01T00:00:00Z", autoRenewingPlan: { autoRenewEnabled: true } },
      { productId: "mt_premium", expiryTime: "2027-01-01T00:00:00Z" },
    ],
  });
  assertEquals(p.productId, "mt_premium");
  assertEquals(p.expiresAt, "2027-01-01T00:00:00Z");
  assertEquals(p.autoRenewing, false);
  assertEquals(p.needsAck, true);
  assertEquals(p.linkedToken, "old");
  assertEquals(p.accountId, "user-1");
});

Deno.test("normalizePurchase rejects purchases without line items", () => {
  assertThrows(() => normalizePurchase({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [] }));
});

Deno.test("service account JWT is RS256-signed and verifiable", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true, ["sign", "verify"],
  );
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const pem = `-----BEGIN PRIVATE KEY-----\n${encodeBase64(pkcs8).replace(/(.{64})/g, "$1\n")}\n-----END PRIVATE KEY-----\n`;
  const sa = parseServiceAccount(JSON.stringify({ client_email: "svc@x.iam.gserviceaccount.com", private_key: pem }))!;
  const jwt = await signAssertion(sa, "scope-a", 1000);
  const [h, c, s] = jwt.split(".");
  const b64 = (x: string) => Uint8Array.from(atob(x.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - x.length % 4) % 4)), (ch) => ch.charCodeAt(0));
  const claims = JSON.parse(new TextDecoder().decode(b64(c)));
  assertEquals(claims.iss, "svc@x.iam.gserviceaccount.com");
  assertEquals(claims.scope, "scope-a");
  assertEquals(claims.exp - claims.iat, 3600);
  assert(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pair.publicKey, b64(s), new TextEncoder().encode(`${h}.${c}`)));
});

Deno.test("parseServiceAccount tolerates missing or broken config", () => {
  assertEquals(parseServiceAccount(undefined), null);
  assertEquals(parseServiceAccount("{not json"), null);
  assertEquals(parseServiceAccount(JSON.stringify({ client_email: "x" })), null);
});

Deno.test("safeEqual never accepts an empty secret", () => {
  assert(safeEqual("abc", "abc"));
  assert(!safeEqual("abc", "abd"));
  assert(!safeEqual("", ""));
  assert(!safeEqual("abc", "abcd"));
});

