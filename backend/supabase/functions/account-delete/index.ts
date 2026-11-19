// POST /account-delete { confirm: "DELETE" } — deletes the account and its data.
// Images in storage are removed first, then the auth user; every table row
// cascades from auth.users -> user_profiles. Audit rows keep no personal data.
import { AppError, handle, json, readJson } from "../_shared/http.ts";
import { adminClient, rateLimit, requireUser } from "../_shared/supabase.ts";

Deno.serve(handle(["POST"], async (req) => {
  const user = await requireUser(req);
  await rateLimit(`account-delete:${user.id}`, 3600, 3);
  const body = await readJson<{ confirm?: unknown }>(req);
  if (body.confirm !== "DELETE") throw new AppError(400, "confirmation_required");

  const db = adminClient();

  // Collect every storage path that belongs to the user.
  const paths: string[] = [];
  const [{ data: pages }, { data: works }] = await Promise.all([
    db.from("translation_pages").select("original_path, rendered_path").eq("user_id", user.id),
    db.from("user_library").select("cover_path").eq("user_id", user.id),
  ]);
  for (const p of pages ?? []) {
    paths.push(p.original_path);
    if (p.rendered_path) paths.push(p.rendered_path);
  }
  for (const w of works ?? []) if (w.cover_path) paths.push(w.cover_path);

  for (let i = 0; i < paths.length; i += 500) {
    const { error } = await db.storage.from("chapter-pages").remove(paths.slice(i, i + 500));
    if (error) throw error;
  }

  const { error } = await db.auth.admin.deleteUser(user.id);
  if (error) throw error;

  await db.from("admin_audit_logs").insert({ actor_id: null, action: "account.deleted", details: { files_removed: paths.length } });
  return json({ ok: true });
}));
