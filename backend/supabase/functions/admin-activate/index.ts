// POST /admin-activate { code } — binds the caller to the Admin role if the
// one-time activation secret matches. The secret never exists in the APK:
// it is created by the operator with backend/scripts/create-admin-secret.ts.
import { AppError, handle, json, readJson, str } from "../_shared/http.ts";
import { adminClient, env, rateLimit, requireUser, toBytea } from "../_shared/supabase.ts";
import { hmacSha256 } from "../_shared/codes.ts";

Deno.serve(handle(["POST"], async (req) => {
  const user = await requireUser(req);
  await rateLimit(`admin-activate:${user.id}`, 3600, 5);
  await rateLimit("admin-activate:global", 3600, 100);

  const body = await readJson<{ code?: unknown }>(req);
  const secret = str(body.code, "code", { min: 16, max: 128 });
  const hash = await hmacSha256(env("ADMIN_SECRET_PEPPER"), secret);

  const { data, error } = await adminClient().rpc("activate_admin", { p_user: user.id, p_secret_hash: toBytea(hash) });
  if (error) throw error;
  if (!data.ok) throw new AppError(403, "invalid_code"); // same answer for every failure reason
  return json(data);
}));
