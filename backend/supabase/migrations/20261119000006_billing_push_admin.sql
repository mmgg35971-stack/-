-- =====================================================================
-- Phase 6/7: Google Play subscriptions, push device tokens, admin
-- subscription management, editable app config, AI cost history,
-- single-segment re-translation bookkeeping.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Push device tokens (FCM). Written only by Edge Functions.
-- ---------------------------------------------------------------------
create table public.device_tokens (
  token        text primary key,
  user_id      uuid not null references public.user_profiles (id) on delete cascade,
  platform     text not null default 'android' check (platform in ('android')),
  app_language text not null default 'ar' check (app_language in ('ar', 'en')),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index device_tokens_user_idx on public.device_tokens (user_id);
alter table public.device_tokens enable row level security;
revoke all on public.device_tokens from anon, authenticated;

-- ---------------------------------------------------------------------
-- Google Play: apply the state returned by the Play Developer API.
-- The Edge Function has already verified the token with Google; this
-- function only maps it onto our subscriptions table atomically.
-- ---------------------------------------------------------------------
create or replace function public.apply_play_subscription(
  p_user            uuid,      -- null = look the owner up by token (RTDN)
  p_plan            text,
  p_token_hash      bytea,
  p_linked_hash     bytea,     -- linkedPurchaseToken (upgrade/downgrade/resubscribe), may be null
  p_order_id        text,
  p_state           text,      -- SUBSCRIPTION_STATE_* from subscriptionsv2
  p_starts_at       timestamptz,
  p_expires_at      timestamptz,
  p_auto_renewing   boolean
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_sub     public.subscriptions;
  v_user    uuid := p_user;
  v_status  public.subscription_status;
begin
  if not exists (select 1 from public.subscription_plans where id = p_plan and id <> 'free') then
    return jsonb_build_object('ok', false, 'error', 'unknown_product');
  end if;

  v_status := case p_state
    when 'SUBSCRIPTION_STATE_ACTIVE' then 'active'
    when 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' then 'grace'
    when 'SUBSCRIPTION_STATE_ON_HOLD' then 'on_hold'
    when 'SUBSCRIPTION_STATE_PAUSED' then 'on_hold'
    when 'SUBSCRIPTION_STATE_CANCELED' then 'cancelled'
    when 'SUBSCRIPTION_STATE_EXPIRED' then 'expired'
    else null
  end::public.subscription_status;
  if v_status is null then
    -- PENDING / UNSPECIFIED: payment not complete, grant nothing yet.
    return jsonb_build_object('ok', false, 'error', 'purchase_pending');
  end if;

  -- Serialize all writers of the same purchase token.
  perform pg_advisory_xact_lock(hashtextextended(encode(p_token_hash, 'hex'), 7));

  select * into v_sub from public.subscriptions where play_purchase_token_hash = p_token_hash for update;

  if found then
    if v_user is not null and v_sub.user_id <> v_user then
      -- A token can belong to exactly one account (prevents sharing one purchase).
      return jsonb_build_object('ok', false, 'error', 'purchase_owned_by_other');
    end if;
    v_user := v_sub.user_id;
    update public.subscriptions
    set plan_id = p_plan,
        status = v_status,
        expires_at = greatest(p_expires_at, starts_at + interval '1 second'),
        auto_renewing = p_auto_renewing,
        play_order_id = coalesce(p_order_id, play_order_id)
    where id = v_sub.id
    returning * into v_sub;
  else
    if v_user is null then
      -- RTDN for a purchase the app has not verified yet: the app will call billing-verify.
      return jsonb_build_object('ok', false, 'error', 'unknown_purchase');
    end if;
    insert into public.subscriptions
      (user_id, plan_id, source, status, starts_at, expires_at, auto_renewing, play_purchase_token_hash, play_order_id)
    values
      (v_user, p_plan, 'google_play', v_status, least(p_starts_at, now()),
       greatest(p_expires_at, least(p_starts_at, now()) + interval '1 second'),
       p_auto_renewing, p_token_hash, p_order_id)
    returning * into v_sub;

    insert into public.notifications (user_id, type, title, body, data)
    values (v_user, 'subscription_activated', 'تم تفعيل اشتراكك',
            'شكراً لك! خطة ' || initcap(p_plan) || ' فعّالة الآن.',
            jsonb_build_object('plan_id', p_plan, 'expires_at', v_sub.expires_at));
  end if;

  -- The old token of an upgrade/downgrade stops granting access.
  if p_linked_hash is not null then
    update public.subscriptions
    set status = 'expired', expires_at = greatest(now(), starts_at + interval '1 second'), auto_renewing = false
    where play_purchase_token_hash = p_linked_hash and user_id = v_user and id <> v_sub.id
      and status in ('active', 'grace', 'cancelled', 'on_hold');
  end if;

  return jsonb_build_object('ok', true, 'user_id', v_user, 'subscription_id', v_sub.id,
                            'plan_id', v_sub.plan_id, 'status', v_sub.status, 'expires_at', v_sub.expires_at);
end;
$$;

-- Refund / revocation from Play (voided purchase or REVOKED notification).
create or replace function public.revoke_play_subscription(p_token_hash bytea, p_status text)
returns boolean
language sql
security definer
set search_path = public
as $$
  with x as (
    update public.subscriptions
    set status = p_status::public.subscription_status,
        expires_at = greatest(now(), starts_at + interval '1 second'),
        auto_renewing = false
    where play_purchase_token_hash = p_token_hash and status not in ('refunded', 'revoked')
    returning 1
  ) select exists (select 1 from x);
$$;

-- ---------------------------------------------------------------------
-- Admin: grant / revoke a subscription manually (support, refunds).
-- ---------------------------------------------------------------------
create or replace function public.admin_grant_subscription(
  p_actor uuid, p_user uuid, p_plan text, p_days integer, p_note text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_starts  timestamptz := now();
  v_sub     uuid;
  v_expires timestamptz;
begin
  if not public.is_admin(p_actor) then raise exception 'forbidden'; end if;
  if p_plan not in ('pro', 'premium') or p_days not between 1 and 3650 then
    return jsonb_build_object('ok', false, 'error', 'invalid_input');
  end if;
  if not exists (select 1 from public.user_profiles where id = p_user) then
    return jsonb_build_object('ok', false, 'error', 'user_not_found');
  end if;

  -- Same plan already running: extend after it, like a gift code.
  select max(expires_at) into v_starts from public.subscriptions
  where user_id = p_user and plan_id = p_plan and status in ('active', 'grace', 'cancelled') and expires_at > now();
  v_starts := coalesce(v_starts, now());
  v_expires := v_starts + make_interval(days => p_days);

  insert into public.subscriptions (user_id, plan_id, source, status, starts_at, expires_at, granted_by)
  values (p_user, p_plan, 'admin_grant', 'active', v_starts, v_expires, p_actor)
  returning id into v_sub;

  insert into public.admin_audit_logs (actor_id, action, target_type, target_id, details)
  values (p_actor, 'subscription_grant', 'user', p_user::text,
          jsonb_build_object('plan', p_plan, 'days', p_days, 'subscription_id', v_sub, 'note', left(coalesce(p_note, ''), 300)));

  insert into public.notifications (user_id, type, title, body, data)
  values (p_user, 'subscription_activated', 'تم تفعيل اشتراكك',
          'تم تفعيل خطة ' || initcap(p_plan) || ' لمدة ' || p_days || ' يوماً.',
          jsonb_build_object('plan_id', p_plan, 'expires_at', v_expires));

  return jsonb_build_object('ok', true, 'subscription_id', v_sub, 'starts_at', v_starts, 'expires_at', v_expires);
end;
$$;

create or replace function public.admin_revoke_subscription(p_actor uuid, p_subscription uuid, p_note text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user uuid;
begin
  if not public.is_admin(p_actor) then raise exception 'forbidden'; end if;
  update public.subscriptions
  set status = 'revoked', expires_at = greatest(now(), starts_at + interval '1 second'), auto_renewing = false
  where id = p_subscription and plan_id <> 'free' and status in ('active', 'grace', 'cancelled', 'on_hold')
  returning user_id into v_user;
  if v_user is null then return false; end if;

  insert into public.admin_audit_logs (actor_id, action, target_type, target_id, details)
  values (p_actor, 'subscription_revoke', 'subscription', p_subscription::text,
          jsonb_build_object('user_id', v_user, 'note', left(coalesce(p_note, ''), 300)));
  return true;
end;
$$;

-- ---------------------------------------------------------------------
-- Admin: editable configuration (whitelisted keys, validated shape).
-- ---------------------------------------------------------------------
create or replace function public.admin_update_config(p_actor uuid, p_key text, p_value jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_old jsonb;
begin
  if not public.is_admin(p_actor) then raise exception 'forbidden'; end if;

  if p_key = 'fair_use' then
    if jsonb_typeof(p_value->'premium_soft_daily_chapters') <> 'number'
       or jsonb_typeof(p_value->'max_job_starts_per_10min') <> 'number'
       or (p_value->>'premium_soft_daily_chapters')::numeric not between 10 and 100000
       or (p_value->>'max_job_starts_per_10min')::numeric not between 1 and 1000 then
      return jsonb_build_object('ok', false, 'error', 'invalid_input');
    end if;
  elsif p_key = 'ai_cost_alerts' then
    if jsonb_typeof(p_value->'daily_usd_limit') <> 'number'
       or (p_value->>'daily_usd_limit')::numeric not between 0 and 1000000 then
      return jsonb_build_object('ok', false, 'error', 'invalid_input');
    end if;
  elsif p_key = 'max_pages_per_chapter' then
    if jsonb_typeof(p_value) <> 'number' or p_value::text::numeric not between 1 and 500 then
      return jsonb_build_object('ok', false, 'error', 'invalid_input');
    end if;
  else
    return jsonb_build_object('ok', false, 'error', 'invalid_input');
  end if;

  select value into v_old from public.app_config where key = p_key for update;
  update public.app_config set value = p_value, updated_at = now() where key = p_key;

  insert into public.admin_audit_logs (actor_id, action, target_type, target_id, details)
  values (p_actor, 'config_update', 'app_config', p_key, jsonb_build_object('old', v_old, 'new', p_value));
  return jsonb_build_object('ok', true);
end;
$$;

-- Daily AI cost / failures for the last N UTC days (admin cost dashboard).
create or replace function public.admin_ai_cost_daily(p_actor uuid, p_days integer)
returns table (day date, cost_usd numeric, requests bigint, failed bigint)
language plpgsql
stable
security definer
set search_path = public
as $$
begin
  if not public.is_admin(p_actor) then raise exception 'forbidden'; end if;
  return query
  select d::date,
         coalesce(sum(e.cost_usd_micros), 0) / 1e6,
         count(e.id),
         count(e.id) filter (where not e.success)
  from generate_series(public.utc_today() - (least(greatest(p_days, 1), 90) - 1), public.utc_today(), interval '1 day') d
  left join public.ai_usage_events e
    on (e.created_at at time zone 'utc')::date = d::date
  group by d
  order by d;
end;
$$;

-- ---------------------------------------------------------------------
-- Single-segment re-translation: write the new machine translation.
-- user_edited_text is cleared only when the user explicitly asked.
-- ---------------------------------------------------------------------
create or replace function public.worker_set_segment_translation(p_segment uuid, p_text text, p_clear_edit boolean)
returns void
language sql
security definer
set search_path = public
as $$
  update public.translation_segments
  set translated_text = left(p_text, 4000),
      user_edited_text = case when p_clear_edit then null else user_edited_text end,
      needs_review = false
  where id = p_segment;
$$;

-- ---------------------------------------------------------------------
-- Grants: same rule as every business function, service_role only.
-- ---------------------------------------------------------------------
revoke execute on function public.apply_play_subscription(uuid, text, bytea, bytea, text, text, timestamptz, timestamptz, boolean) from public, anon, authenticated;
revoke execute on function public.revoke_play_subscription(bytea, text) from public, anon, authenticated;
revoke execute on function public.admin_grant_subscription(uuid, uuid, text, integer, text) from public, anon, authenticated;
revoke execute on function public.admin_revoke_subscription(uuid, uuid, text) from public, anon, authenticated;
revoke execute on function public.admin_update_config(uuid, text, jsonb) from public, anon, authenticated;
revoke execute on function public.admin_ai_cost_daily(uuid, integer) from public, anon, authenticated;
revoke execute on function public.worker_set_segment_translation(uuid, text, boolean) from public, anon, authenticated;
grant execute on function public.apply_play_subscription(uuid, text, bytea, bytea, text, text, timestamptz, timestamptz, boolean) to service_role;
grant execute on function public.revoke_play_subscription(bytea, text) to service_role;
grant execute on function public.admin_grant_subscription(uuid, uuid, text, integer, text) to service_role;
grant execute on function public.admin_revoke_subscription(uuid, uuid, text) to service_role;
grant execute on function public.admin_update_config(uuid, text, jsonb) to service_role;
grant execute on function public.admin_ai_cost_daily(uuid, integer) to service_role;
grant execute on function public.worker_set_segment_translation(uuid, text, boolean) to service_role;
grant all on public.device_tokens to service_role;

-- ---------------------------------------------------------------------
-- Scheduled maintenance where pg_cron is available (Supabase): expire
-- ended subscriptions every 15 minutes. Skipped on plain Postgres (tests).
-- ---------------------------------------------------------------------
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron;
    perform cron.schedule('expire-subscriptions', '*/15 * * * *', 'select public.expire_subscriptions()');
  end if;
end $$;
