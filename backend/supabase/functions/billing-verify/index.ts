// POST /billing-verify { product_id, purchase_token }
// Called by the app after every Play purchase and on start-up (restore).
// Google is asked directly for the purchase state; the app's purchase
// object is never trusted. The token is stored only as SHA-256.
import { AppError, handle, json, readJson, str } from "../_shared/http.ts";
import { adminClient, rateLimit, requireUser, toBytea } from "../_shared/supabase.ts";
import { acknowledge, getSubscription, normalizePurchase, playConfig, sha256 } from "../_shared/google/play.ts";

Deno.serve(handle(["POST"], async (req) => {
  const user = await requireUser(req);
  await rateLimit(`billing:${user.id}`, 3600, 30);
  const body = await readJson<{ product_id?: unknown; purchase_token?: unknown }>(req);
  const token = str(body.purchase_token, "purchase_token", { min: 10, max: 4096 });
  str(body.product_id, "product_id", { min: 1, max: 100 });

  const cfg = playConfig();
  const p = normalizePurchase(await getSubscription(cfg, token));

  // The app sets obfuscatedAccountId = user id at purchase time; a token bought
  // by another account cannot be claimed by this one.
  if (p.accountId && p.accountId !== user.id) throw new AppError(403, "purchase_owned_by_other");

  const db = adminClient();
  const { data: plan } = await db.from("subscription_plans").select("id").eq("play_product_id", p.productId).maybeSingle();
  if (!plan) throw new AppError(400, "unknown_product");

  const { data, error } = await db.rpc("apply_play_subscription", {
    p_user: user.id,
    p_plan: plan.id,
    p_token_hash: toBytea(await sha256(token)),
    p_linked_hash: p.linkedToken ? toBytea(await sha256(p.linkedToken)) : null,
    p_order_id: p.orderId,
    p_state: p.state,
    p_starts_at: p.startsAt,
    p_expires_at: p.expiresAt,
    p_auto_renewing: p.autoRenewing,
  });
  if (error) throw error;
  if (!data.ok) {
    const status = data.error === "purchase_owned_by_other" ? 403 : data.error === "purchase_pending" ? 409 : 400;
    throw new AppError(status, data.error);
  }

  if (p.needsAck) await acknowledge(cfg, p.productId, token);
  return json({ ok: true, plan_id: data.plan_id, status: data.status, expires_at: data.expires_at });
}));
