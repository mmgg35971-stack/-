// POST /jobs-start { job_id } — checks uploads, counts the chapter against the
// daily limit (exactly once, on the server, UTC) and queues it for processing.
import { AppError, handle, json, readJson, uuid } from "../_shared/http.ts";
import { adminClient, rateLimit, requireUser } from "../_shared/supabase.ts";
import { ocrProvider, translationProvider } from "../_shared/providers.ts";
import { kickWorker } from "../_shared/worker_kick.ts";

const STATUS: Record<string, number> = {
  job_not_found: 404,
  invalid_state: 409,
  no_pages: 400,
  too_many_pages: 400,
  quality_not_in_plan: 403,
  daily_limit_reached: 429,
  fair_use_review: 429,
  account_inactive: 403,
};

Deno.serve(handle(["POST"], async (req) => {
  const user = await requireUser(req);
  const db = adminClient();

  const { data: fair } = await db.from("app_config").select("value").eq("key", "fair_use").single();
  await rateLimit(`jobs-start:${user.id}`, 600, Number(fair?.value?.max_job_starts_per_10min ?? 30));

  const body = await readJson<{ job_id?: unknown }>(req);
  const jobId = uuid(body.job_id, "job_id");

  const { data: job } = await db.from("translation_jobs")
    .select("id, user_id, status, usage_counted, usage_refunded").eq("id", jobId).eq("user_id", user.id).maybeSingle();
  if (!job) throw new AppError(404, "job_not_found");

  // Do not charge a chapter if the server cannot process it right now.
  try {
    translationProvider();
    ocrProvider();
  } catch (e) {
    throw new AppError(503, "provider_not_configured", (e as Error).message);
  }

  // Every original must actually be in storage before we count the chapter.
  const { data: pages } = await db.from("translation_pages").select("original_path").eq("job_id", jobId);
  const { data: objects, error: listErr } = await db.storage.from("chapter-pages")
    .list(`${user.id}/${jobId}/original`, { limit: 1000 });
  if (listErr) throw listErr;
  const uploaded = new Set((objects ?? []).map((o) => `${user.id}/${jobId}/original/${o.name}`));
  const missing = (pages ?? []).filter((p) => !uploaded.has(p.original_path)).length;
  if (missing > 0) throw new AppError(409, "uploads_incomplete", undefined, { missing_pages: missing });

  const { data, error } = await db.rpc("start_translation_job", { p_user: user.id, p_job: jobId });
  if (error) throw error;
  if (!data.ok) throw new AppError(STATUS[data.error] ?? 400, data.error, undefined, data);

  kickWorker(jobId);
  return json(data);
}));
