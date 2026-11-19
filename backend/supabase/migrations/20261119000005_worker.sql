-- =====================================================================
-- Worker helpers: bulk writes used by the jobs-worker Edge Function
-- (one round-trip per page / per chunk instead of one per bubble).
-- =====================================================================

-- Replace OCR segments of one page (idempotent: re-running OCR overwrites).
create or replace function public.worker_save_page_ocr(
  p_page uuid, p_width integer, p_height integer, p_segments jsonb
)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_page public.translation_pages;
  v_n integer;
begin
  select * into v_page from public.translation_pages where id = p_page for update;
  if not found then raise exception 'page_not_found'; end if;

  delete from public.translation_segments where page_id = p_page;

  insert into public.translation_segments (
    page_id, job_id, user_id, reading_order, category, bbox_x, bbox_y, bbox_w, bbox_h,
    rotation_deg, is_vertical, original_text, ocr_confidence, needs_review)
  select p_page, v_page.job_id, v_page.user_id,
         (s ->> 'reading_order')::integer,
         coalesce(s ->> 'category', 'unknown')::public.segment_category,
         least(greatest((s ->> 'x')::real, 0), 1),
         least(greatest((s ->> 'y')::real, 0), 1),
         least(greatest((s ->> 'w')::real, 0.0001), 1),
         least(greatest((s ->> 'h')::real, 0.0001), 1),
         coalesce((s ->> 'rotation_deg')::real, 0),
         coalesce((s ->> 'is_vertical')::boolean, false),
         s ->> 'text',
         (s ->> 'confidence')::real,
         coalesce((s ->> 'needs_review')::boolean, false)
  from jsonb_array_elements(p_segments) s;
  get diagnostics v_n = row_count;

  update public.translation_pages
  set width = nullif(p_width, 0), height = nullif(p_height, 0),
      status = case when v_n = 0 then 'completed'::public.page_status else 'translating'::public.page_status end,
      error_code = null, error_message = null
  where id = p_page;
  return v_n;
end;
$$;

-- Apply translations for a chunk of pages and mark them completed.
create or replace function public.worker_apply_translations(p_job uuid, p_page_ids uuid[], p_items jsonb)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_n integer;
begin
  update public.translation_segments t
  set translated_text = i ->> 'arabic',
      category = coalesce(i ->> 'category', t.category::text)::public.segment_category,
      needs_review = t.needs_review or (i ->> 'category') = 'unknown'
  from jsonb_array_elements(p_items) i
  where t.id = (i ->> 'id')::uuid and t.job_id = p_job;
  get diagnostics v_n = row_count;

  update public.translation_pages set status = 'completed', error_code = null, error_message = null
  where job_id = p_job and id = any (p_page_ids);
  return v_n;
end;
$$;

revoke execute on function public.worker_save_page_ocr(uuid, integer, integer, jsonb) from public, anon, authenticated;
revoke execute on function public.worker_apply_translations(uuid, uuid[], jsonb) from public, anon, authenticated;
grant execute on function public.worker_save_page_ocr(uuid, integer, integer, jsonb) to service_role;
grant execute on function public.worker_apply_translations(uuid, uuid[], jsonb) to service_role;
