#!/usr/bin/env bash
# Runs all migrations + database tests against a throwaway PostgreSQL.
#   - Locally: needs PostgreSQL 15+ binaries (initdb/pg_ctl) on PATH or PG_BIN.
#   - In CI:   set DATABASE_URL to a fresh database (see .github/workflows/backend.yml).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIG="$HERE/supabase/migrations"
TESTS="$HERE/supabase/tests"

if [[ -z "${DATABASE_URL:-}" ]]; then
  PG_BIN="${PG_BIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)}"
  export PATH="$PG_BIN:$PATH"
  TMP="$(mktemp -d)"
  trap 'pg_ctl -D "$TMP/data" -m immediate stop >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
  initdb -D "$TMP/data" -U postgres --auth=trust >/dev/null
  pg_ctl -D "$TMP/data" -o "-k $TMP -p 54329 -c listen_addresses=''" -l "$TMP/log" -w start >/dev/null
  export DATABASE_URL="postgresql://postgres@/postgres?host=$TMP&port=54329"
fi

PSQL=(psql "$DATABASE_URL" -X -q -v ON_ERROR_STOP=1)
"${PSQL[@]}" -f "$TESTS/00_supabase_stubs.sql"
for f in "$MIG"/*.sql; do
  echo "migrate: $(basename "$f")"
  "${PSQL[@]}" -f "$f"
done
"${PSQL[@]}" -f "$TESTS/10_business_rules.sql"
"${PSQL[@]}" -f "$TESTS/20_billing_admin.sql"

# --- Concurrency: 20 users try to redeem ONE single-use code at the same instant
"${PSQL[@]}" <<'SQL'
insert into auth.users (id, email)
select ('00000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid, 'race' || i || '@example.com'
from generate_series(1, 20) i;
select public.admin_create_gift_codes('11111111-1111-1111-1111-111111111111', 'premium', 30,
       array[digest('RACE-CODE', 'sha256')], array['PREM-…-RACE'], null, 1, 'race');
SQL
pids=()
for i in $(seq 1 20); do
  uid="00000000-0000-0000-0000-$(printf '%012d' "$i")"
  "${PSQL[@]}" -t -A -c "select public.redeem_gift_code('$uid', digest('RACE-CODE','sha256')) ->> 'ok'" > "${TMPDIR:-/tmp}/race_$i.out" &
  pids+=($!)
done
for p in "${pids[@]}"; do wait "$p"; done
ok=$(cat "${TMPDIR:-/tmp}"/race_*.out | grep -c '^true$' || true)
rm -f "${TMPDIR:-/tmp}"/race_*.out
redemptions=$("${PSQL[@]}" -t -A -c "select count(*) from public.gift_code_redemptions r join public.gift_codes c on c.id = r.gift_code_id where c.code_hint = 'PREM-…-RACE'")
if [[ "$ok" != "1" || "$redemptions" != "1" ]]; then
  echo "FAIL concurrent redemption: ok=$ok redemptions=$redemptions"; exit 1
fi
echo "PASS concurrent redemption (20 parallel attempts -> exactly 1 success)"

# --- Concurrency: 15 parallel chapter starts for a Free user (limit 10)
"${PSQL[@]}" <<'SQL'
insert into auth.users (id, email) values ('44444444-4444-4444-4444-444444444444', 'dave@example.com');
create table public._race_jobs as
with j as (
  insert into public.translation_jobs (user_id, chapter_title, page_count, idempotency_key)
  select '44444444-4444-4444-4444-444444444444', 'c' || i, 1, gen_random_uuid() from generate_series(1, 15) i
  returning id, user_id)
select * from j;
insert into public.translation_pages (job_id, user_id, page_index, original_path)
select id, user_id, 0, 'x' from public._race_jobs;
SQL
pids=()
for id in $("${PSQL[@]}" -t -A -c "select id from public._race_jobs"); do
  "${PSQL[@]}" -t -A -c "select public.start_translation_job('44444444-4444-4444-4444-444444444444', '$id') ->> 'ok'" >/dev/null &
  pids+=($!)
done
for p in "${pids[@]}"; do wait "$p"; done
used=$("${PSQL[@]}" -t -A -c "select public.usage_status('44444444-4444-4444-4444-444444444444') ->> 'used_today'")
counted=$("${PSQL[@]}" -t -A -c "select count(*) from public.translation_jobs where user_id = '44444444-4444-4444-4444-444444444444' and usage_counted")
if [[ "$used" != "10" || "$counted" != "10" ]]; then
  echo "FAIL concurrent daily limit: used=$used counted=$counted"; exit 1
fi
echo "PASS concurrent daily limit (15 parallel starts -> exactly 10 counted)"
echo "ALL BACKEND DATABASE CHECKS PASSED"
