import { env } from "./supabase.ts";

declare const EdgeRuntime: { waitUntil(p: Promise<unknown>): void } | undefined;

/** Fire-and-forget call to the internal worker. Survives the response via waitUntil. */
export function kickWorker(jobId?: string): void {
  const p = fetch(`${env("SUPABASE_URL")}/functions/v1/jobs-worker`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-worker-secret": env("WORKER_SECRET") },
    body: JSON.stringify(jobId ? { job_id: jobId } : {}),
  }).then((r) => r.body?.cancel()).catch((e) => console.error("kickWorker failed", e));
  if (typeof EdgeRuntime !== "undefined") EdgeRuntime.waitUntil(p);
}
