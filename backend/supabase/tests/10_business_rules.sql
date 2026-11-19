-- =====================================================================
-- Business-rule tests for the database layer.
-- Run with: backend/scripts/test-db.sh   (uses a throwaway local Postgres)
-- Every block raises an exception (=> non-zero exit) when a rule breaks.
-- =====================================================================
\set ON_ERROR_STOP on
set client_min_messages = notice;

-- Fixed test identities
\set alice   '''11111111-1111-1111-1111-111111111111'''
\set bob     '''22222222-2222-2222-2222-222222222222'''
\set carol   '''33333333-3333-3333-3333-333333333333'''

create or replace function pg_temp.mk_job(p_user uuid, p_pages integer default 3, p_quality text default 'standard')
returns uuid language plpgsql as $$
declare v_job uuid;
begin
  insert into public.translation_jobs (user_id, chapter_title, page_count, idempotency_key, translation_quality)
  values (p_user, 'Chapter', p_pages, gen_random_uuid(), p_quality) returning id into v_job;
  insert into public.translation_pages (job_id, user_id, page_index, original_path)
  select v_job, p_user, i, p_user || '/' || v_job || '/original/' || i || '.webp' from generate_series(0, p_pages - 1) i;
  return v_job;
end $$;

create or replace function pg_temp.h(p text) returns bytea language sql as $$ select digest(p, 'sha256') $$;

-- ---------------------------------------------------------------------
-- AUTH: first sign-in creates profile + settings
-- ---------------------------------------------------------------------
insert into auth.users (id, email, raw_user_meta_data) values
  (:alice, 'alice@example.com', '{"full_name":"Alice","sub":"g-1","avatar_url":"https://x/a.png"}'),
  (:bob,   'bob@example.com',   '{"name":"Bob"}'),
  (:carol, 'carol@example.com', '{}');

do $$
begin
  assert (select count(*) from public.user_profiles) = 3, 'profiles not created';
  assert (select display_name from public.user_profiles where email = 'alice@example.com') = 'Alice', 'display name';
  assert (select google_account_id from public.user_profiles where email = 'alice@example.com') = 'g-1', 'google id';
  assert (select display_name from public.user_profiles where email = 'carol@example.com') = 'carol', 'fallback name';
  assert (select count(*) from public.user_settings) = 3, 'settings not created';
  raise notice 'PASS auth/profile creation';
end $$;

-- ---------------------------------------------------------------------
-- USAGE: Free = 10/day, counted once, retries never double-count
-- ---------------------------------------------------------------------
do $$
declare
  a uuid := '11111111-1111-1111-1111-111111111111';
  j uuid; r jsonb; i integer;
begin
  r := public.usage_status(a);
  assert r ->> 'plan_id' = 'free' and (r ->> 'daily_limit')::int = 10 and (r ->> 'used_today')::int = 0, 'free status ' || r;

  j := pg_temp.mk_job(a);
  r := public.start_translation_job(a, j);
  assert (r ->> 'ok')::boolean and not (r ->> 'already_counted')::boolean, 'first start ' || r;
  r := public.start_translation_job(a, j);       -- duplicate request / retry
  assert (r ->> 'ok')::boolean and (r ->> 'already_counted')::boolean, 'retry must not count ' || r;
  assert (public.usage_status(a) ->> 'used_today')::int = 1, 'used must be 1';

  for i in 2..10 loop
    r := public.start_translation_job(a, pg_temp.mk_job(a));
    assert (r ->> 'ok')::boolean, 'start #' || i || ' ' || r;
  end loop;
  assert (public.usage_status(a) ->> 'remaining_today')::int = 0, 'remaining 0';

  r := public.start_translation_job(a, pg_temp.mk_job(a));
  assert r ->> 'error' = 'daily_limit_reached', '11th must fail ' || r;
  assert (public.usage_status(a) ->> 'used_today')::int = 10, 'still 10';
  raise notice 'PASS free limit + idempotency';
end $$;

-- Refund when an internal failure happens before any page completed
do $$
declare
  b uuid := '22222222-2222-2222-2222-222222222222';
  j uuid; r jsonb;
begin
  j := pg_temp.mk_job(b);
  r := public.start_translation_job(b, j);
  assert (public.usage_status(b) ->> 'used_today')::int = 1, 'b used 1';
  update public.translation_jobs set status = 'failed' where id = j;
  assert public.refund_translation_job(j, 'provider_unavailable'), 'refund must succeed';
  assert not public.refund_translation_job(j, 'again'), 'refund only once';
  assert (public.usage_status(b) ->> 'used_today')::int = 0, 'b used back to 0';
  -- user retries the same failed chapter: it is counted again (once)
  r := public.start_translation_job(b, j);
  assert (r ->> 'ok')::boolean and not (r ->> 'already_counted')::boolean, 'recount after refund ' || r;
  assert (public.usage_status(b) ->> 'used_today')::int = 1, 'b used 1 again';
  -- no refund once a page completed
  update public.translation_jobs set pages_completed = 1, status = 'failed' where id = j;
  assert not public.refund_translation_job(j, 'x'), 'no refund after partial success';
  raise notice 'PASS refund policy';
end $$;

-- Plan feature gates
do $$
declare
  c uuid := '33333333-3333-3333-3333-333333333333';
  r jsonb;
begin
  r := public.start_translation_job(c, pg_temp.mk_job(c, 61));
  assert r ->> 'error' = 'too_many_pages', 'free max pages ' || r;
  r := public.start_translation_job(c, pg_temp.mk_job(c, 2, 'best'));
  assert r ->> 'error' = 'quality_not_in_plan', 'quality gate ' || r;
  r := public.start_translation_job(c, gen_random_uuid());
  assert r ->> 'error' = 'job_not_found', 'unknown job';
  r := public.start_translation_job('22222222-2222-2222-2222-222222222222', pg_temp.mk_job(c));
  assert r ->> 'error' = 'job_not_found', 'cannot start someone else''s job';
  raise notice 'PASS plan gates';
end $$;

-- ---------------------------------------------------------------------
-- ADMIN activation
-- ---------------------------------------------------------------------
insert into public.admin_activation_secrets (secret_hash, label, max_uses)
values (pg_temp.h('correct-horse-battery'), 'bootstrap', 1);

do $$
declare
  a uuid := '11111111-1111-1111-1111-111111111111';
  b uuid := '22222222-2222-2222-2222-222222222222';
  r jsonb;
begin
  r := public.activate_admin(b, pg_temp.h('wrong'));
  assert r ->> 'error' = 'invalid_code' and not public.is_admin(b), 'wrong secret';
  r := public.activate_admin(a, pg_temp.h('correct-horse-battery'));
  assert (r ->> 'ok')::boolean and public.is_admin(a), 'activate ' || r;
  r := public.activate_admin(b, pg_temp.h('correct-horse-battery'));
  assert r ->> 'error' = 'invalid_code' and not public.is_admin(b), 'single-use secret reused';
  assert exists (select 1 from public.admin_audit_logs where action = 'admin.activated'), 'audit';
  begin
    perform public.admin_create_gift_codes(b, 'pro', 30, array[pg_temp.h('x')], array['PRO-…-XXXX'], null, 1, null);
    raise exception 'non-admin created codes';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.admin_overview_stats(b);
    raise exception 'non-admin read stats';
  exception when insufficient_privilege then null;
  end;
  raise notice 'PASS admin activation + RBAC';
end $$;

-- ---------------------------------------------------------------------
-- GIFT CODES
-- ---------------------------------------------------------------------
do $$
declare
  a uuid := '11111111-1111-1111-1111-111111111111';
  b uuid := '22222222-2222-2222-2222-222222222222';
  c uuid := '33333333-3333-3333-3333-333333333333';
  r jsonb; first_exp timestamptz; code_id uuid;
begin
  perform public.admin_create_gift_codes(a, 'pro', 30,
    array[pg_temp.h('PRO-AAAA'), pg_temp.h('PRO-BBBB'), pg_temp.h('PRO-EXPD'), pg_temp.h('PRO-REVK')],
    array['PRO-…-AAAA', 'PRO-…-BBBB', 'PRO-…-EXPD', 'PRO-…-REVK'], null, 1, 'test');
  perform public.admin_create_gift_codes(a, 'premium', 7, array[pg_temp.h('PREM-CCCC')], array['PREM-…-CCCC'], null, 1, null);

  update public.gift_codes set expires_at = now() - interval '1 minute' where code_hint = 'PRO-…-EXPD';
  select id into code_id from public.gift_codes where code_hint = 'PRO-…-REVK';
  assert public.admin_revoke_gift_code(a, code_id), 'revoke';

  assert public.redeem_gift_code(b, pg_temp.h('NOPE')) ->> 'error' = 'invalid_code', 'invalid';
  assert public.redeem_gift_code(b, pg_temp.h('PRO-EXPD')) ->> 'error' = 'code_expired', 'expired';
  assert public.redeem_gift_code(b, pg_temp.h('PRO-REVK')) ->> 'error' = 'code_revoked', 'revoked';

  r := public.redeem_gift_code(b, pg_temp.h('PRO-AAAA'));
  assert (r ->> 'ok')::boolean and r ->> 'plan_id' = 'pro', 'redeem ' || r;
  first_exp := (r ->> 'expires_at')::timestamptz;
  assert public.usage_status(b) ->> 'plan_id' = 'pro', 'b is pro';
  assert (public.usage_status(b) ->> 'daily_limit')::int = 100, 'pro limit 100';
  assert public.redeem_gift_code(b, pg_temp.h('PRO-AAAA')) ->> 'error' = 'code_already_used', 'reuse same user';
  assert public.redeem_gift_code(c, pg_temp.h('PRO-AAAA')) ->> 'error' = 'code_already_used', 'reuse other user';

  -- same plan stacks after current expiry
  r := public.redeem_gift_code(b, pg_temp.h('PRO-BBBB'));
  assert (r ->> 'starts_at')::timestamptz = first_exp, 'stacked start ' || r;
  assert (r ->> 'expires_at')::timestamptz = first_exp + interval '30 days', 'stacked end';

  -- premium on top of pro starts now
  r := public.redeem_gift_code(b, pg_temp.h('PREM-CCCC'));
  assert (r ->> 'ok')::boolean, 'premium ' || r;
  assert public.usage_status(b) ->> 'plan_id' = 'premium', 'b is premium';
  assert public.usage_status(b) -> 'daily_limit' = 'null'::jsonb, 'premium unlimited';

  assert (public.admin_overview_stats(a) ->> 'gift_codes_total')::int = 5, 'stats total';
  assert (public.admin_overview_stats(a) ->> 'gift_codes_used')::int = 3, 'stats used';
  raise notice 'PASS gift codes';
end $$;

-- Pro code while premium is active is refused (not wasted)
do $$
declare
  a uuid := '11111111-1111-1111-1111-111111111111';
  b uuid := '22222222-2222-2222-2222-222222222222';
begin
  perform public.admin_create_gift_codes(a, 'pro', 30, array[pg_temp.h('PRO-DDDD')], array['PRO-…-DDDD'], null, 1, null);
  assert public.redeem_gift_code(b, pg_temp.h('PRO-DDDD')) ->> 'error' = 'higher_plan_active', 'conflict';
  assert (select status from public.gift_codes where code_hint = 'PRO-…-DDDD') = 'active', 'code kept';
  raise notice 'PASS plan conflict';
end $$;

-- Expiry: when premium ends, the underlying pro becomes effective again; then free
do $$
declare
  b uuid := '22222222-2222-2222-2222-222222222222';
begin
  update public.subscriptions set starts_at = now() - interval '8 days', expires_at = now() - interval '1 second'
  where user_id = b and plan_id = 'premium';
  assert public.usage_status(b) ->> 'plan_id' = 'pro', 'falls back to pro';
  update public.subscriptions set starts_at = now() - interval '90 days', expires_at = now() - interval '1 second'
  where user_id = b and plan_id = 'pro';
  assert public.usage_status(b) ->> 'plan_id' = 'free', 'falls back to free';
  assert public.expire_subscriptions() >= 3, 'expire job';
  raise notice 'PASS subscription expiry';
end $$;

-- ---------------------------------------------------------------------
-- RLS / grants as the app would see them (role authenticated)
-- ---------------------------------------------------------------------
do $$
declare
  n integer; r jsonb;
begin
  set local role authenticated;
  perform set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);

  select count(*) into n from public.translation_jobs;
  assert n = (select count(*) from public.translation_jobs where user_id = auth.uid()), 'sees only own jobs';
  select count(*) into n from public.translation_jobs where user_id <> auth.uid();
  assert n = 0, 'other users jobs visible';
  select count(*) into n from public.user_profiles;
  assert n = 1, 'sees only own profile';
  select count(*) into n from public.translation_history;
  assert n = (select count(*) from public.translation_jobs), 'history scoped';

  r := public.my_entitlements();
  assert r ->> 'plan_id' = 'free', 'my_entitlements';

  begin
    perform 1 from public.gift_codes limit 1;
    raise exception 'gift_codes readable';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.redeem_gift_code(auth.uid(), '\x00'::bytea);
    raise exception 'redeem callable from client';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.start_translation_job(auth.uid(), gen_random_uuid());
    raise exception 'start callable from client';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into public.subscriptions (user_id, plan_id, source, expires_at)
    values (auth.uid(), 'premium', 'admin_grant', now() + interval '1 year');
    raise exception 'client created subscription';
  exception when insufficient_privilege then null;
  end;
  begin
    update public.daily_usage set chapters_used = 0;
    raise exception 'client reset usage';
  exception when insufficient_privilege then null;
  end;
  begin
    update public.translation_jobs set usage_counted = false;
    raise exception 'client changed usage flag';
  exception when insufficient_privilege then null;
  end;
  begin
    update public.user_profiles set status = 'active';
    raise exception 'client changed status';
  exception when insufficient_privilege then null;
  end;

  update public.user_profiles set display_name = 'Carol R' where id = auth.uid();
  update public.user_settings set theme = 'light' where user_id = auth.uid();
  insert into public.user_library (user_id, title) values (auth.uid(), 'Solo Story');
  begin
    insert into public.user_library (user_id, title) values ('22222222-2222-2222-2222-222222222222', 'x');
    raise exception 'inserted for another user';
  exception when insufficient_privilege then null;
  end;
  insert into public.glossary_entries (user_id, source_term, target_term, term_type)
  values (auth.uid(), 'Shadow Monarch', 'ملك الظلال', 'rank');
  begin
    insert into public.glossary_entries (user_id, source_term, target_term)
    values (auth.uid(), 'shadow monarch', 'مكرر');
    raise exception 'duplicate glossary term';
  exception when unique_violation then null;
  end;
  raise notice 'PASS RLS + grants';
end $$;

do $$
begin
  set local role anon;
  assert (select count(*) from public.subscription_plans) = 3, 'anon can read plans';
  begin
    perform 1 from public.user_profiles limit 1;
    raise exception 'anon reads profiles';
  exception when insufficient_privilege then null;
  end;
  raise notice 'PASS anon';
end $$;

\echo ALL DATABASE TESTS PASSED
