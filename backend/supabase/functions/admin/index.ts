// POST /admin { action, ... } — every admin dashboard operation.
// Authorization is checked on the server for each call (requireAdmin); the
// app hiding the screen is NOT a security boundary.
import { AppError, handle, int, json, oneOf, readJson, str, uuid } from "../_shared/http.ts";
import { adminClient, env, rateLimit, requireAdmin, toBytea } from "../_shared/supabase.ts";
import { codeHint, type GiftPlan, generateGiftCode, hmacSha256 } from "../_shared/codes.ts";

const ACTIONS = [
  "stats",
  "users_search",
  "user_detail",
  "audit_logs",
  "gift_codes_create",
  "gift_codes_list",
  "gift_code_revoke",
  "subscription_grant",
  "subscription_revoke",
  "config_get",
  "config_update",
  "ai_cost_daily",
] as const;

const CONFIG_KEYS = ["fair_use", "ai_cost_alerts", "max_pages_per_chapter"] as const;

Deno.serve(handle(["POST"], async (req) => {
  const admin = await requireAdmin(req);
  await rateLimit(`admin:${admin.id}`, 60, 120);
  const db = adminClient();
  const body = await readJson<Record<string, unknown>>(req);
  const action = oneOf(body.action, "action", ACTIONS);

  switch (action) {
    case "stats": {
      const { data, error } = await db.rpc("admin_overview_stats", { p_actor: admin.id });
      if (error) throw error;
      const { data: cfg } = await db.from("app_config").select("value").eq("key", "ai_cost_alerts").single();
      const limit = Number(cfg?.value?.daily_usd_limit ?? 0);
      return json({ ...data, ai_cost_alert: limit > 0 && Number(data.ai_cost_today_usd) >= limit, ai_cost_daily_limit_usd: limit });
    }

    case "users_search": {
      const { data, error } = await db.rpc("admin_search_users", {
        p_actor: admin.id,
        p_query: str(body.query, "query", { max: 100, optional: true }),
        p_limit: body.limit === undefined ? 30 : int(body.limit, "limit", 1, 100),
        p_offset: body.offset === undefined ? 0 : int(body.offset, "offset", 0, 1_000_000),
      });
      if (error) throw error;
      return json({ users: data });
    }

    case "user_detail": {
      const userId = uuid(body.user_id, "user_id");
      const [profile, subs, usage, redemptions, ent] = await Promise.all([
        db.from("user_profiles").select("id, display_name, email, avatar_url, status, created_at, last_seen_at").eq("id", userId).maybeSingle(),
        db.from("subscriptions").select("id, plan_id, source, status, starts_at, expires_at, auto_renewing, created_at")
          .eq("user_id", userId).order("created_at", { ascending: false }).limit(50),
        db.from("daily_usage").select("usage_date, chapters_used").eq("user_id", userId).order("usage_date", { ascending: false }).limit(30),
        db.from("gift_code_redemptions").select("redeemed_at, gift_codes(code_hint, plan_id, duration_days)")
          .eq("user_id", userId).order("redeemed_at", { ascending: false }).limit(50),
        db.rpc("usage_status", { p_user: userId }),
      ]);
      if (!profile.data) throw new AppError(404, "user_not_found");
      await audit(db, admin.id, "users.view", "user", userId);
      return json({
        profile: profile.data,
        entitlements: ent.data,
        subscriptions: subs.data ?? [],
        usage_history: usage.data ?? [],
        redemptions: redemptions.data ?? [],
      });
    }

    case "audit_logs": {
      const limit = body.limit === undefined ? 50 : int(body.limit, "limit", 1, 200);
      const offset = body.offset === undefined ? 0 : int(body.offset, "offset", 0, 1_000_000);
      const { data, error } = await db.from("admin_audit_logs")
        .select("id, actor_id, action, target_type, target_id, details, created_at")
        .order("created_at", { ascending: false }).range(offset, offset + limit - 1);
      if (error) throw error;
      return json({ logs: data });
    }

    case "gift_codes_create": {
      const plan = oneOf(body.plan, "plan", ["pro", "premium"] as const) as GiftPlan;
      const duration = int(body.duration_days, "duration_days", 1, 3650);
      const quantity = int(body.quantity, "quantity", 1, 1000);
      const maxRedemptions = body.max_redemptions === undefined ? 1 : int(body.max_redemptions, "max_redemptions", 1, 100000);
      const notes = str(body.notes, "notes", { max: 500, optional: true }) || null;
      let codeExpiresAt: string | null = null;
      if (body.code_expires_at) {
        const d = new Date(str(body.code_expires_at, "code_expires_at"));
        if (isNaN(d.getTime()) || d.getTime() <= Date.now()) throw new AppError(400, "invalid_input", "code_expires_at");
        codeExpiresAt = d.toISOString();
      }

      const pepper = env("GIFT_CODE_PEPPER");
      const codes = new Set<string>();
      while (codes.size < quantity) codes.add(generateGiftCode(plan));
      const list = [...codes];
      const hashes = await Promise.all(list.map(async (c) => toBytea(await hmacSha256(pepper, c))));

      const { data: batchId, error } = await db.rpc("admin_create_gift_codes", {
        p_actor: admin.id,
        p_plan: plan,
        p_duration_days: duration,
        p_hashes: hashes,
        p_hints: list.map(codeHint),
        p_code_expires_at: codeExpiresAt,
        p_max_redemptions: maxRedemptions,
        p_notes: notes,
      });
      if (error) throw error;

      // Plain codes are returned ONCE (for copy / share / export) and never stored.
      return json({
        batch_id: batchId,
        plan,
        duration_days: duration,
        code_expires_at: codeExpiresAt,
        max_redemptions: maxRedemptions,
        codes: list,
      }, 201);
    }

    case "gift_codes_list": {
      const limit = body.limit === undefined ? 50 : int(body.limit, "limit", 1, 200);
      const offset = body.offset === undefined ? 0 : int(body.offset, "offset", 0, 1_000_000);
      let q = db.from("gift_codes")
        .select("id, batch_id, code_hint, plan_id, duration_days, expires_at, max_redemptions, redemption_count, status, notes, created_at, revoked_at, gift_code_redemptions(redeemed_at, user_profiles(id, email, display_name))", { count: "exact" })
        .order("created_at", { ascending: false })
        .range(offset, offset + limit - 1);
      if (body.plan) q = q.eq("plan_id", oneOf(body.plan, "plan", ["pro", "premium"]));
      if (body.status) q = q.eq("status", oneOf(body.status, "status", ["active", "revoked", "exhausted"]));
      if (body.batch_id) q = q.eq("batch_id", uuid(body.batch_id, "batch_id"));
      if (body.created_from) q = q.gte("created_at", str(body.created_from, "created_from"));
      if (body.created_to) q = q.lte("created_at", str(body.created_to, "created_to"));
      if (body.expired === true) q = q.lt("expires_at", new Date().toISOString());
      const { data, count, error } = await q;
      if (error) throw error;
      return json({ codes: data, total: count });
    }

    case "subscription_grant": {
      const { data, error } = await db.rpc("admin_grant_subscription", {
        p_actor: admin.id,
        p_user: uuid(body.user_id, "user_id"),
        p_plan: oneOf(body.plan, "plan", ["pro", "premium"]),
        p_days: int(body.days, "days", 1, 3650),
        p_note: str(body.note, "note", { max: 300, optional: true }) ?? "",
      });
      if (error) throw error;
      if (!data.ok) throw new AppError(data.error === "user_not_found" ? 404 : 400, data.error);
      return json(data);
    }

    case "subscription_revoke": {
      const { data, error } = await db.rpc("admin_revoke_subscription", {
        p_actor: admin.id,
        p_subscription: uuid(body.subscription_id, "subscription_id"),
        p_note: str(body.note, "note", { max: 300, optional: true }) ?? "",
      });
      if (error) throw error;
      if (!data) throw new AppError(409, "subscription_not_active");
      return json({ ok: true });
    }

    case "config_get": {
      const { data, error } = await db.from("app_config").select("key, value, description, updated_at").in("key", [...CONFIG_KEYS]);
      if (error) throw error;
      return json({ config: data });
    }

    case "config_update": {
      const key = oneOf(body.key, "key", CONFIG_KEYS);
      const { data, error } = await db.rpc("admin_update_config", { p_actor: admin.id, p_key: key, p_value: body.value ?? null });
      if (error) throw error;
      if (!data.ok) throw new AppError(400, data.error);
      return json({ ok: true });
    }

    case "ai_cost_daily": {
      const days = body.days === undefined ? 14 : int(body.days, "days", 1, 90);
      const { data, error } = await db.rpc("admin_ai_cost_daily", { p_actor: admin.id, p_days: days });
      if (error) throw error;
      return json({ days: (data ?? []).map((r: any) => ({ ...r, cost_usd: Number(r.cost_usd), requests: Number(r.requests), failed: Number(r.failed) })) });
    }

    case "gift_code_revoke": {
      const { data, error } = await db.rpc("admin_revoke_gift_code", { p_actor: admin.id, p_code: uuid(body.code_id, "code_id") });
      if (error) throw error;
      if (!data) throw new AppError(409, "code_not_active");
      return json({ ok: true });
    }
  }
}));

async function audit(db: ReturnType<typeof adminClient>, actor: string, action: string, targetType: string, targetId: string) {
  await db.from("admin_audit_logs").insert({ actor_id: actor, action, target_type: targetType, target_id: targetId });
}
