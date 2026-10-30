/** Обрізати й повернути фото перед надсиланням: вільна рамка, поворот на чверть. */

/** Рамка в частках повернутого знімка: 0..1 від лівого верхнього кута. */
export interface CropFrame {
  x: number;
  y: number;
  w: number;
  h: number;
}

export const FULL_FRAME: CropFrame = { x: 0, y: 0, w: 1, h: 1 };
/** Найменша сторона рамки — частка знімка. */
export const MIN_SIDE = 0.1;

export type Quarter = 0 | 1 | 2 | 3;

export const nextQuarter = (q: Quarter): Quarter => (((q + 1) % 4) as Quarter);

/** Розміри знімка після повороту на `q` чвертей за годинниковою. */
export function turnedSize(w: number, h: number, q: Quarter): { w: number; h: number } {
  return q % 2 === 1 ? { w: h, h: w } : { w, h };
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** Рамка завжди всередині знімка й не менша за MIN_SIDE. */
export function clampFrame(f: CropFrame): CropFrame {
  const w = clamp(f.w, MIN_SIDE, 1);
  const h = clamp(f.h, MIN_SIDE, 1);
  return { x: clamp(f.x, 0, 1 - w), y: clamp(f.y, 0, 1 - h), w, h };
}

export type Handle = 'nw' | 'ne' | 'sw' | 'se' | 'move';

/** Тягнемо кут (або всю рамку) на dx/dy у частках знімка. Протилежний кут стоїть. */
export function dragFrame(f: CropFrame, handle: Handle, dx: number, dy: number): CropFrame {
  if (handle === 'move') {
    return { ...f, x: clamp(f.x + dx, 0, 1 - f.w), y: clamp(f.y + dy, 0, 1 - f.h) };
  }
  let left = f.x;
  let top = f.y;
  let right = f.x + f.w;
  let bottom = f.y + f.h;
  if (handle === 'nw' || handle === 'sw') left = clamp(left + dx, 0, right - MIN_SIDE);
  else right = clamp(right + dx, left + MIN_SIDE, 1);
  if (handle === 'nw' || handle === 'ne') top = clamp(top + dy, 0, bottom - MIN_SIDE);
  else bottom = clamp(bottom + dy, top + MIN_SIDE, 1);
  return { x: left, y: top, w: right - left, h: bottom - top };
}

/** Чи щось змінено: повна рамка без повороту — те саме фото. */
export const isUntouched = (f: CropFrame, q: Quarter) =>
  q === 0 && f.x <= 0 && f.y <= 0 && f.w >= 1 && f.h >= 1;

/**
 * План полотна: розмір результату в пікселях і перетворення, яким оригінал
 * лягає на полотно (поворот довкола кута, потім зсув до рамки).
 */
export function cropPlan(w: number, h: number, q: Quarter, f: CropFrame) {
  const t = turnedSize(w, h, q);
  const frame = clampFrame(f);
  const sx = Math.round(frame.x * t.w);
  const sy = Math.round(frame.y * t.h);
  const outW = Math.max(1, Math.min(t.w - sx, Math.round(frame.w * t.w)));
  const outH = Math.max(1, Math.min(t.h - sy, Math.round(frame.h * t.h)));
  const turn =
    q === 1 ? { tx: h, ty: 0, angle: Math.PI / 2 }
    : q === 2 ? { tx: w, ty: h, angle: Math.PI }
    : q === 3 ? { tx: 0, ty: w, angle: -Math.PI / 2 }
    : { tx: 0, ty: 0, angle: 0 };
  return { outW, outH, sx, sy, ...turn };
}

export interface CropDeps {
  decode: (file: Blob) => Promise<{ width: number; height: number; source: CanvasImageSource; close?: () => void }>;
  encode: (draw: (ctx: CanvasRenderingContext2D) => void, width: number, height: number, type: string) => Promise<Blob | null>;
}

const browserCropDeps: CropDeps = {
  decode: async (file) => {
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
    return { width: bmp.width, height: bmp.height, source: bmp, close: () => bmp.close() };
  },
  encode: (draw, width, height, type) => {
    const c = document.createElement('canvas');
    c.width = width;
    c.height = height;
    const ctx = c.getContext('2d');
    if (!ctx) return Promise.resolve(null);
    draw(ctx);
    return new Promise<Blob | null>((r) => c.toBlob(r, type, 0.92));
  },
};

/** PNG і WebP лишаються собою (прозорість), решта — JPEG. */
export function cropType(file: File): string {
  return file.type === 'image/png' || file.type === 'image/webp' ? file.type : 'image/jpeg';
}

/**
 * Новий файл з обрізаного й поверненого знімка. Null — браузер не зміг
 * розкодувати чи закодувати: тоді фото лишається, як було, і людина це бачить.
 */
export async function cropImage(file: File, frame: CropFrame, q: Quarter, deps: CropDeps = browserCropDeps): Promise<File | null> {
  let decoded: Awaited<ReturnType<CropDeps['decode']>>;
  try {
    decoded = await deps.decode(file);
  } catch {
    return null;
  }
  try {
    const plan = cropPlan(decoded.width, decoded.height, q, frame);
    const type = cropType(file);
    const blob = await deps.encode(
      (ctx) => {
        ctx.translate(-plan.sx, -plan.sy);
        ctx.translate(plan.tx, plan.ty);
        ctx.rotate(plan.angle);
        ctx.drawImage(decoded.source, 0, 0);
      },
      plan.outW,
      plan.outH,
      type,
    );
    if (!blob || blob.size <= 0) return null;
    const ext = type === 'image/png' ? 'png' : type === 'image/webp' ? 'webp' : 'jpg';
    const base = file.name.replace(/\.[^.]+$/, '') || 'photo';
    return new File([blob], `${base}.${ext}`, { type, lastModified: Date.now() });
  } catch {
    return null;
  } finally {
    decoded.close?.();
  }
}
