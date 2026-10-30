/**
 * Кадр `PeerProfile` — рівно той, що літає дротом.
 *
 * **ЩО ТУТ БУЛО ЗЛАМАНО.** Цей файл писався 15.09 наперед, «до дня, коли
 * транспорт зʼявиться». Транспорт зʼявився (`core-net/.../peer/PeerProfile.kt`,
 * коміт 6fe51f37) — і виявилось, що дві половини одного контракту розійшлись
 * у ТРЬОХ місцях, кожна зелена сама проти себе:
 *
 *  1. **Номери тегів.** Тут нумерувалось з нуля (rev=0, avatar=1, emoji=2,
 *     text=3, bio=4), у Kotlin — з одиниці (rev=1 … bio=5). Кожне поле
 *     приїжджало б у сусіда: аватар читався б як rev, біо — як статус.
 *  2. **Формат `rev`.** Тут — варінт, у Kotlin — тверді чотири байти
 *     big-endian, і `PeerProfile.decode` відкидає кадр цілком, якщо довжина
 *     не рівно 4. Тобто НІ ОДИН тутешній кадр не був би прочитаний.
 *  3. **Конверт.** Тут поверталися голі байти, а на дроті кадр — це РЯДОК
 *     `PH-PRF:` + base64 без доповнення: саме за цим префіксом
 *     `PeerFrames.kindOf` упізнає рід, а `PeerOutbox.isSpoken` не пускає його
 *     в стрічку.
 *
 * На екрані жодна з трьох не виглядала б як поломка — тільки як «профіль
 * співрозмовника чомусь не оновлюється». Тепер розбіжність ловить золотий
 * вектор (`__tests__/profileWireCodec.test.ts`, `GOLDEN`) — той самий рядок
 * стоїть у `PeerProfileTest.kt`, і порахований він третьою рукою за описом
 * формату, а не знятий з виходу однієї зі сторін.
 *
 * Три правила звідси, з контракту, не вигадані тут:
 *
 *  1. Стеля — тверда. `encodeProfileFrame` кидає, якщо поле більше за межу;
 *     воно не обрізає мовчки. Обрізає той, хто формує дані ДО виклику (форма
 *     профілю), і показує обрізане САМ СОБІ — симетрія знання.
 *  2. Три стани поля: відсутнє (тега нема — «ще не приїхало»), присутнє з
 *     нульовою довжиною («явно очищено»), присутнє зі значенням. Плутати
 *     перші два — або малювати чуже старе фото як нове, або гасити те, чого
 *     людина не стирала.
 *  3. TLV: невідомий тег пропускається, не валить кадр. Обірваний кадр —
 *     ціла відмова (`decodeProfileFrame` → `null`), не часткове застосування.
 */

/** Спільний початок кадру. Той самий рядок, що `PeerProfile.TAG`. */
export const PROFILE_WIRE_TAG = 'PH-PRF:';

/** Стеля контракту. Байти, не символи. */
export const PROFILE_WIRE_LIMITS = {
  /** 6 КіБ — мініатюра, не оригінал. */
  avatarThumbBytes: 6144,
  /** Фото до 512 px — тег 8, `PeerProfile.MAX_PHOTO_BYTES`. */
  photoBytes: 24576,
  /** Один grapheme cluster з запасом на ZWJ/прапори. */
  statusEmojiBytes: 16,
  statusTextBytes: 140,
  bioBytes: 512,
  nameBytes: 256,
  handleBytes: 64,
} as const;

// Числа — частина дроту. Перейменувати поле можна, змінити число не можна
// ніколи. Мусять збігатися з `PeerProfile.kt` (T_REV…T_BIO).
const TAG_REV = 1;
const TAG_AVATAR = 2;
const TAG_STATUS_EMOJI = 3;
const TAG_STATUS_TEXT = 4;
const TAG_BIO = 5;
const TAG_NAME = 6;
const TAG_HANDLE = 7;
const TAG_PHOTO = 8;
/** Банер: 11 — розкладка й фокус, 12 — хеш і розмір кожного фото (`PeerProfile.kt`). */
const TAG_BANNER = 11;
const TAG_BANNER_MEDIA = 12;
const BANNER_V1 = 1;
const BANNER_FLAG_VIDEO_STILL = 1;
const BANNER_MEDIA_ITEM = 19;
export const BANNER_MAX_ITEMS = 6;
/** Стеля одного фото банера — `PeerBanner.MAX_BYTES`. */
export const BANNER_PHOTO_BYTES = 24576;
/** Кадр одного фото банера — `PeerBanner.TAG`. */
export const BANNER_WIRE_TAG = 'PH-PBN:';

export interface BannerItem {
  /** 32 hex: перші 16 байт SHA-256 байтів фото. */
  hash: string;
  size: number;
  /** Фокус 0..255 по ширині й висоті. */
  fx: number;
  fy: number;
}

export interface ProfileBanner {
  items: BannerItem[];
  /** Перше фото — перший кадр відео-банера; петля живе лише на сторінці власника. */
  videoStill: boolean;
}

const HEX32 = /^[0-9a-f]{32}$/;
const validBanner = (b: ProfileBanner): boolean =>
  b.items.length >= 1 &&
  b.items.length <= BANNER_MAX_ITEMS &&
  b.items.every(
    (i) => HEX32.test(i.hash) && i.size >= 1 && i.size <= BANNER_PHOTO_BYTES && i.fx >= 0 && i.fx <= 255 && i.fy >= 0 && i.fy <= 255,
  );

/** `rev` їде твердими чотирма байтами. Інша довжина — не кадр. */
const REV_BYTES = 4;

const enc = new TextEncoder();
const dec = new TextDecoder('utf-8', { fatal: false });

/** Три стани поля — контракт, не здогад компонента. */
export type WireField<T> =
  | { state: 'absent' }
  | { state: 'empty' }
  | { state: 'value'; value: T };

export interface DecodedProfileFrame {
  rev: number;
  avatarThumb: WireField<Uint8Array>;
  statusEmoji: WireField<string>;
  statusText: WireField<string>;
  bio: WireField<string>;
  /** Тег 6: порожнє імʼя в дроті — «не повідомляв» (absent), не очищення. */
  name: WireField<string>;
  /** Тег 7: лише заява, нормалізує й звіряє приймач. */
  handle: WireField<string>;
  /** Тег 8: фото до 24 КіБ. */
  photo: WireField<Uint8Array>;
  /** Теги 11+12. Розбіжність між ними — absent, не чуже фото. */
  banner: WireField<ProfileBanner>;
}

/** Що покласти в кадр. `undefined` — тега не буде («ще не приїхало» для приймача). `null` — явне очищення. */
export interface EncodeProfileInput {
  rev: number;
  avatarThumb?: Uint8Array | null;
  statusEmoji?: string | null;
  statusText?: string | null;
  bio?: string | null;
  name?: string;
  handle?: string | null;
  photo?: Uint8Array | null;
  banner?: ProfileBanner | null;
}

function writeVarint(n: number): number[] {
  if (!Number.isInteger(n) || n < 0) {
    throw new RangeError(`varint: expected a non-negative integer, got ${n}`);
  }
  const out: number[] = [];
  let v = n;
  do {
    let byte = v & 0x7f;
    v = Math.floor(v / 128);
    if (v > 0) byte |= 0x80;
    out.push(byte);
  } while (v > 0);
  return out;
}

/** `null` — вихід за межі буфера: кадр обірваний. */
function readVarint(bytes: Uint8Array, offset: number): { value: number; next: number } | null {
  let result = 0;
  let shift = 0;
  let pos = offset;
  for (;;) {
    if (pos >= bytes.length) return null;
    const byte = bytes[pos];
    result += (byte & 0x7f) * 2 ** shift;
    pos += 1;
    if ((byte & 0x80) === 0) return { value: result, next: pos };
    shift += 7;
    // 28 — та сама межа, що в `PeerProfile.decode`: довжина, яка не
    // вміщається в розумне, — це не довжина.
    if (shift > 28) return null;
  }
}

function pushTLV(out: number[], tag: number, payload: Uint8Array): void {
  out.push(tag, ...writeVarint(payload.length), ...payload);
}

/** `rev` як чотири байти big-endian — рівно `PeerProfile.revBytes`. */
function revBytes(rev: number): Uint8Array {
  if (!Number.isInteger(rev) || rev < 0) {
    throw new RangeError(`rev: expected a non-negative integer, got ${rev}`);
  }
  const v = Math.min(rev, 0xffffffff);
  return new Uint8Array([(v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff]);
}

/**
 * base64 без доповнення — `Base64.getEncoder().withoutPadding()` у Kotlin.
 *
 * Не через розгортання масиву в аргументи одним махом: `String.fromCharCode(
 * ...bytes)` на шести кілобайтах ще виживе, але ця сама помилка вже клала
 * стек на вкладеннях (`messengerApi.b64Of`), і повторювати її немає сенсу.
 */
function b64(bytes: Uint8Array): string {
  const step = 0x8000;
  let s = '';
  for (let i = 0; i < bytes.length; i += step) {
    s += String.fromCharCode(...bytes.subarray(i, i + step));
  }
  return btoa(s).replace(/=+$/, '');
}

/** `null` — не base64. Доповнення відновлюємо самі: на дроті його немає. */
function unb64(text: string): Uint8Array | null {
  const padded = text + '='.repeat((4 - (text.length % 4)) % 4);
  let raw: string;
  try {
    raw = atob(padded);
  } catch {
    return null;
  }
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i);
  return out;
}

/**
 * Кадр як РЯДОК дроту: `PH-PRF:` + base64 без доповнення.
 *
 * Кидає `RangeError`, якщо якесь поле більше за стелю контракту — виклик
 * відповідає за те, щоб дані вже влазили (форма профілю рахує байти живцем і
 * не пускає далі стелі, а фото стискає `avatarCompress`).
 */
export function encodeProfileFrame(input: EncodeProfileInput): string {
  const out: number[] = [];
  pushTLV(out, TAG_REV, revBytes(input.rev));

  const pushOptional = (
    tag: number,
    raw: Uint8Array | string | null | undefined,
    limit: number,
    label: string,
  ) => {
    if (raw === undefined) return; // тега немає взагалі — «ще не приїхало»
    const payload = raw === null ? new Uint8Array(0) : typeof raw === 'string' ? enc.encode(raw) : raw;
    if (payload.length > limit) {
      throw new RangeError(`${label}: ${payload.length} bytes over the ${limit} cap`);
    }
    pushTLV(out, tag, payload);
  };

  pushOptional(TAG_AVATAR, input.avatarThumb, PROFILE_WIRE_LIMITS.avatarThumbBytes, 'avatar_thumb');
  pushOptional(TAG_STATUS_EMOJI, input.statusEmoji, PROFILE_WIRE_LIMITS.statusEmojiBytes, 'status_emoji');
  pushOptional(TAG_STATUS_TEXT, input.statusText, PROFILE_WIRE_LIMITS.statusTextBytes, 'status_text');
  pushOptional(TAG_BIO, input.bio, PROFILE_WIRE_LIMITS.bioBytes, 'bio');
  if (input.name) pushOptional(TAG_NAME, input.name, PROFILE_WIRE_LIMITS.nameBytes, 'name');
  pushOptional(TAG_HANDLE, input.handle, PROFILE_WIRE_LIMITS.handleBytes, 'handle');
  pushOptional(TAG_PHOTO, input.photo, PROFILE_WIRE_LIMITS.photoBytes, 'photo');
  if (input.banner === null) pushTLV(out, TAG_BANNER, new Uint8Array(0));
  else if (input.banner) {
    const b = input.banner;
    if (!validBanner(b)) throw new RangeError('banner: outside the contract (1–6 photos, ≤24 KiB, hex hash, focus 0..255)');
    const lay = [BANNER_V1, b.items.length, b.videoStill ? BANNER_FLAG_VIDEO_STILL : 0];
    const media: number[] = [];
    for (const i of b.items) {
      lay.push(i.fx, i.fy);
      for (let k = 0; k < 32; k += 2) media.push(parseInt(i.hash.slice(k, k + 2), 16));
      media.push((i.size >>> 16) & 0xff, (i.size >>> 8) & 0xff, i.size & 0xff);
    }
    pushTLV(out, TAG_BANNER, new Uint8Array(lay));
    pushTLV(out, TAG_BANNER_MEDIA, new Uint8Array(media));
  }

  return PROFILE_WIRE_TAG + b64(new Uint8Array(out));
}

/** Чи це наш рід. Той самий предикат, що `PeerProfile.isProfile`. */
export function isProfileFrame(frame: string): boolean {
  return frame.startsWith(PROFILE_WIRE_TAG);
}

/**
 * `null` — кадр не наш, покалічений, обірваний або без обовʼязкового `rev`:
 * ЦІЛА відмова, без часткового застосування. Невідомі теги мовчки
 * пропускаються — новіший відправник не валить старіший парсер.
 */
export function decodeProfileFrame(frame: string): DecodedProfileFrame | null {
  if (!isProfileFrame(frame)) return null;
  const bytes = unb64(frame.slice(PROFILE_WIRE_TAG.length));
  if (!bytes) return null;

  let pos = 0;
  let rev: number | null = null;
  const fields = new Map<number, Uint8Array>();

  while (pos < bytes.length) {
    const tag = bytes[pos];
    pos += 1;
    const len = readVarint(bytes, pos);
    if (!len) return null; // довжина обіцяє більше, ніж лишилось — обрив
    pos = len.next;
    const end = pos + len.value;
    if (end > bytes.length) return null; // тіло теж не влазить — обрив
    const payload = bytes.slice(pos, end);
    pos = end;

    if (tag === TAG_REV) {
      // Рівно чотири байти. Інша довжина — не `rev`, і кадр цілком чужий.
      if (payload.length !== REV_BYTES) return null;
      rev = payload[0] * 2 ** 24 + payload[1] * 2 ** 16 + payload[2] * 2 ** 8 + payload[3];
    } else {
      // Невідомий чи відомий-але-вже-прочитаний тег: останній за порядком
      // виграє, як і личить полю, що могли переслати двічі.
      fields.set(tag, payload);
    }
  }

  if (rev === null) return null; // rev обовʼязковий контрактом

  const capped = <T,>(
    tag: number,
    limit: number,
    decode: (p: Uint8Array) => T,
  ): WireField<T> | null => {
    const payload = fields.get(tag);
    if (payload === undefined) return { state: 'absent' };
    // Та сама перевірка, що в `PeerProfile.decode`: поле понад стелю — це не
    // «велике поле», це чужий або зіпсований кадр, і він відкидається цілком.
    if (payload.length > limit) return null;
    if (payload.length === 0) return { state: 'empty' };
    return { state: 'value', value: decode(payload) };
  };

  const avatarThumb = capped(TAG_AVATAR, PROFILE_WIRE_LIMITS.avatarThumbBytes, (p) => p);
  const statusEmoji = capped(TAG_STATUS_EMOJI, PROFILE_WIRE_LIMITS.statusEmojiBytes, (p) => dec.decode(p));
  const statusText = capped(TAG_STATUS_TEXT, PROFILE_WIRE_LIMITS.statusTextBytes, (p) => dec.decode(p));
  const bio = capped(TAG_BIO, PROFILE_WIRE_LIMITS.bioBytes, (p) => dec.decode(p));
  const rawName = capped(TAG_NAME, PROFILE_WIRE_LIMITS.nameBytes, (p) => dec.decode(p));
  const handle = capped(TAG_HANDLE, PROFILE_WIRE_LIMITS.handleBytes, (p) => dec.decode(p));
  const photo = capped(TAG_PHOTO, PROFILE_WIRE_LIMITS.photoBytes, (p) => p);
  const lay = fields.get(TAG_BANNER);
  const media = fields.get(TAG_BANNER_MEDIA);
  const tooLong = (lay && lay.length > 3 + 2 * BANNER_MAX_ITEMS) || (media && media.length > BANNER_MAX_ITEMS * BANNER_MEDIA_ITEM);
  const banner: WireField<ProfileBanner> = tooLong ? { state: 'absent' } : bannerOf(lay, media);
  if (!avatarThumb || !statusEmoji || !statusText || !bio || !rawName || !handle || !photo) return null;
  const name: WireField<string> = rawName.state === 'empty' ? { state: 'absent' } : rawName;

  return { rev, avatarThumb, statusEmoji, statusText, bio, name, handle, photo, banner };
}

const hex = (b: Uint8Array): string => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');

function bannerOf(lay: Uint8Array | undefined, media: Uint8Array | undefined): WireField<ProfileBanner> {
  if (lay === undefined) return { state: 'absent' };
  if (lay.length === 0) return { state: 'empty' };
  if (!media || lay.length < 3 || lay[0] !== BANNER_V1) return { state: 'absent' };
  const n = lay[1];
  if (n < 1 || n > BANNER_MAX_ITEMS || lay.length !== 3 + 2 * n || media.length !== n * BANNER_MEDIA_ITEM) {
    return { state: 'absent' };
  }
  const items: BannerItem[] = [];
  for (let k = 0; k < n; k += 1) {
    const o = k * BANNER_MEDIA_ITEM;
    items.push({
      hash: hex(media.subarray(o, o + 16)),
      size: media[o + 16] * 65536 + media[o + 17] * 256 + media[o + 18],
      fx: lay[3 + 2 * k],
      fy: lay[4 + 2 * k],
    });
  }
  const b = { items, videoStill: (lay[2] & BANNER_FLAG_VIDEO_STILL) !== 0 };
  return validBanner(b) ? { state: 'value', value: b } : { state: 'absent' };
}

/**
 * Кадр одного фото банера `PH-PBN:` — `[1][16 байт хеша][байти]`. Хеш тут лише
 * читається: звіряє його приймач (`PeerBanner.decode` на телефоні), веб байти
 * отримує вже звіреними через міст.
 */
export function decodeBannerPhotoFrame(frame: string): { hash: string; bytes: Uint8Array } | null {
  if (!frame.startsWith(BANNER_WIRE_TAG)) return null;
  const raw = unb64(frame.slice(BANNER_WIRE_TAG.length));
  if (!raw || raw.length < 18 || raw[0] !== BANNER_V1) return null;
  const bytes = raw.slice(17);
  if (bytes.length > BANNER_PHOTO_BYTES) return null;
  return { hash: hex(raw.subarray(1, 17)), bytes };
}

/**
 * Обрізає UTF-8 рядок до `maxBytes`, не розриваючи багатобайтовий символ
 * посередині (інакше хвіст стає replacement-символом `�`).
 *
 * Використовує форма профілю — щоб людина бачила в СЕБЕ те саме, що зрештою
 * поїде дротом, а не дізнавалась про обрізання від співрозмовника.
 */
export function truncateToByteLimit(text: string, maxBytes: number): string {
  const bytes = enc.encode(text);
  if (bytes.length <= maxBytes) return text;
  let end = maxBytes;
  // UTF-8: байт-продовження має вигляд 10xxxxxx (0x80..0xBF) — відступаємо,
  // доки не стоїмо на початку символу.
  while (end > 0 && (bytes[end] & 0xc0) === 0x80) end -= 1;
  return dec.decode(bytes.slice(0, end));
}

/** Скільки байт УЖЕ займає рядок — для лічильника під полем форми. */
export function byteLength(text: string): number {
  return enc.encode(text).length;
}

/**
 * Чи цей кадр новіший за відоме про цього відправника. Дзеркало
 * `PeerProfile.isNewer` — повтор сіткового переказу виглядає на екрані точно
 * так само, як свіже оновлення, тож правило мусить бути перевірним.
 */
export function isNewerRev(incomingRev: number, knownRev: number | null | undefined): boolean {
  if (knownRev === null || knownRev === undefined) return true;
  return incomingRev > knownRev;
}
