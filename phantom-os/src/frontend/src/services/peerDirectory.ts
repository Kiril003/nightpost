/**
 * Глобальний довідник PHANTOM у браузері: знайти людину за ніком, не дістаючи
 * списку людей.
 *
 * Протокол тут НЕ винайдено — він уже стоїть на сервері (`platform-site/
 * server/directory.py`, маршрути `/directory/*`) і на телефоні
 * (`core-net/.../PeerDirectory.kt`). Це третя реалізація ТОГО САМОГО, і саме
 * тому кожна стала в ній величина взята з домовленості, а не придумана:
 * розбіжність на один байт означає, що телефон опублікує запис, якого веб не
 * прочитає, і навпаки. Доведено це не оком: тест проганяє спільний вектор
 * `directory_v2_vector.json` — підпис у ньому зроблений Kotlin'ом, і якщо цей
 * модуль складає речення інакше хоч на пробіл, підпис не звіриться.
 *
 * Що бачить сервер (і це сказано людині вголос на екрані згоди, а не тут):
 * sha256 імені, відкритий ключ, підписаний конверт і час звернень. ІМЕНІ
 * сервер не бачить — до нього їде вже хеш.
 *
 * Головне правило цього файлу: ПІДПИС ЗВІРЯЄТЬСЯ ДО ТОГО, ЯК ЛЮДИНУ ПОКАЗАНО.
 * Сервер не може ані вигадати запис, ані підмінити чужий ключ — підміна ламає
 * підпис, а зламаний підпис тут не «попередження», а окремий стан `forged`,
 * який на скло не пускає нікого. Там, де підпис звірити НЕМА ЧИМ (браузер без
 * Ed25519 у WebCrypto), стан теж окремий — `unverifiable`, — і він теж нікого
 * не показує. Третього шляху, який «ну покажемо, але напишемо дрібним», тут
 * немає навмисно: непідписаний ключ із сервера — це рівно та підміна, проти
 * якої писався весь наскрізний шифр.
 */

import { inDeskShell, nodeOrigin } from './nodeOrigin';
import { readToken } from './tokenStore';

/** Адреса довідника. Це сервер платформи, а не вузол месенджера. */
const DIRECTORY_URL = String(
  import.meta.env?.VITE_DIRECTORY_URL ?? 'https://api.phantom-os.dev',
).replace(/\/+$/, '');

/** Домовлені речення. `v2` — не косметика: підпис v1 накривав ІМʼЯ. */
export const PUBLISH_PREFIX = 'PH-DIR-v2';
export const WITHDRAW_PREFIX = 'PH-DIR-del-v2';

/** Довше сервер не тримає (directory.py: MAX_TTL_DAYS = 30). */
export const MAX_TTL_SECONDS = 30 * 24 * 60 * 60;

/**
 * Найкоротше імʼя, яке ще можна ОПУБЛІКУВАТИ — вісім.
 *
 * Це захід проти словника, а не проти людини: адреса запису — голий sha256
 * імені, тож викрадена база перебирається офлайн, і кожен доданий знак множить
 * простір на 37. Копія числа з `PeerDirectory.MIN_PUBLISH_LENGTH`; розійтись їм
 * не можна — інакше веб заведе імʼя, якого телефон не дасть завести.
 */
export const MIN_PUBLISH_LENGTH = 8;

/** Шукати можна й коротше: сервер ще тримає імена, видані старшими збірками. */
const SHAPE = /^[a-z0-9_]{5,32}$/;
const PUBLISH_SHAPE = new RegExp(`^[a-z0-9_]{${MIN_PUBLISH_LENGTH},32}$`);

const BODY_RE = /^[A-Za-z0-9_-]+$/;
const KEY_RE = /^[A-Za-z0-9+/_-]+={0,2}$/;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/** Імʼя в тій єдиній формі, яку знає сервер, або null. Терпить «@» і регістр. */
export function normalise(raw: string | null | undefined): string | null {
  const bare = (raw ?? '').trim().replace(/^@/, '').toLowerCase();
  return SHAPE.test(bare) ? bare : null;
}

/** Те саме, але з нижньою межею публікації. */
export function normaliseForPublish(raw: string | null | undefined): string | null {
  const bare = (raw ?? '').trim().replace(/^@/, '').toLowerCase();
  return PUBLISH_SHAPE.test(bare) ? bare : null;
}

function toHex(raw: Uint8Array): string {
  let out = '';
  for (let i = 0; i < raw.length; i += 1) out += raw[i].toString(16).padStart(2, '0');
  return out;
}

/** Те, що бачить сервер замість імені. */
export async function handleHash(handle: string): Promise<string> {
  const digest = await subtle().digest('SHA-256', encoder.encode(handle.toLowerCase()));
  return toHex(new Uint8Array(digest));
}

function subtle(): SubtleCrypto {
  const api = globalThis.crypto?.subtle;
  if (!api) throw new Error('WebCrypto unavailable: the page is neither https nor localhost');
  return api;
}

/**
 * FNV-1a 32 біт, останні 6 hex — байт у байт як `PeerInvite.kt` і `directory.py`.
 *
 * Крок — 16-бітна одиниця UTF-16 (`charCodeAt`), а не кодова точка: Kotlin
 * рахує `for (c in body)`, і сурогатна пара для нього ДВА кроки. Для base64url
 * різниці немає, але однакова формула важливіша за коротший запис.
 */
export function checksum(body: string): string {
  let value = 0x811c9dc5;
  for (let i = 0; i < body.length; i += 1) {
    value = (value ^ body.charCodeAt(i)) >>> 0;
    value = Math.imul(value, 16777619) >>> 0;
  }
  return value.toString(16).padStart(8, '0').slice(-6);
}

function b64decode(text: string, expected: number | null = null): Uint8Array | null {
  const bare = (text ?? '').trim();
  if (!bare || !KEY_RE.test(bare)) return null;
  const body = bare.replace(/=+$/, '').replace(/-/g, '+').replace(/_/g, '/');
  try {
    const bin = atob(body + '='.repeat((4 - (body.length % 4)) % 4));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
    return expected !== null && out.length !== expected ? null : out;
  } catch {
    return null;
  }
}

export interface Ph2Invite {
  peerId: string;
  /** У записі довідника — ЗАВЖДИ порожнє: телефон зрізає його в `addressless`. */
  name: string;
  pubKey: string;
  dhPubKey: string;
  host: string;
  port: string;
}

/**
 * Рівно PH2 або нічого. Напівзібране запрошення гірше за відмову: воно стало б
 * записом, з якого ніколи ні до кого не додзвонишся.
 */
export function parsePh2(text: string | null | undefined): Ph2Invite | null {
  const parts = (text ?? '').trim().split(':');
  if (parts.length !== 3 || parts[0] !== 'PH2') return null;
  const [, body, given] = parts;
  if (!BODY_RE.test(body) || checksum(body) !== given.toLowerCase()) return null;
  const raw = b64decode(body);
  if (!raw) return null;
  let fields: string[];
  try {
    fields = decoder.decode(raw).split('|');
  } catch {
    return null;
  }
  if (fields.length !== 6) return null;
  const [peerId, name, pubKey, dhPubKey, host, port] = fields;
  if (!peerId.trim() || !pubKey.trim() || !dhPubKey.trim()) return null;
  return { peerId, name, pubKey, dhPubKey, host, port };
}

/** Речення, під яким стоїть підпис публікації. Бере ГОТОВУ адресу, не імʼя. */
export function publishMessage(hash: string, invite: string, exp: number): Uint8Array {
  return encoder.encode(`${PUBLISH_PREFIX}|${hash}|${invite}|${exp}`);
}

export function withdrawMessage(hash: string, exp: number): Uint8Array {
  return encoder.encode(`${WITHDRAW_PREFIX}|${hash}|${exp}`);
}

/** Чи вміє цей браузер звірити Ed25519. Вирішується один раз і чесно. */
export async function canVerify(): Promise<boolean> {
  try {
    await subtle().importKey(
      'raw',
      new Uint8Array(32) as BufferSource,
      { name: 'Ed25519' },
      false,
      ['verify'],
    );
    return true;
  } catch {
    return false;
  }
}

/** Звіряє підпис. Будь-яка невизначеність — false, ніколи не «мабуть добре». */
export async function verifySignature(
  pubKeyB64: string,
  message: Uint8Array,
  sigB64: string,
): Promise<boolean> {
  const key = b64decode(pubKeyB64, 32);
  const sig = b64decode(sigB64, 64);
  if (!key || !sig) return false;
  try {
    // `as BufferSource`: TS розрізняє Uint8Array<ArrayBuffer> і
    // Uint8Array<ArrayBufferLike>, а WebCrypto — ні. Байти ті самі.
    const imported = await subtle().importKey(
      'raw',
      key as BufferSource,
      { name: 'Ed25519' },
      false,
      ['verify'],
    );
    return await subtle().verify(
      { name: 'Ed25519' },
      imported,
      sig as BufferSource,
      message as BufferSource,
    );
  } catch {
    return false;
  }
}

/**
 * Відбиток ключа — 24 цифри, ТОЧНО як `PeerInvite.fingerprint` на телефоні.
 *
 * Дзеркало контракту, а не власна вигадка: два боки мусять показати людині
 * ОДНАКОВІ цифри, інакше звірка «прочитай мені свій відбиток» нічого не
 * звіряє. Цифри, не base32: їх читають уголос, і цифра має одну назву в
 * кожній мові, а «Q» і «W» — ні.
 *
 * Накриває ОБИДВА ключі: посередник, який пропускає ключ підпису незайманим і
 * підміняє лише ключ шифрування, читає кожен лист.
 */
export async function fingerprint(pubKey: string, dhPubKey: string): Promise<string> {
  const digest = new Uint8Array(
    await subtle().digest('SHA-256', encoder.encode(`${pubKey.trim()}|${dhPubKey.trim()}`)),
  );
  let value = 0n;
  for (let i = 0; i < 12; i += 1) value = (value << 8n) | BigInt(digest[i]);
  return (value % 10n ** 24n).toString().padStart(24, '0');
}

/** «4829 1375 0028 3391 7745 0012» — шість груп, які око тримає. */
export function spokenFingerprint(digits: string): string {
  return (digits.match(/.{1,4}/g) ?? []).join(' ');
}

/** Короткий ідентифікатор — як `PeerRoster.shortId`: «d1d2·7b5f». */
export function shortId(id: string): string {
  const bare = (id ?? '').replace(/[^\p{L}\p{N}]/gu, '').toLowerCase();
  if (bare.length < 8) return bare || '?';
  return `${bare.slice(0, 4)}·${bare.slice(4, 8)}`;
}

export interface VerifiedRecord {
  /** Імʼя, яке ввела ЦЯ людина. Сервер його не називав і назвати не може. */
  handle: string;
  invite: Ph2Invite;
  /** Рядок PH2 як є — саме його віддають месенджеру, щоб почати розмову. */
  compact: string;
  /** 24 цифри для звірки вголос. Пораховано тут, із ключів у записі. */
  fingerprint: string;
  /** Коли запис протухне (unix-секунди). */
  expSeconds: number;
}

/**
 * Чому кожна відмова має власне слово.
 *
 * «Не знайшов» і «не питав» — це різні речі, і зливати їх в одне «нікого
 * немає» означає брехати про те, чого ми не питали. `forged` і `unverifiable`
 * теж різні: перше — сервер підсунув підробку, друге — ми не мали чим
 * подивитись. Жоден із чотирьох невдалих станів нікого не показує.
 */
export type LookupResult =
  | { kind: 'found'; record: VerifiedRecord }
  | { kind: 'absent' }
  | { kind: 'closed' }
  | { kind: 'forged' }
  | { kind: 'unverifiable' }
  | { kind: 'unreachable'; detail: string };

interface RawEntry {
  invite?: unknown;
  sig?: unknown;
  exp?: unknown;
}

/**
 * Перетворює відповідь сервера на запис, ЯКОМУ МОЖНА ВІРИТИ, — або ні на що.
 *
 * Винесено окремо від мережі рівно для того, щоб тест підробленого підпису
 * ганяв саме цю функцію без жодного http.
 */
export async function verifyEntry(handle: string, entry: RawEntry): Promise<LookupResult> {
  const compact = typeof entry.invite === 'string' ? entry.invite.trim() : '';
  const sig = typeof entry.sig === 'string' ? entry.sig.trim() : '';
  const exp = Number(entry.exp);
  if (!compact || !sig || !Number.isFinite(exp)) return { kind: 'forged' };

  const invite = parsePh2(compact);
  if (!invite) return { kind: 'forged' };

  // Адресу в записі довідника телефон зрізає. Якщо вона там є — запис не той,
  // за кого себе видає, і показувати його не можна.
  if (invite.host.trim() || (invite.port.trim() && invite.port.trim() !== '0')) {
    return { kind: 'forged' };
  }

  if (!(await canVerify())) return { kind: 'unverifiable' };

  const hash = await handleHash(handle);
  const ok = await verifySignature(invite.pubKey, publishMessage(hash, compact, exp), sig);
  if (!ok) return { kind: 'forged' };

  return {
    kind: 'found',
    record: {
      handle,
      invite,
      compact,
      fingerprint: await fingerprint(invite.pubKey, invite.dhPubKey),
      expSeconds: exp,
    },
  };
}

/**
 * Спитати довідник про ОДНЕ імʼя, яке тобі вже назвали.
 *
 * Переліку тут немає й бути не може: адреса запису — sha256 імені, і маршрут,
 * у відповідь якого влізло б два записи, на сервері відсутній за побудовою
 * (`response_model` без жодного поля-переліку, і сторож у pytest це тримає).
 */
export async function lookup(rawHandle: string, signal?: AbortSignal): Promise<LookupResult> {
  const handle = normalise(rawHandle);
  if (!handle) return { kind: 'absent' };

  let hash: string;
  try {
    hash = await handleHash(handle);
  } catch {
    // Немає WebCrypto — немає навіть адреси запису. Питати нічого й нема чим,
    // і сказати «нікого немає» тут було б брехнею про незадане питання.
    return { kind: 'unverifiable' };
  }

  let response: Response;
  try {
    response = await fetch(lookupUrl(hash), { method: 'GET', headers: lookupHeaders(), signal });
  } catch (err) {
    return { kind: 'unreachable', detail: err instanceof Error ? err.message : String(err) };
  }

  if (response.status === 404) return { kind: 'absent' };
  if (response.status === 503) return { kind: 'closed' };
  if (!response.ok) {
    return { kind: 'unreachable', detail: `HTTP ${response.status}` };
  }

  let entry: RawEntry;
  try {
    entry = (await response.json()) as RawEntry;
  } catch {
    return { kind: 'forged' };
  }
  return verifyEntry(handle, entry);
}

/**
 * Звідки питати запис.
 *
 * Вікно ПК (Tauri) живе на `tauri://localhost` / `http://tauri.localhost`, а
 * сервер довідника пускає браузер лише з сайту (CORS) — прямий запит звідти
 * відбивався, і людина читала «Довідник не відповів». Тому в оболонці ПК
 * питаємо свій вузол (`api/routes_directory_proxy.py`), а той — сервер.
 * Підпис однаково звіряємо тут, тож вузлу нічого не довіряємо.
 */
export function lookupUrl(hash: string): string {
  if (inDeskShell()) return `${nodeOrigin()}/api/v1/messenger/directory/lookup/${hash}`;
  return `${DIRECTORY_URL}/directory/lookup/${hash}`;
}

function lookupHeaders(): Record<string, string> {
  if (!inDeskShell()) return {};
  const token = readToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Куди цей застосунок ходить по довідник. Показується людині на екрані згоди. */
export function directoryOrigin(): string {
  return DIRECTORY_URL;
}
