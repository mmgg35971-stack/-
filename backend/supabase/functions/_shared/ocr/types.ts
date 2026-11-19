// OCR Engine contract. Each provider returns text regions with normalized
// boxes; post-processing (grouping, reading order, review flags) is shared.

export interface OcrRegion {
  text: string;
  /** Normalized [0..1] box relative to the page. */
  box: { x: number; y: number; w: number; h: number };
  confidence: number; // 0..1
  isVertical: boolean;
  rotationDeg: number;
}

export interface OcrPageResult {
  width: number;
  height: number;
  regions: OcrRegion[];
  usage: { units: number; costUsdMicros: number };
  provider: string;
}

export class OcrError extends Error {
  constructor(
    public readonly code: "provider_not_configured" | "provider_unavailable" | "image_unreadable" | "rate_limited",
    message?: string,
    public readonly retryable = false,
  ) {
    super(message ?? code);
  }
}

export interface OcrProvider {
  readonly name: string;
  recognize(image: Uint8Array, mimeType: string): Promise<OcrPageResult>;
}

/** Below this confidence a region is kept but flagged for the user's review list. */
export const REVIEW_CONFIDENCE = 0.6;

/**
 * Reading order for comics. `rtl` for manga (right-to-left panels),
 * `ltr` for western comics; webtoons/manhwa are top-to-bottom first.
 * Rows are bucketed so bubbles on roughly the same line are ordered horizontally.
 */
export function sortReadingOrder(regions: OcrRegion[], direction: "ltr" | "rtl" | "vertical"): OcrRegion[] {
  const rowTolerance = 0.03;
  return [...regions].sort((a, b) => {
    const ay = a.box.y + a.box.h / 2;
    const by = b.box.y + b.box.h / 2;
    if (Math.abs(ay - by) > rowTolerance) return ay - by;
    const ax = a.box.x + a.box.w / 2;
    const bx = b.box.x + b.box.w / 2;
    return direction === "rtl" ? bx - ax : ax - bx;
  });
}
