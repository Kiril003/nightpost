/**
 * Місце під фото/відео в стрічці — ДО того, як байти розшифровано.
 *
 * Бульбашка, що спершу мала 260×180 спінера, а потім розкривалась у фото
 * справжніх пропорцій, штовхала всю стрічку під собою — і людина, що читала
 * нижче, губила рядок. Тепер коробка має остаточні пропорції з самого
 * початку: з width/height листа, а без них — 4:3 (docs/messenger/MEDIA_DIMENSIONS.md).
 */
export const MEDIA_MAX_W = 320;
export const MEDIA_MAX_H = 260;
export const FALLBACK_RATIO = { width: 4, height: 3 } as const;

export interface MediaBox {
  /** Ширина коробки, px (ще й max-width: 100% у розмітці). */
  width: number;
  /** Для CSS `aspect-ratio`. */
  aspectRatio: string;
  /** Розміри відомі від відправника, а не запасні 4:3. */
  known: boolean;
}

export function mediaBox(w?: number, h?: number, maxW = MEDIA_MAX_W, maxH = MEDIA_MAX_H): MediaBox {
  const known = !!w && !!h && w > 0 && h > 0;
  const rw = known ? (w as number) : FALLBACK_RATIO.width;
  const rh = known ? (h as number) : FALLBACK_RATIO.height;
  // Вписати в maxW×maxH, не збільшуючи малих (іконка 64×64 лишається 64).
  const scale = Math.min(maxW / rw, maxH / rh, known ? 1 : Infinity);
  const width = Math.max(48, Math.round(rw * (Number.isFinite(scale) ? scale : 1)));
  return { width: Math.min(width, maxW), aspectRatio: `${rw} / ${rh}`, known };
}
