/**
 * Аватар ріже ВІДПРАВНИК — і каже, якщо не дорізав.
 *
 * **Вада, заради якої це існує.** `UserProfileModal.handleFileUpload` клав у
 * профіль `reader.readAsDataURL(file)` як є: фото з камери телефона — це
 * два-чотири мегабайти, тобто в ~500 разів більше за стелю кадру
 * ([PROFILE_WIRE_LIMITS.avatarThumbBytes] — 6144 байти). Контракт каже прямо:
 * «Відправник зобовʼязаний сам стиснути/обрізати перед відправкою — кадр не
 * несе оригінал і не покладається на приймача». Без цього людина ставила б
 * фото, бачила його в себе — і кадр мовчки не складався б у нікого.
 *
 * **Чому WebP із відкотом на JPEG.** WebP при тому самому розмірі помітно
 * чистіший на 96×96, але `canvas.toBlob('image/webp')` підтримується не
 * всюди — і, головне, відмовляє ТИХО: браузер повертає PNG під тим самим
 * викликом. PNG на дроті приймач не візьме ([PeerProfile.imageKind] знає рівно
 * WebP і JPEG), тож рід тут перевіряється за БАЙТАМИ результату, а не за тим,
 * що ми просили.
 *
 * **Чому відмова названа.** «Не вдалось» людині нічим не допомагає. Якщо
 * після всієї драбини якості й після зменшення сторони байти все одно не
 * влазять — кажемо скільки вийшло, скільки можна і що з цим робити.
 */

import { tNow } from '../components/chat/tNow';
import { PROFILE_WIRE_LIMITS } from './profileWireCodec';

/** Стеля контракту. Одна на весь продукт — звідси, не числом у компоненті. */
export const AVATAR_BUDGET_BYTES = PROFILE_WIRE_LIMITS.avatarThumbBytes;

/** Квадрат, не більш як 96×96 px ДО кодування — контракт, розділ «Поля». */
export const AVATAR_MAX_SIDE = 96;

/**
 * Драбина якості. Вниз, доки не влізе.
 *
 * Нижче 0.4 на 96×96 картинка стає кашею, і краще зменшити сторону, ніж
 * показати людину плямою: сторона, зменшена вдвічі, дає вчетверо менше
 * пікселів, тобто виграє більше, ніж останні кроки якості.
 */
const QUALITY_LADDER = [0.92, 0.85, 0.75, 0.65, 0.55, 0.45, 0.4] as const;

/** Сторони, якими відступаємо, коли якість уже вичерпана. */
const SIDE_LADDER = [AVATAR_MAX_SIDE, 80, 64, 48] as const;

/** Фото для списку й постера — окремий тег кадру, стеля `PeerProfile.MAX_PHOTO_BYTES`. */
export const PHOTO_BUDGET_BYTES = PROFILE_WIRE_LIMITS.photoBytes;
export const PHOTO_SIDE_LADDER = [512, 448, 384, 320, 256] as const;

export type AvatarMime = 'image/webp' | 'image/jpeg';

export interface AvatarThumb {
  bytes: Uint8Array;
  mime: AvatarMime;
  /** Сторона квадрата в пікселях — та, на якій зрештою вийшло. */
  side: number;
  quality: number;
  /** Готове до `<img src>`; те саме, що покаже співрозмовник. */
  dataUrl: string;
}

export type AvatarRefusal =
  /** Файл не прочитався як зображення взагалі. */
  | { kind: 'not_an_image'; detail: string }
  /** Полотна немає — старий WebView або вимкнений canvas. */
  | { kind: 'no_canvas'; detail: string }
  /** Браузер не вміє ні WebP, ні JPEG на полотні: віддає лише PNG. */
  | { kind: 'no_supported_format'; got: string }
  /** Драбину пройдено до кінця, і все одно завелике. */
  | {
      kind: 'still_too_big';
      bestBytes: number;
      limit: number;
      mime: AvatarMime;
      side: number;
      quality: number;
    };

export type AvatarCompressResult =
  | { ok: true; thumb: AvatarThumb }
  | { ok: false; refusal: AvatarRefusal };

/**
 * Речення для людини. Живе поруч із причиною, а не в компоненті: два місця,
 * що описують одну відмову, розходяться на першій же правці.
 */
export function sayRefusal(r: AvatarRefusal): string {
  switch (r.kind) {
    case 'not_an_image':
      return tNow('avatarRefusal.notImage', { detail: r.detail });
    case 'no_canvas':
      return tNow('avatarRefusal.noCanvas');
    case 'no_supported_format':
      return tNow('avatarRefusal.noFormat', { got: r.got });
    case 'still_too_big':
      return tNow('avatarRefusal.tooBig', { best: r.bestBytes, side: r.side, quality: r.quality, limit: r.limit });
  }
}

/** Квадрат по центру: бік — менша сторона, зміщення — половина зайвого. */
export function squareCrop(width: number, height: number): { sx: number; sy: number; side: number } {
  const side = Math.min(width, height);
  return { sx: Math.round((width - side) / 2), sy: Math.round((height - side) / 2), side };
}

/**
 * Малює один варіант і повертає байти, або null — цей рід/якість не вийшли.
 *
 * Порт, а не прямий виклик полотна: у jsdom полотна немає взагалі, і без
 * цього шва драбину не можна було б перевірити нічим, окрім браузера. Правило
 * «спускайся, доки не влізе» — це логіка, і вона мусить бути перевірною.
 */
export type DrawAttempt = (side: number, mime: AvatarMime, quality: number) => Uint8Array | null;

/**
 * Найменше, що влазить у бюджет. Спершу якість, потім сторона.
 *
 * Найкращий (найменший) з усіх невдалих варіантів запамʼятовується — саме він
 * їде у відмову: людині треба знати, наскільки не влізло, а не просто що не
 * влізло.
 */
export function compressToBudget(
  draw: DrawAttempt,
  budget: number = AVATAR_BUDGET_BYTES,
  sides: readonly number[] = SIDE_LADDER,
): AvatarCompressResult {
  let best: { bytes: Uint8Array; mime: AvatarMime; side: number; quality: number } | null = null;
  let sawAnyOutput = false;

  for (const side of sides) {
    for (const quality of QUALITY_LADDER) {
      for (const mime of ['image/webp', 'image/jpeg'] as const) {
        const bytes = draw(side, mime, quality);
        if (!bytes || bytes.length === 0) continue;
        sawAnyOutput = true;
        if (bytes.length <= budget) {
          return {
            ok: true,
            thumb: { bytes, mime, side, quality, dataUrl: dataUrlOf(bytes, mime) },
          };
        }
        if (!best || bytes.length < best.bytes.length) {
          best = { bytes, mime, side, quality };
        }
      }
    }
  }

  if (!sawAnyOutput || !best) {
    return { ok: false, refusal: { kind: 'no_supported_format', got: 'image/png' } };
  }
  return {
    ok: false,
    refusal: {
      kind: 'still_too_big',
      bestBytes: best.bytes.length,
      limit: budget,
      mime: best.mime,
      side: best.side,
      quality: best.quality,
    },
  };
}

/** Байти → `data:`-адреса. Той самий рід, що впізнає приймач за байтами. */
export function dataUrlOf(bytes: Uint8Array, mime: string): string {
  const step = 0x8000;
  let s = '';
  for (let i = 0; i < bytes.length; i += step) {
    s += String.fromCharCode(...bytes.subarray(i, i + step));
  }
  return `data:${mime};base64,${btoa(s)}`;
}

/** base64 без `data:`-шапки — те, що чекає міст у полі `avatar`. */
export function base64Of(bytes: Uint8Array): string {
  return dataUrlOf(bytes, 'application/octet-stream').split(',')[1] ?? '';
}

/**
 * Готова мініатюра з уже збереженої `data:`-адреси — або `null`.
 *
 * **Навіщо.** Фото, обране минулого разу, лежить у профілі як `data:`-адреса,
 * а байтів поруч уже немає: стиснення сталося в іншій сесії, у памʼяті вкладки
 * від нього нічого не лишилось. Без цього людина, яка відкрила вікно й
 * натиснула «Надіслати контактам», не чіпаючи фото, розіслала б кадр БЕЗ тега
 * аватара — тобто «нічого нового про фото», — і той, до кого перший кадр не
 * доїхав, не побачив би його вже ніколи.
 *
 * Перевіряються ті самі три речі, що й на дроті, і саме в такому порядку:
 * адреса читається як байти, байти — це WebP або JPEG, байти влазять у
 * бюджет. Пресет-SVG падає на другій перевірці, чужа велика картинка — на
 * третій; обидва повертають `null`, і це не помилка, а «цього кадром не
 * повезеш». Викликач мусить сказати це вголос, а не мовчки не покласти тег.
 */
export function thumbB64OfDataUrl(url: string, budget: number = AVATAR_BUDGET_BYTES): string | null {
  if (!url.startsWith('data:')) return null;
  const bytes = bytesOfDataUrl(url);
  if (!bytes || bytes.length === 0) return null;
  if (imageKindOf(bytes) === null) return null;
  if (bytes.length > budget) return null;
  return base64Of(bytes);
}

/**
 * Рід за байтами — те саме правило, що в `PeerProfile.imageKind`.
 *
 * Питається САМЕ ТУТ, бо `canvas.toDataURL('image/webp')` у браузері, що
 * WebP не вміє, мовчки повертає PNG під тим самим викликом. Повірити слову
 * виклику означало б покласти на дріт байти, які приймач не візьме, — і
 * побачити це вже на чужому екрані.
 */
export function imageKindOf(bytes: Uint8Array): AvatarMime | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return 'image/jpeg';
  }
  if (
    bytes.length >= 12 &&
    bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46 && // RIFF
    bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50 // WEBP
  ) {
    return 'image/webp';
  }
  return null;
}

// ── справжнє полотно ────────────────────────────────────────────────────

function bytesOfDataUrl(url: string): Uint8Array | null {
  const comma = url.indexOf(',');
  if (comma < 0) return null;
  try {
    const raw = atob(url.slice(comma + 1));
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('decode failed'));
    img.src = src;
  });
}

/** Обране людиною зображення, вже розкодоване — щоб показати його в обрізці. */
/**
 * Адресу знімка НЕ відкликаємо тут: обрізка показує `img.src`, і відкликана
 * blob-адреса дає порожнє коло. Відкликає той, хто закінчив, — [closeImage].
 */
export async function openImage(file: Blob): Promise<{ ok: true; img: HTMLImageElement } | { ok: false; refusal: AvatarRefusal }> {
  const objectUrl = URL.createObjectURL(file);
  try {
    const img = await loadImage(objectUrl);
    if (!(img.naturalWidth || img.width) || !(img.naturalHeight || img.height)) {
      URL.revokeObjectURL(objectUrl);
      return { ok: false, refusal: { kind: 'not_an_image', detail: tNow('avatar.zeroSize') } };
    }
    return { ok: true, img };
  } catch (e) {
    URL.revokeObjectURL(objectUrl);
    return { ok: false, refusal: { kind: 'not_an_image', detail: e instanceof Error ? e.message : String(e) } };
  }
}

export function closeImage(img: HTMLImageElement): void {
  if (img.src.startsWith('blob:')) URL.revokeObjectURL(img.src);
}

export type SquareCrop = { sx: number; sy: number; side: number };

function canvasDraw(img: HTMLImageElement, crop: SquareCrop): DrawAttempt | null {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  return (side, mime, quality) => {
    canvas.width = side;
    canvas.height = side;
    ctx.clearRect(0, 0, side, side);
    ctx.drawImage(img, crop.sx, crop.sy, crop.side, crop.side, 0, 0, side, side);
    const bytes = bytesOfDataUrl(canvas.toDataURL(mime, quality));
    if (!bytes) return null;
    // Браузер без WebP віддає PNG під тим самим `toDataURL('image/webp')`.
    return imageKindOf(bytes) === mime ? bytes : null;
  };
}

export type ProfilePhotoResult =
  | { ok: true; thumb: AvatarThumb; photo: AvatarThumb }
  | { ok: false; refusal: AvatarRefusal };

/**
 * Обраний квадрат — двома розмірами: мініатюра для старих збірок і фото до
 * 512 px для списку й постера. Обидва їдуть одним кадром.
 */
export function profilePhotoFrom(draw: DrawAttempt): ProfilePhotoResult {
  const thumb = compressToBudget(draw);
  if (!thumb.ok) return thumb;
  const photo = compressToBudget(draw, PHOTO_BUDGET_BYTES, PHOTO_SIDE_LADDER);
  if (!photo.ok) return photo;
  return { ok: true, thumb: thumb.thumb, photo: photo.thumb };
}

export function compressProfilePhoto(img: HTMLImageElement, crop: SquareCrop): ProfilePhotoResult {
  const draw = canvasDraw(img, crop);
  if (!draw) return { ok: false, refusal: { kind: 'no_canvas', detail: 'getContext("2d") === null' } };
  return profilePhotoFrom(draw);
}

/**
 * Стиснути обране людиною фото до мініатюри кадру.
 *
 * Читає файл, ріже по центру в квадрат, спускається драбиною і повертає або
 * готову мініатюру, або НАЗВАНУ відмову.
 */
export async function compressAvatarFile(file: Blob): Promise<AvatarCompressResult> {
  const opened = await openImage(file);
  if (!opened.ok) return opened;
  const img = opened.img;
  const draw = canvasDraw(img, squareCrop(img.naturalWidth || img.width, img.naturalHeight || img.height));
  const out: AvatarCompressResult = draw
    ? compressToBudget(draw)
    : { ok: false, refusal: { kind: 'no_canvas', detail: 'getContext("2d") === null' } };
  closeImage(img);
  return out;
}
