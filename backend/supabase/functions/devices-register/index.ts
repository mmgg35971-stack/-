// POST /devices-register { token, app_language? }  — store this device's FCM token
// DELETE /devices-register { token }                — on sign-out
import { handle, json, oneOf, readJson, str } from "../_shared/http.ts";
import { adminClient, rateLimit, requireUser } from "../_shared/supabase.ts";

Deno.serve(handle(["POST", "DELETE"], async (req) => {
  const user = await requireUser(req);
  await rateLimit(`devices:${user.id}`, 3600, 30);
  const body = await readJson<{ token?: unknown; app_language?: unknown }>(req);
  const token = str(body.token, "token", { min: 20, max: 4096 });
  const db = adminClient();

  if (req.method === "DELETE") {
    await db.from("device_tokens").delete().eq("token", token).eq("user_id", user.id);
    return json({ ok: true });
  }

  const lang = body.app_language === undefined ? "ar" : oneOf(body.app_language, "app_language", ["ar", "en"]);
  // A token moves to whoever signed in last on that device.
  const { error } = await db.from("device_tokens")
    .upsert({ token, user_id: user.id, app_language: lang, updated_at: new Date().toISOString() }, { onConflict: "token" });
  if (error) throw error;
  // Keep at most 10 devices per user.
  const { data: rows } = await db.from("device_tokens").select("token").eq("user_id", user.id).order("updated_at", { ascending: false });
  const stale = (rows ?? []).slice(10).map((r) => r.token);
  if (stale.length) await db.from("device_tokens").delete().in("token", stale);
  return json({ ok: true });
}));
