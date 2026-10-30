/**
 * Фото перед вивантаженням: менше байтів, відомі розміри, мініатюра.
 *
 * Камера телефона дає 4000×3000 і 4–8 МБ. Стрічка ніде не малює більше
 * ~320 px по ширині, а лайтбокс — розміру екрана, тож 12 Мп їхали вузлом,
 * мешем і сховком задарма — і впирались у межу вкладення (4 МБ на телефоні).
 *
 *  - довша сторона > 2560 → зменшуємо до 2560 (JPEG чи WebP, якість 0.85);
 *  - GIF (анімація) і SVG (вектор) не чіпаємо ніколи; малі фото — теж;
 *  - EXIF-поворот застосовується при розкодуванні (`imageOrientation:
 *    'from-image'`), тож і пікселі, і записані розміри — такі, як людина
 *    бачить фото, а не як його тримав сенсор;
 *  - мініатюра ~320 px — для миттєвої бульбашки, поки вивантажується оригінал;
 *  - перекодоване вийшло БІЛЬШИМ за оригінал — їде оригінал.
 *
 * Розміри (`width`/`height`) їдуть у тілі листа — див.
 * docs/messenger/MEDIA_DIMENSIONS.md.
 */

export const MAX_EDGE = 2560;
export const THUMB_EDGE = 320;
/**
 * Мікромініатюра для «проявлення» (мікро №8): 20 px по довшій стороні, JPEG
 * 0.5 — близько півкілобайта. Їде в ТІЛІ листа (`thumb`), тож у одержувача
 * розмите фото стоїть одразу, ще до того, як приїхали й розшифрувались байти.
 */
export const MICRO_EDGE = 20;
export const MICRO_MAX_CHARS = 1800;
export const QUALITY = 0.85;
const THUMB_QUALITY = 0.7;

export interface Decoded {
  width: number;
  height: number;
  /** Що малювати на полотні (ImageBitmap у браузері). */
  source: unknown;
  close?: () => void;
}

export interface ImagePrepDeps {
  decode: (file: Blob) => Promise<Decoded>;
  encode: (source: unknown, width: number, height: number, type: string, quality: number) => Promise<Blob | null>;
}

export interface PreparedImage {
  /** Те, що піде на вузол: зменшене або оригінал. */
  file: File;
  /** Розміри саме того, що піде (після повороту й зменшення). */
  width?: number;
  height?: number;
  thumb?: Blob;
  thumbWidth?: number;
  thumbHeight?: number;
  /** data:image/jpeg;base64,… ≤ MICRO_MAX_CHARS — для тіла листа. */
  micro?: string;
}

/** Вписати w×h у квадрат `max`, не збільшуючи. */
const blobToDataUrl = async (blob: Blob): Promise<string> => {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let bin = '';
  for (let i = 0; i < bytes.length; i += 1) bin += String.fromCharCode(bytes[i]);
  return `data:${blob.type || 'image/jpeg'};base64,${btoa(bin)}`;
};

/** Чи рядок з тіла листа — придатна мікромініатюра (лише data:image, малий). */
export const isMicroThumb = (v: unknown): v is string =>
  typeof v === 'string' && /^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/.test(v) && v.length <= MICRO_MAX_CHARS;

export function fitWithin(width: number, height: number, max: number): { width: number; height: number } {
  const long = Math.max(width, height);
  if (long <= max || long <= 0) return { width, height };
  const k = max / long;
  return { width: Math.max(1, Math.round(width * k)), height: Math.max(1, Math.round(height * k)) };
}

const isGif = (f: File) => f.type === 'image/gif' || /\.gif$/i.test(f.name);
const isSvg = (f: File) => f.type === 'image/svg+xml' || /\.svg$/i.test(f.name);

/** PNG/WebP можуть мати прозорість — їм WebP; решті — JPEG. */
export function targetType(file: File): string {
  return file.type === 'image/png' || file.type === 'image/webp' ? 'image/webp' : 'image/jpeg';
}

function renamed(name: string, type: string): string {
  const ext = type === 'image/webp' ? 'webp' : type === 'image/png' ? 'png' : 'jpg';
  const base = name.replace(/\.[^.]+$/, '');
  return `${base || 'photo'}.${ext}`;
}

export const browserDeps: ImagePrepDeps = {
  decode: async (file) => {
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
    return { width: bmp.width, height: bmp.height, source: bmp, close: () => bmp.close() };
  },
  encode: async (source, width, height, type, quality) => {
    if (typeof OffscreenCanvas !== 'undefined') {
      const c = new OffscreenCanvas(width, height);
      const ctx = c.getContext('2d');
      if (!ctx) return null;
      ctx.drawImage(source as CanvasImageSource, 0, 0, width, height);
      return c.convertToBlob({ type, quality });
    }
    const c = document.createElement('canvas');
    c.width = width;
    c.height = height;
    const ctx = c.getContext('2d');
    if (!ctx) return null;
    ctx.drawImage(source as CanvasImageSource, 0, 0, width, height);
    return new Promise<Blob | null>((r) => c.toBlob(r, type, quality));
  },
};

/** Чи вміє це середовище розкодувати й намалювати (у jsdom — ні). */
export function canPrepareImages(): boolean {
  return typeof createImageBitmap === 'function';
}

/**
 * Готує фото до вивантаження. Будь-яка відмова браузера — не помилка
 * надсилання: тоді їде оригінал без розмірів, як до цього модуля.
 */
export async function prepareImage(
  file: File,
  deps: ImagePrepDeps | null = canPrepareImages() ? browserDeps : null,
  opts?: { original?: boolean },
): Promise<PreparedImage> {
  if (!deps || isSvg(file) || !file.type.startsWith('image/')) return { file };
  let decoded: Decoded;
  try {
    decoded = await deps.decode(file);
  } catch {
    return { file };
  }
  try {
    const out: PreparedImage = { file, width: decoded.width, height: decoded.height };
    const type = targetType(file);

    const t = fitWithin(decoded.width, decoded.height, THUMB_EDGE);
    try {
      const thumb = await deps.encode(decoded.source, t.width, t.height, type, THUMB_QUALITY);
      if (thumb && thumb.size > 0) Object.assign(out, { thumb, thumbWidth: t.width, thumbHeight: t.height });
    } catch { /* без мініатюри — бульбашка покаже сам файл */ }

    const m20 = fitWithin(decoded.width, decoded.height, MICRO_EDGE);
    try {
      const micro = await deps.encode(decoded.source, m20.width, m20.height, 'image/jpeg', 0.5);
      const url = micro && micro.size > 0 ? await blobToDataUrl(micro) : '';
      if (url.startsWith('data:image/') && url.length <= MICRO_MAX_CHARS) out.micro = url;
    } catch { /* без мікромініатюри — бульбашка просто чекає на фото */ }

    // «Оригінал»: розміри й мініатюри є, а байти — ті самі, що обрала людина.
    if (!opts?.original && !isGif(file) && Math.max(decoded.width, decoded.height) > MAX_EDGE) {
      const m = fitWithin(decoded.width, decoded.height, MAX_EDGE);
      try {
        const blob = await deps.encode(decoded.source, m.width, m.height, type, QUALITY);
        // Браузер без кодера WebP мовчки віддає PNG — і він буває більшим.
        if (blob && blob.size > 0 && blob.size < file.size) {
          const outType = blob.type || type;
          out.file = new File([blob], renamed(file.name, outType), { type: outType, lastModified: file.lastModified });
          out.width = m.width;
          out.height = m.height;
        }
      } catch { /* лишається оригінал */ }
    }
    return out;
  } finally {
    decoded.close?.();
  }
}
