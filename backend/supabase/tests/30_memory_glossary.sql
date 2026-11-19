-- =====================================================================
-- Tests for story memory controls and AI glossary suggestions.
-- =====================================================================
\set ON_ERROR_STOP on
set client_min_messages = notice;

insert into auth.users (id, email, raw_user_meta_data) values
  ('a7777777-7777-7777-7777-777777777777', 'gina@example.com', '{"name":"Gina"}'),
  ('a8888888-8888-8888-8888-888888888888', 'hank@example.com', '{"name":"Hank"}');

do $$
declare
  g uuid := 'a7777777-7777-7777-7777-777777777777';
  h uuid := 'a8888888-8888-8888-8888-888888888888';
  w uuid;
  n integer;
begin
  insert into public.user_library (user_id, title) values (g, 'Memory Story') returning id into w;
  insert into public.glossary_entries (user_id, work_id, source_term, target_term) values (g, null, 'Hunter', 'صياد');
  insert into public.glossary_entries (user_id, work_id, source_term, target_term) values (g, w, 'Gate', 'البوابة');

  n := public.worker_add_glossary_suggestions(g, w, '[
    {"source":"hunter","target":"صيّاد","type":"rank"},
    {"source":"Gate","target":"بوابة","type":"place"},
    {"source":"Shadow Monarch","target":"ملك الظلال","type":"rank"},
    {"source":"shadow monarch","target":"ملك الظل","type":"rank"},
    {"source":"Igris","target":"إيغريس","type":"not_a_type"},
    {"source":"","target":"x"},
    "garbage"
  ]'::jsonb);
  assert n = 2, 'only new, valid, de-duplicated terms are suggested (got ' || n || ')';
  assert (select target_term from public.glossary_entries where user_id = g and work_id is null and source_term = 'Hunter') = 'صياد', 'global term untouched';
  assert (select status from public.glossary_entries where user_id = g and work_id = w and source_term = 'Gate') = 'confirmed', 'confirmed term untouched';
  assert (select term_type::text from public.glossary_entries where user_id = g and lower(source_term) = 'igris') = 'term', 'unknown type falls back to term';
  assert (select count(*) from public.glossary_entries where user_id = g and status = 'suggested') = 2, 'two pending';
  assert public.worker_add_glossary_suggestions(g, null, '[{"source":"A","target":"ب"}]') = 0, 'no suggestions without a work';

  -- Owner confirms a suggestion and freezes memory; another user cannot touch them.
  insert into public.work_context (work_id, user_id, story_summary) values (w, g, 'Jinwoo enters the gate.');
  perform set_config('request.jwt.claim.sub', g::text, true);
  set local role authenticated;
  update public.glossary_entries set status = 'confirmed' where work_id = w and source_term = 'Shadow Monarch';
  update public.work_context set memory_frozen = true, story_summary = '' where work_id = w;
  begin
    perform public.worker_add_glossary_suggestions(g, w, '[{"source":"B","target":"ب"}]');
    raise exception 'client called worker suggestion function';
  exception when insufficient_privilege then null;
  end;
  reset role;

  perform set_config('request.jwt.claim.sub', h::text, true);
  set local role authenticated;
  update public.glossary_entries set status = 'suggested' where work_id = w;
  update public.work_context set memory_frozen = false where work_id = w;
  reset role;

  assert (select status from public.glossary_entries where work_id = w and source_term = 'Shadow Monarch') = 'confirmed', 'owner confirmed';
  assert (select memory_frozen and story_summary = '' from public.work_context where work_id = w), 'owner froze and cleared memory; other user changed nothing';
  raise notice 'PASS glossary suggestions + memory controls';
end $$;

\echo MEMORY/GLOSSARY TESTS PASSED
