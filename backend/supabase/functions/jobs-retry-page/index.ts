// POST /jobs-retry-page { page_id } — re-processes one failed page without
// touching the other pages and without counting the chapter again.
import { AppError, handle, json, readJson, uuid } from "../_shared/http.ts";
import { adminClient, rateLimit, requireUser } from "../_shared/supabase.ts";
import { kickWorker } from "../_shared/worker_kick.ts";

Deno.serve(handle(["POST"], async (req) => {
  const user = await requireUser(req);
  await rateLimit(`retry-page:${user.id}`, 600, 60);
  const db = adminClient();

  const body = await readJson<{ page_id?: unknown }>(req);
  const pageId = uuid(body.page_id, "page_id");

  const { data: page } = await db.from("translation_pages")
    .select("id, job_id, status, translation_jobs!inner(id, status, usage_counted, usage_refunded)")
    .eq("id", pageId).eq("user_id", user.id).maybeSingle();
  if (!page) throw new AppError(404, "page_not_found");
  if (page.status !== "failed") throw new AppError(409, "page_not_failed");

  const job = (page as any).translation_jobs;
  // A refunded chapter was given back to the user; processing it again must go through jobs-start.
  if (!job.usage_counted || job.usage_refunded) throw new AppError(409, "restart_required");
  if (!["failed", "partially_failed", "completed"].includes(job.status)) throw new AppError(409, "job_busy");

  const { error: pErr } = await db.from("translation_pages")
    .update({ status: "pending", attempts: 0, error_code: null, error_message: null }).eq("id", pageId);
  if (pErr) throw pErr;
  const { error: jErr } = await db.from("translation_jobs")
    .update({ status: "ocr", completed_at: null }).eq("id", job.id);
  if (jErr) throw jErr;

  kickWorker(job.id);
  return json({ ok: true, job_id: job.id, page_id: pageId });
}));
