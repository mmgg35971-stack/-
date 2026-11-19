#!/usr/bin/env bash
# Type-check, lint and unit-test all Edge Functions.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../supabase/functions"
DENO="${DENO:-deno}"
"$DENO" check */index.ts _shared/*.ts _shared/*/*.ts ../../scripts/create-admin-secret.ts
"$DENO" lint
"$DENO" test --allow-env tests/
