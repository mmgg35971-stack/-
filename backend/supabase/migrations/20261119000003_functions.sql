-- =====================================================================
-- Server-side business logic (SECURITY DEFINER)
-- ---------------------------------------------------------------------
-- كل دالة هنا تنفَّذ داخل Transaction واحدة. الدوال التي تغيّر الخطط أو
-- الاستخدام أو الأكواد أو الصلاحيات متاحة لـ service_role فقط، أي أن
-- التطبيق لا يستطيع استدعاءها مباشرة؛ يجب أن يمر عبر Edge Function تتحقق
-- من JWT ومن معدل الطلبات ثم تستدعيها.
-- =====================================================================

-- ---------------------------------------------------------------------
-- New Google sign-in => profile + settings rows
-- ---------------------------------------------------------------------
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.user_profiles (id, google_account_id, display_name, email, avatar_url)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'provider_id', new.raw_user_meta_data ->> 'sub'),
    left(coalesce(new.raw_user_meta_data ->> 'full_name', new.raw_user_meta_data ->> 'name', split_part(new.email, '@', 1)), 60),
    new.email,
    new.raw_user_meta_data ->> 'avatar_url'
  )
  on conflict (id) do nothing;

  insert into public.user_settings (user_id) values (new.id) on conflict (user_id) do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------
create or replace function public.utc_today()
returns date
language sql
stable
as $$ select (now() at time zone 'utc')::date $$;

create or replace function public.is_admin(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.admin_roles r
    join public.user_profiles p on p.id = r.user_id
    where r.user_id = p_user and r.revoked_at is null and p.status = 'active'
  );
$$;

-- Effective plan = highest-rank subscription that is currently in force.
create or replace function public.effective_plan(p_user uuid)
returns table (plan_id text, subscription_id uuid, expires_at timestamptz, source public.subscription_source)
language sql
stable
security definer
set search_path = public
as $$
  select s.plan_id, s.id, s.expires_at, s.source
  from public.subscriptions s
  join public.subscription_plans p on p.id = s.plan_id
  where s.user_id = p_user
    and s.status in ('active', 'grace', 'cancelled')
    and s.starts_at <= now()
    and (s.expires_at is null or s.expires_at > now())
  order by p.rank desc, s.expires_at desc nulls last
  limit 1;
$$;

-- Fixed-window rate limiter. Returns true if the call is allowed.
create or replace function public.rate_limit_hit(p_key text, p_window_seconds integer, p_max integer)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_window timestamptz := to_timestamp(floor(extract(epoch from now()) / p_window_seconds) * p_window_seconds);
  v_hits integer;
begin
  insert into public.rate_limit_buckets as b (bucket_key, window_start, hits)
  values (p_key, v_window, 1)
  on conflict (bucket_key, window_start) do update set hits = b.hits + 1
  returning hits into v_hits;

  -- opportunistic cleanup of old windows
  if random() < 0.01 then
    delete from public.rate_limit_buckets where window_start < now() - interval '1 day';
  end if;

  return v_hits <= p_max;
end;
$$;

-- ---------------------------------------------------------------------
-- Usage status (what the Home screen shows). Server is the only source.
-- ---------------------------------------------------------------------
create or replace function public.usage_status(p_user uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_plan_id text;
  v_expires timestamptz;
  v_source public.subscription_source;
  v_plan public.subscription_plans;
  v_used integer;
  v_today date := public.utc_today();
begin
  select e.plan_id, e.expires_at, e.source into v_plan_id, v_expires, v_source from public.effective_plan(p_user) e;
  v_plan_id := coalesce(v_plan_id, 'free');
  select * into v_plan from public.subscription_plans where id = v_plan_id;

  select coalesce(sum(chapters_used), 0) into v_used
  from public.daily_usage where user_id = p_user and usage_date = v_today;

  return jsonb_build_object(
    'plan_id', v_plan.id,
    'plan_name', v_plan.display_name,
    'plan_source', coalesce(v_source::text, 'free'),
    'plan_expires_at', v_expires,
    'daily_limit', v_plan.daily_chapter_limit,      -- null = unlimited
    'used_today', v_used,
    'remaining_today', case when v_plan.daily_chapter_limit is null then null
                            else greatest(v_plan.daily_chapter_limit - v_used, 0) end,
    'usage_date', v_today,
    'resets_at', ((v_today + 1)::timestamp at time zone 'utc'),
    'queue_priority', v_plan.queue_priority,
    'features', v_plan.features,
    'is_admin', public.is_admin(p_user)
  );
end;
$$;

-- Callable by the app for the signed-in user only (reads auth.uid()).
create or replace function public.my_entitlements()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select case when auth.uid() is null then null else public.usage_status(auth.uid()) end;
$$;

-- ---------------------------------------------------------------------
-- Daily Usage Manager: start a chapter (counts exactly once)
-- ---------------------------------------------------------------------
-- Policy (documented in docs/architecture/ARCHITECTURE.md):
--  * A chapter is counted when processing is accepted (status -> queued).
--  * Re-starting the same job (same id / idempotency key) never counts again.
--  * If the job then fails for an internal reason before ANY page finished,
--    refund_translation_job() returns the chapter to the user.
--  * The day is the UTC day on the server clock; the phone clock is ignored.
create or replace function public.start_translation_job(p_user uuid, p_job uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_job public.translation_jobs;
  v_plan_id text;
  v_plan public.subscription_plans;
  v_today date := public.utc_today();
  v_used integer;
  v_soft_cap integer;
  v_max_pages integer;
begin
  select * into v_job from public.translation_jobs where id = p_job and user_id = p_user for update;
  if not found then
    return jsonb_build_object('ok', false, 'error', 'job_not_found');
  end if;

  if v_job.usage_counted and not v_job.usage_refunded then
    return jsonb_build_object('ok', true, 'already_counted', true, 'status', v_job.status,
                              'usage', public.usage_status(p_user));
  end if;

  if v_job.status not in ('draft', 'uploading', 'failed') then
    return jsonb_build_object('ok', false, 'error', 'invalid_state', 'status', v_job.status);
  end if;

  if v_job.page_count = 0 or not exists (select 1 from public.translation_pages where job_id = p_job) then
    return jsonb_build_object('ok', false, 'error', 'no_pages');
  end if;

  if (select status from public.user_profiles where id = p_user) <> 'active' then
    return jsonb_build_object('ok', false, 'error', 'account_inactive');
  end if;

  select e.plan_id into v_plan_id from public.effective_plan(p_user) e;
  select * into v_plan from public.subscription_plans where id = coalesce(v_plan_id, 'free');

  v_max_pages := coalesce((v_plan.features ->> 'max_pages_per_chapter')::integer, 60);
  if v_job.page_count > v_max_pages then
    return jsonb_build_object('ok', false, 'error', 'too_many_pages', 'max_pages', v_max_pages);
  end if;

  if v_job.translation_quality <> all (
       array(select jsonb_array_elements_text(v_plan.features -> 'translation_quality'))) then
    return jsonb_build_object('ok', false, 'error', 'quality_not_in_plan');
  end if;

  -- Serialize all starts of this user for today on one row lock.
  insert into public.daily_usage (user_id, usage_date) values (p_user, v_today)
  on conflict (user_id, usage_date) do nothing;

  select chapters_used into v_used from public.daily_usage
  where user_id = p_user and usage_date = v_today for update;

  if v_plan.daily_chapter_limit is not null and v_used >= v_plan.daily_chapter_limit then
    return jsonb_build_object('ok', false, 'error', 'daily_limit_reached',
                              'usage', public.usage_status(p_user));
  end if;

  if v_plan.daily_chapter_limit is null then
    select (value ->> 'premium_soft_daily_chapters')::integer into v_soft_cap
    from public.app_config where key = 'fair_use';
    if v_soft_cap is not null and v_used >= v_soft_cap then
      return jsonb_build_object('ok', false, 'error', 'fair_use_review');
    end if;
  end if;

  update public.daily_usage set chapters_used = v_used + 1, updated_at = now()
  where user_id = p_user and usage_date = v_today;

  update public.translation_jobs
  set usage_counted = true,
      usage_refunded = false,
      usage_date = v_today,
      status = 'queued',
      queue_priority = v_plan.queue_priority,
      started_at = coalesce(started_at, now()),
      error_code = null,
      error_message = null
  where id = p_job;

  return jsonb_build_object('ok', true, 'already_counted', false, 'status', 'queued',
                            'usage', public.usage_status(p_user));
end;
$$;

create or replace function public.refund_translation_job(p_job uuid, p_reason text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_job public.translation_jobs;
begin
  select * into v_job from public.translation_jobs where id = p_job for update;
  if not found or not v_job.usage_counted or v_job.usage_refunded or v_job.pages_completed > 0 then
    return false;
  end if;

  update public.daily_usage
  set chapters_used = greatest(chapters_used - 1, 0), updated_at = now()
  where user_id = v_job.user_id and usage_date = v_job.usage_date;

  update public.translation_jobs
  set usage_refunded = true, error_code = coalesce(error_code, p_reason)
  where id = p_job;
  return true;
end;
$$;

-- Queue: worker claims the highest-priority job (SKIP LOCKED => safe with many workers)
create or replace function public.claim_next_job()
returns public.translation_jobs
language plpgsql
security definer
set search_path = public
as $$
declare
  v_job public.translation_jobs;
begin
  select * into v_job from public.translation_jobs
  where status = 'queued'
  order by queue_priority desc, started_at
  limit 1
  for update skip locked;

  if not found then
    return null;
  end if;

  update public.translation_jobs set status = 'ocr' where id = v_job.id returning * into v_job;
  return v_job;
end;
$$;

-- Recompute job counters/status from its pages (called by the worker)
create or replace function public.finalize_translation_job(p_job uuid)
returns public.job_status
language plpgsql
security definer
set search_path = public
as $$
declare
  v_total integer;
  v_done integer;
  v_failed integer;
  v_status public.job_status;
begin
  select count(*), count(*) filter (where status = 'completed'), count(*) filter (where status = 'failed')
  into v_total, v_done, v_failed
  from public.translation_pages where job_id = p_job;

  v_status := case
    when v_done = v_total and v_total > 0 then 'completed'
    when v_done + v_failed < v_total then null      -- still running
    when v_done = 0 then 'failed'
    else 'partially_failed'
  end;

  update public.translation_jobs
  set pages_completed = v_done,
      pages_failed = v_failed,
      status = coalesce(v_status, status),
      completed_at = case when v_status is not null then now() else completed_at end,
      processing_ms = case when v_status is not null and started_at is not null
                           then (extract(epoch from now() - started_at) * 1000)::integer else processing_ms end
  where id = p_job;

  return v_status;
end;
$$;

-- ---------------------------------------------------------------------
-- Gift codes
-- ---------------------------------------------------------------------
-- The Edge Function generates codes with a CSPRNG, shows/export them ONCE,
-- and passes only HMAC hashes + display hints here.
create or replace function public.admin_create_gift_codes(
  p_actor uuid,
  p_plan text,
  p_duration_days integer,
  p_hashes bytea[],
  p_hints text[],
  p_code_expires_at timestamptz,
  p_max_redemptions integer,
  p_notes text
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_batch uuid;
begin
  if not public.is_admin(p_actor) then
    raise exception 'forbidden' using errcode = '42501';
  end if;
  if coalesce(array_length(p_hashes, 1), 0) = 0 or array_length(p_hashes, 1) <> array_length(p_hints, 1) then
    raise exception 'invalid_codes';
  end if;

  insert into public.gift_code_batches (created_by, plan_id, duration_days, quantity, notes)
  values (p_actor, p_plan, p_duration_days, array_length(p_hashes, 1), p_notes)
  returning id into v_batch;

  insert into public.gift_codes (batch_id, code_hash, code_hint, plan_id, duration_days, expires_at,
                                 max_redemptions, notes, created_by)
  select v_batch, h, t, p_plan, p_duration_days, p_code_expires_at, p_max_redemptions, p_notes, p_actor
  from unnest(p_hashes, p_hints) as u(h, t);

  insert into public.admin_audit_logs (actor_id, action, target_type, target_id, details)
  values (p_actor, 'gift_codes.create', 'gift_code_batch', v_batch::text,
          jsonb_build_object('plan', p_plan, 'duration_days', p_duration_days,
                             'quantity', array_length(p_hashes, 1),
                             'expires_at', p_code_expires_at, 'max_redemptions', p_max_redemptions));
  return v_batch;
end;
$$;

create or replace function public.admin_revoke_gift_code(p_actor uuid, p_code uuid)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
begin
  if not public.is_admin(p_actor) then
    raise exception 'forbidden' using errcode = '42501';
  end if;

  update public.gift_codes
  set status = 'revoked', revoked_at = now(), revoked_by = p_actor
  where id = p_code and status = 'active';

  if not found then
    return false;
  end if;

  insert into public.admin_audit_logs (actor_id, action, target_type, target_id)
  values (p_actor, 'gift_codes.revoke', 'gift_code', p_code::text);
  return true;
end;
$$;

-- Redemption: one transaction, row lock on the code + advisory lock on the user,
-- so two simultaneous redemptions of the same code can never both succeed.
create or replace function public.redeem_gift_code(p_user uuid, p_code_hash bytea)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_code public.gift_codes;
  v_cur_plan text;
  v_cur_expires timestamptz;
  v_cur_rank smallint;
  v_new_rank smallint;
  v_starts timestamptz := now();
  v_sub uuid;
  v_expires timestamptz;
begin
  perform pg_advisory_xact_lock(hashtextextended('redeem:' || p_user::text, 0));

  select * into v_code from public.gift_codes where code_hash = p_code_hash for update;
  if not found then
    return jsonb_build_object('ok', false, 'error', 'invalid_code');
  end if;
  if v_code.status = 'revoked' then
    return jsonb_build_object('ok', false, 'error', 'code_revoked');
  end if;
  if v_code.status = 'exhausted' or v_code.redemption_count >= v_code.max_redemptions then
    return jsonb_build_object('ok', false, 'error', 'code_already_used');
  end if;
  if v_code.expires_at is not null and v_code.expires_at <= now() then
    return jsonb_build_object('ok', false, 'error', 'code_expired');
  end if;
  if exists (select 1 from public.gift_code_redemptions where gift_code_id = v_code.id and user_id = p_user) then
    return jsonb_build_object('ok', false, 'error', 'code_already_used');
  end if;

  select e.plan_id, e.expires_at into v_cur_plan, v_cur_expires from public.effective_plan(p_user) e;
  select rank into v_cur_rank from public.subscription_plans where id = coalesce(v_cur_plan, 'free');
  select rank into v_new_rank from public.subscription_plans where id = v_code.plan_id;

  if v_cur_rank > v_new_rank then
    -- e.g. Pro code while Premium is active: refuse instead of silently wasting it
    return jsonb_build_object('ok', false, 'error', 'higher_plan_active',
                              'current_plan', v_cur_plan, 'current_expires_at', v_cur_expires);
  end if;

  if v_cur_rank = v_new_rank and v_cur_expires is not null then
    -- same plan: stack after the latest future expiry of that plan
    select max(s.expires_at) into v_starts
    from public.subscriptions s
    where s.user_id = p_user and s.plan_id = v_code.plan_id
      and s.status in ('active', 'grace', 'cancelled') and s.expires_at > now();
  end if;
  -- higher plan: starts now; a lower paid plan keeps running underneath and
  -- becomes effective again when the higher one ends (nothing is lost).

  v_expires := v_starts + make_interval(days => v_code.duration_days);

  insert into public.subscriptions (user_id, plan_id, source, status, starts_at, expires_at, gift_code_id)
  values (p_user, v_code.plan_id, 'gift_code', 'active', v_starts, v_expires, v_code.id)
  returning id into v_sub;

  insert into public.gift_code_redemptions (gift_code_id, user_id, subscription_id)
  values (v_code.id, p_user, v_sub);

  update public.gift_codes
  set redemption_count = redemption_count + 1,
      status = case when redemption_count + 1 >= max_redemptions then 'exhausted'::public.gift_code_status else status end
  where id = v_code.id;

  insert into public.notifications (user_id, type, title, body, data)
  values (p_user, 'gift_code_redeemed', 'تم تفعيل كود الهدية',
          'تم تفعيل خطة ' || initcap(v_code.plan_id) || ' لمدة ' || v_code.duration_days || ' يوماً.',
          jsonb_build_object('plan_id', v_code.plan_id, 'expires_at', v_expires));

  return jsonb_build_object('ok', true, 'plan_id', v_code.plan_id, 'starts_at', v_starts,
                            'expires_at', v_expires, 'duration_days', v_code.duration_days);
end;
$$;

-- ---------------------------------------------------------------------
-- Admin activation (secret lives only in the DB as an HMAC hash)
-- ---------------------------------------------------------------------
create or replace function public.activate_admin(p_user uuid, p_secret_hash bytea)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_secret public.admin_activation_secrets;
begin
  if public.is_admin(p_user) then
    return jsonb_build_object('ok', true, 'already_admin', true);
  end if;

  select * into v_secret from public.admin_activation_secrets
  where secret_hash = p_secret_hash for update;

  if not found
     or v_secret.revoked_at is not null
     or (v_secret.expires_at is not null and v_secret.expires_at <= now())
     or v_secret.use_count >= v_secret.max_uses then
    insert into public.admin_audit_logs (actor_id, action, details)
    values (p_user, 'admin.activate_failed', '{}'::jsonb);
    return jsonb_build_object('ok', false, 'error', 'invalid_code');
  end if;

  update public.admin_activation_secrets set use_count = use_count + 1 where id = v_secret.id;

  insert into public.admin_roles (user_id, role, activation_secret_id)
  values (p_user, 'admin', v_secret.id)
  on conflict (user_id) do update set revoked_at = null, granted_at = now(), activation_secret_id = excluded.activation_secret_id;

  insert into public.admin_audit_logs (actor_id, action, target_type, target_id, details)
  values (p_user, 'admin.activated', 'admin_secret', v_secret.id::text, jsonb_build_object('label', v_secret.label));

  return jsonb_build_object('ok', true, 'already_admin', false);
end;
$$;

-- ---------------------------------------------------------------------
-- Admin dashboard data (real numbers from the DB, nothing invented)
-- ---------------------------------------------------------------------
create or replace function public.admin_overview_stats(p_actor uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_today date := public.utc_today();
  v_plans jsonb;
begin
  if not public.is_admin(p_actor) then
    raise exception 'forbidden' using errcode = '42501';
  end if;

  select jsonb_object_agg(plan_id, n) into v_plans from (
    select coalesce(e.plan_id, 'free') as plan_id, count(*) as n
    from public.user_profiles u
    left join lateral public.effective_plan(u.id) e on true
    group by 1
  ) t;

  return jsonb_build_object(
    'generated_at', now(),
    'users_total', (select count(*) from public.user_profiles),
    'users_active_7d', (select count(*) from public.user_profiles where last_seen_at > now() - interval '7 days'),
    'users_by_plan', coalesce(v_plans, '{}'::jsonb),
    'chapters_translated_total', (select count(*) from public.translation_jobs where status in ('completed', 'partially_failed')),
    'chapters_started_today', (select coalesce(sum(chapters_used), 0) from public.daily_usage where usage_date = v_today),
    'jobs_failed_total', (select count(*) from public.translation_jobs where status = 'failed'),
    'jobs_failed_today', (select count(*) from public.translation_jobs where status = 'failed' and usage_date = v_today),
    'gift_codes_total', (select count(*) from public.gift_codes),
    'gift_codes_used', (select count(*) from public.gift_codes where redemption_count > 0),
    'gift_codes_remaining', (select count(*) from public.gift_codes
                             where status = 'active' and (expires_at is null or expires_at > now())),
    'ai_cost_today_usd', (select coalesce(sum(cost_usd_micros), 0) / 1e6 from public.ai_usage_events
                          where created_at >= (v_today::timestamp at time zone 'utc')),
    'ai_requests_failed_today', (select count(*) from public.ai_usage_events
                                 where not success and created_at >= (v_today::timestamp at time zone 'utc'))
  );
end;
$$;

create or replace function public.admin_search_users(p_actor uuid, p_query text, p_limit integer, p_offset integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_today date := public.utc_today();
  v_rows jsonb;
begin
  if not public.is_admin(p_actor) then
    raise exception 'forbidden' using errcode = '42501';
  end if;

  select coalesce(jsonb_agg(r order by r.created_at desc), '[]'::jsonb) into v_rows from (
    select u.id, u.display_name, u.email, u.avatar_url, u.status, u.created_at, u.last_seen_at,
           coalesce(e.plan_id, 'free') as plan_id, e.expires_at as plan_expires_at,
           coalesce(d.chapters_used, 0) as used_today,
           public.is_admin(u.id) as is_admin
    from public.user_profiles u
    left join lateral public.effective_plan(u.id) e on true
    left join public.daily_usage d on d.user_id = u.id and d.usage_date = v_today
    where p_query is null or p_query = ''
       or u.email ilike '%' || p_query || '%'
       or u.display_name ilike '%' || p_query || '%'
       or u.id::text = p_query
    order by u.created_at desc
    limit least(greatest(p_limit, 1), 100) offset greatest(p_offset, 0)
  ) r;

  insert into public.admin_audit_logs (actor_id, action, details)
  values (p_actor, 'users.search', jsonb_build_object('query', left(coalesce(p_query, ''), 100)));

  return v_rows;
end;
$$;

-- Mark subscriptions whose end date passed (run from a scheduled job).
create or replace function public.expire_subscriptions()
returns integer
language sql
security definer
set search_path = public
as $$
  with x as (
    update public.subscriptions set status = 'expired'
    where status in ('active', 'grace', 'cancelled') and expires_at is not null and expires_at <= now()
    returning 1
  ) select count(*)::integer from x;
$$;

-- ---------------------------------------------------------------------
-- Execute grants
-- ---------------------------------------------------------------------
revoke execute on all functions in schema public from public, anon, authenticated;
grant execute on all functions in schema public to service_role;

-- The only RPC the app may call directly: it reads auth.uid() and returns own data.
grant execute on function public.my_entitlements() to authenticated;
-- needed by RLS / views evaluated as authenticated
grant execute on function public.utc_today() to authenticated;
