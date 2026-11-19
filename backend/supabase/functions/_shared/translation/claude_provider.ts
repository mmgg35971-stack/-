import Anthropic from "@anthropic-ai/sdk";
import { buildUserMessage, OUTPUT_SCHEMA, SYSTEM_PROMPT } from "./prompt.ts";
import {
  type SegmentCategory,
  type TranslationProvider,
  type TranslationRequest,
  TranslationError,
  type TranslationResult,
} from "./types.ts";

// Claude Opus 5.5 list price: $4 / $20 per million tokens => 4 / 20 micro-USD per token.
const PRICE_MICROS = { input: 4, output: 20, cacheRead: 0.2 };

const EFFORT: Record<TranslationRequest["quality"], "medium" | "high" | "xhigh"> = {
  standard: "medium",
  high: "high",
  best: "xhigh",
};

interface ModelOutput {
  segments: Array<{ id: string; arabic: string; category: SegmentCategory }>;
  suggested_glossary: Array<{ source: string; target: string; type: string }>;
  updated_summary: string;
  characters: Array<{ name: string; arabic: string; notes: string }>;
}

export class ClaudeTranslationProvider implements TranslationProvider {
  readonly name = "claude";
  private readonly client: Anthropic;

  constructor(apiKey: string, private readonly model = "claude-opus-5-5") {
    this.client = new Anthropic({ apiKey, maxRetries: 2, timeout: 10 * 60 * 1000 });
  }

  async translateChapter(req: TranslationRequest): Promise<TranslationResult> {
    if (req.segments.length === 0) {
      return {
        segments: [],
        suggestedGlossary: [],
        updatedContext: { summary: req.context.summary, characters: req.context.characters },
        usage: { inputUnits: 0, outputUnits: 0, costUsdMicros: 0 },
        provider: this.name,
      };
    }

    let message;
    try {
      const stream = this.client.beta.messages.stream({
        model: this.model,
        max_tokens: 64000,
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
        output_config: {
          effort: EFFORT[req.quality],
          format: { type: "json_schema", schema: OUTPUT_SCHEMA },
        },
        system: [{ type: "text", text: SYSTEM_PROMPT, cache_control: { type: "ephemeral" } }],
        messages: [{ role: "user", content: buildUserMessage(req) }],
      });
      message = await stream.finalMessage();
    } catch (e) {
      if (e instanceof Anthropic.RateLimitError) throw new TranslationError("rate_limited", e.message, true);
      if (e instanceof Anthropic.AuthenticationError) throw new TranslationError("provider_not_configured", e.message);
      if (e instanceof Anthropic.InternalServerError || e instanceof Anthropic.APIConnectionError) {
        throw new TranslationError("provider_unavailable", (e as Error).message, true);
      }
      if (e instanceof Anthropic.APIError) throw new TranslationError("provider_unavailable", e.message, (e.status ?? 0) >= 500);
      throw e;
    }

    if (message.stop_reason === "refusal") throw new TranslationError("refused", "The model declined this chapter");
    if (message.stop_reason === "max_tokens") throw new TranslationError("incomplete_response", "Output truncated", true);

    const text = message.content.flatMap((b: any) => (b.type === "text" ? [b.text] : [])).join("");
    let out: ModelOutput;
    try {
      out = JSON.parse(text) as ModelOutput;
    } catch {
      throw new TranslationError("invalid_response", "Model returned invalid JSON", true);
    }

    const byId = new Map(out.segments.map((s) => [s.id, s]));
    const missing = req.segments.filter((s) => !byId.has(s.id));
    if (missing.length > 0) {
      // Rule: never silently drop a line.
      throw new TranslationError("incomplete_response", `${missing.length} segments missing`, true);
    }

    const u = message.usage as any;
    const inputUnits = (u.input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0);
    const cost = (u.input_tokens ?? 0) * PRICE_MICROS.input +
      (u.cache_creation_input_tokens ?? 0) * PRICE_MICROS.input * 1.25 +
      (u.cache_read_input_tokens ?? 0) * PRICE_MICROS.cacheRead +
      (u.output_tokens ?? 0) * PRICE_MICROS.output;

    return {
      segments: req.segments.map((s) => {
        const r = byId.get(s.id)!;
        return { id: s.id, arabic: r.arabic, category: r.category };
      }),
      suggestedGlossary: out.suggested_glossary.slice(0, 50),
      updatedContext: {
        summary: out.updated_summary.slice(0, 4000),
        characters: out.characters.slice(0, 60),
      },
      usage: { inputUnits, outputUnits: u.output_tokens ?? 0, costUsdMicros: Math.round(cost) },
      provider: `${this.name}:${message.model}`,
    };
  }
}
