// POST /gift-codes-redeem { code } — validates and redeems a gift code atomically.
import { AppError, handle, json, readJson, str } from "../_shared/http.ts";
import { adminClient, env, rateLimit, requireUser, toBytea } from "../_shared/supabase.ts";
import { hmacSha256, looksLikeGiftCode, normalizeCode } from "../_shared/codes.ts";

Deno.serve(handle(["POST"], async (req) => {
  const user = await requireUser(req);
  // Brute-force protection: 10 attempts / hour / user (60-bit codes make guessing hopeless anyway).
  await rateLimit(`redeem:${user.id}`, 3600, 10);

  const body = await readJson<{ code?: unknown }>(req);
  const code = normalizeCode(str(body.code, "code", { min: 8, max: 40 }));
  if (!looksLikeGiftCode(code)) throw new AppError(400, "invalid_code");

  const hash = await hmacSha256(env("GIFT_CODE_PEPPER"), code);
  const { data, error } = await adminClient().rpc("redeem_gift_code", { p_user: user.id, p_code_hash: toBytea(hash) });
  if (error) throw error;

  if (!data.ok) throw new AppError(data.error === "higher_plan_active" ? 409 : 400, data.error, undefined, data);
  return json(data);
}));
