-- =====================================================================
-- Row Level Security + explicit grants
-- ---------------------------------------------------------------------
-- المبدأ: التطبيق (role = authenticated) يقرأ بياناته فقط، ويعدّل فقط
-- الأعمدة غير الحساسة. كل ما يخص الخطط والاستخدام والأكواد والمدير
-- يتم عبر Edge Functions تستخدم service_role ودوال SECURITY DEFINER.
-- =====================================================================

-- Start from zero: Supabase grants broad defaults on public; remove them.
revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke execute on all functions in schema public from public, anon, authenticated;

alter default privileges in schema public revoke all on tables from anon, authenticated;
alter default privileges in schema public revoke execute on functions from public, anon, authenticated;

grant usage on schema public to anon, authenticated, service_role;
grant all on all tables in schema public to service_role;
grant all on all sequences in schema public to service_role;

-- Enable RLS on every table
alter table public.app_config              enable row level security;
alter table public.subscription_plans      enable row level security;
alter table public.user_profiles           enable row level security;
alter table public.user_settings           enable row level security;
alter table public.subscriptions           enable row level security;
alter table public.daily_usage             enable row level security;
alter table public.user_library            enable row level security;
alter table public.translation_jobs        enable row level security;
alter table public.translation_pages       enable row level security;
alter table public.translation_segments    enable row level security;
alter table public.glossary_entries        enable row level security;
alter table public.work_context            enable row level security;
alter table public.gift_code_batches       enable row level security;
alter table public.gift_codes              enable row level security;
alter table public.gift_code_redemptions   enable row level security;
alter table public.admin_activation_secrets enable row level security;
alter table public.admin_roles             enable row level security;
alter table public.admin_audit_logs        enable row level security;
alter table public.notifications           enable row level security;
alter table public.ai_usage_events         enable row level security;
alter table public.rate_limit_buckets      enable row level security;
alter table public.translation_reports     enable row level security;

-- ---------------------------------------------------------------------
-- Public catalog: plans (prices live in Google Play, not here)
-- ---------------------------------------------------------------------
grant select on public.subscription_plans to anon, authenticated;
create policy plans_read on public.subscription_plans
  for select to anon, authenticated using (is_active);

-- ---------------------------------------------------------------------
-- Profile: read own; update only display_name / avatar_url
-- ---------------------------------------------------------------------
grant select on public.user_profiles to authenticated;
grant update (display_name, avatar_url) on public.user_profiles to authenticated;
create policy profiles_select_own on public.user_profiles
  for select to authenticated using (id = auth.uid());
create policy profiles_update_own on public.user_profiles
  for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

-- ---------------------------------------------------------------------
-- Settings: read + update own (all columns are user preferences)
-- ---------------------------------------------------------------------
grant select on public.user_settings to authenticated;
-- column-level grant (a table-level UPDATE would also allow changing user_id)
grant update (reading_direction, page_transition, reader_fullscreen, image_quality, reader_brightness,
              arabic_font, font_scale, translation_quality, show_original_text, keep_original_images,
              use_glossary, app_language, theme, notifications_enabled, allow_training_use)
  on public.user_settings to authenticated;
create policy settings_select_own on public.user_settings
  for select to authenticated using (user_id = auth.uid());
create policy settings_update_own on public.user_settings
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ---------------------------------------------------------------------
-- Read-only for the owner: subscriptions, usage, redemptions
-- ---------------------------------------------------------------------
grant select on public.subscriptions to authenticated;
create policy subscriptions_select_own on public.subscriptions
  for select to authenticated using (user_id = auth.uid());

grant select on public.daily_usage to authenticated;
create policy daily_usage_select_own on public.daily_usage
  for select to authenticated using (user_id = auth.uid());

grant select on public.gift_code_redemptions to authenticated;
create policy redemptions_select_own on public.gift_code_redemptions
  for select to authenticated using (user_id = auth.uid());

-- ---------------------------------------------------------------------
-- Library: full CRUD on own rows (but cannot change owner)
-- ---------------------------------------------------------------------
grant select, insert, delete on public.user_library to authenticated;
grant update (title, content_type, cover_path, category, is_favorite, last_read_job_id, last_read_page, last_read_at)
  on public.user_library to authenticated;
create policy library_select_own on public.user_library
  for select to authenticated using (user_id = auth.uid());
create policy library_insert_own on public.user_library
  for insert to authenticated with check (user_id = auth.uid());
create policy library_update_own on public.user_library
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy library_delete_own on public.user_library
  for delete to authenticated using (user_id = auth.uid());

-- ---------------------------------------------------------------------
-- Jobs: read/delete own; rename/re-assign work only.
-- Creation and every status/usage change go through Edge Functions.
-- ---------------------------------------------------------------------
grant select, delete on public.translation_jobs to authenticated;
grant update (chapter_title, chapter_number, work_id) on public.translation_jobs to authenticated;
create policy jobs_select_own on public.translation_jobs
  for select to authenticated using (user_id = auth.uid());
create policy jobs_update_own on public.translation_jobs
  for update to authenticated using (user_id = auth.uid())
  with check (
    user_id = auth.uid()
    and (work_id is null or exists (select 1 from public.user_library w where w.id = work_id and w.user_id = auth.uid()))
  );
-- حذف السجل مسموح فقط بعد انتهاء المعالجة (حتى لا يُحذف فصل قيد التشغيل)
create policy jobs_delete_own on public.translation_jobs
  for delete to authenticated
  using (user_id = auth.uid() and status in ('draft', 'completed', 'partially_failed', 'failed', 'cancelled'));

grant select on public.translation_history to authenticated;

grant select on public.translation_pages to authenticated;
create policy pages_select_own on public.translation_pages
  for select to authenticated using (user_id = auth.uid());

-- Segments: the editor may change the user's own text/style only
grant select on public.translation_segments to authenticated;
grant update (user_edited_text, style, category, reported_bad) on public.translation_segments to authenticated;
create policy segments_select_own on public.translation_segments
  for select to authenticated using (user_id = auth.uid());
create policy segments_update_own on public.translation_segments
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

grant select, insert on public.translation_reports to authenticated;
create policy reports_select_own on public.translation_reports
  for select to authenticated using (user_id = auth.uid());
create policy reports_insert_own on public.translation_reports
  for insert to authenticated with check (
    user_id = auth.uid()
    and exists (select 1 from public.translation_segments s where s.id = segment_id and s.user_id = auth.uid())
  );

-- ---------------------------------------------------------------------
-- Glossary + context: full CRUD on own rows, work must be own
-- ---------------------------------------------------------------------
grant select, insert, delete on public.glossary_entries to authenticated;
grant update (work_id, source_term, target_term, term_type, notes) on public.glossary_entries to authenticated;
create policy glossary_select_own on public.glossary_entries
  for select to authenticated using (user_id = auth.uid());
create policy glossary_write_own on public.glossary_entries
  for insert to authenticated with check (
    user_id = auth.uid()
    and (work_id is null or exists (select 1 from public.user_library w where w.id = work_id and w.user_id = auth.uid()))
  );
create policy glossary_update_own on public.glossary_entries
  for update to authenticated using (user_id = auth.uid()) with check (
    user_id = auth.uid()
    and (work_id is null or exists (select 1 from public.user_library w where w.id = work_id and w.user_id = auth.uid()))
  );
create policy glossary_delete_own on public.glossary_entries
  for delete to authenticated using (user_id = auth.uid());

grant select, insert, delete on public.work_context to authenticated;
grant update (story_summary, characters, dialogue_style) on public.work_context to authenticated;
create policy context_select_own on public.work_context
  for select to authenticated using (user_id = auth.uid());
create policy context_insert_own on public.work_context
  for insert to authenticated with check (
    user_id = auth.uid()
    and exists (select 1 from public.user_library w where w.id = work_id and w.user_id = auth.uid())
  );
create policy context_update_own on public.work_context
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy context_delete_own on public.work_context
  for delete to authenticated using (user_id = auth.uid());

-- ---------------------------------------------------------------------
-- Notifications: read, mark read, delete own
-- ---------------------------------------------------------------------
grant select, delete on public.notifications to authenticated;
grant update (read_at) on public.notifications to authenticated;
create policy notifications_select_own on public.notifications
  for select to authenticated using (user_id = auth.uid());
create policy notifications_update_own on public.notifications
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy notifications_delete_own on public.notifications
  for delete to authenticated using (user_id = auth.uid());

-- ---------------------------------------------------------------------
-- No client access at all (no grants, no policies):
--   app_config, gift_code_batches, gift_codes, admin_activation_secrets,
--   admin_roles, admin_audit_logs, ai_usage_events, rate_limit_buckets
-- Admin screens read these through admin-* Edge Functions that check
-- public.is_admin() on the server.
-- ---------------------------------------------------------------------
