/**
 * Шифрування і розшифрування вкладень — у браузері, не на вузлі.
 *
 * Вузлам віддається вже шифротекст, а ключ до нього їде в тілі повідомлення,
 * яке вузол запечатує кадром до вузла співрозмовника. Дороги між вузлами —
 * ретранслятор, скринька, чужий канал — везуть непрозорі байти.
 *
 * Власний вузол при цьому довірений: тіло повідомлення проходить через нього
 * відкрито, як чати проходять через ваш телефон. Сам файл він зберігає
 * шифротекстом і не розшифровує — це робить браузер власника.
 *
 * Відбиток звіряємо ДО розшифрування. Якщо байти по дорозі підмінили, людина
 * побачить чесну помилку, а не биту картинку і не тишу.
 */

import { tNow } from '../components/chat/tNow';
import type { SecureMedia } from '../types/messenger';
import { readToken } from './tokenStore';
import { nodeOrigin } from './nodeOrigin';
import { getCachedMediaBlob, putCachedMediaBlob, deleteCachedMediaBlob } from './mediaStorageCache';
import { messengerApi } from './messengerApi';
import { ApiError } from './api';
import { CARRIER_OPEN_MAX, ChunkTampered, openCarrier } from './carrier/seal';
import { saveCarrierTo, type CarrierSink } from './carrier/sink';

/**
 * Чи ми всередині телефона. Міст ставиться до першого скрипта бандла, тож
 * питати можна вже тут, при завантаженні модуля.
 */
const ON_PHONE = typeof (globalThis as {
  PhantomBridge?: { call?: unknown };
}).PhantomBridge?.call === 'function';

/**
 * Стеля вузла — той самий номінал, що в messenger/blobs.py (ПК) чи
 * PeerFile.MAX_BYTES (телефон). Міряє ШИФРОТЕКСТ.
 *
 * Ці числа були власною константою ТУТ — вигаданою в тому сенсі, що ніщо не
 * тримало їх рівними тому, що реально прийме вузол: розійдись вони бодай
 * одним байтом, людина або впиралась у відмову нижче за фактичну стелю, або
 * шифрувала файл, приречений на 413. Тепер це стартовий ГІД, який
 * `applyMediaLimitFromNode` заміняє на справжнє число, щойно `/identity`
 * (ПК завжди, телефон — коли міст оголосить `max_attachment_bytes`, див.
 * `docs/MEDIA_LIMIT_HANDSHAKE.md`) його підтвердить. `let`, а не `const`:
 * імпортери читають живе значення при кожному виклику, не знімок при
 * завантаженні модуля.
 */
export let MEDIA_LIMIT_BYTES = ON_PHONE ? 4 * 1024 * 1024 : 25 * 1024 * 1024;

/**
 * Стеля для ВІДКРИТОГО файла.
 *
 * Вузол зважує шифротекст, а AES-GCM дописує до нього 16 байтів тега. Файл
 * рівно в 25 МіБ проходив тут і гинув там із сирим 413 — тож віднімаємо
 * запас із запасом і кажемо про межу до вибору, а не після завантаження.
 */
export let MEDIA_PLAIN_LIMIT_BYTES = MEDIA_LIMIT_BYTES - 64;

/** Межа в МБ числом — підпис мовою інтерфейсу складає `mediaLimitLabel`. */
let MEDIA_LIMIT_MB = ON_PHONE ? '4' : '25';

/**
 * Звідки взялося поточне число: 'node' — вузол підтвердив його через
 * `/identity`; 'fallback' — вузол ще не сказав нічого (стара збірка хоста,
 * АБО телефон, чий міст ще не оголошує `max_attachment_bytes`), і число —
 * той самий гід, що був тут завжди. Ніде в UI цей стан не підписаний словом
 * «виміряно» чи «від пристрою», доки не 'node' — це і є чесність, про яку
 * просив власник: не вигадувати підтвердження, якого не було.
 */
let mediaLimitSourceValue: 'node' | 'fallback' = 'fallback';

export function mediaLimitSource(): 'node' | 'fallback' {
  return mediaLimitSourceValue;
}

/**
 * Приймає межу, яку оголосив вузол через `/identity`
 * (`NodeIdentity.max_attachment_bytes`), і перераховує похідні від неї числа.
 *
 * Викликати з будь-якою відповіддю identity — навіть коли поля нема: тоді це
 * НЕ помилка, а «вузол цього ще не каже», і функція просто нічого не чіпає.
 * `bytes` мусить бути додатним скінченним цілим — вигадана чи зіпсована
 * відповідь моста не сміє посунути межу нижче нуля чи в нескінченність.
 */
export function applyMediaLimitFromNode(bytes: number | null | undefined): void {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes <= 64) return;
  MEDIA_LIMIT_BYTES = Math.floor(bytes);
  MEDIA_PLAIN_LIMIT_BYTES = MEDIA_LIMIT_BYTES - 64;
  const mib = MEDIA_LIMIT_BYTES / (1024 * 1024);
  // Ціле число МіБ — «до 25 МБ», не ціле — одна десяткова: «до 4.5 МБ».
  // Вузол каже байтами; людині байти нецікаві, а фальшива точність (25.03 МБ)
  // читалась би як вимір, якого тут немає.
  const mibLabel = Number.isInteger(mib) ? String(mib) : mib.toFixed(1);
  MEDIA_LIMIT_MB = mibLabel;
  mediaLimitSourceValue = 'node';
}

/**
 * Чи це те, що браузер справді намалює тегом <img>.
 *
 * mime image/* сюди не годиться: .psd приходить як image/vnd.adobe.photoshop,
 * ставав «фото» і показувався битою іконкою БЕЗ кнопки «Зберегти» — файл
 * ставав недосяжним. Тож питаємо і розширення, і mime.
 */
const RENDERABLE_MIME = new Set([
  'image/png', 'image/jpeg', 'image/jpg', 'image/webp', 'image/gif', 'image/avif',
]);
const RENDERABLE_EXT = new Set(['png', 'jpg', 'jpeg', 'webp', 'gif', 'avif']);
const VAGUE_MIME = new Set(['', 'application/octet-stream']);

export function isRenderableImage(name: string, mime: string): boolean {
  const dot = name.lastIndexOf('.');
  const ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : '';
  if (!RENDERABLE_EXT.has(ext)) return false;
  const m = (mime || '').toLowerCase().split(';')[0].trim();
  // Система інколи не знає mime для файла з диска — розширення тоді вирішує.
  return RENDERABLE_MIME.has(m) || VAGUE_MIME.has(m);
}

export class MediaNotArrived extends Error {
  constructor() {
    super(tNow('media.notArrived'));
    this.name = 'MediaNotArrived';
  }
}

export class MediaTampered extends Error {
  constructor() {
    super(tNow('media.hashMismatch'));
    this.name = 'MediaTampered';
  }
}

const hex = (buf: ArrayBuffer): string =>
  Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');

/** Повертаємо саме ArrayBuffer: WebCrypto хоче BufferSource з певним сховищем. */
const unhex = (s: string): ArrayBuffer => {
  const buf = new ArrayBuffer(s.length / 2);
  const out = new Uint8Array(buf);
  for (let i = 0; i < out.length; i += 1) out[i] = parseInt(s.substr(i * 2, 2), 16);
  return buf;
};

/** Обʼєктні URL живуть до перезавантаження вкладки; двічі дешифрувати те саме не треба. */
const ready = new Map<string, string>();
const inflight = new Map<string, Promise<string>>();

export type MediaProgress = (done: number, total: number) => void;

export function cachedMediaUrl(blobId: string): string | undefined {
  return ready.get(blobId);
}

/** Шифрує файл перед завантаженням: на вузол іде шифротекст, ключ — окремо, у тілі повідомлення. */
export async function encryptForUpload(
  file: File,
): Promise<{ ciphertext: Blob; keyHex: string; nonceHex: string; sha256: string }> {
  const plain = await file.arrayBuffer();
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const key = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt']);
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce }, key, plain);
  const digest = await crypto.subtle.digest('SHA-256', ct);
  return {
    ciphertext: new Blob([ct], { type: 'application/octet-stream' }),
    keyHex: hex(raw.buffer as ArrayBuffer),
    nonceHex: hex(nonce.buffer as ArrayBuffer),
    sha256: hex(digest),
  };
}

/**
 * Звідки брати шифротекст.
 *
 * Усередині телефона вузла немає, і мережі з цього запиту теж немає: байти
 * лежать у каталозі застосунку, а віддає їх перехоплювач WebView за власним
 * origin. Стара адреса `/api/v1/...` там не блокувалась би помилкою — вона
 * поверталась би порожньою, відбиток не збігався б, і кожне фото читалось би
 * як підмінене.
 */
function blobUrl(blobId: string): string {
  return ON_PHONE ? `/webmsgr/_files/${blobId}` : `${nodeOrigin()}/api/v1/messenger/files/${blobId}`;
}

async function fetchAndDecrypt(media: SecureMedia): Promise<string> {
  const token = readToken();
  const res = await fetch(blobUrl(media.blobId), {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    credentials: 'include',
  });
  // 404 — не помилка, а чесний стан: блоб ще не приїхав на цей вузол.
  if (res.status === 404) throw new MediaNotArrived();
  // 410 — приїхало і виявилось не тим: шматки зібрались, відбиток не збігся.
  // Чеканням це не лікується, тож і показувати очікування не можна.
  if (res.status === 410) throw new MediaTampered();
  if (!res.ok) throw new Error(tNow('media.nodeMissing', { status: res.status }));

  const ct = await res.arrayBuffer();
  const digest = hex(await crypto.subtle.digest('SHA-256', ct));
  if (digest !== media.sha256) throw new MediaTampered();

  const key = await crypto.subtle.importKey(
    'raw',
    unhex(media.keyHex),
    'AES-GCM',
    false,
    ['decrypt'],
  );
  let plain: ArrayBuffer;
  try {
    plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: unhex(media.nonceHex) },
      key,
      ct,
    );
  } catch {
    // Тег AEAD не зійшовся — ключ не той або байти биті. Обидва варіанти
    // означають те саме: показувати нічого.
    throw new MediaTampered();
  }
  const blob = new Blob([plain], { type: media.mime || 'application/octet-stream' });
  void putCachedMediaBlob(media.blobId, blob);
  const url = URL.createObjectURL(blob);
  ready.set(media.blobId, url);
  return url;
}

async function openCarrierMedia(media: SecureMedia, onProgress?: MediaProgress): Promise<string> {
  const m = media.carrier!;
  if (m.size > CARRIER_OPEN_MAX) {
    throw new RangeError(tNow('media.tooBig', { size: m.size }));
  }
  onProgress?.(0, m.n);
  let blob: Blob;
  try {
    blob = await openCarrier(m, async (i) => {
      const b = await messengerApi.carrierGetChunk(media.blobId, i);
      onProgress?.(i + 1, m.n);
      return b;
    });
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) throw new MediaNotArrived();
    if (err instanceof ChunkTampered) throw new MediaTampered();
    throw err;
  }
  void putCachedMediaBlob(media.blobId, blob);
  const url = URL.createObjectURL(blob);
  ready.set(media.blobId, url);
  return url;
}

/** Потокове збереження Carrier-вкладення будь-якого розміру без кешування Blob у памʼяті. */
export async function saveCarrierMedia(
  media: SecureMedia,
  sink: CarrierSink,
  onProgress?: MediaProgress,
): Promise<void> {
  const m = media.carrier;
  if (!m) {
    throw new Error(tNow('media.noManifest'));
  }
  onProgress?.(0, m.n);
  try {
    await saveCarrierTo(
      m,
      (i) => messengerApi.carrierGetChunk(media.blobId, i),
      sink,
      onProgress,
    );
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) throw new MediaNotArrived();
    if (err instanceof ChunkTampered) throw new MediaTampered();
    throw err;
  }
}

/** Розшифроване вкладення як object URL. Той самий блоб не дешифрується двічі. */
export async function loadMedia(media: SecureMedia, onProgress?: MediaProgress): Promise<string> {
  const done = ready.get(media.blobId);
  if (done) return done;

  const running = inflight.get(media.blobId);
  if (running) return running;

  const cachedBlob = await getCachedMediaBlob(media.blobId);
  if (cachedBlob) {
    const cachedUrl = URL.createObjectURL(cachedBlob);
    ready.set(media.blobId, cachedUrl);
    return cachedUrl;
  }

  const task = (media.carrier ? openCarrierMedia(media, onProgress) : fetchAndDecrypt(media)).finally(() =>
    inflight.delete(media.blobId),
  );
  inflight.set(media.blobId, task);
  return task;
}

/**
 * Власне вкладення щойно вивантажено: відкритий файл уже тут, у вкладці.
 * Кладемо його в кеш під blobId, щоб бульбашка не качала й не розшифровувала
 * назад те, що сама щойно зашифрувала.
 */
export function rememberUploadedMedia(blobId: string, plain: Blob): string {
  const had = ready.get(blobId);
  if (had) return had;
  const url = URL.createObjectURL(plain);
  ready.set(blobId, url);
  void putCachedMediaBlob(blobId, plain);
  return url;
}

/** Після «Запитати ще раз» треба спробувати заново, а не віддати стару відмову. */
export function forgetMedia(blobId: string): void {
  const url = ready.get(blobId);
  if (url) URL.revokeObjectURL(url);
  ready.delete(blobId);
  inflight.delete(blobId);
  void deleteCachedMediaBlob(blobId);
}

export function humanSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return tNow('media.unitB', { n: 0 });
  if (bytes < 1024) return tNow('media.unitB', { n: bytes });
  if (bytes < 1024 * 1024) return tNow('media.unitKB', { n: (bytes / 1024).toFixed(0) });
  return tNow('media.unitMB', { n: (bytes / 1024 / 1024).toFixed(1) });
}

/** Межа вкладення словами мовою інтерфейсу («до 4 МБ» / «up to 4 MB»). */
export function mediaLimitLabel(): string {
  return tNow('coreChat.upTo', { n: MEDIA_LIMIT_MB });
}

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(dot + 1).toUpperCase() : tNow('media.fileExt');
}
