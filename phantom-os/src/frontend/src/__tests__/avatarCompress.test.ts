/**
 * Драбина стиснення аватара — логіка, не полотно.
 *
 * Полотна в jsdom немає, тому справжній `compressAvatarFile` тут не бігає, і
 * це названо вголос, а не сховано: перевіряється ПРАВИЛО («спускайся, доки не
 * влізе; не вліз — скажи, наскільки»), бо саме воно вирішує, поїде фото чи
 * мовчки не поїде. Те, що полотно справді малює 96×96 і що браузер справді
 * вміє WebP, цими тестами НЕ доведено — це доводиться на апараті.
 */
import { describe, it, expect } from 'vitest';
import {
  AVATAR_BUDGET_BYTES,
  AVATAR_MAX_SIDE,
  compressToBudget,
  imageKindOf,
  sayRefusal,
  squareCrop,
  thumbB64OfDataUrl,
  dataUrlOf,
  type AvatarMime,
  type DrawAttempt,
} from '../services/avatarCompress';

const WEBP_HEAD = [0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50];
const JPEG_HEAD = [0xff, 0xd8, 0xff];

const bytesOf = (n: number, head: number[] = WEBP_HEAD) =>
  new Uint8Array([...head, ...new Array(Math.max(0, n - head.length)).fill(7)]);

describe('squareCrop', () => {
  it('ландшафт ріжеться по центру', () => {
    expect(squareCrop(200, 100)).toEqual({ sx: 50, sy: 0, side: 100 });
  });
  it('портрет ріжеться по центру', () => {
    expect(squareCrop(100, 300)).toEqual({ sx: 0, sy: 100, side: 100 });
  });
  it('квадрат лишається як є', () => {
    expect(squareCrop(96, 96)).toEqual({ sx: 0, sy: 0, side: 96 });
  });
});

describe('imageKindOf — рід за байтами, не зі слів виклику', () => {
  it('впізнає WebP і JPEG', () => {
    expect(imageKindOf(bytesOf(20, WEBP_HEAD))).toBe('image/webp');
    expect(imageKindOf(bytesOf(20, JPEG_HEAD))).toBe('image/jpeg');
  });

  /**
   * Саме цей випадок ловить тиху відмову: браузер без WebP повертає PNG під
   * викликом `toDataURL('image/webp')`. PNG на дроті приймач не візьме.
   */
  it('PNG — не наш рід', () => {
    const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
    expect(imageKindOf(png)).toBeNull();
  });

  it('обрізок, коротший за сигнатуру, — не рід', () => {
    expect(imageKindOf(new Uint8Array([0x52, 0x49]))).toBeNull();
  });
});

describe('compressToBudget', () => {
  it('бере перший варіант, що влазить, і не спускається далі', () => {
    const tried: Array<[number, AvatarMime, number]> = [];
    const draw: DrawAttempt = (side, mime, quality) => {
      tried.push([side, mime, quality]);
      return bytesOf(1000);
    };
    const res = compressToBudget(draw);
    expect(res.ok).toBe(true);
    if (!res.ok) return;
    expect(res.thumb.side).toBe(AVATAR_MAX_SIDE);
    expect(res.thumb.mime).toBe('image/webp');
    expect(res.thumb.bytes.length).toBe(1000);
    // Рівно одна спроба: влізло з першого разу.
    expect(tried).toHaveLength(1);
    expect(res.thumb.dataUrl.startsWith('data:image/webp;base64,')).toBe(true);
  });

  it('спускається якістю, доки не влізе', () => {
    // Розмір падає з якістю; влазить лише на третьому щаблі.
    const draw: DrawAttempt = (_side, _mime, quality) =>
      bytesOf(quality > 0.8 ? AVATAR_BUDGET_BYTES + 500 : quality > 0.7 ? AVATAR_BUDGET_BYTES + 1 : 4000);
    const res = compressToBudget(draw);
    expect(res.ok).toBe(true);
    if (!res.ok) return;
    expect(res.thumb.quality).toBeLessThanOrEqual(0.75);
    expect(res.thumb.bytes.length).toBeLessThanOrEqual(AVATAR_BUDGET_BYTES);
  });

  it('вичерпавши якість, зменшує сторону', () => {
    const draw: DrawAttempt = (side) =>
      bytesOf(side >= AVATAR_MAX_SIDE ? AVATAR_BUDGET_BYTES + 100 : 3000);
    const res = compressToBudget(draw);
    expect(res.ok).toBe(true);
    if (!res.ok) return;
    expect(res.thumb.side).toBeLessThan(AVATAR_MAX_SIDE);
  });

  /** Тиха відмова WebP не має зупиняти роботу: JPEG поруч на тому ж щаблі. */
  it('WebP не вийшов — бере JPEG', () => {
    const draw: DrawAttempt = (_side, mime) => (mime === 'image/webp' ? null : bytesOf(2000, JPEG_HEAD));
    const res = compressToBudget(draw);
    expect(res.ok).toBe(true);
    if (!res.ok) return;
    expect(res.thumb.mime).toBe('image/jpeg');
  });

  it('жодного роду не вийшло — названа відмова, не мовчання', () => {
    const res = compressToBudget(() => null);
    expect(res.ok).toBe(false);
    if (res.ok) return;
    expect(res.refusal.kind).toBe('no_supported_format');
    expect(sayRefusal(res.refusal)).toMatch(/WebP/);
  });

  /**
   * ГОЛОВНЕ: відмова несе ЧИСЛО. «Не вдалось» людині нічим не допомагає —
   * «6244 байт із 6144» каже, що бракує зовсім трохи, і що робити далі.
   */
  it('не влізло навіть на дні драбини — кажемо скільки вийшло і скільки можна', () => {
    const draw: DrawAttempt = (side) => bytesOf(AVATAR_BUDGET_BYTES + side);
    const res = compressToBudget(draw);
    expect(res.ok).toBe(false);
    if (res.ok) return;
    expect(res.refusal.kind).toBe('still_too_big');
    if (res.refusal.kind !== 'still_too_big') return;
    // Найменший із усіх невдалих, не перший-ліпший: це найменша сторона.
    expect(res.refusal.bestBytes).toBe(AVATAR_BUDGET_BYTES + 48);
    expect(res.refusal.limit).toBe(AVATAR_BUDGET_BYTES);
    expect(res.refusal.side).toBe(48);
    const said = sayRefusal(res.refusal);
    expect(said).toContain(String(AVATAR_BUDGET_BYTES + 48));
    expect(said).toContain(String(AVATAR_BUDGET_BYTES));
  });

  it('рівно на стелі — це «влізло», а не «завелике»', () => {
    const res = compressToBudget(() => bytesOf(AVATAR_BUDGET_BYTES));
    expect(res.ok).toBe(true);
  });
});

/**
 * Збережене фото — те, що вже лежить у профілі `data:`-адресою.
 *
 * Без цього шва людина, яка відкрила вікно й нічого не чіпала, слала б кадр
 * БЕЗ тега аватара: байтів у памʼяті вкладки немає, стиснення сталося в іншій
 * сесії. Той, до кого перший кадр не доїхав, не побачив би фото вже ніколи.
 */
describe('мініатюра зі збереженої адреси', () => {
  /** Справжня шапка JPEG — те саме, що впізнає `imageKind` на дроті. */
  const jpeg = (n: number): Uint8Array => {
    const b = new Uint8Array(n);
    b[0] = 0xff; b[1] = 0xd8; b[2] = 0xff;
    return b;
  };

  it('JPEG у межах стелі повертається як чистий base64, без шапки', () => {
    const url = dataUrlOf(jpeg(64), 'image/jpeg');
    const out = thumbB64OfDataUrl(url);
    expect(out).toBe(url.split(',')[1]);
    expect(out).not.toContain('data:');
  });

  it('пресет-SVG не їде: рід перевіряється за БАЙТАМИ, не за шапкою адреси', () => {
    // Шапка каже «svg», і це слово нікого не цікавить; тіло тут узагалі не
    // base64, тож декодування падає першим — обидва шляхи ведуть у null.
    expect(thumbB64OfDataUrl('data:image/svg+xml;utf8,<svg/>')).toBeNull();
  });

  it('чужа шапка над JPEG-байтами не рятує — і не шкодить: важать байти', () => {
    // Адреса каже «png», байти — JPEG. Приймач читає байти, отже й ми.
    const url = dataUrlOf(jpeg(32), 'image/png');
    expect(thumbB64OfDataUrl(url)).toBe(url.split(',')[1]);
  });

  it('PNG-байти не їдуть: дріт возить рівно WebP і JPEG', () => {
    const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    expect(thumbB64OfDataUrl(dataUrlOf(png, 'image/png'))).toBeNull();
  });

  it('понад стелю — null, а не мовчазне врізання', () => {
    expect(thumbB64OfDataUrl(dataUrlOf(jpeg(AVATAR_BUDGET_BYTES + 1), 'image/jpeg'))).toBeNull();
    expect(thumbB64OfDataUrl(dataUrlOf(jpeg(AVATAR_BUDGET_BYTES), 'image/jpeg'))).not.toBeNull();
  });

  it('не `data:`-адреса й порожнеча — null', () => {
    expect(thumbB64OfDataUrl('https://example.org/a.jpg')).toBeNull();
    expect(thumbB64OfDataUrl('')).toBeNull();
    expect(thumbB64OfDataUrl('data:image/jpeg;base64,')).toBeNull();
  });
});
