-- =====================================================================
-- Tests for Play subscriptions, admin subscription/config tools, AI cost
-- history, re-translation writes and device tokens.
-- =====================================================================
\set ON_ERROR_STOP on
set client_min_messages = notice;

create or replace function pg_temp.mk_job(p_user uuid, p_pages integer default 3)
returns uuid language plpgsql as $$
declare v_job uuid;
begin
  insert into public.translation_jobs (user_id, chapter_title, page_count, idempotency_key)
  values (p_user, 'Chapter', p_pages, gen_random_uuid()) returning id into v_job;
  insert into public.translation_pages (job_id, user_id, page_index, original_path)
  select v_job, p_user, i, p_user || '/' || v_job || '/original/' || i || '.webp' from generate_series(0, p_pages - 1) i;
  return v_job;
end $$;

insert into auth.users (id, email, raw_user_meta_data) values
  ('a4444444-4444-4444-4444-444444444444', 'dave@example.com', '{"name":"Dave"}'),
  ('a5555555-5555-5555-5555-555555555555', 'erin@example.com', '{"name":"Erin"}'),
  ('a6666666-6666-6666-6666-666666666666', 'boss@example.com', '{"name":"Boss"}');
insert into public.admin_roles (user_id) values ('a6666666-6666-6666-6666-666666666666');

-- ---------------------------------------------------------------------
-- PLAY: purchase -> active Pro; replay by another account refused;
-- renewal extends; cancel keeps access; upgrade ends the old token.
-- ---------------------------------------------------------------------
do $$
declare
  d uuid := 'a4444444-4444-4444-4444-444444444444';
  e uuid := 'a5555555-5555-5555-5555-555555555555';
  t1 bytea := digest('token-1', 'sha256');
  t2 bytea := digest('token-2', 'sha256');
  r jsonb;
begin
  r := public.apply_play_subscription(d, 'pro', t1, null, 'GPA.1', 'SUBSCRIPTION_STATE_PENDING', now(), now() + interval '30 days', true);
  assert r ->> 'error' = 'purchase_pending', 'pending must not grant ' || r;
  assert (public.usage_status(d) ->> 'plan_id') = 'free', 'still free while pending';

  r := public.apply_play_subscription(d, 'pro', t1, null, 'GPA.1', 'SUBSCRIPTION_STATE_ACTIVE', now(), now() + interval '30 days', true);
  assert (r ->> 'ok')::boolean, 'purchase ' || r;
  assert (public.usage_status(d) ->> 'plan_id') = 'pro', 'pro after purchase';

  r := public.apply_play_subscription(e, 'pro', t1, null, 'GPA.1', 'SUBSCRIPTION_STATE_ACTIVE', now(), now() + interval '30 days', true);
  assert r ->> 'error' = 'purchase_owned_by_other', 'token replay ' || r;
  assert (public.usage_status(e) ->> 'plan_id') = 'free', 'erin gained nothing';

  -- RTDN renewal (owner looked up by token)
  r := public.apply_play_subscription(null, 'pro', t1, null, 'GPA.1..0', 'SUBSCRIPTION_STATE_ACTIVE', now(), now() + interval '60 days', true);
  assert (r ->> 'ok')::boolean and (r ->> 'user_id')::uuid = d, 'renewal ' || r;
  assert (select count(*) from public.subscriptions where user_id = d and source = 'google_play') = 1, 'renewal updates in place';

  -- cancelled but paid until expiry: still Pro
  perform public.apply_play_subscription(null, 'pro', t1, null, null, 'SUBSCRIPTION_STATE_CANCELED', now(), now() + interval '60 days', false);
  assert (public.usage_status(d) ->> 'plan_id') = 'pro', 'cancel keeps access until expiry';

  -- unknown token from RTDN is ignored
  r := public.apply_play_subscription(null, 'pro', digest('nope', 'sha256'), null, null, 'SUBSCRIPTION_STATE_ACTIVE', now(), now() + interval '1 day', true);
  assert r ->> 'error' = 'unknown_purchase', 'unknown rtdn ' || r;

  -- upgrade to Premium with linked token: old Pro ends
  r := public.apply_play_subscription(d, 'premium', t2, t1, 'GPA.2', 'SUBSCRIPTION_STATE_ACTIVE', now(), now() + interval '30 days', true);
  assert (r ->> 'ok')::boolean, 'upgrade ' || r;
  assert (public.usage_status(d) ->> 'plan_id') = 'premium', 'premium after upgrade';
  assert (select status from public.subscriptions where play_purchase_token_hash = t1) = 'expired', 'old token expired';

  -- refund
  assert public.revoke_play_subscription(t2, 'refunded'), 'refund applied';
  assert (public.usage_status(d) ->> 'plan_id') = 'free', 'free after refund';
  assert not public.revoke_play_subscription(t2, 'refunded'), 'refund idempotent';

  r := public.apply_play_subscription(d, 'free', digest('x', 'sha256'), null, null, 'SUBSCRIPTION_STATE_ACTIVE', now(), now() + interval '1 day', true);
  assert r ->> 'error' = 'unknown_product', 'free is not purchasable';
  raise notice 'PASS play subscriptions';
end $$;

-- ---------------------------------------------------------------------
-- ADMIN: grant / revoke subscriptions, config updates, cost history
-- ---------------------------------------------------------------------
do $$
declare
  e uuid := 'a5555555-5555-5555-5555-555555555555';
  boss uuid := 'a6666666-6666-6666-6666-666666666666';
  r jsonb; sub uuid; n integer;
begin
  begin
    perform public.admin_grant_subscription(e, e, 'premium', 30, 'self');
    raise exception 'non-admin granted';
  exception when raise_exception then
    if sqlerrm <> 'forbidden' then raise; end if;
  end;

  r := public.admin_grant_subscription(boss, e, 'premium', 30, 'support');
  assert (r ->> 'ok')::boolean, 'grant ' || r;
  sub := (r ->> 'subscription_id')::uuid;
  assert (public.usage_status(e) ->> 'plan_id') = 'premium', 'granted premium';
  r := public.admin_grant_subscription(boss, e, 'premium', 10, 'extend');
  assert (r ->> 'starts_at')::timestamptz >= now() + interval '29 days', 'same plan stacks ' || r;
  assert (public.admin_grant_subscription(boss, e, 'free', 10, '') ->> 'error') = 'invalid_input', 'free grant refused';

  assert public.admin_revoke_subscription(boss, sub, 'test'), 'revoke';
  assert not public.admin_revoke_subscription(boss, sub, 'again'), 'revoke twice';
  assert (select count(*) from public.admin_audit_logs where action in ('subscription_grant', 'subscription_revoke') and actor_id = boss) = 3, 'audited';

  assert (public.admin_update_config(boss, 'ai_cost_alerts', '{"daily_usd_limit": 25}') ->> 'ok')::boolean, 'config ok';
  assert (select value ->> 'daily_usd_limit' from public.app_config where key = 'ai_cost_alerts') = '25', 'config saved';
  assert (public.admin_update_config(boss, 'ai_cost_alerts', '{"daily_usd_limit": "lots"}') ->> 'error') = 'invalid_input', 'bad shape';
  assert (public.admin_update_config(boss, 'max_pages_per_chapter', '9999') ->> 'error') = 'invalid_input', 'out of range';
  assert (public.admin_update_config(boss, 'secret_key', '1') ->> 'error') = 'invalid_input', 'unknown key';
  assert (public.admin_update_config(boss, 'max_pages_per_chapter', '150') ->> 'ok')::boolean, 'pages ok';

  insert into public.ai_usage_events (provider, operation, cost_usd_micros, success) values
    ('claude', 'translate', 1500000, true), ('claude', 'translate', 0, false);
  select count(*) into n from public.admin_ai_cost_daily(boss, 14);
  assert n = 14, 'one row per day, got ' || n;
  assert (select cost_usd from public.admin_ai_cost_daily(boss, 14) where day = public.utc_today()) >= 1.5, 'today cost';
  assert (select failed from public.admin_ai_cost_daily(boss, 14) where day = public.utc_today()) >= 1, 'today failures';
  raise notice 'PASS admin subscriptions/config/cost';
end $$;

-- ---------------------------------------------------------------------
-- RE-TRANSLATION keeps user edits unless asked; device tokens private
-- ---------------------------------------------------------------------
do $$
declare
  d uuid := 'a4444444-4444-4444-4444-444444444444';
  j uuid; p uuid; s uuid;
begin
  j := pg_temp.mk_job(d, 1);
  select id into p from public.translation_pages where job_id = j;
  insert into public.translation_segments (page_id, job_id, user_id, reading_order, bbox_x, bbox_y, bbox_w, bbox_h, original_text, translated_text, user_edited_text)
  values (p, j, d, 0, 0.1, 0.1, 0.2, 0.1, 'Hello', 'مرحبا', 'أهلاً') returning id into s;

  perform public.worker_set_segment_translation(s, 'السلام عليكم', false);
  assert (select translated_text = 'السلام عليكم' and user_edited_text = 'أهلاً' from public.translation_segments where id = s), 'edit kept';
  perform public.worker_set_segment_translation(s, 'مرحباً بك', true);
  assert (select translated_text = 'مرحباً بك' and user_edited_text is null from public.translation_segments where id = s), 'edit replaced on request';

  insert into public.device_tokens (token, user_id) values ('fcm-token-aaaaaaaaaaaaaaaaaaaa', d);
  perform set_config('request.jwt.claim.sub', d::text, true);
  set local role authenticated;
  begin
    perform 1 from public.device_tokens;
    raise exception 'client can read device tokens';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.apply_play_subscription(d, 'premium', digest('free-money', 'sha256'), null, null, 'SUBSCRIPTION_STATE_ACTIVE', now(), now() + interval '1 year', true);
    raise exception 'client called billing function';
  exception when insufficient_privilege then null;
  end;
  raise notice 'PASS re-translation + device tokens + grants';
end $$;

\echo BILLING/ADMIN TESTS PASSED
