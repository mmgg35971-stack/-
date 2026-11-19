// POST /billing-rtdn?secret=...  Google Play Real-Time Developer Notifications
// (Cloud Pub/Sub push). Keeps renewals, cancellations, holds, refunds and
// expiry in sync without waiting for the app to open.
import { AppError, handle, json } from "../_shared/http.ts";
import { adminClient, env, toBytea } from "../_shared/supabase.ts";
import { getSubscription, normalizePurchase, playConfig, sha256 } from "../_shared/google/play.ts";
import { safeEqual } from "../_shared/codes.ts";

// SubscriptionNotification.notificationType values that end access immediately.
const REVOKED = 12;

Deno.serve(handle(["POST"], async (req) => {
  const secret = new URL(req.url).searchParams.get("secret") ?? "";
  if (!safeEqual(secret, env("PLAY_RTDN_SECRET"))) throw new AppError(401, "unauthenticated");

  const envelope = await req.json().catch(() => null);
  const raw = envelope?.message?.data;
  if (typeof raw !== "string") return json({ ok: true, ignored: "no_data" });
  const msg = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(raw), (c) => c.charCodeAt(0))));

  const db = adminClient();
  const cfg = playConfig();
  if (msg.packageName && msg.packageName !== cfg.packageName) return json({ ok: true, ignored: "package" });

  const sub = msg.subscriptionNotification;
  const voided = msg.voidedPurchaseNotification;
  const token: string | undefined = sub?.purchaseToken ?? voided?.purchaseToken;
  if (!token) return json({ ok: true, ignored: "type" }); // test / one-time product notifications
  const hash = toBytea(await sha256(token));

  if (voided) {
    await db.rpc("revoke_play_subscription", { p_token_hash: hash, p_status: "refunded" });
    return json({ ok: true });
  }
  if (sub.notificationType === REVOKED) {
    await db.rpc("revoke_play_subscription", { p_token_hash: hash, p_status: "revoked" });
    return json({ ok: true });
  }

  const p = normalizePurchase(await getSubscription(cfg, token));
  const { data: plan } = await db.from("subscription_plans").select("id").eq("play_product_id", p.productId).maybeSingle();
  if (!plan) return json({ ok: true, ignored: "product" });

  const { data, error } = await db.rpc("apply_play_subscription", {
    p_user: null,
    p_plan: plan.id,
    p_token_hash: hash,
    p_linked_hash: p.linkedToken ? toBytea(await sha256(p.linkedToken)) : null,
    p_order_id: p.orderId,
    p_state: p.state,
    p_starts_at: p.startsAt,
    p_expires_at: p.expiresAt,
    p_auto_renewing: p.autoRenewing,
  });
  if (error) throw error;
  // Always 200 so Pub/Sub does not retry forever on purchases we don't know yet.
  return json({ ok: true, result: data });
}));
