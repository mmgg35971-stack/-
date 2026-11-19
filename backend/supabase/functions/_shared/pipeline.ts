// Chapter pipeline: OCR every page (one page per step) -> translate the whole
// chapter in context-carrying chunks -> finalize. Each step is small and
// idempotent, so a crash or timeout just means the next worker run continues.
//
// Rendering of Arabic onto the image happens on the device (Android text
// engine handles Arabic shaping/RTL natively) using the stored boxes and
// translations; originals are never modified.
import type { SupabaseClient } from "npm:@supabase/supabase-js@2.119.0";
import { ocrProvider, translationProvider } from "./providers.ts";
import { OcrError, REVIEW_CONFIDENCE, sortReadingOrder } from "./ocr/types.ts";
import { type SourceSegment, TranslationError, type TranslationRequest } from "./translation/types.ts";
import { sendPush } from "./google/fcm.ts";

export const MAX_PAGE_ATTEMPTS = 3;
export const CHUNK_MAX_PAGES = 8;
export const CHUNK_MAX_SEGMENTS = 300;

export type Job = {
  id: string;
  user_id: string;
  work_id: string | null;
  chapter_title: string;
  status: string;
  translation_quality: "standard" | "high" | "best";
  usage_counted: boolean;
};

export type StepResult = "progress" | "job_done" | "idle";

export async function runStep(db: SupabaseClient, job: Job): Promise<StepResult> {
  if (job.status === "queued") {
    // conditional update = claim; a concurrent worker that loses simply moves on
    await db.from("translation_jobs").update({ status: "ocr" }).eq("id", job.id).eq("status", "queued");
    return "progress";
  }
  if (job.status === "ocr") return await ocrNextPage(db, job);
  if (job.status === "translating") return await translateNextChunk(db, job);
  return "idle";
}

// ---------------------------------------------------------------------------
// OCR
// ---------------------------------------------------------------------------
async function ocrNextPage(db: SupabaseClient, job: Job): Promise<StepResult> {
  // Pages left in "ocr" by a crashed worker for > 5 min go back to the queue.
  await db.from("translation_pages").update({ status: "pending" })
    .eq("job_id", job.id).eq("status", "ocr").lt("updated_at", new Date(Date.now() - 5 * 60_000).toISOString());

  const { data: next } = await db.from("translation_pages")
    .select("id, page_index, original_path, mime_type, attempts")
    .eq("job_id", job.id).eq("status", "pending").order("page_index").limit(1).maybeSingle();

  if (!next) {
    const { count } = await db.from("translation_pages")
      .select("id", { count: "exact", head: true }).eq("job_id", job.id).eq("status", "ocr");
    if ((count ?? 0) > 0) return "idle"; // another worker is still on a page
    await db.from("translation_jobs").update({ status: "translating" }).eq("id", job.id).eq("status", "ocr");
    return "progress";
  }

  // Claim the page; if another worker got it first, just try the next one.
  const { data: claimed } = await db.from("translation_pages")
    .update({ status: "ocr", attempts: next.attempts + 1 })
    .eq("id", next.id).eq("status", "pending").select("id");
  if (!claimed || claimed.length === 0) return "progress";
  const page = next;

  try {
    const ocr = ocrProvider();
    const { data: blob, error } = await db.storage.from("chapter-pages").download(page.original_path);
    if (error || !blob) throw new OcrError("image_unreadable", error?.message ?? "missing original");
    const result = await ocr.recognize(new Uint8Array(await blob.arrayBuffer()), page.mime_type ?? "image/jpeg");

    await logUsage(db, job, ocr.name, "ocr", result.usage.units, 0, result.usage.costUsdMicros, true);

    const direction = await readingDirection(db, job.user_id);
    const ordered = sortReadingOrder(result.regions, direction);
    const segments = ordered.map((r, i) => ({
      reading_order: i,
      category: guessCategory(r.text, r.box.h),
      x: r.box.x, y: r.box.y, w: r.box.w, h: r.box.h,
      rotation_deg: r.rotationDeg,
      is_vertical: r.isVertical,
      text: r.text,
      confidence: r.confidence,
      needs_review: r.confidence < REVIEW_CONFIDENCE, // kept and shown for review, never dropped
    }));

    const { error: saveErr } = await db.rpc("worker_save_page_ocr", {
      p_page: page.id, p_width: result.width, p_height: result.height, p_segments: segments,
    });
    if (saveErr) throw saveErr;
  } catch (e) {
    await failOrRetryPage(db, job, page.id, page.attempts + 1, e);
  }
  return "progress";
}

/** Cheap first guess; the translator refines categories with full context. */
export function guessCategory(text: string, boxH: number): string {
  const letters = text.replace(/[^A-Za-z]/g, "");
  if (letters.length > 0 && letters.length <= 12 && letters === letters.toUpperCase() && /[!]/.test(text) && boxH > 0.04) {
    return "sound_effect";
  }
  return "dialogue";
}

// ---------------------------------------------------------------------------
// Translation (context-aware, chunked)
// ---------------------------------------------------------------------------
async function translateNextChunk(db: SupabaseClient, job: Job): Promise<StepResult> {
  const { data: pages } = await db.from("translation_pages")
    .select("id, page_index, attempts").eq("job_id", job.id).eq("status", "translating").order("page_index");

  if (!pages || pages.length === 0) {
    await finalize(db, job);
    return "job_done";
  }

  // Build a chunk of consecutive pages within the segment budget.
  const { data: allSegs } = await db.from("translation_segments")
    .select("id, page_id, reading_order, original_text, category, bbox_x, bbox_y, bbox_w, bbox_h")
    .in("page_id", pages.slice(0, CHUNK_MAX_PAGES).map((p) => p.id))
    .order("reading_order");

  const pageIndex = new Map(pages.map((p) => [p.id, p.page_index]));
  const chunkPages: typeof pages = [];
  let count = 0;
  for (const p of pages.slice(0, CHUNK_MAX_PAGES)) {
    const n = (allSegs ?? []).filter((s) => s.page_id === p.id).length;
    if (chunkPages.length > 0 && count + n > CHUNK_MAX_SEGMENTS) break;
    chunkPages.push(p);
    count += n;
  }
  const chunkIds = new Set(chunkPages.map((p) => p.id));
  const segments: SourceSegment[] = (allSegs ?? [])
    .filter((s) => chunkIds.has(s.page_id))
    .sort((a, b) => (pageIndex.get(a.page_id)! - pageIndex.get(b.page_id)!) || a.reading_order - b.reading_order)
    .map((s) => ({
      id: s.id,
      page: pageIndex.get(s.page_id)!,
      readingOrder: s.reading_order,
      text: s.original_text,
      category: s.category,
      box: { x: s.bbox_x, y: s.bbox_y, w: s.bbox_w, h: s.bbox_h },
    }));

  const attempt = Math.max(...chunkPages.map((p) => p.attempts)) + 1;
  await db.from("translation_pages").update({ attempts: attempt }).in("id", [...chunkIds]);

  try {
    const provider = translationProvider();
    const request = await buildRequest(db, job, segments);
    const result = await provider.translateChapter(request);

    await logUsage(db, job, result.provider, "translate", result.usage.inputUnits, result.usage.outputUnits,
      result.usage.costUsdMicros, true);

    const { error } = await db.rpc("worker_apply_translations", {
      p_job: job.id, p_page_ids: [...chunkIds], p_items: result.segments,
    });
    if (error) throw error;

    if (job.work_id) {
      await db.from("work_context").upsert({
        work_id: job.work_id,
        user_id: job.user_id,
        story_summary: result.updatedContext.summary,
        characters: result.updatedContext.characters,
      }, { onConflict: "work_id" });
    }
  } catch (e) {
    for (const p of chunkPages) await failOrRetryPage(db, job, p.id, attempt, e);
  }
  return "progress";
}

export async function buildRequest(db: SupabaseClient, job: Job, segments: SourceSegment[]): Promise<TranslationRequest> {
  const [{ data: settings }, { data: ent }] = await Promise.all([
    db.from("user_settings").select("use_glossary").eq("user_id", job.user_id).single(),
    db.rpc("usage_status", { p_user: job.user_id }),
  ]);

  let glossary: TranslationRequest["glossary"] = [];
  if (settings?.use_glossary !== false) {
    let q = db.from("glossary_entries").select("source_term, target_term, term_type").eq("user_id", job.user_id);
    q = job.work_id ? q.or(`work_id.is.null,work_id.eq.${job.work_id}`) : q.is("work_id", null);
    const { data } = await q.limit(500);
    glossary = (data ?? []).map((g) => ({ source: g.source_term, target: g.target_term, type: g.term_type }));
  }

  let context: TranslationRequest["context"] = { workTitle: null, summary: "", characters: [], dialogueStyle: "" };
  if (job.work_id) {
    const { data: work } = await db.from("user_library").select("title").eq("id", job.work_id).single();
    context.workTitle = work?.title ?? null;
    if (ent?.features?.context_manager) {
      const { data: wc } = await db.from("work_context").select("*").eq("work_id", job.work_id).maybeSingle();
      if (wc) {
        context = { ...context, summary: wc.story_summary, characters: wc.characters ?? [], dialogueStyle: wc.dialogue_style };
      }
    }
  }

  return { chapterTitle: job.chapter_title, segments, glossary, context, quality: job.translation_quality };
}

// ---------------------------------------------------------------------------
// Failures, finalize, bookkeeping
// ---------------------------------------------------------------------------
async function failOrRetryPage(db: SupabaseClient, job: Job, pageId: string, attempts: number, e: unknown) {
  const code = e instanceof OcrError || e instanceof TranslationError ? e.code : "internal_error";
  const retryable = (e instanceof OcrError || e instanceof TranslationError) ? e.retryable : true;
  const message = e instanceof Error ? e.message.slice(0, 500) : String(e).slice(0, 500);
  console.error("page failed", pageId, code, message);

  await logUsage(db, job, "pipeline", job.status === "translating" ? "translate" : "ocr", 0, 0, 0, false, code);

  const giveUp = !retryable || attempts >= MAX_PAGE_ATTEMPTS;
  const update: Record<string, unknown> = { error_code: code, error_message: message };
  if (giveUp) update.status = "failed";
  else if (job.status === "ocr") update.status = "pending";
  await db.from("translation_pages").update(update).eq("id", pageId);
}

async function finalize(db: SupabaseClient, job: Job) {
  const { data: status, error } = await db.rpc("finalize_translation_job", { p_job: job.id });
  if (error) throw error;

  if (status === "failed") {
    // Nothing was delivered because of a server/provider problem: give the chapter back.
    await db.rpc("refund_translation_job", { p_job: job.id, p_reason: "processing_failed" });
  }

  const { data: settings } = await db.from("user_settings").select("notifications_enabled").eq("user_id", job.user_id).single();
  if (settings?.notifications_enabled !== false && status) {
    const ok = status === "completed" || status === "partially_failed";
    await db.from("notifications").insert({
      user_id: job.user_id,
      type: ok ? "job_completed" : "job_failed",
      title: ok ? "اكتملت ترجمة الفصل" : "تعذرت ترجمة الفصل",
      body: ok
        ? (status === "partially_failed" ? `${job.chapter_title}: اكتمل مع صفحات تحتاج إعادة محاولة.` : `${job.chapter_title} جاهز للقراءة.`)
        : `${job.chapter_title}: لم يُحتسب الفصل من حدك اليومي. حاول مرة أخرى لاحقاً.`,
      data: { job_id: job.id, status },
    });
    await sendPush(db, job.user_id, {
      title: ok ? "اكتملت ترجمة الفصل" : "تعذرت ترجمة الفصل",
      body: job.chapter_title,
      data: { job_id: job.id, status: String(status) },
    });
  }
}

async function readingDirection(db: SupabaseClient, userId: string): Promise<"ltr" | "rtl" | "vertical"> {
  const { data } = await db.from("user_settings").select("reading_direction").eq("user_id", userId).single();
  return (data?.reading_direction ?? "vertical") as "ltr" | "rtl" | "vertical";
}

export async function logUsage(
  db: SupabaseClient, job: Job, provider: string, operation: "ocr" | "translate",
  inputUnits: number, outputUnits: number, cost: number, success: boolean, errorCode?: string,
) {
  await db.from("ai_usage_events").insert({
    job_id: job.id, user_id: job.user_id, provider, operation,
    input_units: inputUnits, output_units: outputUnits, cost_usd_micros: cost, success, error_code: errorCode ?? null,
  });
}
