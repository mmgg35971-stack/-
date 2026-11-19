import { encodeBase64 } from "@std/encoding/base64";
import { OcrError, type OcrPageResult, type OcrProvider, type OcrRegion } from "./types.ts";

// Google Cloud Vision DOCUMENT_TEXT_DETECTION: returns blocks (≈ one bubble /
// caption) with pixel bounding boxes and per-symbol confidence.
// Pricing: $1.50 per 1000 images (first 1000/month free) => 1500 micro-USD per page.
const COST_MICROS_PER_PAGE = 1500;

export class GoogleVisionOcrProvider implements OcrProvider {
  readonly name = "google_vision";

  constructor(private readonly apiKey: string) {}

  async recognize(image: Uint8Array, _mimeType: string): Promise<OcrPageResult> {
    let res: Response;
    try {
      res = await fetch(`https://vision.googleapis.com/v1/images:annotate?key=${encodeURIComponent(this.apiKey)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          requests: [{
            image: { content: encodeBase64(image) },
            features: [{ type: "DOCUMENT_TEXT_DETECTION" }],
            imageContext: { languageHints: ["en"] },
          }],
        }),
        signal: AbortSignal.timeout(60_000),
      });
    } catch (e) {
      throw new OcrError("provider_unavailable", String(e), true);
    }

    if (res.status === 429) throw new OcrError("rate_limited", "Vision quota", true);
    if (res.status === 401 || res.status === 403) throw new OcrError("provider_not_configured", await res.text());
    if (!res.ok) throw new OcrError("provider_unavailable", `Vision HTTP ${res.status}`, res.status >= 500);

    const body = await res.json();
    const r = body.responses?.[0];
    if (r?.error) throw new OcrError("image_unreadable", r.error.message ?? "vision error");
    return parseVisionResponse(r ?? {}, this.name, COST_MICROS_PER_PAGE);
  }
}

/** Exported for unit tests (no network). */
export function parseVisionResponse(r: any, provider: string, cost: number): OcrPageResult {
  const page = r.fullTextAnnotation?.pages?.[0];
  if (!page) return { width: 0, height: 0, regions: [], usage: { units: 1, costUsdMicros: cost }, provider };

  const W = page.width as number;
  const H = page.height as number;
  const regions: OcrRegion[] = [];

  for (const block of page.blocks ?? []) {
    if (block.blockType && block.blockType !== "TEXT") continue;
    let text = "";
    let confSum = 0;
    let confN = 0;
    for (const para of block.paragraphs ?? []) {
      for (const word of para.words ?? []) {
        for (const sym of word.symbols ?? []) {
          text += sym.text ?? "";
          if (typeof sym.confidence === "number") {
            confSum += sym.confidence;
            confN++;
          }
          const br = sym.property?.detectedBreak?.type;
          if (br === "SPACE" || br === "SURE_SPACE" || br === "EOL_SURE_SPACE" || br === "LINE_BREAK") text += " ";
          else if (br === "HYPHEN") text += "";
        }
      }
      text += " ";
    }
    text = text.replace(/\s+/g, " ").trim();
    if (!text) continue;

    const vs: Array<{ x?: number; y?: number }> = block.boundingBox?.vertices ?? [];
    if (vs.length < 4) continue;
    const xs = vs.map((v) => v.x ?? 0);
    const ys = vs.map((v) => v.y ?? 0);
    const minX = Math.max(0, Math.min(...xs));
    const minY = Math.max(0, Math.min(...ys));
    const maxX = Math.min(W, Math.max(...xs));
    const maxY = Math.min(H, Math.max(...ys));
    if (maxX <= minX || maxY <= minY) continue;

    // rotation from the top edge of the (possibly rotated) quad
    const dx = (vs[1].x ?? 0) - (vs[0].x ?? 0);
    const dy = (vs[1].y ?? 0) - (vs[0].y ?? 0);
    const rotationDeg = Math.round((Math.atan2(dy, dx) * 180) / Math.PI);

    const w = (maxX - minX) / W;
    const h = (maxY - minY) / H;
    regions.push({
      text,
      box: { x: minX / W, y: minY / H, w, h },
      confidence: confN ? confSum / confN : (block.confidence ?? 0),
      isVertical: (maxY - minY) > 2.5 * (maxX - minX) && !text.includes(" "),
      rotationDeg,
    });
  }

  return { width: W, height: H, regions, usage: { units: 1, costUsdMicros: cost }, provider };
}
