// Shared HTTP helpers for every Edge Function: JSON responses, CORS,
// typed errors and a single place that converts exceptions to responses.

export const corsHeaders: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-worker-secret",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

/** An error that is safe to show to the client (code is stable, message is Arabic-friendly). */
export class AppError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message?: string,
    public readonly details?: Record<string, unknown>,
  ) {
    super(message ?? code);
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}

export async function readJson<T>(req: Request, maxBytes = 64 * 1024): Promise<T> {
  const text = await req.text();
  if (text.length > maxBytes) throw new AppError(413, "payload_too_large");
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new AppError(400, "invalid_json");
  }
}

type Handler = (req: Request) => Promise<Response>;

/** Wraps a handler: CORS preflight, method check, error mapping, no stack traces leaked. */
export function handle(methods: string[], fn: Handler): Handler {
  return async (req) => {
    if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
    if (!methods.includes(req.method)) return json({ error: "method_not_allowed" }, 405);
    try {
      return await fn(req);
    } catch (e) {
      if (e instanceof AppError) {
        return json({ error: e.code, message: e.message, ...(e.details ?? {}) }, e.status);
      }
      console.error("unhandled", e);
      return json({ error: "internal_error" }, 500);
    }
  };
}

// ---- tiny validators (no external dependency) -------------------------------

export function str(v: unknown, name: string, opts: { min?: number; max?: number; optional?: boolean } = {}): string {
  if (v === undefined || v === null || v === "") {
    if (opts.optional) return "";
    throw new AppError(400, "invalid_input", `${name} is required`);
  }
  if (typeof v !== "string") throw new AppError(400, "invalid_input", `${name} must be a string`);
  const s = v.trim();
  if (opts.min !== undefined && s.length < opts.min) throw new AppError(400, "invalid_input", `${name} too short`);
  if (opts.max !== undefined && s.length > opts.max) throw new AppError(400, "invalid_input", `${name} too long`);
  return s;
}

export function int(v: unknown, name: string, min: number, max: number): number {
  if (typeof v !== "number" || !Number.isInteger(v) || v < min || v > max) {
    throw new AppError(400, "invalid_input", `${name} must be an integer in [${min}, ${max}]`);
  }
  return v;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function uuid(v: unknown, name: string): string {
  if (typeof v !== "string" || !UUID_RE.test(v)) throw new AppError(400, "invalid_input", `${name} must be a UUID`);
  return v.toLowerCase();
}

export function oneOf<T extends string>(v: unknown, name: string, allowed: readonly T[]): T {
  if (typeof v !== "string" || !allowed.includes(v as T)) {
    throw new AppError(400, "invalid_input", `${name} must be one of ${allowed.join(", ")}`);
  }
  return v as T;
}
