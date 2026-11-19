// POST /jobs-create — registers a chapter and its pages, returns the storage
// paths the app must upload each original image to (with the user's own JWT).
// Idempotent: the same idempotency_key always returns the same job.
//
// body: {
//   idempotency_key: uuid, chapter_title: string, chapter_number?: number,
//   work_id?: uuid, quality?: "standard"|"high"|"best",
//   pages: [{ mime_type: "image/jpeg"|"image/png"|"image/webp", byte_size: number }]
// }
import { AppError, handle, int, json, oneOf, readJson, str, uuid } from "../_shared/http.ts";
import { adminClient, rateLimit, requireUser } from "../_shared/supabase.ts";

const MIME = ["image/jpeg", "image/png", "image/webp"] as const;
const EXT: Record<(typeof MIME)[number], string> = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" };

Deno.serve(handle(["POST"], async (req) => {
  const user = await requireUser(req);
  await rateLimit(`jobs-create:${user.id}`, 600, 60);
  const db = adminClient();

  const body = await readJson<Record<string, unknown>>(req, 256 * 1024);
  const idempotencyKey = uuid(body.idempotency_key, "idempotency_key");

  const { data: existing } = await db.from("translation_jobs")
    .select("id, status, page_count").eq("user_id", user.id).eq("idempotency_key", idempotencyKey).maybeSingle();
  if (existing) {
    const { data: pages } = await db.from("translation_pages")
      .select("id, page_index, original_path, status").eq("job_id", existing.id).order("page_index");
    return json({ job: existing, pages, reused: true });
  }

  const chapterTitle = str(body.chapter_title, "chapter_title", { min: 1, max: 200 });
  const chapterNumber = body.chapter_number === undefined || body.chapter_number === null
    ? null
    : Number(body.chapter_number);
  if (chapterNumber !== null && (!Number.isFinite(chapterNumber) || chapterNumber < 0 || chapterNumber > 999999)) {
    throw new AppError(400, "invalid_input", "chapter_number");
  }
  const quality = body.quality === undefined ? "standard" : oneOf(body.quality, "quality", ["standard", "high", "best"]);
  const workId = body.work_id ? uuid(body.work_id, "work_id") : null;

  if (!Array.isArray(body.pages) || body.pages.length === 0) throw new AppError(400, "no_pages");
  const { data: cfg } = await db.from("app_config").select("key, value").in("key", ["max_pages_per_chapter", "max_page_bytes"]);
  const maxPages = Number(cfg?.find((c) => c.key === "max_pages_per_chapter")?.value ?? 200);
  const maxBytes = Number(cfg?.find((c) => c.key === "max_page_bytes")?.value ?? 15 * 1024 * 1024);
  if (body.pages.length > maxPages) throw new AppError(400, "too_many_pages", undefined, { max_pages: maxPages });

  const pages = body.pages.map((p: any, i: number) => ({
    mime: oneOf(p?.mime_type, `pages[${i}].mime_type`, MIME),
    size: int(p?.byte_size, `pages[${i}].byte_size`, 1, maxBytes),
  }));

  if (workId) {
    const { data: work } = await db.from("user_library").select("id").eq("id", workId).eq("user_id", user.id).maybeSingle();
    if (!work) throw new AppError(404, "work_not_found");
  }

  const { data: job, error: jobErr } = await db.from("translation_jobs").insert({
    user_id: user.id,
    work_id: workId,
    chapter_title: chapterTitle,
    chapter_number: chapterNumber,
    page_count: pages.length,
    status: "uploading",
    translation_quality: quality,
    idempotency_key: idempotencyKey,
  }).select("id, status, page_count").single();
  if (jobErr) {
    // concurrent duplicate with the same idempotency key
    if ((jobErr as any).code === "23505") throw new AppError(409, "duplicate_request");
    throw jobErr;
  }

  const rows = pages.map((p, i) => ({
    job_id: job.id,
    user_id: user.id,
    page_index: i,
    original_path: `${user.id}/${job.id}/original/${String(i).padStart(4, "0")}.${EXT[p.mime]}`,
    mime_type: p.mime,
    byte_size: p.size,
  }));
  const { data: pageRows, error: pagesErr } = await db.from("translation_pages")
    .insert(rows).select("id, page_index, original_path, status").order("page_index");
  if (pagesErr) {
    await db.from("translation_jobs").delete().eq("id", job.id);
    throw pagesErr;
  }

  return json({ job, pages: pageRows, bucket: "chapter-pages", reused: false }, 201);
}));
