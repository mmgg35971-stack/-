// Google Play Developer API: subscriptionsv2 is the source of truth for
// every purchase; the app's own purchase object is never trusted.
import { AppError } from "../http.ts";
import { accessToken, parseServiceAccount, type ServiceAccount } from "./auth.ts";

const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

export interface PlayConfig {
  packageName: string;
  sa: ServiceAccount;
}

export function playConfig(): PlayConfig {
  const sa = parseServiceAccount(Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"));
  const packageName = Deno.env.get("GOOGLE_PLAY_PACKAGE_NAME") ?? "";
  if (!sa || !packageName) throw new AppError(503, "billing_not_configured", "Google Play service account is not configured");
  return { sa, packageName };
}

/** The parts of SubscriptionPurchaseV2 we rely on. */
export interface PlaySubscription {
  subscriptionState: string;
  linkedPurchaseToken?: string;
  acknowledgementState?: string;
  startTime?: string;
  latestOrderId?: string;
  externalAccountIdentifiers?: { obfuscatedExternalAccountId?: string };
  lineItems?: Array<{ productId: string; expiryTime?: string; autoRenewingPlan?: { autoRenewEnabled?: boolean } }>;
  testPurchase?: unknown;
}

export interface NormalizedPurchase {
  productId: string;
  state: string;
  startsAt: string;
  expiresAt: string;
  autoRenewing: boolean;
  orderId: string | null;
  linkedToken: string | null;
  accountId: string | null;
  needsAck: boolean;
}

/** Picks the line item that expires last (one product per purchase in our catalog). */
export function normalizePurchase(p: PlaySubscription): NormalizedPurchase {
  const items = (p.lineItems ?? []).filter((li) => li.productId && li.expiryTime);
  if (items.length === 0) throw new AppError(400, "invalid_purchase", "Purchase has no line items");
  const li = items.reduce((a, b) => (Date.parse(b.expiryTime!) > Date.parse(a.expiryTime!) ? b : a));
  return {
    productId: li.productId,
    state: p.subscriptionState,
    startsAt: p.startTime ?? new Date().toISOString(),
    expiresAt: li.expiryTime!,
    autoRenewing: li.autoRenewingPlan?.autoRenewEnabled === true,
    orderId: p.latestOrderId ?? null,
    linkedToken: p.linkedPurchaseToken ?? null,
    accountId: p.externalAccountIdentifiers?.obfuscatedExternalAccountId ?? null,
    needsAck: p.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING",
  };
}

export async function getSubscription(cfg: PlayConfig, token: string): Promise<PlaySubscription> {
  const res = await fetch(`${BASE}/${encodeURIComponent(cfg.packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`, {
    headers: { Authorization: `Bearer ${await accessToken(cfg.sa, SCOPE)}` },
  });
  if (res.status === 404 || res.status === 400 || res.status === 410) throw new AppError(400, "invalid_purchase");
  if (!res.ok) {
    console.error("play get error", res.status, (await res.text()).slice(0, 300));
    throw new AppError(503, "provider_unavailable", "Google Play is unavailable");
  }
  return await res.json();
}

/** Purchases must be acknowledged within 3 days or Google refunds them. */
export async function acknowledge(cfg: PlayConfig, productId: string, token: string): Promise<void> {
  const url = `${BASE}/${encodeURIComponent(cfg.packageName)}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}:acknowledge`;
  const res = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${await accessToken(cfg.sa, SCOPE)}`, "content-type": "application/json" },
    body: "{}",
  });
  if (!res.ok && res.status !== 409) console.error("play ack error", res.status, (await res.text()).slice(0, 300));
}

export async function sha256(text: string): Promise<Uint8Array<ArrayBuffer>> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
}
