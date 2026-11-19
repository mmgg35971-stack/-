import { createClient, type SupabaseClient, type User } from "@supabase/supabase-js";
import { AppError } from "./http.ts";

export function env(name: string, required = true): string {
  const v = Deno.env.get(name) ?? "";
  if (required && !v) throw new AppError(503, "server_not_configured", `Missing server secret ${name}`);
  return v;
}

let admin: SupabaseClient | null = null;

/** Service-role client. Bypasses RLS — only use after authorizing the caller. */
export function adminClient(): SupabaseClient {
  if (!admin) {
    admin = createClient(env("SUPABASE_URL"), env("SUPABASE_SERVICE_ROLE_KEY"), {
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }
  return admin;
}

/** Verifies the bearer JWT with Supabase Auth (server-side) and returns the user. */
export async function requireUser(req: Request): Promise<User> {
  const header = req.headers.get("Authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token) throw new AppError(401, "unauthenticated");

  const { data, error } = await adminClient().auth.getUser(token);
  if (error || !data.user) throw new AppError(401, "session_expired");

  const { data: profile } = await adminClient()
    .from("user_profiles").select("status").eq("id", data.user.id).maybeSingle();
  if (!profile) throw new AppError(403, "profile_missing");
  if (profile.status !== "active") throw new AppError(403, "account_inactive");
  return data.user;
}

export async function requireAdmin(req: Request): Promise<User> {
  const user = await requireUser(req);
  const { data, error } = await adminClient().rpc("is_admin", { p_user: user.id });
  if (error) throw error;
  if (data !== true) throw new AppError(403, "forbidden");
  return user;
}

/** Fixed-window rate limit backed by Postgres (works across all function instances). */
export async function rateLimit(key: string, windowSeconds: number, max: number): Promise<void> {
  const { data, error } = await adminClient().rpc("rate_limit_hit", {
    p_key: key,
    p_window_seconds: windowSeconds,
    p_max: max,
  });
  if (error) throw error;
  if (data !== true) throw new AppError(429, "rate_limited", "Too many requests, try again later");
}

/** Converts bytes to the Postgres bytea hex literal PostgREST expects. */
export function toBytea(bytes: Uint8Array): string {
  return "\\x" + Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
