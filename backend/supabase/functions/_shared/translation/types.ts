// Translation Engine contract. The app and the worker only know this
// interface; swapping AI vendors means adding one new class here.

export type SegmentCategory = "dialogue" | "narration" | "sound_effect" | "title" | "caption" | "unknown";
export type TranslationQuality = "standard" | "high" | "best";

export interface SourceSegment {
  /** Stable id (DB uuid) so results can be matched back without relying on order. */
  id: string;
  page: number;
  readingOrder: number;
  text: string;
  /** Hint from OCR; the translator may refine it. */
  category: SegmentCategory;
  /** Normalized [0..1] box, lets the model reason about bubble size. */
  box: { x: number; y: number; w: number; h: number };
}

export interface GlossaryTerm {
  source: string;
  target: string;
  type: string;
}

export interface StoryContext {
  workTitle: string | null;
  summary: string;
  characters: Array<{ name: string; arabic?: string; notes?: string }>;
  dialogueStyle: string;
}

export interface TranslationRequest {
  chapterTitle: string;
  segments: SourceSegment[];
  glossary: GlossaryTerm[];
  context: StoryContext;
  quality: TranslationQuality;
}

export interface TranslatedSegment {
  id: string;
  arabic: string;
  category: SegmentCategory;
}

export interface TranslationResult {
  segments: TranslatedSegment[];
  /** New terms the model saw that should be consistent from now on (user confirms before saving). */
  suggestedGlossary: GlossaryTerm[];
  /** Updated, bounded story memory for the next chapter. */
  updatedContext: Pick<StoryContext, "summary" | "characters">;
  usage: { inputUnits: number; outputUnits: number; costUsdMicros: number };
  provider: string;
}

export class TranslationError extends Error {
  constructor(
    public readonly code:
      | "provider_not_configured"
      | "provider_unavailable"
      | "rate_limited"
      | "refused"
      | "invalid_response"
      | "incomplete_response",
    message?: string,
    public readonly retryable = false,
  ) {
    super(message ?? code);
  }
}

export interface TranslationProvider {
  readonly name: string;
  translateChapter(req: TranslationRequest): Promise<TranslationResult>;
}
