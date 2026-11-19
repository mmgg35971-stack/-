// Operator tool: creates a one-time Admin Activation Code.
//
//   ADMIN_SECRET_PEPPER=<same value as the Supabase secret> \
//     deno run --allow-env backend/scripts/create-admin-secret.ts "label" [max_uses] [days_valid]
//
// Prints the code ONCE (give it to the future admin over a private channel)
// and the SQL to run in the Supabase SQL editor. Only the HMAC goes to the DB.
// Revoke:  update public.admin_activation_secrets set revoked_at = now() where label = '...';
import { generateAdminSecret, hmacSha256 } from "../supabase/functions/_shared/codes.ts";

const pepper = Deno.env.get("ADMIN_SECRET_PEPPER");
if (!pepper) {
  console.error("Set ADMIN_SECRET_PEPPER first (the same value configured in Supabase secrets).");
  Deno.exit(1);
}
const label = (Deno.args[0] ?? "bootstrap").replace(/'/g, "");
const maxUses = Math.min(Math.max(Number(Deno.args[1] ?? 1), 1), 10);
const days = Math.min(Math.max(Number(Deno.args[2] ?? 7), 1), 365);

const secret = generateAdminSecret();
const hash = await hmacSha256(pepper, secret);
const hex = Array.from(hash, (b) => b.toString(16).padStart(2, "0")).join("");

console.log("\nAdmin Activation Code (shown once):\n\n  " + secret + "\n");
console.log("SQL:\n");
console.log(
  `insert into public.admin_activation_secrets (secret_hash, label, max_uses, expires_at)\n` +
    `values ('\\x${hex}', '${label}', ${maxUses}, now() + interval '${days} days');\n`,
);
