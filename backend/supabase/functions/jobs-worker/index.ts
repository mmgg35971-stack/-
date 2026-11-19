// INTERNAL: POST /jobs-worker  (header x-worker-secret)
// Runs pipeline steps until its time budget is spent, then hands off to a
// fresh invocation. Also safe to call from a scheduler (pg_cron) every minute
// to pick up anything left behind after a crash.
import { safeEqual } from "../_shared/codes.ts";
import { handle, json, readJson } from "../_shared/http.ts";
import { adminClient, env } from "../_shared/supabase.ts";
import { runStep } from "../_shared/pipeline.ts";
import { kickWorker } from "../_shared/worker_kick.ts";

const TIME_BUDGET_MS = 110_000;

Deno.serve(handle(["POST"], async (req) => {
  if (!safeEqual(req.headers.get("x-worker-secret") ?? "", env("WORKER_SECRET"))) {
    return json({ error: "forbidden" }, 403);
  }
  const started = Date.now();
  const db = adminClient();
  const body = await readJson<{ job_id?: string }>(req).catch(() => ({} as { job_id?: string }));

  let jobId: string | null = body.job_id ?? null;
  let steps = 0;

  while (Date.now() - started < TIME_BUDGET_MS) {
    if (!jobId) {
      const { data: claimed } = await db.rpc("claim_next_job");
      jobId = claimed?.id ?? null;
      if (!jobId) break; // queue empty
    }
    const { data: job } = await db.from("translation_jobs")
      .select("id, user_id, work_id, chapter_title, status, translation_quality, usage_counted")
      .eq("id", jobId).single();
    if (!job) {
      jobId = null;
      continue;
    }
    const r = await runStep(db, job);
    steps++;
    if (r !== "progress") jobId = null; // done or idle: move on to the queue
  }

  if (Date.now() - started >= TIME_BUDGET_MS) kickWorker(jobId ?? undefined); // continue in a fresh invocation
  return json({ ok: true, steps });
}));
