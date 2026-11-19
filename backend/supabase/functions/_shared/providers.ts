// Provider factory: the only place that knows which vendor is active.
// Switch vendors with the TRANSLATION_PROVIDER / OCR_PROVIDER secrets,
// no app update required.
import { ClaudeTranslationProvider } from "./translation/claude_provider.ts";
import { type TranslationProvider, TranslationError } from "./translation/types.ts";
import { GoogleVisionOcrProvider } from "./ocr/google_vision_provider.ts";
import { OcrError, type OcrProvider } from "./ocr/types.ts";

export function translationProvider(): TranslationProvider {
  const name = Deno.env.get("TRANSLATION_PROVIDER") ?? "claude";
  switch (name) {
    case "claude": {
      const key = Deno.env.get("ANTHROPIC_API_KEY");
      if (!key) throw new TranslationError("provider_not_configured", "ANTHROPIC_API_KEY is not set");
      return new ClaudeTranslationProvider(key, Deno.env.get("CLAUDE_MODEL") || "claude-opus-5-5");
    }
    default:
      throw new TranslationError("provider_not_configured", `Unknown TRANSLATION_PROVIDER ${name}`);
  }
}

export function ocrProvider(): OcrProvider {
  const name = Deno.env.get("OCR_PROVIDER") ?? "google_vision";
  switch (name) {
    case "google_vision": {
      const key = Deno.env.get("GOOGLE_VISION_API_KEY");
      if (!key) throw new OcrError("provider_not_configured", "GOOGLE_VISION_API_KEY is not set");
      return new GoogleVisionOcrProvider(key);
    }
    default:
      throw new OcrError("provider_not_configured", `Unknown OCR_PROVIDER ${name}`);
  }
}
