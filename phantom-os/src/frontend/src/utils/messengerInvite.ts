/**
 * Запрошення — усе, що потрібно, щоб впустити людину, в одному рядку.
 *
 * Раніше, щоб почати розмову, людина мусила окремо роздобути IP чужого вузла
 * і окремо ключ на 355 символів. Тут ті самі байти лежать в одному конверті:
 * ключ, адреса вузла і напис, яким запрошувач себе називає.
 *
 * Конверт двійковий, а не JSON з base64 всередині: вкладати base64 в base64
 * коштувало б третину довжини на порожньому місці.
 *
 *   "PHI" · версія · u16 довжина ключа · ключ · u8+адреса · u8+імʼя
 *
 * Далі все разом — base64url. 266 байтів ключа — це підлога, нижче якої
 * рядок не стиснути: там самі лише ключі й підписи, вони випадкові.
 */

import { nodeOrigin } from '../services/nodeOrigin';
import { request } from '../services/api';
import type { NodeContact } from '../services/messengerApi';
import { DEFAULT_LOCALE, translate, type Locale } from '../i18n';

const SCHEME = 'phantom://invite/';
const MAGIC = [0x50, 0x48, 0x49]; // "PHI"
const VERSION = 1;

/** Стислий ключ вузла без конверта — 266 байтів, ~355 символів base64url. */
const COMPACT_MIN_BYTES = 230;

export interface InviteParts {
  /** Ключ у тому вигляді, який приймає вузол (`compact` з /messenger/identity). */
  compact: string;
  /** Адреса вузла запрошувача. Порожньо — лист чекатиме на ретранслятор. */
  address: string;
  /** Як запрошувач себе назвав. Це напис, а не доказ. */
  name: string;
}

export type ParsedInvite =
  | ({ kind: 'invite' } & InviteParts)
  | { kind: 'compact'; compact: string }
  | { kind: 'bundle'; bundle: Record<string, unknown> };

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function b64urlEncode(raw: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < raw.length; i += 1) bin += String.fromCharCode(raw[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function b64urlDecode(text: string): Uint8Array {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
  return out;
}

/** Ріже рядок так, щоб він уліз у 255 байтів і не розвалив кирилицю навпіл. */
function clampBytes(text: string, limit: number): Uint8Array {
  let cut = text;
  let raw = encoder.encode(cut);
  while (raw.length > limit && cut.length > 0) {
    cut = cut.slice(0, -1);
    raw = encoder.encode(cut);
  }
  return raw;
}

export function encodeInvite(parts: InviteParts): string {
  const bundle = b64urlDecode(parts.compact.trim());
  const addr = clampBytes(parts.address.trim(), 255);
  const who = clampBytes(parts.name.trim(), 255);

  const out = new Uint8Array(6 + bundle.length + 1 + addr.length + 1 + who.length);
  out.set(MAGIC, 0);
  out[3] = VERSION;
  out[4] = (bundle.length >> 8) & 0xff;
  out[5] = bundle.length & 0xff;
  let p = 6;
  out.set(bundle, p);
  p += bundle.length;
  out[p] = addr.length;
  p += 1;
  out.set(addr, p);
  p += addr.length;
  out[p] = who.length;
  p += 1;
  out.set(who, p);

  return SCHEME + b64urlEncode(out);
}

/** Версія запрошення новіша за ту, яку вміє цей вузол. */
export class InviteTooNew extends Error {}

function decodeEnvelope(raw: Uint8Array): InviteParts | null {
  if (raw.length < 8) return null;
  if (raw[0] !== MAGIC[0] || raw[1] !== MAGIC[1] || raw[2] !== MAGIC[2]) return null;
  if (raw[3] !== VERSION) throw new InviteTooNew(String(raw[3]));

  const bundleLen = (raw[4] << 8) | raw[5];
  let p = 6;
  if (bundleLen < COMPACT_MIN_BYTES || p + bundleLen + 2 > raw.length) return null;
  const bundle = raw.slice(p, p + bundleLen);
  p += bundleLen;

  const addrLen = raw[p];
  p += 1;
  if (p + addrLen + 1 > raw.length) return null;
  const address = decoder.decode(raw.slice(p, p + addrLen));
  p += addrLen;

  const nameLen = raw[p];
  p += 1;
  if (p + nameLen > raw.length) return null;
  const name = decoder.decode(raw.slice(p, p + nameLen));

  return { compact: b64urlEncode(bundle), address, name };
}

/**
 * Приймає все, що людина могла вставити: посилання, голий base64, ключ без
 * конверта, розгорнутий JSON — і навіть коли навколо лишились слова з чату.
 */
export function parseInvite(text: string): ParsedInvite | null {
  const raw = (text || '').trim();
  if (!raw) return null;
  // Код телефона: PH2:<ключі>:<контрольна сума>. Не base64 і не конверт PHI —
  // його впізнає сам телефон, тож віддаємо як є, навіть із чужими словами навколо.
  const phone = findInText(raw);
  if (phone) return { kind: 'compact', compact: phone };

  if (raw.startsWith('{')) {
    try {
      return { kind: 'bundle', bundle: JSON.parse(raw) as Record<string, unknown> };
    } catch {
      return null;
    }
  }

  // Люди вставляють запрошення разом із «привіт, ось воно» і переносами рядка.
  // Беремо всі суцільні шматки base64url і пробуємо довші першими.
  const tokens = (raw.match(/[A-Za-z0-9\-_]{40,}/g) ?? []).sort((a, b) => b.length - a.length);
  let tooNew: InviteTooNew | null = null;

  for (const token of tokens) {
    let bytes: Uint8Array;
    try {
      bytes = b64urlDecode(token);
    } catch {
      continue;
    }
    try {
      const parts = decodeEnvelope(bytes);
      if (parts) return { kind: 'invite', ...parts };
    } catch (err) {
      if (err instanceof InviteTooNew) tooNew = err;
      continue;
    }
    // Ключ без конверта: перший байт — версія стислого ключа з crypto/keys.py.
    if (bytes.length >= COMPACT_MIN_BYTES && bytes[0] === 1) {
      return { kind: 'compact', compact: token };
    }
  }

  if (tooNew) throw tooNew;
  return null;
}

/** Код телефона десь у тексті: PH<версія>:<тіло base64url>:<6 hex>. */
const PHONE_CODE = /PH[0-9]:[A-Za-z0-9_-]+:[0-9a-fA-F]{6}/;

/**
 * Код телефона з усього, що людина могла вставити — веб-двійник
 * `PeerInvite.findInText`.
 *
 * Приймає голий код, `phantom://invite/<код>`, https-посилання сайту
 * (`https://phantom-os.dev/i/#<код>`) і цілий текст «Поділитися» з
 * привітанням. Месенджери, що «чистять» посилання, кодують двокрапки в
 * `%3A` — тому спершу розкодовуємо саме їх (і лише їх: решта символів коду —
 * base64url і hex, кодувати там нічого). Обрізаний код не знаходиться —
 * краще чесне «не схоже на запрошення», ніж контакт із битим ключем.
 */
export function findInText(text: string | null | undefined): string | null {
  const raw = (text ?? '').replace(/%3A/gi, ':');
  const hit = raw.match(PHONE_CODE);
  return hit ? hit[0] : null;
}

/**
 * Сирий код телефона з відповіді `/messenger/identity`: з `compact`, а якщо
 * там не PH-код — з `link` (`phantom://invite/<код>`). Саме він іде в QR (D6).
 * null — коду телефона немає (вузол ПК зі своїм конвертом PHI).
 */
export function phoneCodeOf(me: { compact?: string | null; link?: string | null } | null | undefined): string | null {
  if (!me) return null;
  return findInText(me.compact) ?? findInText(me.link);
}

/** Сторінка сайту, що відкриває застосунок; код — після `#`, тож на сервер він не їде. */
export const INVITE_WEB_BASE = 'https://phantom-os.dev/i/#';

/** https-посилання на запрошення: месенджери його підсвічують, на відміну від `phantom://`. */
export function inviteWebLink(code: string): string {
  return INVITE_WEB_BASE + code.trim();
}

/**
 * Текст «Поділитися» (D6, 08.10): https-посилання, а під ним — сам код
 * окремим рядком, на випадок, коли посилання не відкривається. Код у QR —
 * без посилання (його читає наш же сканер, і що коротше, то крупніші модулі).
 */
export function inviteShareText(code: string, locale: Locale = DEFAULT_LOCALE): string {
  const clean = code.trim();
  return [
    translate('msgr.add.shareLead', locale, { link: inviteWebLink(clean) }),
    '',
    translate('msgr.add.shareFallback', locale),
    clean,
  ].join('\n');
}

/** Звідки прийшов ключ людини. Міст пише CAMERA лише для `camera` (B0). */
export type InviteSource = 'camera';

/**
 * Додати людину за запрошенням. Те саме `POST /messenger/contacts`, що й
 * `messengerApi.addContact`, плюс `source: 'camera'`, коли ключ знято
 * камерою з екрана людини: міст записує тоді вищий щабель довіри до ключа
 * (KeySource.CAMERA). Старий міст і вузол ПК поле ігнорують — нічого не
 * ламається, щабель лишається звичайним.
 */
export function addInvitedContact(
  display_name: string,
  key: { bundle?: Record<string, unknown>; compact?: string },
  peer_address?: string | null,
  source?: InviteSource,
): Promise<NodeContact> {
  return request<NodeContact>('POST', '/messenger/contacts', {
    display_name,
    bundle: key.bundle ?? null,
    compact: key.compact ?? null,
    peer_address: peer_address || null,
    ...(source ? { source } : {}),
  });
}

/** `reach` із рядка моста. У типах `NodeContact`/`NodeIdentity` його нема, доки B0 не злитий. */
const reachOf = (row: object | null | undefined): unknown => (row as { reach?: unknown } | null | undefined)?.reach;

/**
 * Людина ще не отримала наш контакт: ми її додали, а вона нас — ні.
 *
 * Читаємо ЛИШЕ `reach` моста B0 (`'mutual' | 'waiting'`). `session_ready`
 * тут нічого не каже: нинішній міст телефона ставить його «ключ є», тобто
 * true одразу після будь-якого скану, а вузол ПК лишає false для коду
 * телефона назавжди (`intake` не зводить сесії). Довіритись йому — означало б
 * або ніколи не показати «чекаємо», або чекати вічно. Поля немає — людина
 * вважається на звʼязку, як було до цього аркуша.
 */
export function isWaiting(c: object): boolean {
  return reachOf(c) === 'waiting';
}

export function isMutual(c: object): boolean {
  return reachOf(c) === 'mutual';
}

/**
 * Чи міст каже `reach` — тобто вміє представити нас людині після скану лише
 * з одного боку (REACH + B0). Знак — хоч один рядок контактів із `reach`
 * (так у брифі B0, P0-9 п.1; рядок, який міст віддає на POST
 * /messenger/contacts, аркуш кладе в список одразу) або поле `reach` у
 * `/messenger/identity`. Друге — ПРОХАННЯ до B0, у брифі його нема: лише воно
 * показує обіцянку на першому запуску, ще до першої людини. Поки знаку нема —
 * обіцянки «одного сканування досить» на екрані нема.
 */
export function speaksReach(me: object | null | undefined, contacts: ReadonlyArray<object> | null | undefined): boolean {
  const own = reachOf(me);
  if (own !== undefined && own !== null) return true;
  return (contacts ?? []).some((c) => typeof reachOf(c) === 'string');
}

/** Поля тіла коду телефона (PH2:<тіло>:<сума>, тіло — `peer|імʼя|…` у base64url). */
function phoneInviteFields(code: string): string[] {
  const m = /^PH[0-9]:([A-Za-z0-9_-]+):[0-9a-fA-F]{6}$/.exec((code || '').trim());
  if (!m) return [];
  try {
    return new TextDecoder().decode(b64urlDecode(m[1])).split('|');
  } catch {
    return [];
  }
}

/**
 * Ім'я, яким людина назвалась у коді телефона. Без нього контакт з телефона
 * лягав у список ПК як «Без імені», хоча телефон своє ім'я передав.
 */
export function phoneInviteName(code: string): string {
  return (phoneInviteFields(code)[1] ?? '').trim().slice(0, 120);
}

/** peer_id у коді телефона — перше поле тіла (`PeerInvite.encode`). */
export function phoneInvitePeer(code: string): string {
  return (phoneInviteFields(code)[0] ?? '').trim();
}

/**
 * У тексті — МОЄ ж запрошення: людина щойно скопіювала свій код, щоб
 * надіслати, і відкрила аркуш знову. Порівнюємо peer, а не рядок: код несе
 * ще й адресу, яка міняється з мережею, а peer — ні.
 */
export function isOwnInvite(text: string | null | undefined, selfIds: ReadonlyArray<string | null | undefined>): boolean {
  const code = findInText(text);
  if (!code) return false;
  const peer = phoneInvitePeer(code);
  return Boolean(peer) && selfIds.some((id) => Boolean(id) && id === peer);
}

/**
 * node_id = sha256(identity_ed)[:32] — так само, як його рахує вузол.
 * Ім’я в запрошенні можна написати будь-яке; це число бере початок у самому
 * ключі, тож підмінити його, не підмінивши ключ, неможливо.
 *
 * Повертає null там, де браузер не дає SubtleCrypto (сторінка не по https і
 * не на localhost) — тоді вузол назветься вже після відкриття розмови.
 */
export async function nodeIdOf(compact: string): Promise<string | null> {
  try {
    const raw = b64urlDecode(compact.trim());
    if (raw.length < 34 || !globalThis.crypto?.subtle) return null;
    const digest = await globalThis.crypto.subtle.digest('SHA-256', raw.slice(2, 34));
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')
      .slice(0, 32);
  } catch {
    return null;
  }
}

/** Адреса, за якою цей вузол видно браузеру. Людина може її виправити. */
export function selfAddressGuess(): string {
  try {
    // Під Tauri origin сторінки — tauri://localhost, а не вузол.
    return nodeOrigin() || window.location.origin;
  } catch {
    return '';
  }
}

/** Копіює в буфер; там, де clipboard API немає (сторінка не по https), — старим способом. */
export async function copyText(text: string): Promise<boolean> {
  try {
    const bridge = (typeof window !== 'undefined' && (window as unknown as { PhantomBridge?: { copyToClipboard?: (s: string) => boolean } }).PhantomBridge);
    if (bridge && typeof bridge.copyToClipboard === 'function') {
      const ok = bridge.copyToClipboard(text);
      if (ok) return true;
    }
  } catch {
    /* далі на web API */
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* впадемо на запасний шлях */
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
}

/** Зазирає в буфер обміну. Відмова в дозволі — не помилка, просто null. */
export async function inviteInClipboard(): Promise<string | null> {
  try {
    const bridge = (typeof window !== 'undefined' && (window as unknown as { PhantomBridge?: { getFromClipboard?: () => string } }).PhantomBridge);
    if (bridge && typeof bridge.getFromClipboard === 'function') {
      const text = bridge.getFromClipboard();
      if (text && parseInvite(text)) return text;
    }
  } catch {
    /* далі на web API */
  }
  try {
    if (!navigator.clipboard?.readText) return null;
    const text = await navigator.clipboard.readText();
    if (!text) return null;
    return parseInvite(text) ? text : null;
  } catch {
    return null;
  }
}
