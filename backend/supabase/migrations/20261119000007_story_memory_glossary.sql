-- =====================================================================
-- Story memory controls + glossary suggestions (enhancement phase C).
--   * glossary_entries.status: 'suggested' terms come from the AI and are
--     NOT used in translation until the user confirms them.
--   * work_context.memory_frozen: the worker stops rewriting the memory.
-- Rollback:
--   drop function public.worker_add_glossary_suggestions(uuid, uuid, jsonb);
--   alter table public.glossary_entries drop column status;
--   alter table public.work_context drop column memory_frozen;
--   (then re-apply the original column grants from 20261119000002_rls.sql)
-- =====================================================================

alter table public.glossary_entries
  add column status text not null default 'confirmed' check (status in ('suggested', 'confirmed'));

create index glossary_suggested_idx on public.glossary_entries (user_id, work_id) where status = 'suggested';

alter table public.work_context
  add column memory_frozen boolean not null default false;

grant update (status) on public.glossary_entries to authenticated;
grant update (memory_frozen) on public.work_context to authenticated;

-- Called only by the worker (service role) after a chunk is translated.
-- Skips terms the user already has (for this work or globally), caps the
-- pending suggestions per work, and never touches confirmed entries.
create or replace function public.worker_add_glossary_suggestions(p_user uuid, p_work uuid, p_items jsonb)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_pending integer;
  v_room integer;
  v_added integer;
begin
  if p_work is null or p_items is null or jsonb_typeof(p_items) <> 'array' then
    return 0;
  end if;
  select count(*) into v_pending from public.glossary_entries
   where user_id = p_user and work_id = p_work and status = 'suggested';
  v_room := greatest(0, 100 - v_pending);
  if v_room = 0 then
    return 0;
  end if;

  insert into public.glossary_entries (user_id, work_id, source_term, target_term, term_type, status)
  select p_user, p_work, t.src, t.dst, t.typ, 'suggested'
  from (
    select distinct on (lower(left(btrim(i ->> 'source'), 120)))
           left(btrim(i ->> 'source'), 120) as src,
           left(btrim(i ->> 'target'), 120) as dst,
           case when (i ->> 'type') = any (enum_range(null::public.glossary_term_type)::text[])
                then (i ->> 'type')::public.glossary_term_type else 'term' end as typ
    from jsonb_array_elements(p_items) i
    where jsonb_typeof(i) = 'object'
      and char_length(btrim(coalesce(i ->> 'source', ''))) >= 1
      and char_length(btrim(coalesce(i ->> 'target', ''))) >= 1
  ) t
  where not exists (
    select 1 from public.glossary_entries g
     where g.user_id = p_user
       and (g.work_id is null or g.work_id = p_work)
       and lower(g.source_term) = lower(t.src)
  )
  limit v_room
  on conflict do nothing;
  get diagnostics v_added = row_count;
  return v_added;
end $$;

revoke all on function public.worker_add_glossary_suggestions(uuid, uuid, jsonb) from public, anon, authenticated;
grant execute on function public.worker_add_glossary_suggestions(uuid, uuid, jsonb) to service_role;
