// GET /me — profile + server-computed entitlements (plan, daily usage, admin flag).
// The app must treat this as the only source of truth for plan and limits.
import { handle, json } from "../_shared/http.ts";
import { adminClient, requireUser } from "../_shared/supabase.ts";
import { kickWorker } from "../_shared/worker_kick.ts";

Deno.serve(handle(["GET", "POST"], async (req) => {
  const user = await requireUser(req);
  const db = adminClient();

  const [{ data: profile, error: pErr }, { data: entitlements, error: eErr }] = await Promise.all([
    db.from("user_profiles")
      .update({ last_seen_at: new Date().toISOString() })
      .eq("id", user.id)
      .select("id, display_name, email, avatar_url, status, created_at")
      .single(),
    db.rpc("usage_status", { p_user: user.id }),
  ]);
  if (pErr) throw pErr;
  if (eErr) throw eErr;

  // Self-healing without a scheduler: if this user's chapter has not moved for
  // 2 minutes (worker crashed or timed out), wake the worker again.
  const stale = new Date(Date.now() - 120_000).toISOString();
  const { data: stuck } = await db.from("translation_jobs").select("id")
    .eq("user_id", user.id).in("status", ["queued", "ocr", "translating"]).lt("updated_at", stale).limit(1);
  if (stuck?.length) kickWorker(stuck[0].id);

  return json({ profile, entitlements, server_time: new Date().toISOString() });
}));
