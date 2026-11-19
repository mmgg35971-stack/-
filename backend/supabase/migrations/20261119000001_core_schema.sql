-- =====================================================================
-- Manga Translator — Core schema
-- ---------------------------------------------------------------------
-- هذا الملف ينشئ الجداول الأساسية فقط (بدون صلاحيات RLS وبدون دوال).
-- الصلاحيات في 20261119000002_rls.sql والدوال الحساسة في 0003.
--
-- ملاحظة: Supabase يملك جدول auth.users (حسابات Google)، لذلك جدول
-- "users" المذكور في المواصفات يُمثَّل هنا بـ public.user_profiles المرتبط
-- بـ auth.users بعلاقة 1:1.
-- كل الأوقات timestamptz، وحساب اليوم يتم دائماً بتوقيت UTC.
-- =====================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------
create type public.account_status as enum ('active', 'suspended', 'pending_deletion');

create type public.subscription_source as enum ('free', 'google_play', 'gift_code', 'admin_grant');

create type public.subscription_status as enum (
  'active',      -- فعال
  'grace',       -- فترة سماح بعد فشل التجديد (Play)
  'on_hold',     -- معلق بسبب فشل الدفع
  'cancelled',   -- ألغى المستخدم التجديد (يبقى فعالاً حتى expires_at)
  'expired',
  'refunded',
  'revoked'      -- أبطله المدير
);

create type public.content_type as enum ('manga', 'manhwa', 'manhua', 'webtoon', 'comic');

create type public.job_status as enum (
  'draft',            -- أُنشئ ولم تُرفع صوره بعد
  'uploading',
  'queued',           -- تم الخصم من الحد اليومي وينتظر المعالجة
  'ocr',
  'translating',
  'rendering',
  'completed',
  'partially_failed', -- بعض الصفحات فشلت والبقية سليمة
  'failed',
  'cancelled'
);

create type public.page_status as enum ('pending', 'ocr', 'translating', 'rendering', 'completed', 'failed');

create type public.segment_category as enum ('dialogue', 'narration', 'sound_effect', 'title', 'caption', 'unknown');

create type public.glossary_term_type as enum (
  'character', 'skill', 'weapon', 'place', 'kingdom', 'clan', 'rank', 'ability', 'military', 'magic', 'term'
);

create type public.gift_code_status as enum ('active', 'revoked', 'exhausted');

create type public.admin_role as enum ('admin', 'super_admin');

-- ---------------------------------------------------------------------
-- Helper: updated_at trigger
-- ---------------------------------------------------------------------
create or replace function public.tg_set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- ---------------------------------------------------------------------
-- App configuration (server-side tunables, never exposed to clients)
-- ---------------------------------------------------------------------
create table public.app_config (
  key         text primary key,
  value       jsonb not null,
  description text,
  updated_at  timestamptz not null default now()
);

insert into public.app_config (key, value, description) values
  ('fair_use', '{"premium_soft_daily_chapters": 500, "max_job_starts_per_10min": 30}',
   'ضوابط الاستخدام العادل لخطة Premium: ليست حداً يومياً معلناً بل حماية من الإساءة الآلية'),
  ('ai_cost_alerts', '{"daily_usd_limit": 50}', 'حد تنبيه تكلفة الذكاء الاصطناعي اليومي'),
  ('max_pages_per_chapter', '200', 'الحد الأقصى لعدد صفحات الفصل الواحد'),
  ('max_page_bytes', '15728640', 'الحد الأقصى لحجم الصفحة الواحدة (15MB)');

-- ---------------------------------------------------------------------
-- 1) subscription_plans
-- ---------------------------------------------------------------------
create table public.subscription_plans (
  id                   text primary key check (id in ('free', 'pro', 'premium')),
  display_name         text not null,
  rank                 smallint not null unique,         -- free=0 < pro=1 < premium=2
  daily_chapter_limit  integer check (daily_chapter_limit is null or daily_chapter_limit > 0), -- null = غير محدود
  queue_priority       smallint not null,                -- أعلى = أسبق في الطابور
  features             jsonb not null default '{}'::jsonb,
  play_product_id      text unique,                      -- معرف المنتج في Google Play (الأسعار تُدار هناك)
  is_active            boolean not null default true,
  updated_at           timestamptz not null default now()
);

create trigger subscription_plans_updated before update on public.subscription_plans
  for each row execute function public.tg_set_updated_at();

insert into public.subscription_plans (id, display_name, rank, daily_chapter_limit, queue_priority, features, play_product_id) values
  ('free', 'Free', 0, 10, 10,
   '{"translation_quality": ["standard"], "glossary": true, "context_manager": false, "max_pages_per_chapter": 60, "advanced_rendering": false, "batch_export": false}',
   null),
  ('pro', 'Pro', 1, 100, 50,
   '{"translation_quality": ["standard", "high"], "glossary": true, "context_manager": true, "max_pages_per_chapter": 120, "advanced_rendering": false, "batch_export": true}',
   'mt_pro'),
  ('premium', 'Premium', 2, null, 100,
   '{"translation_quality": ["standard", "high", "best"], "glossary": true, "context_manager": true, "max_pages_per_chapter": 200, "advanced_rendering": true, "batch_export": true}',
   'mt_premium');

-- ---------------------------------------------------------------------
-- 2) user_profiles  (== "users" in the spec)
-- ---------------------------------------------------------------------
create table public.user_profiles (
  id                 uuid primary key references auth.users (id) on delete cascade,
  google_account_id  text,
  display_name       text check (char_length(display_name) <= 60),
  email              text,
  avatar_url         text check (avatar_url is null or char_length(avatar_url) <= 1024),
  status             public.account_status not null default 'active',
  deletion_requested_at timestamptz,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  last_seen_at       timestamptz
);

create index user_profiles_email_idx on public.user_profiles (lower(email));
create index user_profiles_created_idx on public.user_profiles (created_at desc);

create trigger user_profiles_updated before update on public.user_profiles
  for each row execute function public.tg_set_updated_at();

-- ---------------------------------------------------------------------
-- 3) user_settings
-- ---------------------------------------------------------------------
create table public.user_settings (
  user_id               uuid primary key references public.user_profiles (id) on delete cascade,
  -- reading
  reading_direction     text not null default 'vertical' check (reading_direction in ('vertical', 'ltr', 'rtl')),
  page_transition       text not null default 'scroll' check (page_transition in ('scroll', 'slide', 'none')),
  reader_fullscreen     boolean not null default true,
  image_quality         text not null default 'high' check (image_quality in ('data_saver', 'balanced', 'high', 'original')),
  reader_brightness     real not null default 1.0 check (reader_brightness between 0.1 and 1.0),
  -- translation
  arabic_font           text not null default 'cairo' check (arabic_font in ('cairo', 'ibm_plex_sans_arabic', 'noto_sans_arabic')),
  font_scale            real not null default 1.0 check (font_scale between 0.6 and 1.8),
  translation_quality   text not null default 'standard' check (translation_quality in ('standard', 'high', 'best')),
  show_original_text    boolean not null default false,
  keep_original_images  boolean not null default true,
  use_glossary          boolean not null default true,
  -- app
  app_language          text not null default 'ar' check (app_language in ('ar', 'en')),
  theme                 text not null default 'dark' check (theme in ('dark', 'light', 'system')),
  notifications_enabled boolean not null default true,
  allow_training_use    boolean not null default false, -- لا نستخدم تعديلات المستخدم لأي تدريب دون موافقة
  updated_at            timestamptz not null default now()
);

create trigger user_settings_updated before update on public.user_settings
  for each row execute function public.tg_set_updated_at();

-- ---------------------------------------------------------------------
-- 4) subscriptions
-- ---------------------------------------------------------------------
create table public.subscriptions (
  id                     uuid primary key default gen_random_uuid(),
  user_id                uuid not null references public.user_profiles (id) on delete cascade,
  plan_id                text not null references public.subscription_plans (id),
  source                 public.subscription_source not null,
  status                 public.subscription_status not null default 'active',
  starts_at              timestamptz not null default now(),
  expires_at             timestamptz,             -- null = لا ينتهي (free فقط)
  auto_renewing          boolean not null default false,
  play_purchase_token_hash bytea unique,          -- sha256 لرمز الشراء، لا نخزن الرمز نفسه
  play_order_id          text,
  gift_code_id           uuid,                    -- FK يضاف بعد إنشاء gift_codes
  granted_by             uuid references public.user_profiles (id) on delete set null,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  constraint subscriptions_paid_needs_expiry check (plan_id = 'free' or expires_at is not null),
  constraint subscriptions_dates check (expires_at is null or expires_at > starts_at)
);

create index subscriptions_user_active_idx on public.subscriptions (user_id, status, expires_at desc);

create trigger subscriptions_updated before update on public.subscriptions
  for each row execute function public.tg_set_updated_at();

-- ---------------------------------------------------------------------
-- 5) daily_usage  (UTC day)
-- ---------------------------------------------------------------------
create table public.daily_usage (
  user_id        uuid not null references public.user_profiles (id) on delete cascade,
  usage_date     date not null,
  chapters_used  integer not null default 0 check (chapters_used >= 0),
  updated_at     timestamptz not null default now(),
  primary key (user_id, usage_date)
);

create index daily_usage_date_idx on public.daily_usage (usage_date);

-- ---------------------------------------------------------------------
-- 6) user_library  (works / series saved by the user)
-- ---------------------------------------------------------------------
create table public.user_library (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references public.user_profiles (id) on delete cascade,
  title            text not null check (char_length(title) between 1 and 200),
  content_type     public.content_type not null default 'manhwa',
  cover_path       text,                      -- مسار داخل Storage bucket "chapter-pages"
  category         text check (char_length(category) <= 40),
  is_favorite      boolean not null default false,
  last_read_job_id uuid,                      -- FK بعد translation_jobs
  last_read_page   integer not null default 0 check (last_read_page >= 0),
  last_read_at     timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create index user_library_user_idx on public.user_library (user_id, updated_at desc);
create index user_library_title_idx on public.user_library (user_id, lower(title));

create trigger user_library_updated before update on public.user_library
  for each row execute function public.tg_set_updated_at();

-- ---------------------------------------------------------------------
-- 7) translation_jobs  (one chapter)
-- ---------------------------------------------------------------------
create table public.translation_jobs (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references public.user_profiles (id) on delete cascade,
  work_id          uuid references public.user_library (id) on delete set null,
  chapter_title    text not null check (char_length(chapter_title) between 1 and 200),
  chapter_number   numeric(8, 2),
  page_count       integer not null default 0 check (page_count >= 0),
  status           public.job_status not null default 'draft',
  queue_priority   smallint not null default 10,
  translation_quality text not null default 'standard' check (translation_quality in ('standard', 'high', 'best')),
  idempotency_key  uuid not null,             -- يولده التطبيق مرة واحدة لكل فصل لمنع التكرار
  usage_counted    boolean not null default false,
  usage_date       date,                      -- يوم UTC الذي احتُسب فيه الفصل
  usage_refunded   boolean not null default false,
  pages_completed  integer not null default 0 check (pages_completed >= 0),
  pages_failed     integer not null default 0 check (pages_failed >= 0),
  error_code       text,
  error_message    text,
  created_at       timestamptz not null default now(),
  started_at       timestamptz,
  completed_at     timestamptz,
  processing_ms    integer,
  updated_at       timestamptz not null default now(),
  unique (user_id, idempotency_key)
);

create index translation_jobs_user_created_idx on public.translation_jobs (user_id, created_at desc);
create index translation_jobs_queue_idx on public.translation_jobs (queue_priority desc, created_at)
  where status in ('queued', 'ocr', 'translating', 'rendering');
create index translation_jobs_work_idx on public.translation_jobs (work_id, chapter_number);

create trigger translation_jobs_updated before update on public.translation_jobs
  for each row execute function public.tg_set_updated_at();

alter table public.user_library
  add constraint user_library_last_read_job_fk
  foreign key (last_read_job_id) references public.translation_jobs (id) on delete set null;

-- ---------------------------------------------------------------------
-- 8) translation_pages
-- ---------------------------------------------------------------------
create table public.translation_pages (
  id             uuid primary key default gen_random_uuid(),
  job_id         uuid not null references public.translation_jobs (id) on delete cascade,
  user_id        uuid not null references public.user_profiles (id) on delete cascade,
  page_index     integer not null check (page_index >= 0),
  original_path  text not null,               -- الأصل لا يُستبدل أبداً
  rendered_path  text,                        -- النسخة المترجمة (ملف منفصل)
  width          integer check (width > 0),
  height         integer check (height > 0),
  byte_size      integer check (byte_size > 0),
  mime_type      text check (mime_type in ('image/jpeg', 'image/png', 'image/webp')),
  status         public.page_status not null default 'pending',
  attempts       smallint not null default 0,
  error_code     text,
  error_message  text,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (job_id, page_index)
);

create index translation_pages_job_idx on public.translation_pages (job_id, page_index);

create trigger translation_pages_updated before update on public.translation_pages
  for each row execute function public.tg_set_updated_at();

-- ---------------------------------------------------------------------
-- 9) translation_segments  (one text region detected by OCR)
-- ---------------------------------------------------------------------
create table public.translation_segments (
  id                uuid primary key default gen_random_uuid(),
  page_id           uuid not null references public.translation_pages (id) on delete cascade,
  job_id            uuid not null references public.translation_jobs (id) on delete cascade,
  user_id           uuid not null references public.user_profiles (id) on delete cascade,
  reading_order     integer not null check (reading_order >= 0),
  category          public.segment_category not null default 'unknown',
  -- Bounding box normalized to [0,1] relative to page size
  bbox_x            real not null check (bbox_x between 0 and 1),
  bbox_y            real not null check (bbox_y between 0 and 1),
  bbox_w            real not null check (bbox_w > 0 and bbox_w <= 1),
  bbox_h            real not null check (bbox_h > 0 and bbox_h <= 1),
  rotation_deg      real not null default 0,
  is_vertical       boolean not null default false,
  original_text     text not null default '',
  ocr_confidence    real check (ocr_confidence between 0 and 1),
  needs_review      boolean not null default false, -- نص لم يُتعرف عليه بثقة: يُعرض للمراجعة ولا يُحذف
  translated_text   text,
  user_edited_text  text,                     -- تعديل المستخدم يتقدم على الترجمة الآلية
  style             jsonb not null default '{}'::jsonb, -- font, size, color, background, alignment, offset
  reported_bad      boolean not null default false,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  unique (page_id, reading_order)
);

create index translation_segments_page_idx on public.translation_segments (page_id, reading_order);
create index translation_segments_job_idx on public.translation_segments (job_id);

create trigger translation_segments_updated before update on public.translation_segments
  for each row execute function public.tg_set_updated_at();

-- ---------------------------------------------------------------------
-- 10) translation_history — a view over jobs (no duplicated data)
-- ---------------------------------------------------------------------
create view public.translation_history
with (security_invoker = true) as
select
  j.id,
  j.user_id,
  j.work_id,
  w.title           as work_title,
  w.cover_path,
  j.chapter_title,
  j.chapter_number,
  j.page_count,
  j.status,
  j.pages_completed,
  j.pages_failed,
  j.processing_ms,
  (j.status in ('completed', 'partially_failed')) as is_saved,
  j.created_at,
  j.completed_at
from public.translation_jobs j
left join public.user_library w on w.id = j.work_id;

-- ---------------------------------------------------------------------
-- 11) glossary_entries
-- ---------------------------------------------------------------------
create table public.glossary_entries (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references public.user_profiles (id) on delete cascade,
  work_id      uuid references public.user_library (id) on delete cascade, -- null = قاموس عام للمستخدم
  source_term  text not null check (char_length(source_term) between 1 and 120),
  target_term  text not null check (char_length(target_term) between 1 and 120),
  term_type    public.glossary_term_type not null default 'term',
  notes        text check (char_length(notes) <= 500),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create unique index glossary_unique_term_idx
  on public.glossary_entries (user_id, coalesce(work_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(source_term));

create trigger glossary_entries_updated before update on public.glossary_entries
  for each row execute function public.tg_set_updated_at();

-- ---------------------------------------------------------------------
-- 12) work_context  (Context Manager — bounded story memory per work)
-- ---------------------------------------------------------------------
create table public.work_context (
  work_id       uuid primary key references public.user_library (id) on delete cascade,
  user_id       uuid not null references public.user_profiles (id) on delete cascade,
  story_summary text not null default '' check (char_length(story_summary) <= 4000),
  characters    jsonb not null default '[]'::jsonb check (jsonb_typeof(characters) = 'array' and jsonb_array_length(characters) <= 60),
  dialogue_style text not null default '' check (char_length(dialogue_style) <= 1000),
  updated_at    timestamptz not null default now()
);

create trigger work_context_updated before update on public.work_context
  for each row execute function public.tg_set_updated_at();

-- ---------------------------------------------------------------------
-- 13) gift_code_batches / gift_codes / gift_code_redemptions
-- ---------------------------------------------------------------------
create table public.gift_code_batches (
  id             uuid primary key default gen_random_uuid(),
  created_by     uuid not null references public.user_profiles (id),
  plan_id        text not null references public.subscription_plans (id) check (plan_id in ('pro', 'premium')),
  duration_days  integer not null check (duration_days between 1 and 3650),
  quantity       integer not null check (quantity between 1 and 1000),
  notes          text check (char_length(notes) <= 500),
  created_at     timestamptz not null default now()
);

create table public.gift_codes (
  id                uuid primary key default gen_random_uuid(),
  batch_id          uuid not null references public.gift_code_batches (id) on delete cascade,
  code_hash         bytea not null unique,     -- HMAC-SHA256(code) — الكود نفسه لا يُخزن
  code_hint         text not null check (char_length(code_hint) between 4 and 12), -- مثل PRO-…-Q8WA للعرض فقط
  plan_id           text not null references public.subscription_plans (id) check (plan_id in ('pro', 'premium')),
  duration_days     integer not null check (duration_days between 1 and 3650),
  expires_at        timestamptz,               -- انتهاء صلاحية الكود نفسه (ليس الاشتراك)
  max_redemptions   integer not null default 1 check (max_redemptions between 1 and 100000),
  redemption_count  integer not null default 0 check (redemption_count >= 0),
  status            public.gift_code_status not null default 'active',
  notes             text check (char_length(notes) <= 500),
  created_by        uuid not null references public.user_profiles (id),
  created_at        timestamptz not null default now(),
  revoked_at        timestamptz,
  revoked_by        uuid references public.user_profiles (id),
  constraint gift_codes_count_le_max check (redemption_count <= max_redemptions)
);

create index gift_codes_batch_idx on public.gift_codes (batch_id);
create index gift_codes_filter_idx on public.gift_codes (plan_id, status, created_at desc);

alter table public.subscriptions
  add constraint subscriptions_gift_code_fk
  foreign key (gift_code_id) references public.gift_codes (id) on delete set null;

create table public.gift_code_redemptions (
  id               uuid primary key default gen_random_uuid(),
  gift_code_id     uuid not null references public.gift_codes (id) on delete cascade,
  user_id          uuid not null references public.user_profiles (id) on delete cascade,
  subscription_id  uuid references public.subscriptions (id) on delete set null,
  redeemed_at      timestamptz not null default now(),
  unique (gift_code_id, user_id)              -- لا يستخدم نفس المستخدم نفس الكود مرتين
);

create index gift_code_redemptions_user_idx on public.gift_code_redemptions (user_id, redeemed_at desc);

-- ---------------------------------------------------------------------
-- 14) admin_roles / admin_activation_secrets
-- ---------------------------------------------------------------------
create table public.admin_activation_secrets (
  id           uuid primary key default gen_random_uuid(),
  secret_hash  bytea not null unique,         -- HMAC-SHA256(secret)
  label        text not null,
  max_uses     integer not null default 1 check (max_uses between 1 and 10),
  use_count    integer not null default 0,
  expires_at   timestamptz,
  revoked_at   timestamptz,
  created_at   timestamptz not null default now(),
  constraint admin_secret_uses check (use_count <= max_uses)
);

create table public.admin_roles (
  user_id               uuid primary key references public.user_profiles (id) on delete cascade,
  role                  public.admin_role not null default 'admin',
  granted_at            timestamptz not null default now(),
  activation_secret_id  uuid references public.admin_activation_secrets (id) on delete set null,
  revoked_at            timestamptz
);

-- ---------------------------------------------------------------------
-- 15) admin_audit_logs  (append-only)
-- ---------------------------------------------------------------------
create table public.admin_audit_logs (
  id           bigint generated always as identity primary key,
  actor_id     uuid references public.user_profiles (id) on delete set null,
  action       text not null,
  target_type  text,
  target_id    text,
  details      jsonb not null default '{}'::jsonb,
  ip_hash      text,
  created_at   timestamptz not null default now()
);

create index admin_audit_logs_created_idx on public.admin_audit_logs (created_at desc);
create index admin_audit_logs_actor_idx on public.admin_audit_logs (actor_id, created_at desc);

create or replace function public.tg_audit_append_only()
returns trigger
language plpgsql
as $$
begin
  raise exception 'admin_audit_logs is append-only';
end;
$$;

create trigger admin_audit_logs_no_update before update or delete on public.admin_audit_logs
  for each row execute function public.tg_audit_append_only();

-- ---------------------------------------------------------------------
-- 16) notifications
-- ---------------------------------------------------------------------
create table public.notifications (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references public.user_profiles (id) on delete cascade,
  type        text not null check (type in (
                'job_completed', 'job_failed', 'subscription_expiring',
                'gift_code_redeemed', 'subscription_activated', 'admin_notice')),
  title       text not null,
  body        text not null,
  data        jsonb not null default '{}'::jsonb,
  read_at     timestamptz,
  created_at  timestamptz not null default now()
);

create index notifications_user_idx on public.notifications (user_id, created_at desc);

-- ---------------------------------------------------------------------
-- 17) ai_usage_events  (cost monitoring for admin)
-- ---------------------------------------------------------------------
create table public.ai_usage_events (
  id               bigint generated always as identity primary key,
  job_id           uuid references public.translation_jobs (id) on delete set null,
  user_id          uuid references public.user_profiles (id) on delete set null,
  provider         text not null,
  operation        text not null check (operation in ('ocr', 'translate', 'render')),
  input_units      integer not null default 0,
  output_units     integer not null default 0,
  cost_usd_micros  bigint not null default 0,
  success          boolean not null,
  error_code       text,
  created_at       timestamptz not null default now()
);

create index ai_usage_events_created_idx on public.ai_usage_events (created_at desc);

-- ---------------------------------------------------------------------
-- 18) rate_limit_buckets  (fixed-window limiter used by Edge Functions)
-- ---------------------------------------------------------------------
create table public.rate_limit_buckets (
  bucket_key    text not null,
  window_start  timestamptz not null,
  hits          integer not null default 0,
  primary key (bucket_key, window_start)
);

-- ---------------------------------------------------------------------
-- 19) translation_reports  (user flags a bad translation)
-- ---------------------------------------------------------------------
create table public.translation_reports (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references public.user_profiles (id) on delete cascade,
  segment_id  uuid not null references public.translation_segments (id) on delete cascade,
  reason      text not null check (char_length(reason) between 1 and 500),
  created_at  timestamptz not null default now()
);
