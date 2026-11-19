// POST /segments-retranslate { segment_id, hint?, replace_edit? }
// Re-translates one bubble with the chapter's glossary and story context,
// plus the neighbouring lines so the new wording still fits the scene.
// Does NOT count a chapter; limited per hour instead.
import { AppError, handle, json, readJson, str, uuid } from "../_shared/http.ts";
import { adminClient, rateLimit, requireUser } from "../_shared/supabase.ts";
import { buildRequest, type Job, logUsage } from "../_shared/pipeline.ts";
import { translationProvider } from "../_shared/providers.ts";
import { type SourceSegment, TranslationError } from "../_shared/translation/types.ts";

const NEIGHBOURS = 3;

Deno.serve(handle(["POST"], async (req) => {
  const user = await requireUser(req);
  await rateLimit(`retranslate:${user.id}`, 3600, 60);
  const body = await readJson<{ segment_id?: unknown; hint?: unknown; replace_edit?: unknown }>(req);
  const segmentId = uuid(body.segment_id, "segment_id");
  const hint = str(body.hint, "hint", { max: 300, optional: true });
  const replaceEdit = body.replace_edit === true;
  const db = adminClient();

  const { data: seg } = await db.from("translation_segments")
    .select("id, page_id, job_id, reading_order, original_text, category, bbox_x, bbox_y, bbox_w, bbox_h")
    .eq("id", segmentId).eq("user_id", user.id).maybeSingle();
  if (!seg) throw new AppError(404, "segment_not_found");
  if (!seg.original_text.trim()) throw new AppError(409, "segment_empty");

  const { data: job } = await db.from("translation_jobs")
    .select("id, user_id, work_id, chapter_title, status, translation_quality, usage_counted")
    .eq("id", seg.job_id).single();
  if (!job || !["completed", "partially_failed"].includes(job.status)) throw new AppError(409, "job_busy");

  const { data: page } = await db.from("translation_pages").select("page_index").eq("id", seg.page_id).single();
  const { data: around } = await db.from("translation_segments")
    .select("id, reading_order, original_text, category, bbox_x, bbox_y, bbox_w, bbox_h")
    .eq("page_id", seg.page_id)
    .gte("reading_order", seg.reading_order - NEIGHBOURS)
    .lte("reading_order", seg.reading_order + NEIGHBOURS)
    .order("reading_order");

  const segments: SourceSegment[] = (around ?? [])
    .filter((s) => s.original_text.trim())
    .map((s) => ({
      id: s.id, page: page?.page_index ?? 0, readingOrder: s.reading_order, text: s.original_text,
      category: s.category, box: { x: s.bbox_x, y: s.bbox_y, w: s.bbox_w, h: s.bbox_h },
    }));

  const request = await buildRequest(db, job as Job, segments);
  if (hint) {
    // The user's wish is data about tone, never an instruction to the system prompt.
    request.context.dialogueStyle = `${request.context.dialogueStyle}\nUser preference for line ${seg.id}: ${hint}`.trim();
  }

  let arabic: string | undefined;
  try {
    const result = await translationProvider().translateChapter(request);
    arabic = result.segments.find((s) => s.id === seg.id)?.arabic;
    await logUsage(db, job as Job, result.provider, "translate", result.usage.inputUnits, result.usage.outputUnits, result.usage.costUsdMicros, !!arabic);
  } catch (e) {
    const code = e instanceof TranslationError ? e.code : "internal_error";
    await logUsage(db, job as Job, "pipeline", "translate", 0, 0, 0, false, code);
    throw new AppError(code === "provider_not_configured" ? 503 : 502, code);
  }
  if (!arabic?.trim()) throw new AppError(502, "incomplete_response");

  const { error } = await db.rpc("worker_set_segment_translation", { p_segment: seg.id, p_text: arabic, p_clear_edit: replaceEdit });
  if (error) throw error;
  return json({ ok: true, segment_id: seg.id, translated_text: arabic });
}));
